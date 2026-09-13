package com.luyuan.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 学期锚点（校历来源：中南大学资料_2026-09-14/csu_luyuan_data.json · academic_calendar）：
 * 路河 = 2026 级新生口径，2026-2027 S1 新生第一周周一 = 2026-09-14，新生学期共 20 周。
 * 换学期：改这两个常量，或写 prefs semester_start 覆盖（App 读取优先级：prefs > 常量）。
 */
const val SEMESTER_START_DEFAULT = "2026-09-14"
const val SEMESTER_WEEKS_DEFAULT = 20

fun semesterStartDefault(): LocalDate = try {
    LocalDate.parse(SEMESTER_START_DEFAULT)
} catch (_: Exception) {
    LocalDate.of(2026, 9, 14)
}

/** 学期第几周（锚点日=第一周周一；今天早于锚点=第 1 周，不显示负数） */
fun semesterWeekOf(today: LocalDate): Int {
    val start = semesterStartDefault()
    if (today.isBefore(start)) return 1
    val days = ChronoUnit.DAYS.between(start, today)
    return (days / 7).toInt() + 1
}
