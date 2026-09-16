package com.luyuan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luyuan.data.KeiwuEvent
import com.luyuan.data.KeiwuGrade
import com.luyuan.data.KeiwuLedgerItem
import com.luyuan.data.KeiwuRefBundle
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 课务（PC）三视图 · vc85（2026-09-16 手机版批）：
 * 校历 / 成绩·学业 / CSU 速查 —— 以 LazyListScope 扩展形式供「学业」hub 内嵌，
 * 与课表共用同一个滚动容器（不加新页面不加新底栏签，hub 收敛口径）。
 * 数据 = KeiwuStore 打包件（PC 课务导出 → Syncthing），只读；缺件显示等待同步提示。
 * 视觉 = 路远 v2 口径：LuyuanColors 令牌 + 平面卡（零阴影）+ 琥珀/红/蓝/紫/绿徽章。
 */

// ---------- 徽章 / 小组件 ----------

internal fun keiwuTypeBadgeColor(type: String): Pair<Color, Color> = when (type) {
    "考试" -> LuyuanColors.Red to LuyuanColors.RedBg
    "假期" -> LuyuanColors.Green700 to LuyuanColors.Green100
    "军训" -> LuyuanColors.Blue to LuyuanColors.BlueBg
    "评奖" -> LuyuanColors.Amber to LuyuanColors.AmberBg
    "报到" -> LuyuanColors.Blue to LuyuanColors.BlueBg
    "作业" -> LuyuanColors.Purple to LuyuanColors.PurpleBg
    else -> LuyuanColors.Ink3 to Color(0xFFF6F3EB)
}

@Composable
private fun KeiwuBadge(text: String, fg: Color, bg: Color) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun KeiwuOfficialTag() {
    Text(
        "官方",
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFFA8843A),
        modifier = Modifier
            .border(1.dp, Color(0xFFA8843A), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

/** 「等电脑同步」占位（打包件缺失/为空时） */
@Composable
private fun KeiwuWaitHint(what: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .padding(vertical = 26.dp, horizontal = 16.dp)
    ) {
        Text("还没有${what}数据", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LuyuanColors.Ink2)
        Spacer(Modifier.height(5.dp))
        Text(
            "在电脑端课务系统录入后，会自动同步出现在这里",
            fontSize = 11.sp, color = LuyuanColors.Ink4
        )
    }
}

@Composable
private fun KeiwuSectionHead(text: String, count: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(4.dp)
                .background(LuyuanColors.Green500, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = LuyuanColors.Ink2)
        if (count.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(count, fontSize = 10.5.sp, color = LuyuanColors.Ink4)
        }
    }
}

// ---------- 日程（vc87：作业截止 + 校历事件合并时间线；路河「截止日期和开始日期单开一页」→ 学业页分段承载） ----------

/** 提前提醒天数：事件自带优先，缺省按类型（镜像 PC server 口径：考试7/评奖5/假期2/军训3/报到3/其他3） */
internal fun keiwuEventLead(e: KeiwuEvent): Int =
    if (e.remind_days_before > 0) e.remind_days_before
    else when (e.type) {
        "考试" -> 7; "评奖" -> 5; "假期" -> 2; "军训" -> 3; "报到" -> 3; else -> 3
    }

internal data class KeiwuAgendaItem(
    val date: LocalDate,
    val title: String,
    val sub: String,
    val kind: String,
    val official: Boolean,
    val note: String,
    val days: Int,
    val isTodo: Boolean
)

