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

/**
 * 周次规格 "1-16" / "1,3,5" / "2-8,10-16"；空=每周都有。
 * vc113 从 LuyuanClock 私有版升为公共口径（照片扫描手动补扫同用——10-05 国庆周 bug：手动扫漏看周次，
 * 把假期里恰好落在课程时钟窗口的照片当课堂照扫了）。vc115 起全 App 唯一实现
 * （LuyuanClock/TodayWidgetProvider 私有副本已删，改动只改这里）。
 */
fun weeksMatch(spec: String, week: Int): Boolean {
    val w = spec.trim()
    if (w.isEmpty()) return true
    for (part in w.split(',', '，', ';', '；')) {
        val p = part.trim()
        if (p.isEmpty()) continue
        val seg = p.split('-', '～', '~')
        if (seg.size == 2) {
            val a = seg[0].trim().toIntOrNull()
            val b = seg[1].trim().toIntOrNull()
            if (a != null && b != null && week in a..b) return true
        } else {
            if (p.toIntOrNull() == week) return true
        }
    }
    return false
}
