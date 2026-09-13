package com.luyuan.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「消息待办」云端抽取（立项单 T2）：把一条命中信号词的消息原文交给 DeepSeek，
 * 抽成结构化待办。复用 AskRemote 的 Key / baseUrl（同一套配置，零新增依赖）。
 *
 * 铁律：
 * - 输出**严格 JSON**，解析失败即丢弃（绝不猜、绝不编）
 * - 失败静默降级：无网 / 无 Key / 余额不足 / 超时 → 返回 null，不崩不弹错
 * - 模型固定 deepseek-v4-flash + thinking disabled（省钱、够用、快）
 */
object MessageTodoExtractor {

    const val PKG_WECHAT = "com.tencent.mm"
    const val PKG_QQ = "com.tencent.mobileqq"

    /** 抽取结果（模型给的四个字段，全部可空；text 空 = 判定为无事要办） */
    data class Extracted(val text: String, val who: String, val whenText: String)

    private val json = Json { ignoreUnknownKeys = true }

    private const val TIMEOUT_CONNECT = 10_000
    private const val TIMEOUT_READ = 25_000

    private const val SYSTEM_PROMPT = """你是路远的待办抽取器。用户会给你一条从聊天软件收到的消息。
你的任务：判断这条消息里有没有「需要手机主人去做的事」，如果有，抽成 JSON。

只输出一个 JSON 对象，不要任何解释、不要 markdown 代码块。格式：
{"has_todo": true/false, "text": "要办的事（简短陈述句，保留时间/物品等关键信息，不要加自己的推测）", "who": "谁说的（消息里能看出才填，看不出留空字符串）", "when": "时间描述的原文（如'明天下午三点'，没有留空字符串）"}

判断原则（宁可漏，不可错）：
- 有事要办 → has_todo=true。例："明天下午三点提醒我交材料" → text="明天下午三点要交材料"
- 闲聊/表情/问候/通知类（如"哈哈哈哈"、"收到"、"在吗"、"你的快递已到"）→ has_todo=false
- 不要脑补：消息里没说的信息绝不添加；广告、群公告类一律 has_todo=false
- text 里不要出现"提醒我"这种转述口吻，直接说要办的事"""

    /**
     * 抽取一条消息。命中待办返回 Extracted；判定无事要办、或任何失败 → null。
     * 调用方负责已做信号词粗筛与外发计数。
     */
    fun extract(context: Context, sender: String, text: String, source: String): Extracted? {
        val cfg = try {
            AskRemote.loadConfig(context)
        } catch (_: Exception) {
            return null
        }
        if (!cfg.ready) return null // 没填 Key：静默不处理

        return try {
            val payload = buildJsonObject {
                put("model", "deepseek-v4-flash")
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", SYSTEM_PROMPT)
                    })
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", "来源：$source\n发送者：$sender\n消息：$text")
                    })
                })
                // v4 系默认思考开启，抽取任务显式关掉（省钱且更快）
                put("thinking", buildJsonObject { put("type", "disabled") })
                put("temperature", 0.1)
                put("max_tokens", 400)
            }

            val conn = URL(cfg.baseUrl + "/chat/completions").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = TIMEOUT_CONNECT
                conn.readTimeout = TIMEOUT_READ
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer " + cfg.key)
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                // 每发一条就计一次外发（无论解析成败，原文确实出网了）——隐私计数必须诚实
                MessageSettings.bumpSent(context)
                val code = conn.responseCode
                if (code !in 200..299) return null // 401/402/429 等一律静默（记账那套 humanError 是给交互用的）
                val body = conn.inputStream?.bufferedReader()?.readText().orEmpty()
                parseReply(body)
            } finally {
                conn.disconnect()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 从模型回复里抠出 JSON 并校验；任何不合规 → null（块体：内部有 return，表达式体编译不过） */
    private fun parseReply(body: String): Extracted? {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val content = root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: return null
            // 模型偶尔裹 markdown 代码块或加前后缀：取第一个 { 到最后一个 }
            val s = content.indexOf('{')
            val e = content.lastIndexOf('}')
            if (s < 0 || e <= s) return null
            val obj = json.parseToJsonElement(content.substring(s, e + 1)).jsonObject

            val has = obj["has_todo"]?.jsonPrimitive?.content?.trim()?.lowercase()
            val isTodo = has == "true" || has == "1"
            if (!isTodo) return null

            val text = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (text.isEmpty()) return null
            // 防幻觉：事情描述不能是空壳；长度兜底（模型抽风时输出超长文本）
            if (text.length > 200) return null

            Extracted(
                text = text,
                who = obj["who"]?.jsonPrimitive?.content?.trim().orEmpty().take(20),
                whenText = obj["when"]?.jsonPrimitive?.content?.trim().orEmpty().take(40)
            )
        } catch (_: Exception) {
            null
        }
    }
}