/** 事件（校历）+ 未完成作业（带 ISO 截止日的 todo_*.json）合并成一条日程线 */
internal fun keiwuAgendaBuild(events: List<KeiwuEvent>, todos: List<com.luyuan.data.Todo>, today: LocalDate): List<KeiwuAgendaItem> {
    val out = mutableListOf<KeiwuAgendaItem>()
    for (e in events) {
        if (e.deleted || e.date.isBlank()) continue
        val ld = try {
            LocalDate.parse(e.date.take(10))
        } catch (_: Exception) {
            continue
        }
        val ongoing = e.end.isNotBlank() && try {
            LocalDate.parse(e.end.take(10)).toEpochDay() >= today.toEpochDay()
        } catch (_: Exception) {
            false
        }
        if (ld.isBefore(today) && !ongoing) continue   // 过去的纯事件不进日程
        out.add(
            KeiwuAgendaItem(ld, e.name, e.type, e.type, e.source == "官方校历", e.note,
                (ld.toEpochDay() - today.toEpochDay()).toInt(), isTodo = false)
        )
    }
    for (t in todos) {
        if (t.done || t.deleted) continue
        val d = t.when_text.trim()
        if (d.length < 10) continue   // 没有结构化截止日的（自由文本"下课后"）不进日程
        val ld = try {
            LocalDate.parse(d.substring(0, 10))
        } catch (_: Exception) {
            continue
        }
        out.add(
            KeiwuAgendaItem(ld, t.text, if (t.who.isNotBlank() && t.who != "课务") t.who else "作业",
                "作业", official = false, note = "", days = (ld.toEpochDay() - today.toEpochDay()).toInt(),
                isTodo = true)
        )
    }
    return out.sortedWith(compareBy({ it.date }, { !it.isTodo }))
}

fun LazyListScope.keiwuAgendaItems(
    events: List<KeiwuEvent>,
    todos: List<com.luyuan.data.Todo>,
    today: LocalDate
) {
    val all = keiwuAgendaBuild(events, todos, today)
    val overdue = all.filter { it.isTodo && it.days < 0 }.sortedBy { it.days }
    val urgent = all.filter {
        (it.isTodo && it.days in 0..1) ||
            (!it.isTodo && it.days in 0..when (it.kind) {
                "考试" -> 7; "评奖" -> 5; "假期" -> 2; else -> 3
            })
    }.sortedBy { it.days }
    val upcoming = all.filter { it.days >= 0 }

    if (overdue.isNotEmpty()) {
        item(key = "ag_overdue_head") { KeiwuSectionHead("逾期未交", "${overdue.size} 条") }
        for ((idx, u) in overdue.withIndex()) {
            item(key = "ag_od_" + u.date.toString() + "_$idx") { agendaRow(u) }
        }
    }
    item(key = "ag_urgent_head") { KeiwuSectionHead("需要你上心", "${urgent.size} 条临近") }
    if (urgent.isEmpty()) {
        item(key = "ag_urgent_empty") {
            Text(
                "近期没有要紧的日子",
                fontSize = 11.5.sp, color = LuyuanColors.Ink4,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }
    }
    for ((idx, u) in urgent.withIndex()) {
        item(key = "ag_u_" + u.date.toString() + "_$idx") { agendaRow(u) }
    }
    item(key = "ag_tl_head") {
        Spacer(Modifier.height(4.dp))
        KeiwuSectionHead("日程时间轴", "未来 ${upcoming.size} 条")
    }
    for ((idx, u) in upcoming.withIndex()) {
        item(key = "ag_f_" + u.date.toString() + "_$idx") { agendaRow(u) }
    }
}

@Composable
private fun agendaRow(u: KeiwuAgendaItem) {
    val urgent = u.isTodo && u.days <= 1
    val (lfg, lbg) = when {
        u.days <= 1 -> LuyuanColors.Red to LuyuanColors.RedBg
        u.days <= 3 -> LuyuanColors.Amber to LuyuanColors.AmberBg
        else -> LuyuanColors.Green700 to LuyuanColors.Green100
    }
    val label = when {
        u.days < 0 -> "逾期" + (-u.days) + "天"
        u.days == 0 -> "今天"
        u.days == 1 -> "明天"
        else -> "" + u.days + "天"
    }
    val (bfg, bbg) = keiwuTypeBadgeColor(u.kind)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (u.days <= 0) LuyuanColors.RedBg else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                u.date.toString().let { if (it.length == 10) it.substring(5) else it },
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LuyuanColors.Green700
            )
            Spacer(Modifier.width(7.dp))
            KeiwuBadge(u.kind, bfg, bbg)
            if (u.official) {
                Spacer(Modifier.width(5.dp))
                KeiwuOfficialTag()
            }
            Spacer(Modifier.weight(1f))
            Text(
                label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = lfg,
                modifier = Modifier.background(lbg, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            u.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LuyuanColors.Ink1
        )
        if (u.sub.isNotBlank() && u.sub != u.kind) {
            Text(u.sub, fontSize = 10.5.sp, color = LuyuanColors.Ink3)
        }
        if (u.note.isNotBlank()) {
            Text(u.note, fontSize = 10.5.sp, color = LuyuanColors.Ink3, lineHeight = 15.sp)
        }
    }
}

