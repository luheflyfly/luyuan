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
                            bottom = 104.dp
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

private val TIME_FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm")

private fun fmtWhen(iso: String): String = try {
    OffsetDateTime.parse(iso).format(TIME_FMT)
} catch (_: Exception) {
    iso.take(16).replace("T", " ")
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
                if (!r.read) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(LuyuanPalette.Green700, CircleShape)
                    )
                    Spacer(Modifier.width(7.dp))
                }
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
                r.title.ifBlank { r.text.take(30) },
                fontSize = 15.5.sp,
                lineHeight = 21.sp,
                fontWeight = if (r.read) FontWeight.Medium else FontWeight.SemiBold,
                color = LuyuanPalette.Ink1,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis
            )
            // A 方案（2026-10-20 路河拍板）：收起=标题+两行摘要；点开才出正文
            if (!expanded) {
                val sum = (r.summary.ifBlank { r.text.lineSequence().firstOrNull() ?: "" })
                    .replace(Regex("^[①-⑮0-9一二三四五六七八九十、.·\\s]+"), "")
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
                        r.text,
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
