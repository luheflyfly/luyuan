package com.luyuan.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/**
 * ChineseDue 考卷（vc119 立，路河拍板）：解析器是全 App 单源，改它必过此卷。
 * base=2026-10-06（周二）09:00。跑法：gradle :app:testDebugUnitTest（CI 门禁）。
 */
class ChineseDueTest {

    private val base: LocalDateTime = LocalDateTime.of(2026, 10, 6, 9, 0)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi)

    // ---------- 原口径回归（vc118 行为不回退） ----------

    @Test fun todayWithClock() {
        assertEquals(at(2026, 10, 6, 17, 0), ChineseDue.parse("今天17点前交", base)!!.at)
    }

    @Test fun tonight() {
        assertEquals(at(2026, 10, 6, 22, 0), ChineseDue.parse("今晚22点截止", base)!!.at)
    }

    @Test fun tomorrowExplicitTime() {
        assertEquals(at(2026, 10, 7, 18, 0), ChineseDue.parse("明天18:00前完成申请", base)!!.at)
    }

    @Test fun dayAfterTomorrow() {
        assertEquals(at(2026, 10, 8, 23, 59), ChineseDue.parse("后天之前报名", base)!!.at)
    }

    @Test fun bigDayAfterTomorrow() {
        assertEquals(at(2026, 10, 9, 23, 59), ChineseDue.parse("大后天之内", base)!!.at)
    }

    @Test fun nextWeekWednesday() {
        // base 周二 → 下个周一 10/12，+2 = 10/14
        assertEquals(at(2026, 10, 14, 23, 59), ChineseDue.parse("下周三交", base)!!.at)
    }

    @Test fun weekContainsToday() {
        assertEquals(at(2026, 10, 9, 23, 59), ChineseDue.parse("周五交", base)!!.at)
    }

    @Test fun weekend() {
        assertEquals(at(2026, 10, 10, 23, 59), ChineseDue.parse("周末前完成", base)!!.at)
    }

    @Test fun monthEnd() {
        assertEquals(at(2026, 10, 31, 23, 59), ChineseDue.parse("月底前上报", base)!!.at)
    }

    @Test fun monthDayNoYearNoRoll() {
        // 已过不自动跨年（X月X日分支）
        assertEquals(at(2026, 9, 24, 18, 0), ChineseDue.parse("9月24日18:00前登录教务系统", base)!!.at)
    }

    @Test fun pureDayRollsNextMonth() {
        assertEquals(at(2026, 10, 25, 23, 59), ChineseDue.parse("请25号前提交", base)!!.at)
    }

    @Test fun afternoonConverts() {
        assertEquals(at(2026, 10, 7, 15, 30), ChineseDue.parse("明天下午3点半交", base)!!.at)
    }

    @Test fun noonStaysNoon() {
        assertEquals(at(2026, 10, 7, 12, 0), ChineseDue.parse("明天中午12点", base)!!.at)
    }

    @Test fun noMatchReturnsNull() {
        assertNull(ChineseDue.parse("尽快处理一下", base))
    }

    // ---------- vc119 增量①：URL 剥离 ----------

    @Test fun urlDateIgnored() {
        // 网址里的「10月1日」不许被当截止；真截止=明天
        assertEquals(at(2026, 10, 7, 17, 0), ChineseDue.parse("详情见 https://mp.weixin.qq.com/s?d=10月1日 明天17点", base)!!.at)
    }

    // ---------- vc119 增量②：多日期仲裁 ----------

    @Test fun multiDateDeadlineMarkerWins() {
        assertEquals(at(2026, 9, 20, 18, 0), ChineseDue.parse("第一轮9月1日提交，截止9月20日18:00", base)!!.at)
    }

    @Test fun multiDateRangeBridgeTakesLast() {
        assertEquals(at(2026, 9, 10, 23, 59), ChineseDue.parse("9月1日至9月10日受理报名", base)!!.at)
    }

    @Test fun multiDateNoBasisRefuses() {
        // 无截止词也无范围桥 → 不猜
        assertNull(ChineseDue.parse("9月1日开学，10月8日运动会", base))
    }

    // ---------- vc119 增量③：已截止/已过期不滚月 ----------

    @Test fun pureDayPastStaysWhenSaidExpired() {
        assertEquals(at(2026, 10, 5, 23, 59), ChineseDue.parse("这个5号截止了", base)!!.at)
    }

    @Test fun pureDayPastStillRollsWithoutExpireWord() {
        assertEquals(at(2026, 11, 5, 23, 59), ChineseDue.parse("5号交材料", base)!!.at)
    }

    // ---------- vc119 增量④：中文钟点 ----------

    @Test fun chineseNumeralHalf() {
        assertEquals(at(2026, 10, 7, 15, 30), ChineseDue.parse("明天下午三点半", base)!!.at)
    }

    @Test fun chineseNumeralQuarter() {
        assertEquals(at(2026, 10, 9, 15, 15), ChineseDue.parse("周五下午三点一刻", base)!!.at)
    }

    @Test fun chineseNumeralTwenty() {
        assertEquals(at(2026, 10, 6, 20, 0), ChineseDue.parse("今晚二十点前", base)!!.at)
    }

    // ---------- vc119 增量⑤：时段区间取结束端 ----------

    @Test fun rangeTakesEnd() {
        assertEquals(at(2026, 10, 7, 10, 0), ChineseDue.parse("明天8点—10点期间去还", base)!!.at)
    }

    @Test fun rangeDashTakesEnd() {
        assertEquals(at(2026, 10, 6, 10, 0), ChineseDue.parse("今日7-10点可办理", base)!!.at)
    }

    // ---------- 兜底稳定性 ----------

    @Test fun emptyTextNull() {
        assertNull(ChineseDue.parse("", base))
    }

    @Test fun urlOnlyTextNull() {
        assertNull(ChineseDue.parse("https://example.com/a/b", base))
    }

    @Test fun illegalDateNoCrash() {
        assertNull(ChineseDue.parse("13月40日交", base))
    }

    @Test fun pureIllegalDayNoCrash() {
        assertNull(ChineseDue.parse("32号交", base))
    }

    @Test fun cnNumHelper() {
        assertEquals(3, ChineseDue.cnNum("三")!!)
        assertEquals(20, ChineseDue.cnNum("二十")!!)
        assertEquals(15, ChineseDue.cnNum("十五")!!)
        assertEquals(10, ChineseDue.cnNum("十")!!)
        assertEquals(7, ChineseDue.cnNum("七")!!)
        assertNull(ChineseDue.cnNum("点"))
    }
}