// ---------- 成绩·学业 ----------

internal val KEIWU_FIVE_MAP = mapOf(
    "优秀" to 95.0, "良好" to 85.0, "中等" to 75.0, "及格" to 65.0, "不及格" to 0.0
)

internal data class KeiwuGradeStats(
    val count: Int,
    val weightedAvg: Double?,      // 加权平均分（CSU 排名口径）
    val countedCredits: Double,
    val failCredits: Double,
    val warning: String            // normal | academic | dropout
)

/** 镜像 PC server.py grade_stats 口径：两级制不计排名；补考/重修合格按 60 计入；不及格学分累计 */
internal fun keiwuGradeStats(items: List<KeiwuGrade>): KeiwuGradeStats {
    var counted = 0.0
    var sum = 0.0
    var failCredits = 0.0
    for (g in items.filter { !it.deleted }) {
        val raw = g.grade.trim()
        var v: Double? = null
        var countRank = false
        when (g.grade_type) {
            "five" -> {
                v = KEIWU_FIVE_MAP[raw] ?: raw.toDoubleOrNull()
                countRank = v != null
            }
            "pass" -> {
                val passed = raw in setOf("合格", "通过", "pass", "P", "1", "true", "True")
                v = if (passed) null else 0.0
                countRank = false
            }
            else -> {
                v = raw.toDoubleOrNull()
                countRank = v != null
            }
        }
        if (g.attempt == "resit" || g.attempt == "retake") {
            v = v?.let { if (it > 60.0) 60.0 else it }   // 补考/重修合格一律按 60 计入（刷分无效）
        }
        if (v != null && v < 60.0) failCredits += g.credits
        if (countRank && v != null && g.credits > 0) {
            counted += g.credits
            sum += v * g.credits
        }
    }
    val weighted = if (counted > 0) {
        ((sum / counted * 100.0).roundToInt()) / 100.0
    } else null
    val warning = when {
        failCredits >= 25 -> "dropout"
        failCredits >= 16 -> "academic"
        else -> "normal"
    }
    return KeiwuGradeStats(items.count { !it.deleted }, weighted, counted, failCredits, warning)
}

private fun fmt2(v: Double): String {
    val r = (v * 100.0).roundToInt() / 100.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else "%.2f".format(r)
}

