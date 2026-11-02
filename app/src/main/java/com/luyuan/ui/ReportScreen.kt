package com.luyuan.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.luyuan.data.Report
import com.luyuan.data.ReportStore
import com.luyuan.ui.design.LuyuanPalette
import com.luyuan.ui.design.LuyuanRadius
import com.luyuan.ui.design.LuyuanSection
import com.luyuan.ui.design.LuyuanSpace
import com.luyuan.ui.design.LuyuanTextAction
import com.luyuan.ui.design.LuyuanTopBar
import com.luyuan.ui.design.LuyuanType
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 路远远页（vc125 起，vc127 重排）：与电脑工作台「路远远」页签同源，SYNC_FORMAT §10.6。
 *
 * vc127 改的是「读起来累」：
 * - 不再整卡铺浅绿（未读=绿底会让一屏发绿），改成左侧 3dp 墨绿竖条 + 标题加粗，安静但一眼可辨。
 * - 标题 15.5 两行、摘要 12.5 两行、分类/时间一行 11sp；展开才给正文与操作（收起=纯阅读）。
 * - 顶栏给「未读 N · 共 M」和「全部已读」，不用再一条条点。
 * - 列表底部留 104dp，最后一条不会被悬浮胶囊压住。
 */
@Composable
fun ReportScreen(onBack: () -> Unit, embedded: Boolean = false) {
    val context = LocalContext.current
    var reports by remember { mutableStateOf(emptyList<Report>()) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<Report?>(null) }

    val reloadScope = rememberCoroutineScope()
    fun reload() {
        reloadScope.launch {
            reports = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ReportStore.list(context) }.getOrDefault(emptyList())
            }
        }
    }
    LaunchedEffect(Unit) { reload() }
    // ON_RESUME 回页即重读；页面常开每 30s 重读（Syncthing 静默落盘，无广播可听）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            reload()
        }
    }

    BackHandler(enabled = deleteTarget != null) { deleteTarget = null }

    val unread = remember(reports) { reports.filter { !it.read } }
    val seen = remember(reports) { reports.filter { it.read } }

    fun setRead(r: Report, read: Boolean) {
        reloadScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ReportStore.setRead(context, r.id, read)
            }
            reload()
        }
    }
    fun setAllRead() {
        if (unread.isEmpty()) return
        reloadScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                unread.forEach { r -> runCatching { ReportStore.setRead(context, r.id, true) } }
            }
            reload()
        }
    }
    fun del(r: Report) {
        reloadScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ReportStore.discard(context, r.id)
            }
            reload()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                LuyuanTopBar(
                    title = "路远远",
                    subtitle = if (reports.isEmpty()) "还没有汇报" else "未读 ${unread.size} · 共 ${reports.size}",
                    onBack = if (embedded) null else onBack,
                    actions = {
                        if (unread.isNotEmpty()) {
                            LuyuanTextAction("全部已读", onClick = { setAllRead() })
                        }
                    }
                )
            }
        ) { pad ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
            ) {
                if (reports.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.Campaign,
                        title = "还没有汇报",
                        subtitle = "路远远的晨报、提醒和讲解会写在这里，\n电脑工作台也能看到同一份。"
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = LuyuanSpace.gutter,
                            end = LuyuanSpace.gutter,
                            top = 2.dp,
                            bottom = 76.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (unread.isNotEmpty()) {
                            item { LuyuanSection("未读", unread.size) }
                            items(unread, key = { it.id }) { r ->
                                ReportRow(
                                    r = r,
                                    expanded = expandedId == r.id,
                                    onToggle = {
                                        val opening = expandedId != r.id
                                        expandedId = if (opening) r.id else null
                                        if (opening) setRead(r, true)   // 点开=读过（与 PC 同口径）
                                    },
                                    onSetRead = { setRead(r, !r.read) },
                                    onDelete = { deleteTarget = r }
                                )
                            }
                        }
                        if (seen.isNotEmpty()) {
                            item {
                                LuyuanSection(
                                    "已看过",
                                    seen.size,
                                    modifier = Modifier.padding(top = if (unread.isEmpty()) 0.dp else 10.dp)
                                )
                            }
                            items(seen, key = { it.id }) { r ->
                                ReportRow(
                                    r = r,
                                    expanded = expandedId == r.id,
                                    onToggle = { expandedId = if (expandedId == r.id) null else r.id },
                                    onSetRead = { setRead(r, false) },
                                    onDelete = { deleteTarget = r }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 删除确认（物理删，电脑和手机都会消失；软删可不确认，这里必须问一句）
        deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                containerColor = LuyuanPalette.SurfaceHigh,
                title = { Text("删掉这条汇报？", style = LuyuanType.cardTitle.copy(fontSize = 17.sp)) },
                text = {
                    Text(
                        (target.title.ifBlank { target.text }) + "\n\n电脑和手机都会删掉，不可恢复。",
                        style = LuyuanType.bodySmall
                    )
                },
                confirmButton = {
                    TextButton(onClick = { del(target); deleteTarget = null }) {
                        Text("删掉", color = LuyuanPalette.Red, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) {
                        Text("先留着", color = LuyuanPalette.Ink2)
                    }
                }
            )
        }
    }
}

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

/* 2026-10-29 复查：原来固定 "10/27 17:44"，和笔记页的「今天/昨天/周X」两套写法。
   统一成微信式：今天只给时:分，昨天带前缀，今年只给月日。 */
private fun fmtWhen(iso: String): String {
    val dt = runCatching { OffsetDateTime.parse(iso).toLocalDateTime() }
        .recoverCatching { java.time.LocalDateTime.parse(iso) }
        .getOrNull() ?: return iso.take(16).replace("T", " ")
    val now = java.time.LocalDateTime.now()
    val hm = dt.format(TIME_FMT)
    return when {
        dt.toLocalDate() == now.toLocalDate() -> hm
        dt.toLocalDate() == now.toLocalDate().minusDays(1) -> "昨天 $hm"
        dt.year == now.year -> "${dt.monthValue}月${dt.dayOfMonth}日 $hm"
        else -> "${dt.year}年${dt.monthValue}月${dt.dayOfMonth}日"
    }
}

@Composable
private fun ReportRow(
    r: Report,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSetRead: () -> Unit,
    onDelete: () -> Unit
) {
    val chevron by animateFloatAsState(
        if (expanded) 180f else 0f,
        tween(LuyuanMotion.Fast),
        label = "chevron"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(LuyuanRadius.lgShape)
            .background(LuyuanPalette.Surface)
            .border(1.dp, LuyuanPalette.Line, LuyuanRadius.lgShape)
            .clickable { onToggle() }
    ) {
        // 未读：一条 3dp 墨绿竖条，比整卡浅绿底安静得多
        if (!r.read) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(LuyuanPalette.Green700)
            )
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.category.ifBlank { "汇报" },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LuyuanPalette.Green700
                )
                Spacer(Modifier.width(7.dp))
                Text(fmtWhen(r.created_at), style = LuyuanType.caption)
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = LuyuanPalette.Ink4,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(chevron)
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                cleanReportTitle(r.title.ifBlank { r.text.take(30) }),
                fontSize = 15.5.sp,
                lineHeight = 21.sp,
                fontWeight = if (r.read) FontWeight.Medium else FontWeight.SemiBold,
                color = LuyuanPalette.Ink1,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis
            )
            // A 方案（2026-10-20 路河拍板）：收起=标题+两行摘要；点开才出正文
            if (!expanded) {
                val sum = cleanReportSummary(r.summary, r.text)
                if (sum.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        sum,
                        fontSize = 12.5.sp,
                        color = LuyuanPalette.Ink3,
                        lineHeight = 18.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Text(
                        cleanReportBody(r.text),
                        fontSize = 14.sp,
                        color = LuyuanPalette.Ink2,
                        lineHeight = 23.sp
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    LuyuanTextAction(if (r.read) "标未读" else "看过了", onClick = onSetRead)
                    Spacer(Modifier.width(4.dp))
                    LuyuanTextAction("删掉", onClick = onDelete, tone = LuyuanPalette.Red)
                }
            }
        }
    }
}

