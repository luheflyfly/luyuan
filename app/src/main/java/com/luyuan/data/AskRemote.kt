package com.luyuan.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 问路远手机版（终稿 §3.A2 · v1.9.0 模型目录版）：OpenAI 兼容 API 直连。
 * - Key/URL 只存本机 App 私有 SharedPreferences（隐私红线 §4.2，永不进同步目录/仓库）
 * - 模型从内置目录选择（2026-09 官方现状：deepseek-chat/reasoner 已于 2026-07-24 停用，
 *   现行模型 = deepseek-v4-flash 系；思考开关 = "thinking":{"type":"enabled"/"disabled"}，
 *   不发该参数默认思考开启，所以快答必须显式 disabled；视觉 = deepseek-v4-flash-vision-exp）
 * - 上下文 = 本机近期笔记 + 待办摘要；私密标签笔记**默认不外发**，
 *   经用户本次显式授权（allowPrivate=true，问答页勾选）后可外发（立项单 2026-09-13 红线变更）
 */
object AskRemote {

    private const val PREFS = "ask_prefs"
    private const val DEFAULT_BASE = "https://api.deepseek.com"
    private const val PRIVATE_TAG = "私密"
    private const val MAX_CONTEXT_CHARS = 6000

    /** 可选模型（类 Chatbox 选择器目录）：thinking=null 表示该模型不带 thinking 参数 */
    data class ModelInfo(
        val key: String,
        val id: String,
        val label: String,
        val emoji: String,
        val thinking: Boolean?,
        val vision: Boolean
    )

    val CATALOG = listOf(
        ModelInfo("flash_fast", "deepseek-v4-flash", "V4 Flash · 快答", "⚡", thinking = false, vision = false),
        ModelInfo("flash_deep", "deepseek-v4-flash", "V4 Flash · 深思", "🧠", thinking = true, vision = false),
        ModelInfo("flash_vision", "deepseek-v4-flash-vision-exp", "V4 Flash 视觉·实验", "👁", thinking = null, vision = true),
        ModelInfo("pro_fast", "deepseek-v4-pro", "V4 Pro · 旗舰", "💎", thinking = false, vision = false),
        ModelInfo("pro_deep", "deepseek-v4-pro", "V4 Pro · 深思", "💎🧠", thinking = true, vision = false)
    )

    fun modelByKey(key: String): ModelInfo = CATALOG.firstOrNull { it.key == key } ?: CATALOG[0]

    data class Config(val key: String, val baseUrl: String, val modelKey: String) {
        val ready: Boolean get() = key.isNotBlank()
    }

    data class Turn(val role: String, val content: String)

    fun loadConfig(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var modelKey = p.getString("model_key", null)
        if (modelKey == null) {
            // 迁移 v1.8.0 手填的模型名（含 "DeepSeekchat" 之类误填）：一律归到新目录对应条目。
            // 旧名已于 2026-07-24 停用，语义映射：chat→快答，reasoner→深思
            val legacy = (p.getString("model", "") ?: "").lowercase()
                .replace(" ", "").replace("_", "-")
            modelKey = when {
                legacy.contains("reasoner") -> "flash_deep"
                legacy.contains("vision") -> "flash_vision"
                legacy.contains("pro") -> "pro_fast"
                else -> "flash_fast" // deepseek-chat / deepseekchat / 空值
            }
            p.edit().putString("model_key", modelKey).apply()
        }
        return Config(
            key = p.getString("key", "") ?: "",
            baseUrl = (p.getString("base", "") ?: "").ifBlank { DEFAULT_BASE },
            modelKey = modelKey
        )
    }

    fun saveConfig(context: Context, key: String, baseUrl: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("key", key.trim())
            .putString("base", baseUrl.trim().trimEnd('/'))
            .apply()
    }

    fun saveModelKey(context: Context, modelKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("model_key", modelKey).apply()
    }

    // ---- 会话历史持久化（v1.10.0）：App 私有 filesDir，不进同步目录（隐私红线同 §4.2） ----
    private const val HISTORY_FILE = "ask_history.json"
    private const val MAX_HISTORY_TURNS = 60

    fun loadHistory(context: Context): MutableList<Turn> = try {
        val f = File(context.filesDir, HISTORY_FILE)
        if (!f.exists()) mutableListOf()
        else Json.parseToJsonElement(f.readText()).jsonArray.map { el ->
            val o = el.jsonObject
            Turn(
                o["role"]?.jsonPrimitive?.content ?: "user",
                o["content"]?.jsonPrimitive?.content ?: ""
            )
        }.filter { it.content.isNotBlank() }.toMutableList()
    } catch (_: Exception) {
        mutableListOf()
    }

