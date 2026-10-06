package com.luyuan.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 截止时间中文解析 · 全 App 单源（vc118 合一，路河拍板"算法合一成一个可调用部件"）。
 *
 * 此前三套各自为政：TodoScreen（最全，路河日用主口径）/ PhotoScan.extractDue（子集，且
 * "周X不含今天""下周X严格下周"与主口径分叉）/ LuyuanViewModel.parseBirthdayMd（生日月-日专用）。
 * 本文件=TodoScreen 口径逐字升为公共实现，PhotoScan 自 vc118 起向此对齐。
 * 分叉裁决（主口径）：周X/星期X/礼拜X 含今天；下周X=下个周一+(X-1)；X月X日 已过不自动跨年
 * （原文"X日前"=当天语义）；无时段=当天 23:59。扩新匹配规则只改这里，勿再各处自造。
 *
 * vc119（路河拍板"时间截止可以"，吸收偷闲 DeadlineParser 四增量，只收窄不放宽）：
 * ①解析前剥掉 URL（防网址里的数字被当日期）；②多日期仲裁（截止词邻近优先→范围桥接取尾→
 * 认不了不猜返回 null）；③纯D日遇"已截止/已过期"不再自动滚下月；④中文钟点（三点半/一刻/三刻）；
 * ⑤时段区间取结束端（真实语料：手机 124 条待办中区间式 2 条，云端均取结束端）。
 * 配套考卷：app/src/test/.../ChineseDueTest.kt，CI testDebugUnitTest 门禁。
 */
object ChineseDue {

    // ---------- 正则（原 TodoScreen 版 + vc119 增量） ----------
    val RX_URL = Regex("https?://\\S+")
    val RX_HM = Regex("(\\d{1,2}):(\\d{2})")
    val RX_MD = Regex("(\\d{1,2})月(\\d{1,2})[日号]")
    val RX_D = Regex("(\\d{1,2})[日号]")
    // (?<![下个])：把「下周三/下个星期三」让给 RX_NEXTWEEK，别当本周的周三吃掉
    val RX_WEEK = Regex("(?<![下个])[周星期礼拜]([一二三四五六日天])")
    val RX_NEXTWEEK = Regex("下(?:周|星期|礼拜)([一二三四五六日天])")
    val RX_CN_TIME = Regex("(上午|早上|中午|下午|傍晚|晚上|夜里|凌晨)?(\\d{1,2}|[零〇一二两三四五六七八九十]{1,3})[点时](半|一刻|三刻|([0-9零〇一二两三四五六七八九十]{1,3})分?)?")
    // 时段区间（「8点—10点期间」「今日7-10点」）：RX_RANGE 取结束端钟点
    private val RX_RANGE = Regex("\\d{1,2}\\s*[点时]?\\s*[—\\-~～]\\s*(\\d{1,2})\\s*[点时]")
    // 多日期仲裁（借偷闲 DeadlineParser）：截止词标记 / 范围桥接
    private val RX_DEADLINE_MARK = Regex("截止|截至|最晚|不晚于")
    private val RX_PAST_DUE = Regex("已(?:经)?(?:截止|结束|过期)|(?:截止|结束|过期)了")
    private val RX_RANGE_BRIDGE = Regex("[至到—~～]")

