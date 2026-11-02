package com.luyuan.data

import android.content.Context

/**
 * vc131「消息通知」开关 + 已通知去重表（2026-11-02 路河派单：北极星提问 → 通知带按钮 → 点一下就答）。
 *
 * 只存两件东西，都在 App 私有 SharedPreferences（隐私纪律同 LocationSettings / MessageSettings）：
 *  - `enabled`      用户开关，**默认开**；关掉 = 不再发通知（拉取也跳过，省电）
 *  - `notified`     最近 20 个已通知过的 `id`，换行分隔 —— 同一条问题只弹一次
 *
 * 为什么去重表要滚 20 条而不是全存：
 * 服务端 `pending` 只会给"未答未过期"的，正常待答数是个位数；留 20 条足够挡住重复弹，
 * 又不会让 prefs 无限长大（每条 id 约 30 字符，20 条 ≈ 600 字节）。
 *
 * 为什么失败不在这里记：通知线要求**静默失败**（网络不通就不发，下次再试，不弹错误），
 * 所以这里没有 lastErr —— 设置页也不需要给用户看"第几次没拉到"。
 */
object InboxSettings {

    private const val PREFS = "inbox_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_NOTIFIED = "notified"
    private const val NOTIFIED_KEEP = 20

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- 开关（默认开） ----------

    fun enabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    // ---------- 已通知去重（最近 20 个 id） ----------

    fun notifiedIds(ctx: Context): List<String> =
        (p(ctx).getString(KEY_NOTIFIED, "") ?: "")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun alreadyNotified(ctx: Context, id: String): Boolean = notifiedIds(ctx).contains(id)

    /** 记一条已通知；超过 20 条就丢最老的（先进先出）。 */
    fun markNotified(ctx: Context, id: String) {
        if (id.isBlank()) return
        val list = notifiedIds(ctx).toMutableList()
        if (list.contains(id)) return
        list.add(id)
        while (list.size > NOTIFIED_KEEP) list.removeAt(0)
        p(ctx).edit().putString(KEY_NOTIFIED, list.joinToString("\n")).apply()
    }

    /** 清空去重表（手工重放/排查用；正常路径不该调）。 */
    fun clearNotified(ctx: Context) {
        p(ctx).edit().remove(KEY_NOTIFIED).apply()
    }
}