    fun saveHistory(context: Context, history: List<Turn>) {
        try {
            val arr = buildJsonArray {
                for (t in history.takeLast(MAX_HISTORY_TURNS)) {
                    add(buildJsonObject {
                        put("role", t.role)
                        put("content", t.content)
                    })
                }
            }
            File(context.filesDir, HISTORY_FILE).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    fun clearHistory(context: Context) {
        try {
            File(context.filesDir, HISTORY_FILE).delete()
        } catch (_: Exception) {
        }
    }

    /**
     * 本机上下文：今日待办摘要 + 近期笔记摘录。超长截断，控 token。
     * 私密标签笔记默认**不**进上下文；allowPrivate=true（用户本次显式授权）时包含。
     */
    fun buildContext(context: Context, allowPrivate: Boolean = false): String {
        val sb = StringBuilder()
        val todoLines = mutableListOf<String>()
        try {
            for (c in ContactRepository.listContacts(context)) {
                for (t in c.undoneTodos) todoLines.add("${t.text}（${c.name}）")
            }
        } catch (_: Exception) {
        }
        try {
            for (n in NoteRepository.listNotes(context)) {
                if (!allowPrivate && PRIVATE_TAG in n.tags) continue
                if (!n.remind_at.isNullOrBlank() && n.remind_fired != true) {
                    todoLines.add("[提醒] ${n.text.take(40)}")
                }
            }
        } catch (_: Exception) {
        }
        if (todoLines.isNotEmpty()) {
            sb.appendLine("【未完成待办/提醒】")
            todoLines.take(20).forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }
        try {
            val notes = NoteRepository.listNotes(context)
                .filter { allowPrivate || PRIVATE_TAG !in it.tags }
                .take(30)
            if (notes.isNotEmpty()) {
                sb.appendLine("【最近笔记摘录（新→旧）】")
                for (n in notes) {
                    val day = n.created_at.take(10)
                    val body = n.text.replace("\n", " ").take(80)
                    sb.appendLine("- [$day] $body")
                }
            }
        } catch (_: Exception) {
        }
        return if (sb.isBlank()) "（本机暂无笔记与待办）" else sb.toString().take(MAX_CONTEXT_CHARS)
    }

    /**
     * 调 OpenAI 兼容 chat/completions。images = data URL（data:image/jpeg;base64,...），
     * 仅视觉模型可用。返回回答文本；失败抛异常（message 已是人话）。
     */
    fun ask(
        config: Config,
        model: ModelInfo,
        question: String,
        images: List<String>,
        history: List<Turn>,
        localContext: String
    ): String {
        if (!config.ready) throw IllegalStateException("还没填 API Key，去设置页填一个")

        val userContent: JsonElement =
            if (images.isEmpty()) {
                kotlinx.serialization.json.JsonPrimitive(question)
            } else {
                buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", question.ifBlank { "看看这些图片" })
                    })
                    for (du in images) {
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", du) })
                        })
                    }
                }
            }

        val payload = buildJsonObject {
            put("model", model.id)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put(
                        "content",
                        "你是「路远」，路河的本地记事助手。回答要简洁、说人话。" +
                            "下面是用户本机的待办与笔记摘录，仅作参考；" +
                            "与问题无关就不要复述；摘录里没有的信息不要编造。\n\n$localContext"
                    )
                })
                for (m in history.takeLast(8)) {
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userContent)
                })
            })
            // 思考开关（官方格式）：v4 系默认思考开启——快答必须显式 disabled
            if (model.thinking != null) {
                put("thinking", buildJsonObject {
                    put("type", if (model.thinking) "enabled" else "disabled")
                })
                if (model.thinking) put("reasoning_effort", "high")
            }
            // 思考模式下 temperature 不生效，干脆不发
            if (model.thinking != true) put("temperature", 0.4)
            put("max_tokens", if (model.vision) 2000 else 1500)
        }

        val conn = URL(config.baseUrl + "/chat/completions")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 120000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer " + config.key)
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException(humanError(code, body))
            }
            val root = Json.parseToJsonElement(body).jsonObject
            val content = root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
            return content?.trim().orEmpty().ifEmpty { "（模型返回了空回答）" }
        } finally {
            conn.disconnect()
        }
    }

    private fun humanError(code: Int, body: String): String = when (code) {
        401 -> "API Key 无效（去设置页检查一下）"
        402 -> "API 账户余额不足"
        404 -> "接口地址或模型不对（模型在对话页顶部切换）"
        429 -> "请求太频繁或额度限流，稍后再试"
        else -> {
            val snippet = body.take(200).replace(Regex("\\s+"), " ")
            "请求失败（$code）：$snippet"
        }
    }

    /** 连通性自检（#4 钥匙子页）：发一条 max_tokens=1 的探测请求，返回人话化结果。 */
    data class CheckResult(val ok: Boolean, val message: String, val latencyMs: Long)

    fun checkConnectivity(context: Context): CheckResult {
        val cfg = loadConfig(context)
        if (!cfg.ready) return CheckResult(false, "还没填 API Key", 0)
        val model = modelByKey(cfg.modelKey)
        val start = System.currentTimeMillis()
        return try {
            val payload = buildJsonObject {
                put("model", model.id)
                put("messages", buildJsonArray {
                    add(buildJsonObject { put("role", "user"); put("content", "ping") })
                })
                if (model.thinking != null) {
                    put("thinking", buildJsonObject { put("type", if (model.thinking) "enabled" else "disabled") })
                }
                if (model.thinking != true) put("temperature", 0.4)
                put("max_tokens", 1)
            }
            val conn = URL(cfg.baseUrl + "/chat/completions").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer " + cfg.key)
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
                val latency = System.currentTimeMillis() - start
                if (code !in 200..299) return CheckResult(false, humanError(code, body), latency)
                CheckResult(true, "正常 · ${latency}ms", latency)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            CheckResult(false, "连不上：${e.message ?: "网络错误"}", System.currentTimeMillis() - start)
        }
    }
}