    private val WEEK_MAP = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7)

    /** 命中结果：phrase=命中的原文片段（底稿/标签回显用），at=绝对时刻 */
    data class DueHit(val phrase: String, val at: LocalDateTime)

    /** 中文数字 → Int（三点=3、二十=20、十五=15）；纯数字直接转；认不出 null */
    fun cnNum(v: String): Int? {
        if (v.isEmpty()) return null
        if (v.all { it.isDigit() }) return v.toIntOrNull()
        val d = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (v == "十") return 10
        if (v.startsWith("十")) return 10 + (d[v.getOrNull(1)] ?: return null)
        if (v.endsWith("十")) return (d[v.firstOrNull()] ?: return null) * 10
        val i = v.indexOf('十')
        if (i > 0) return (d[v.firstOrNull()] ?: return null) * 10 + (d[v.getOrNull(i + 1)] ?: return null)
        return if (v.length == 1) d[v.single()] else null
    }

    /** 中文钟点 → LocalTime：下午三点半=15:30，中午12=12:00，凌晨2=02:00；识别不出 null */
    fun cnTime(t: String): LocalTime? {
        val m = RX_CN_TIME.find(t) ?: return null
        var h = m.groupValues[2].toIntOrNull() ?: cnNum(m.groupValues[2]) ?: return null
        val min = when (m.groupValues[3]) {
            "半" -> 30
            "一刻" -> 15
            "三刻" -> 45
            else -> m.groupValues[4].toIntOrNull()?.takeIf { it in 0..59 } ?: 0
        }
        val ampm = m.groupValues[1]
        if (h !in 1..12 && ampm.isNotBlank()) return null
        if (h > 23) return null
        if ((ampm == "下午" || ampm == "傍晚" || ampm == "晚上" || ampm == "夜里") && h < 12) h += 12
        if (ampm == "中午" && h < 12) h = 12
        return try { LocalTime.of(h, min) } catch (_: Exception) { null }
    }

    /** 区间结束端钟点（「8点—10点期间」→10:00）；无区间 null */
    private fun rangeEndTime(t: String): LocalTime? = RX_RANGE.find(t)?.let { m ->
        m.groupValues[1].toIntOrNull()?.takeIf { it in 0..23 }?.let { h -> runCatching { LocalTime.of(h, 0) }.getOrNull() }
    }

    /** 显式 HH:MM 优先，回落中文钟点 */
    fun parseTime(t: String): LocalTime? = RX_HM.find(t)?.let { m ->
        try { LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) } catch (_: Exception) { null }
    } ?: cnTime(t)

    /**
     * 文本 → 截止时刻+命中短语（确定性，不做猜测；解析不出 = null，调方自行兜底）。
     * 分支顺序即优先级：今天系 → 下周X → 周末 → 月底 → 周X → M月D日 → 纯D日 → 纯钟点。
     */
    fun parse(text: String, now: LocalDateTime): DueHit? {
        val t = text.replace(RX_URL, " ").trim()
        if (t.isEmpty()) return null
        val time = rangeEndTime(t) ?: parseTime(t)
        val eod = time ?: LocalTime.of(23, 59)
        // 1) 今天 / 明天 / 后天 / 大后天
        val day = when {
            t.contains("大后天") -> now.toLocalDate().plusDays(3)
            t.contains("后天") -> now.toLocalDate().plusDays(2)
            t.contains("明天") -> now.toLocalDate().plusDays(1)
            t.contains("今天") || t.contains("今晚") -> now.toLocalDate()
            else -> null
        }
        if (day != null) {
            val phrase = listOf("大后天", "后天", "明天", "今晚", "今天").firstOrNull { t.contains(it) } ?: t.take(12)
            return DueHit(phrase, LocalDateTime.of(day, eod))
        }
        // 2) 下周X → 下个周一再走 (X-1) 天（RX_NEXTWEEK 先于 RX_WEEK）
        RX_NEXTWEEK.find(t)?.groupValues?.get(1)?.let { ch ->
            WEEK_MAP[ch.firstOrNull()]?.let { target ->
                val monday = now.toLocalDate().plusDays((8 - now.dayOfWeek.value).toLong())
                return DueHit(RX_NEXTWEEK.find(t)!!.value, LocalDateTime.of(monday.plusDays((target - 1).toLong()), eod))
            }
        }
        // 3) 周末 → 这个周末（周六；今天周末就算今天）
        if (t.contains("周末")) {
            var d = now.toLocalDate()
            while (d.dayOfWeek.value != 6) d = d.plusDays(1)
            return DueHit("周末", LocalDateTime.of(d, eod))
        }
        // 4) 月底 → 本月最后一天
        if (t.contains("月底") || t.contains("月末")) {
            val phrase = if (t.contains("月底")) "月底" else "月末"
            return DueHit(phrase, LocalDateTime.of(now.toLocalDate().withDayOfMonth(now.toLocalDate().lengthOfMonth()), eod))
        }
        // 5) 周X/星期X/礼拜X → 下一个该星期几（含今天；与 PC 相对星期口径同向）
        RX_WEEK.find(t)?.groupValues?.get(1)?.let { ch ->
            WEEK_MAP[ch.firstOrNull()]?.let { target ->
                var d = now.toLocalDate()
                var guard = 0
                while (d.dayOfWeek.value != target && guard < 8) {
                    d = d.plusDays(1)
                    guard += 1
                }
                return DueHit(RX_WEEK.find(t)!!.value, LocalDateTime.of(d, eod))
            }
        }
        // 6) M月D日/号（「前」=当天 23:59 语义，日期不变）；多日期仲裁 vc119
        val mds = RX_MD.findAll(t).toList()
        if (mds.size > 1) {
            val marker = RX_DEADLINE_MARK.find(t)
            var chosen = marker?.let { mk -> mds.firstOrNull { it.range.first >= mk.range.last && it.range.first - mk.range.last < 16 } }
            if (chosen == null) {
                val bridge = t.substring(mds.first().range.last, mds.last().range.first)
                if (RX_RANGE_BRIDGE.containsMatchIn(bridge)) chosen = mds.last()
            }
            if (chosen == null) return null // 多日期无仲裁依据，不猜（调方自行兜底显示 when_text 原文）
            return try {
                DueHit(chosen.value, LocalDateTime.of(LocalDate.of(now.year, chosen.groupValues[1].toInt(), chosen.groupValues[2].toInt()), eod))
            } catch (_: Exception) {
                null
            }
        }
        RX_MD.find(t)?.let { m ->
            try {
                return DueHit(m.value, LocalDateTime.of(LocalDate.of(now.year, m.groupValues[1].toInt(), m.groupValues[2].toInt()), eod))
            } catch (_: Exception) {
            }
        }
        // 7) 纯 D日/号（本月内；已过滚下月同日防"25号"在 26 号解析成过去；vc119：明示已截止/已过期则不滚）
        RX_D.find(t)?.let { m ->
            try {
                var dd = LocalDate.of(now.year, now.month, m.groupValues[1].toInt())
                if (dd.isBefore(now.toLocalDate()) && !RX_PAST_DUE.containsMatchIn(t)) dd = dd.plusMonths(1)
                return DueHit(m.value, LocalDateTime.of(dd, eod))
            } catch (_: Exception) {
            }
        }
        // 8) 纯钟点（今天）
        if (time != null) return DueHit(RX_CN_TIME.find(t)?.value ?: RX_HM.find(t)?.value ?: t.take(12), LocalDateTime.of(now.toLocalDate(), time))
        return null
    }

    /** ISO 时间串 → 毫秒（取前 19 位标准格式，回落日期 09:00） */
    fun parseIsoMillis(s: String): Long? = try {
        LocalDateTime.parse(s.take(19), java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: Exception) {
        try {
            LocalDate.parse(s.take(10)).atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    /** 解析生日串为 月/日（支持 "10月20日" / "10-20" / "2026-10-20" / "10/20"）——原 LuyuanViewModel 版 */
    fun parseBirthdayMonthDay(s: String): Pair<Int, Int>? {
        val m = Regex("""(\d{1,2})\s*[月/\-./]\s*(\d{1,2})""").find(s) ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2].toIntOrNull() ?: return null
        if (a in 1..12 && b in 1..31) return a to b
        return null
    }

    /** 截止时间分值（毫秒）：remind_at/due_at > when_text 显式词；无 → Long.MAX（排最后） */
    fun deadlineScore(whenText: String, remindAt: String?, createdAt: String, now: LocalDateTime): Long {
        // 1) 联系人待办 remind_at / 消息待办 due_at 是 ISO 时间，最准
        if (!remindAt.isNullOrBlank()) {
            parseIsoMillis(remindAt)?.let { return it }
        }
        // 2) when_text 显式词（created_at 兜底不参与排序，避免新建的永远沉底/置顶）
        parse(whenText, now)?.let { return it.at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
        return Long.MAX_VALUE
    }

    /** 展示用的截止标签 */
    fun dueLabel(remindAt: String?, whenText: String, now: LocalDateTime): String {
        if (!remindAt.isNullOrBlank()) {
            parseIsoMillis(remindAt)?.let {
                val d = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
                return "截止 " + d.monthValue + "/" + d.dayOfMonth + " " + d.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            }
        }
        val t = whenText.trim()
        if (t.isNotEmpty()) {
            RX_NEXTWEEK.find(t)?.value?.let { return "截止 " + it }
            if (t.contains("周末")) return "截止 周末"
            if (t.contains("月底") || t.contains("月末")) return "截止 月底"
            RX_WEEK.find(t)?.value?.let { return "截止 " + t.take(12) }
            if (t.contains("今天") || t.contains("今晚") || t.contains("明天") || t.contains("后天") || t.contains("大后天")) return "截止 " + t.take(12)
            RX_MD.find(t)?.value?.let { return "截止 " + it }
            RX_D.find(t)?.value?.let { return "截止 " + it }
            RX_HM.find(t)?.value?.let { return "截止 " + it }
            cnTime(t)?.let { return "截止 " + t.take(12) }
        }
        return "无期限"
    }
}