fun LazyListScope.keiwuGradesItems(items: List<KeiwuGrade>) {
    val grades = items.filter { !it.deleted }
    if (grades.isEmpty()) {
        item(key = "gr_empty") { KeiwuWaitHint("成绩") }
        return
    }
    val st = keiwuGradeStats(grades)
    item(key = "gr_hero") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(listOf(LuyuanColors.GradGreenStart, LuyuanColors.GradGreenEnd)),
                    RoundedCornerShape(22.dp)
                )
                .padding(18.dp)
        ) {
            Text(
                "加权平均分 · CSU 排名口径",
                fontSize = 11.sp, color = Color(0xFFCFE0D6), letterSpacing = 1.sp
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    st.weightedAvg?.let { fmt2(it) } ?: "—",
                    fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White
                )
                Text(
                    " /100", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFFCFE0D6)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "计入 ${fmt2(st.countedCredits)} 学分 · 不及格 ${fmt2(st.failCredits)} 学分 · ${st.count} 门",
                fontSize = 11.sp, color = Color(0xFFCFE0D6)
            )
        }
    }
    item(key = "gr_warn") {
        Spacer(Modifier.height(8.dp))
        val (txt, fg, bg) = when (st.warning) {
            "dropout" -> Triple("⛔ 退学警示线（累计不及格≥25学分）", LuyuanColors.Red, LuyuanColors.RedBg)
            "academic" -> Triple("⚠ 学业警示线（累计不及格≥16学分）", LuyuanColors.Amber, LuyuanColors.AmberBg)
            else -> Triple("学业正常，继续加油", LuyuanColors.Green700, LuyuanColors.Green100)
        }
        Text(
            txt, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = fg,
            modifier = Modifier.background(bg, RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
    item(key = "gr_note") {
        Spacer(Modifier.height(6.dp))
        Text(
            "排名用「加权平均分 = Σ(成绩×学分)/Σ学分」而非 GPA；补考/重修合格一律按 60 计入（刷分无效）；两级制不计排名。",
            fontSize = 10.sp, color = LuyuanColors.Ink4, lineHeight = 14.sp
        )
    }
    val sems = grades.map { it.semester }.filter { it.isNotBlank() }.distinct()
    val groups: List<Pair<String, List<KeiwuGrade>>> =
        if (sems.isEmpty()) listOf("" to grades)
        else sems.map { sm -> sm to grades.filter { it.semester == sm } }
    for ((sm, list) in groups) {
        val credits = list.sumOf { it.credits }
        item(key = "gr_head_$sm") {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeiwuSectionHead(sm.ifBlank { "课程成绩" }, "${list.size} 门 · ${fmt2(credits)} 学分")
                Spacer(Modifier.width(6.dp))
                if (credits > 35) Text("· 超35学分", fontSize = 10.sp, color = LuyuanColors.Red, fontWeight = FontWeight.Bold)
                else if (credits > 0 && credits < 15) Text("· 低于15学分", fontSize = 10.sp, color = LuyuanColors.Amber, fontWeight = FontWeight.Bold)
            }
        }
        for ((i, g) in list.withIndex()) {
            item(key = "gr_row_${g.id}_$i") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            g.course_name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                            color = LuyuanColors.Ink1, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            (when (g.grade_type) {
                                "five" -> "五级制"
                                "pass" -> "两级制"
                                else -> "百分制"
                            } + if (g.attempt != "first") " · " + when (g.attempt) {
                                "resit" -> "补考"
                                "retake" -> "重修"
                                else -> ""
                            } else ""),
                            fontSize = 10.sp, color = LuyuanColors.Ink3
                        )
                    }
                    Text(
                        "${fmt2(g.credits)} 学分", fontSize = 10.sp,
                        color = LuyuanColors.Ink3,
                        modifier = Modifier
                            .background(Color(0xFFF6F3EB), RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        g.grade, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = LuyuanColors.Blue,
                        modifier = Modifier
                            .background(LuyuanColors.BlueBg, RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

// ---------- CSU 速查 ----------

fun LazyListScope.keiwuCsuItems(
    ref: KeiwuRefBundle?,
    ledger: List<KeiwuLedgerItem>,
    sub: Int,
    onSub: (Int) -> Unit
) {
    item(key = "csu_tabs") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 2.dp)
        ) {
            val names = listOf("奖学金", "竞赛库", "综测加分", "推免时间线")
            for ((i, n) in names.withIndex()) {
                val on = sub == i
                Text(
                    n,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (on) Color.White else LuyuanColors.Ink2,
                    modifier = Modifier
                        .background(
                            if (on) LuyuanColors.Green700 else MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(999.dp)
                        )
                        .border(
                            1.dp,
                            if (on) LuyuanColors.Green700 else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(999.dp)
                        )
                        .clickable { onSub(i) }
                        .padding(horizontal = 13.dp, vertical = 6.dp)
                )
            }
        }
    }
    item(key = "csu_disc") {
        Spacer(Modifier.height(6.dp))
        Text(
            ref?.disclaimer?.ifBlank { null } ?: "政策每年可能微调，以学校当年发文为准",
            fontSize = 10.sp, color = LuyuanColors.Ink4
        )
    }
    if (ref == null) {
        item(key = "csu_empty") {
            Spacer(Modifier.height(8.dp))
            KeiwuWaitHint("CSU 速查")
        }
        return
    }
    when (sub) {
        0 -> keiwuScholarshipCards(ref)
        1 -> keiwuCompetitionCards(ref)
        2 -> keiwuZongceCards(ref, ledger)
        else -> keiwuTuimianCards(ref)
    }
}

private fun LazyListScope.keiwuScholarshipCards(ref: KeiwuRefBundle) {
    if (ref.scholarships.isEmpty()) {
        item(key = "sch_empty") { KeiwuWaitHint("奖学金") }
        return
    }
    for ((i, s) in ref.scholarships.withIndex()) {
        item(key = "sch_$i") {
            val (lfg, lbg) = when (s.level) {
                "国家" -> LuyuanColors.Red to LuyuanColors.RedBg
                "校级" -> LuyuanColors.Green700 to LuyuanColors.Green100
                else -> LuyuanColors.Blue to LuyuanColors.BlueBg
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KeiwuBadge(s.level.ifBlank { "—" }, lfg, lbg)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        s.name, fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
                        color = LuyuanColors.Ink1, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(4.dp))
                val amt = when {
                    s.amount != null -> keiwuFmtMoney(s.amount)
                    s.amount_min != null && s.amount_max != null -> "${keiwuFmtMoney(s.amount_min)}-${keiwuFmtMoney(s.amount_max)}"
                    else -> "—"
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        amt, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LuyuanColors.Green700
                    )
                    Text(
                        if (s.name.contains("勤工")) " 元/时" else " 元",
                        fontSize = 10.sp, color = LuyuanColors.Ink3
                    )
                }
                kvLine("条件", s.requirement)
                kvLine("时间", s.window)
                kvLine("名额", s.quota)
            }
        }
    }
}

