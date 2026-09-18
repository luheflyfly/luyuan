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
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 「消息待办」云端抽取（立项单 T2；vc98 路河拍板改为窗口版）：
 * 不再逐条消息判定——把同聊天的 5 分钟消息窗（带发送者/时间）整体交给 DeepSeek，
 * 以任务为单位输出：合并重复、丢弃寒暄与缺上下文的碎片、截止时间直接算成 ISO 绝对时间。
 *
 * 铁律：
 * - 输出**严格 JSON**，解析失败即丢弃（绝不猜、绝不编）
 * - 失败静默降级：无网 / 无 Key / 余额不足 / 超时 → 返回空列表，不崩不弹错
 * - 模型固定 deepseek-v4-flash + thinking disabled（省钱、够用、快）
 */
object MessageTodoExtractor {

    const val PKG_WECHAT = "com.tencent.mm"
    const val PKG_QQ = "com.tencent.mobileqq"

    /** 抽取结果（due_iso = 锚定抽取时刻的绝对时间；空 = 没有可识别截止） */
    data class Extracted(val text: String, val who: String, val whenText: String, val dueIso: String)

    private val json = Json { ignoreUnknownKeys = true }

    private const val TIMEOUT_CONNECT = 10_000
    private const val TIMEOUT_READ = 25_000

    private const val SYSTEM_PROMPT = """你是路河的待办整理员。给你一个聊天窗口最近几分钟的消息（按时间顺序，可能多人发言）。找出其中【需要手机主人路河去做的事】，以任务为单位整理输出。

只输出一个 JSON 对象，不要任何解释、不要 markdown 代码块。格式：
{"tasks":[{"text":"要办的事","who":"谁安排的/相关的（看得出才填，否则空字符串）","when_text":"时间描述的原文（没有填空字符串）","due_iso":"截止的绝对时间（没有填空字符串）"}]}

整理规则：
- 以任务为单位：同一件事被多条消息/多个人反复说，合并成一条输出，绝不重复。
- 闲聊、寒暄、"收到/好的/哈哈"、表情、接龙计数、纯通知无需行动的 → 不输出。
- 缺了上下文就做不了的不要输出：只说"发一下""出来填""改一下"而窗口里看不出发什么/填什么/改什么 → 不输出（等上下文齐的那一轮再整理）。
- text 直接说要办的事，不要"提醒我"这类转述腔，保留时间/地点/数量等关键信息，不加自己的推测。
- due_iso：把时间描述相对【当前时间】算成绝对时间，ISO8601 格式如 2026-09-18T15:00:00+08:00（"明天下午三点"→明天15:00；"周五前"→周五23:59）；相对时间算不出或没有时间 → 空字符串。
- 没有要办的事 → {"tasks":[]}"""

    /**
     * 抽取一个聊天窗口。返回任务列表（空 = 窗口里没有要办的事）；**null = 抽取失败**
     * （无网/无 Key/HTTP 错/解析崩）——调用方应把窗口放回缓冲等下次触发重试，别把消息丢了。
     */
    fun extractWindow(context: Context, chat: String, msgs: List<BufferedMessage>): List<Extracted>? {
        if (msgs.isEmpty()) return emptyList()
        val cfg = try {
            AskRemote.loadConfig(context)
        } catch (_: Exception) {
            return null
        }
        if (!cfg.ready) return null // 没填 Key：不算"没待办"，算失败（回头补抽）

        val now = OffsetDateTime.now()
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val body = buildString {
            msgs.take(MessageBuffer.MAX_PER_CHAT).forEach { m ->
                val who = if (m.sender.isNotBlank() && m.sender != m.chat) m.sender else m.chat
                appendLine("[${fmt.format(java.time.Instant.ofEpochMilli(m.at).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime())}] $who：${m.body.take(200)}")
            }
        }


        val payload = buildJsonObject {
            put("model", "deepseek-v4-flash")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "聊天：$chat\n当前时间：${fmt.format(now)}\n\n消息：\n$body")
                })
            })
            // v4 系默认思考开启，抽取任务显式关掉（省钱且更快）
            put("thinking", buildJsonObject { put("type", "disabled") })
            put("temperature", 0.1)
            put("max_tokens", 800)
        }

        return try {
            val conn = URL(cfg.baseUrl + "/chat/completions").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = TIMEOUT_CONNECT
                conn.readTimeout = TIMEOUT_READ
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer " + cfg.key)
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                // 整窗出网一次计一次（原文确实出网了）——隐私计数必须诚实
                MessageSettings.bumpSent(context)
                val code = conn.responseCode
                if (code !in 200..299) return null // 401/402/429 等一律静默
                val resp = conn.inputStream?.bufferedReader()?.readText().orEmpty()
                parseReply(resp) ?: emptyList()   // 解析崩=失败（null）；解析成但没任务=空
            } finally {
                conn.disconnect()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 从模型回复里抠出 JSON 并校验；结构坏/不合规 → null（失败）；合规但 tasks 空 → 空列表 */
    private fun parseReply(body: String): List<Extracted>? {
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
            val arr = obj["tasks"]?.jsonArray ?: return null

            val out = mutableListOf<Extracted>()
            for (t in arr) {
                try {
                    val o = t.jsonObject
                    val text = o["text"]?.jsonPrimitive?.content?.trim().orEmpty()
                    if (text.isEmpty()) continue
                    // 防幻觉：事情描述不能是空壳；长度兜底（模型抽风时输出超长文本）
                    if (text.length > 200) continue
                    // 去重（模型偶尔仍输出重复项）：同 text 只留一条
                    if (out.any { it.text == text }) continue
                    out.add(
                        Extracted(
                            text = text,
                            who = o["who"]?.jsonPrimitive?.content?.trim().orEmpty().take(20),
                            whenText = o["when_text"]?.jsonPrimitive?.content?.trim().orEmpty().take(40),
                            dueIso = o["due_iso"]?.jsonPrimitive?.content?.trim().orEmpty().take(40)
                        )
                    )
                    if (out.size >= 8) break   // 单窗任务上限（正常 0~3 条）
                } catch (_: Exception) {
                }
            }
            out
        } catch (_: Exception) {
            null
        }
    }
}
