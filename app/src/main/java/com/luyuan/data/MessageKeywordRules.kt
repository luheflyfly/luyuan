package com.luyuan.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 消息关键词规则（vc120，中档·路河拍板"都做"）：用户自定义关注词，命中即"本地保底"——
 * 云端窗口抽取之外多一道保险网：带命中标记的消息在窗口冲刷时若未被任何抽取任务覆盖，
 * 兜底落一条待办（网络不好/模型抽漏时不白丢）。
 *
 * 存 SharedPreferences（设备本地，不进同步目录、不出网）；删除/停用规则不回溯既有待办。
 * MVP 口径：词+启停。类型/P0-P3 重要性字段留给下批（对齐偷闲「消息正文关键词」交互）。
 */
object MessageKeywordRules {

    data class Rule(val id: String, val keyword: String, val enabled: Boolean = true)

    private const val PREFS = "luyuan_msg_kw"
    private const val KEY = "rules"

    fun list(context: Context): List<Rule> = try {
        val arr = JSONArray(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "[]") ?: "[]"
        )
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Rule(o.getString("id"), o.getString("kw"), o.optBoolean("on", true))
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun add(context: Context, keyword: String): Boolean {
        val kw = keyword.trim()
        if (kw.isEmpty()) return false
        val cur = list(context).toMutableList()
        // 同词去重（含已停用的）
        if (cur.any { it.keyword.equals(kw, ignoreCase = true) }) return false
        cur.add(Rule(newId(), kw))
        save(context, cur)
        return true
    }

    fun setEnabled(context: Context, id: String, on: Boolean) {
        save(context, list(context).map { if (it.id == id) it.copy(enabled = on) else it })
    }

    fun remove(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
    }

    /** 命中检查：任一启用词是消息正文子串（忽略大小写）→ true */
    fun hit(context: Context, body: String): Boolean {
        val b = body.lowercase()
        return list(context).any { it.enabled && b.contains(it.keyword.lowercase()) }
    }

    private fun save(context: Context, rules: List<Rule>) {
        val arr = JSONArray()
        for (r in rules) arr.put(JSONObject().put("id", r.id).put("kw", r.keyword).put("on", r.enabled))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    private fun newId(): String = "kw_" + System.currentTimeMillis().toString(36) + (0..999).random()
}
