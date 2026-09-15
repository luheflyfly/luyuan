package com.luyuan.data

import android.content.Context

/**
 * 底栏自定义（2026-09-15 路河拍板：子界面可自由选择是否放底栏）。
 * 笔记(0)/日记(3) 永远保留（核心记录页），记账/课程/人脉可关。
 * 关了的页仍可从其他入口进入（顶栏📋待办、左缘抽屉、深链），只是不占底栏。
 */
object BottomNavPrefs {
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("luyuan_bottom_nav", Context.MODE_PRIVATE)

    const val KEY_LEDGER = "show_ledger"   // 记账，默认开
    const val KEY_COURSE = "show_course"   // 课程，默认开
    const val KEY_PEOPLE = "show_people"   // 人脉，默认开

    fun showLedger(ctx: Context) = prefs(ctx).getBoolean(KEY_LEDGER, true)
    fun showCourse(ctx: Context) = prefs(ctx).getBoolean(KEY_COURSE, true)
    fun showPeople(ctx: Context) = prefs(ctx).getBoolean(KEY_PEOPLE, true)

    fun set(ctx: Context, key: String, v: Boolean) =
        prefs(ctx).edit().putBoolean(key, v).apply()

    /** 固定五页槽位（index 与历史深链一致：0笔记 1记账 2课程 3日记 4人脉），返回可见槽位 */
    fun visibleSlots(ctx: Context): List<Int> {
        val out = mutableListOf(0, 3)   // 笔记、日记永远在
        if (showLedger(ctx)) out.add(1)
        if (showCourse(ctx)) out.add(2)
        if (showPeople(ctx)) out.add(4)
        return out.sorted()
    }
}
