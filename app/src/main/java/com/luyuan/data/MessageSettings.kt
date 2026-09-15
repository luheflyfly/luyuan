package com.luyuan.data

import android.content.Context

/**
 * 「消息待办」开关与隐私计数（立项单 T4）。
 * 全部存 App 私有 SharedPreferences —— 不进同步目录、不进仓库（隐私红线）。
 *
 * 隐私设计（立项单 §4）：
 * - 总开关默认**关闭**，不开启时任何消息不出本机（监听器直接 return）
 * - 微信 / QQ 各自独立开关，默认关闭
 * - `sentCount` = 本月已外发的原文条数（可追溯、可随时关；跨月自动归零）
 */
object MessageSettings {

    private const val PREFS = "message_todo_prefs"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 总开关（默认关） */
    fun enabled(ctx: Context): Boolean = p(ctx).getBoolean("enabled", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("enabled", on).apply()
    }

    /** 来源白名单：微信 */
    fun wechatOn(ctx: Context): Boolean = p(ctx).getBoolean("src_wechat", false)

    fun setWechatOn(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("src_wechat", on).apply()
    }

    /** 来源白名单：QQ */
    fun qqOn(ctx: Context): Boolean = p(ctx).getBoolean("src_qq", false)

    fun setQqOn(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("src_qq", on).apply()
    }

    /** 含群聊（通知群场景，09-14 路河反馈「相当多的群都是专门的通知群」）：默认关；开了才处理群消息 */
    fun groupsOn(ctx: Context): Boolean = p(ctx).getBoolean("src_groups", false)

    fun setGroupsOn(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("src_groups", on).apply()
    }

    /** 该包名当前是否放行（总开关 + 对应来源开关都开） */
    fun allowedPackage(ctx: Context, pkg: String): Boolean {
        if (!enabled(ctx)) return false
        return when (pkg) {
            MessageTodoExtractor.PKG_WECHAT -> wechatOn(ctx)
            MessageTodoExtractor.PKG_QQ -> qqOn(ctx)
            else -> false
        }
    }

    // ---------- 不计入待办的联系人（2026-09-15 路河：朋友闲聊不进工作流，待办服务大学工作） ----------
    // 存 App 私有 prefs（隐私红线同上：不进同步目录）。语义三道闸：
    // ①监听入口 title 命中即丢弃；②待办页显示层 who/sender 命中即隐（PC 端生成的 todo_*.json 也被滤）；
    // ③待确认卡长按快捷加入。PC 端采集侧自行实现同名排除，todo_*.json 格式零改动。

    private const val EXCLUDED_KEY = "todo_excluded_contacts"

    /** 排除名单（trim 后精确匹配） */
    fun excludedContacts(ctx: Context): Set<String> =
        p(ctx).getStringSet(EXCLUDED_KEY, emptySet()) ?: emptySet()

    fun isExcluded(ctx: Context, name: String): Boolean {
        val n = name.trim()
        return n.isNotEmpty() && n in excludedContacts(ctx)
    }

    fun addExcluded(ctx: Context, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        p(ctx).edit().putStringSet(EXCLUDED_KEY, excludedContacts(ctx) + n).apply()
    }

    fun removeExcluded(ctx: Context, name: String) {
        p(ctx).edit().putStringSet(EXCLUDED_KEY, excludedContacts(ctx) - name.trim()).apply()
    }

    // ---------- 本月外发计数（透明可查） ----------

    private fun ym(): String = java.time.YearMonth.now().toString()

    /** 本月已外发条数（跨月自动归零） */
    fun sentCountThisMonth(ctx: Context): Int {
        val prefs = p(ctx)
        if (prefs.getString("sent_ym", "") != ym()) return 0
        return prefs.getInt("sent_count", 0)
    }

    /** 记一条外发（每次真正把原文发到云端前调用） */
    fun bumpSent(ctx: Context) {
        val prefs = p(ctx)
        val cur = if (prefs.getString("sent_ym", "") == ym()) prefs.getInt("sent_count", 0) else 0
        prefs.edit()
            .putString("sent_ym", ym())
            .putInt("sent_count", cur + 1)
            .apply()
    }

    /** 本机是否已拿到通知使用权（引导卡用；复用记账 listener 的判定，同一次授权对两个 listener 都生效） */
    fun notificationAccess(ctx: Context): Boolean =
        com.luyuan.platform.PaymentNotificationListener.enabled(ctx)
}
