package com.luyuan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luyuan.data.V2EntityRepository
import com.luyuan.domain.Note
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 本周回顾（v2/review.html 施工，2026-09-13 批）：入口=笔记页顶栏 📊。
 * 一页看完本周：记录节奏柱图 / 心情序列 / 支出趋势 / 最常出现的人 / 本周最长的一句。
 * 🔴 全部只读——数据源=现有笔记/日记/账目，不生成任何记录、不外发。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(vm: LuyuanViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val notes by vm.notes.collectAsStateWithLifecycle()
    val diaries by vm.diaries.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    val monday = remember(today) { today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
    val lastMon = monday.minusDays(7)

    val all = remember(notes, diaries) { notes + diaries }
    fun dayOf(n: Note): LocalDate? = try {
        LocalDate.parse((n.updated_at.ifBlank { n.created_at }).take(10))
    } catch (_: Exception) {
        null
    }
    fun inRange(n: Note, from: LocalDate, to: LocalDate): Boolean {
        val d = dayOf(n) ?: return false
        return !d.isBefore(from) && !d.isAfter(to)
    }

    val thisWeek = remember(all, monday, today) { all.filter { inRange(it, monday, today) } }
    val lastWeek = remember(all, lastMon, monday) { all.filter { inRange(it, lastMon, monday.minusDays(1)) } }
    val diaryCount = remember(thisWeek) { thisWeek.count { it.tags.contains("日记") } }
    val perDay = remember(thisWeek, monday) {
        (0..6).map { d ->
            val date = monday.plusDays(d.toLong())
            date to thisWeek.count { dayOf(it) == date }
        }
    }
    val best = remember(perDay) { perDay.maxByOrNull { it.second } }
    val diff = thisWeek.size - lastWeek.size
    // 日记连续天数（从今天或昨天往前数，日记页同款口径）
    val streak = remember(diaries, today) {
        val days = diaries.mapNotNull { dayOf(it) }.toHashSet()
        var cur = if (days.contains(today)) today else today.minusDays(1)
        var n = 0
        while (days.contains(cur)) {
            n += 1
            cur = cur.minusDays(1)
        }
        n
    }

    // 支出（只读，读盘放后台一次）
    var weekSpend by remember { mutableStateOf(0.0) }
    var lastSpend by remember { mutableStateOf(0.0) }
    var topCats by remember { mutableStateOf(listOf<Pair<String, Double>>()) }
    var spendLoaded by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val list = V2EntityRepository.listExpenses(context)
            fun spendOf(from: LocalDate, to: LocalDate): Double {
                var s = 0.0
                for (e in list) {
                    val d = try {
                        LocalDate.parse((e.spent_at.ifBlank { e.created_at }).take(10))
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    if (!d.isBefore(from) && !d.isAfter(to)) s += e.amount
                }
                return s
            }
            val cats = HashMap<String, Double>()
            for (e in list) {
                val d = try {
                    LocalDate.parse((e.spent_at.ifBlank { e.created_at }).take(10))
                } catch (_: Exception) {
                    null
                } ?: continue
                if (!d.isBefore(monday) && !d.isAfter(today)) {
                    val c = e.category.ifBlank { "其他" }
                    cats[c] = (cats[c] ?: 0.0) + e.amount
                }
            }
            weekSpend = spendOf(monday, today)
            lastSpend = spendOf(lastMon, monday.minusDays(1))
            topCats = cats.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value }
            spendLoaded = true
        }
    }

    // 最常出现的人
    val topPeople = remember(contacts, thisWeek) {
        contacts.map { c -> c to thisWeek.count { it.text.contains(c.name) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(3)
    }
    // 本周最长的一句（优先日记）
    val longest = remember(thisWeek) { thisWeek.maxByOrNull { it.text.length } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("本周回顾", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${monday.monthValue}/${monday.dayOfMonth} – ${today.monthValue}/${today.dayOfMonth}",
                            fontSize = 11.sp, color = LuyuanColors.Ink4
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
        ) {
            // 深绿英雄卡
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(listOf(LuyuanColors.Green700, LuyuanColors.Green500)),
                            RoundedCornerShape(18.dp)
                        )
                        .padding(17.dp)
                ) {
                    Text("本周你记了", fontSize = 10.5.sp, color = Color(0xCCFFFFFF))
                    Text(
                        "${thisWeek.size} 条笔记 · $diaryCount 篇日记",
                        fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            append(if (diff >= 0) "比上周多 $diff 条" else "比上周少 ${-diff} 条")
                            if (best != null && best.second > 0) {
                                append("；最勤的一天是周" + dayShort(best.first.dayOfWeek.value) + "（${best.second} 条）")
                            }
                            if (streak > 0) append("。日记连续 $streak 天没断")
                        },
                        fontSize = 11.sp, lineHeight = 17.sp, color = Color(0xD9FFFFFF)
                    )
                }
            }
            // 记录节奏
            item {
                ReviewCard("记录节奏（条 / 天）") {
                    val mx = (perDay.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        modifier = Modifier.height(74.dp)
                    ) {
                        for ((date, n) in perDay) {
                            val isToday = date == today
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom,
                                modifier = Modifier.weight(1f).fillMaxSize()
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height((74f * n / mx).coerceAtLeast(if (n > 0) 8f else 3f).dp)
                                        .background(
                                            if (isToday) Brush.verticalGradient(
                                                listOf(LuyuanColors.Green500, LuyuanColors.Green700)
                                            ) else LuyuanColors.Green100,
                                            RoundedCornerShape(5.dp)
                                        )
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("$n", fontSize = 9.5.sp, color = LuyuanColors.Ink3)
                                Text(
                                    if (isToday) "今天" else "周" + dayShort(date.dayOfWeek.value),
                                    fontSize = 9.sp, color = LuyuanColors.Ink4
                                )
                            }
                        }
                    }
                }
            }
            // 支出趋势
            if (spendLoaded) {
                item {
                    ReviewCard("支出趋势") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "¥" + formatMoney(weekSpend),
                                fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = LuyuanColors.Ink1
                            )
                            Spacer(Modifier.width(8.dp))
                            if (lastSpend > 0.0) {
                                val pct = ((weekSpend - lastSpend) / lastSpend * 100).toInt()
                                if (pct != 0) {
                                    Text(
                                        (if (pct > 0) "比上周 ↑" else "比上周 ↓") + "${kotlin.math.abs(pct)}%",
                                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                        color = if (pct > 0) LuyuanColors.Amber else LuyuanColors.Green700,
                                        modifier = Modifier
                                            .background(
                                                if (pct > 0) LuyuanColors.AmberBg else LuyuanColors.Green100,
                                                RoundedCornerShape(999.dp)
                                            )
                                            .padding(horizontal = 10.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                        if (topCats.isEmpty()) {
                            Text("这周还没记账", fontSize = 11.sp, color = LuyuanColors.Ink4)
                        } else {
                            val mx = topCats.firstOrNull()?.second ?: 1.0
                            val barColors = listOf(LuyuanColors.Amber, LuyuanColors.Purple, LuyuanColors.Green500)
                            for ((i, pair) in topCats.withIndex()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    Text(pair.first, fontSize = 11.5.sp, color = LuyuanColors.Ink2, modifier = Modifier.width(40.dp))
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .height(6.dp)
                                            .background(LuyuanColors.Ink4.copy(alpha = 0.12f), RoundedCornerShape(99.dp))
                                    ) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth((pair.second / mx).toFloat().coerceIn(0.05f, 1f))
                                                .height(6.dp)
                                                .background(barColors[i % barColors.size], RoundedCornerShape(99.dp))
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text("¥" + formatMoney(pair.second), fontSize = 11.sp, color = LuyuanColors.Ink3)
                                }
                            }
                        }
                    }
                }
            }
            // 最常出现的人
            if (topPeople.isNotEmpty()) {
                item {
                    ReviewCard("这周最常出现的人") {
                        for ((c, n) in topPeople) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 6.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(LuyuanColors.Green100, CircleShape)
                                ) {
                                    Text(
                                        c.name.take(1),
                                        fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = LuyuanColors.Green700
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(c.name, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = LuyuanColors.Ink1)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "出现 $n 次" + if (c.undoneTodos.isNotEmpty()) " · ${c.undoneTodos.size} 件待办" else "",
                                    fontSize = 11.sp, color = LuyuanColors.Ink3
                                )
                            }
                        }
                    }
                }
            }
            // 本周最长的一句
            if (longest != null && longest.text.length >= 12) {
                item {
                    ReviewCard("本周最长的一句") {
                        Text(
                            "「" + longest.text.take(120) + (if (longest.text.length > 120) "…" else "") + "」",
                            fontSize = 13.sp, lineHeight = 24.sp, color = LuyuanColors.Ink1
                        )
                        val d = dayOf(longest)
                        Text(
                            "—— " + (if (d != null) "${d.monthValue}月${d.dayOfMonth}日 " else "") +
                                if (longest.tags.contains("日记")) "日记" else "笔记",
                            fontSize = 10.sp, color = LuyuanColors.Ink4,
                            modifier = Modifier.padding(top = 7.dp)
                        )
                    }
                }
            }
            item {
                Text(
                    "回顾只读展示，不生成任何记录",
                    fontSize = 10.sp, color = LuyuanColors.Ink4,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ReviewCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Text(
            title,
            fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = LuyuanColors.Ink3
        )
        Spacer(Modifier.height(9.dp))
        content()
    }
}

/** 金额显示：整数不带小数，带小数留一位（回顾页只读展示，不参与记账） */
internal fun formatMoney(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(java.util.Locale.CHINA, "%.1f", v)