/* ---- 2026-10-29 复查：汇报格式清洗 ----
   问题一：分类签已经写了「提醒」，标题还顶一个「提醒：」——重复。
   问题二：路远远有时把「没说利害」「通知没写」这种自我说明当摘要，对人零信息量。
   生成侧已改提示词（steward.js），这里再兜一层，历史数据也能看。 */
private val META_SUM = Regex("^(没说|没提|通知没写|原文没写|原文没提|未提及|不清楚|不详|未知)")

internal fun cleanReportTitle(t: String): String {
    var s = t.trim()
    val urgent = Regex("^【?(急|重要)】?").containsMatchIn(s)
    s = s.replace(Regex("^【?(急|重要)】?\\s*"), "")
        .replace(Regex("^(提醒|晨报|简报|来件|通知|管家)\\s*[:：]\\s*"), "")
    return (if (urgent) "【急】" else "") + s
}

internal fun stripMark(s: String): String = s
    .replace(Regex("^[①-⑮]\\s*"), "")
    .replace(Regex("^\\d{1,2}\\s*[、.．]\\s*"), "")
    .replace(Regex("^[一二三四五六七八九十]{1,3}\\s*、\\s*"), "")
    .trim()

internal fun cleanReportSummary(summary: String, text: String): String {
    val s = stripMark(if (summary.isBlank()) text.lineSequence().firstOrNull() ?: "" else summary)
    if (s.isNotEmpty() && !META_SUM.containsMatchIn(s)) return s
    val first = stripMark(text.lineSequence().firstOrNull() ?: "")
    if (first.isNotEmpty() && !META_SUM.containsMatchIn(first)) return first
    return "原文没写影响，点开看全文"
}

/* 2026-10-29 复查：正文里的「①没说利害」整行是机器的自我说明，删掉并重新编号；
   完全相同的整行只留一次（逾期列表里有重复抽出的同一条待办）。 */
private val MARKS = listOf("①","②","③","④","⑤","⑥","⑦","⑧","⑨","⑩")
private val NUM_LINE = Regex("^\\s*(?:[①-⑩]|\\d+[、.．])\\s*(.+)$")

internal fun cleanReportBody(t: String): String {
    val seen = HashSet<String>()
    val kept = ArrayList<String>()
    for (raw in t.split("\n")) {
        val line = raw.trimEnd()
        if (line.isBlank()) { if (kept.isNotEmpty() && kept.last().isNotEmpty()) kept.add(""); continue }
        val m = NUM_LINE.matchEntire(line)
        val content = m?.groupValues?.get(1)?.trim()
        if (content != null && META_SUM.containsMatchIn(content)) continue
        if (!seen.add(line)) continue
        kept.add(line)
    }
    var n = 0
    while (kept.isNotEmpty() && kept.last().isEmpty()) kept.removeAt(kept.size - 1)
    return kept.joinToString("\n") { line ->
        val m = NUM_LINE.matchEntire(line)
        if (m == null) line
        else {
            val mark = if (n < MARKS.size) MARKS[n] else "${n + 1}、"
            n++
            mark + " " + m.groupValues[1].trim()
        }
    }
}
