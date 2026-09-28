package com.luyuan.data

import android.content.Context

/**
 * vc111 灵动岛悬浮胶囊开关（2026-09-28 路河派单：像假灵动岛那样在挖孔旁提示）。
 * 只存 App 私有 prefs，不进同步目录（隐私纪律同 MessageSettings）。
 * 默认开；悬浮窗权限没授的机器（如平板）加窗失败自动静默，等于没开。
 */
object IslandSettings {

    private const val PREFS = "island_prefs"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = p(ctx).getBoolean("enabled", true)

    fun setEnabled(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("enabled", on).apply()
    }
}