private fun keiwuFmtMoney(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

@Composable
private fun kvLine(k: String, v: String) {
    if (v.isBlank()) return
    Row(modifier = Modifier.padding(top = 3.dp)) {
        Text(
            k, fontSize = 10.5.sp, color = LuyuanColors.Ink3, modifier = Modifier.width(34.dp)
        )
        Text(v, fontSize = 11.sp, color = LuyuanColors.Ink2, lineHeight = 15.sp)
    }
}

private fun LazyListScope.keiwuCompetitionCards(ref: KeiwuRefBundle) {
    if (ref.competitions.isEmpty()) {
        item(key = "cmp_empty") { KeiwuWaitHint("竞赛") }
        return
    }
    for ((i, c) in ref.competitions.withIndex()) {
        item(key = "cmp_$i") {
            val (lfg, lbg) = when (c.level) {
                "国际" -> LuyuanColors.Purple to LuyuanColors.PurpleBg
                "国家" -> LuyuanColors.Red to LuyuanColors.RedBg
                else -> LuyuanColors.Blue to LuyuanColors.BlueBg
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KeiwuBadge(c.level.ifBlank { "—" }, lfg, lbg)
                    if (c.csu_key_funded_64) {
                        Spacer(Modifier.width(5.dp))
                        KeiwuBadge("重点资助", LuyuanColors.Amber, LuyuanColors.AmberBg)
                    }
                    if (c.csu_strength) {
                        Spacer(Modifier.width(5.dp))
                        KeiwuBadge("CSU强项", LuyuanColors.Green700, LuyuanColors.Green100)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    c.name, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = LuyuanColors.Ink1
                )
                kvLine("赛程", c.window)
                kvLine("主办", (c.organizer + (if (c.team.isNotBlank()) " · " + c.team else "")))
            }
        }
    }
}

private fun LazyListScope.keiwuZongceCards(ref: KeiwuRefBundle, ledger: List<KeiwuLedgerItem>) {
    val comp = ref.comprehensive
    val sum = ledger.sumOf { it.points }
    val capped = minOf(3.0, sum)
    item(key = "zc_formula") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text("综测分公式", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = LuyuanColors.Ink2)
            Spacer(Modifier.height(3.dp))
            Text(comp.formula, fontSize = 11.sp, color = LuyuanColors.Ink2, lineHeight = 15.sp)
            kvLine("适用", comp.applicable_cohorts)
        }
    }
    item(key = "zc_ledger_head") {
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            KeiwuSectionHead("我的加分台账")
            Spacer(Modifier.weight(1f))
            Text(
                "累计 ${fmt2(sum)} · 计入 ${fmt2(capped)}（上限 3）",
                fontSize = 10.sp, color = LuyuanColors.Ink3
            )
        }
    }
    item(key = "zc_progress") {
        val frac = (sum / 3.0).coerceIn(0.0, 1.0).toFloat()
        Box(
            Modifier
                .fillMaxWidth()
                .height(7.dp)
                .background(Color(0xFFF6F3EB), RoundedCornerShape(999.dp))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(7.dp)
                    .background(
                        Brush.horizontalGradient(listOf(LuyuanColors.Green500, LuyuanColors.Green700)),
                        RoundedCornerShape(999.dp)
                    )
            )
        }
    }
    if (ledger.isEmpty()) {
        item(key = "zc_ledger_empty") {
            Text(
                "还没记过加分。在电脑端课务系统「综测加分」里记一条（如：国赛二等奖），同步后这里能看到。",
                fontSize = 10.5.sp, color = LuyuanColors.Ink4, lineHeight = 15.sp
            )
        }
    }
    for ((i, x) in ledger.withIndex()) {
        item(key = "zc_row_${x.id}_$i") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        x.item, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = LuyuanColors.Ink1, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        (x.category + (if (x.date.isNotBlank()) " · " + x.date else "")),
                        fontSize = 10.sp, color = LuyuanColors.Ink3
                    )
                }
                Text(
                    "+" + fmt2(x.points), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = LuyuanColors.Green700,
                    modifier = Modifier
                        .background(LuyuanColors.Green100, RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
    item(key = "zc_bonus_head") {
        Spacer(Modifier.height(6.dp))
        KeiwuSectionHead("官方分值参考")
    }
    for ((i, b) in comp.bonuses.withIndex()) {
        item(key = "zc_bonus_$i") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    b.item, fontSize = 11.5.sp, color = LuyuanColors.Ink2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "+" + fmt2(b.points), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = LuyuanColors.Green700
                )
            }
        }
    }
}

private fun LazyListScope.keiwuTuimianCards(ref: KeiwuRefBundle) {
    val t = ref.tuimian
    item(key = "tm_formula") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text("推免总成绩", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = LuyuanColors.Ink2)
            Spacer(Modifier.height(3.dp))
            Text(t.formula, fontSize = 11.sp, color = LuyuanColors.Ink2, lineHeight = 15.sp)
        }
    }
    if (t.veto.isNotEmpty()) {
        item(key = "tm_veto_head") {
            Spacer(Modifier.height(6.dp))
            KeiwuSectionHead("一票否决")
        }
        for ((i, v) in t.veto.withIndex()) {
            item(key = "tm_veto_$i") {
                Text(
                    "· $v", fontSize = 11.sp, color = LuyuanColors.Ink2,
                    lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
        }
    }
    item(key = "tm_tl_head") {
        Spacer(Modifier.height(6.dp))
        KeiwuSectionHead("关键时间线")
    }
    for ((i, s) in t.timeline.withIndex()) {
        item(key = "tm_tl_$i") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(
                    s.window, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                    color = LuyuanColors.Green700, modifier = Modifier.width(74.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(s.stage, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = LuyuanColors.Ink1)
                    if (s.note.isNotBlank()) {
                        Text(s.note, fontSize = 10.5.sp, color = LuyuanColors.Ink3, lineHeight = 14.sp)
                    }
                }
            }
        }
    }
}
