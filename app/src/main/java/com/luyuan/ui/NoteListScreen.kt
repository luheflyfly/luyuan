package com.luyuan.ui

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luyuan.domain.Note
import com.luyuan.platform.PermissionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** 自实现下拉刷新：列表到顶继续下拉累计 overPull，松手超过阈值触发刷新。
 *  不用任何 pullrefresh 库 API——不同 compose/material 版本签名差异太大，CI 连败两次的教训。
 *  刷新资格（用户口径）：本手势中途若滚过列表（consumed.y>0）→ 只给跟手皮筋不触发刷新；
 *  快速滚到顶的惯性余量（Fling）一律不参与；必须顶端静止后新起一次下滑才刷新。 */
private class PullRefreshConnection(
    private val thresholdPx: Float,
    private val onRefresh: () -> Unit
) : NestedScrollConnection {
    var overPull by mutableFloatStateOf(0f)
        private set

    private var sawListScroll = false
    private var lastCall = 0L

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // 手指上滑时先把拉出的距离收回去，收完才放行给列表滚动
        if (overPull > 0f && available.y < 0f) {
            val take = available.y.coerceAtLeast(-overPull)
            overPull += take
            return Offset(0f, take)
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val now = System.currentTimeMillis()
        if (now - lastCall > 300) sawListScroll = false // 与上帧间隔久 = 新的一次触摸
        lastCall = now
        if (source != NestedScrollSource.Drag) {
            // 惯性滚动冲到顶的余量：既不拉皮筋也不触发刷新
            return Offset.Zero
        }
        if (consumed.y > 0f) {
            // 本手势还在滚列表（没到顶）→ 取消刷新资格，之后到顶只剩跟手皮筋
            sawListScroll = true
            return Offset.Zero
        }
        if (available.y > 0f && !sawListScroll) {
            // 已在顶端、且本手势是从顶端开始的刻意下拖 → 皮筋跟手
            overPull = (overPull + available.y * 0.5f).coerceAtMost(thresholdPx * 1.25f)
            return Offset(0f, available.y)
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!sawListScroll && overPull >= thresholdPx) {
            overPull = thresholdPx // 触发后停在标准位置，刷新完成由 refreshDone 收回
            onRefresh()
        } else {
            overPull = 0f // 没拉到阈值也立刻弹回，不用手动收
        }
        sawListScroll = true // 手势结束，下个手势靠 300ms 间隔判定重置
        return Velocity.Zero
    }

    fun reset() {
        overPull = 0f
    }
}

// ---------- 日期分组 ----------

private fun parseDay(created: String): LocalDate? = try {
    OffsetDateTime.parse(created).toLocalDate()
} catch (_: Exception) {
    try {
        LocalDateTime.parse(created).toLocalDate()
    } catch (_: Exception) {
        null
    }
}

private fun formatTime(created: String): String {
    val dt: LocalDateTime? = try {
        OffsetDateTime.parse(created).toLocalDateTime()
    } catch (_: Exception) {
        try { LocalDateTime.parse(created) } catch (_: Exception) { null }
    }
    return dt?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: ""
}

/** 微信式相对日期：今天 / 昨天 / 周X（7天内）/ 2026.9.5 */
private fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    val dow = when (d.dayOfWeek.value) {
        1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; 6 -> "六"; else -> "日"
    }
    return when {
        d == today -> "今天"
        d == today.minusDays(1) -> "昨天"
        d.isAfter(today.minusDays(7)) -> "周$dow"
        else -> "${d.year}.${d.monthValue}.${d.dayOfMonth}"
    }
}

private data class DayGroup(val label: String, val notes: List<Note>)

private fun groupByDay(notes: List<Note>): List<DayGroup> {
    val out = mutableListOf<DayGroup>()
    for (n in notes) {
        val d = parseDay(n.created_at)
        val label = if (d == null) "更早" else dayLabel(d)
        if (out.isNotEmpty() && out.last().label == label) {
            out[out.size - 1] = out.last().copy(notes = out.last().notes + n)
        } else {
            out.add(DayGroup(label, listOf(n)))
        }
    }
    return out
}

// ---------- 心情时间线（SenseVoice 情绪 emoji 按天统计） ----------

private val MOOD_EMOJIS = setOf("😊", "😡", "😮", "😔", "😰", "🤢")

private fun shortDayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when {
        d == today -> "今"
        d == today.minusDays(1) -> "昨"
        else -> when (d.dayOfWeek.value) {
            1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; 6 -> "六"; else -> "日"
        }
    }
}

private fun moodByDay(notes: List<Note>): List<Pair<LocalDate, Map<String, Int>>> {
    val today = LocalDate.now()
    val grouped = HashMap<LocalDate, MutableMap<String, Int>>()
    for (n in notes) {
        val d = parseDay(n.created_at) ?: continue
        // emoji 在 UTF-16 是代理对，必须按 code point 遍历（PC 端踩过同样的坑）
        var i = 0
        while (i < n.text.length) {
            val cp = n.text.codePointAt(i)
            val s = String(Character.toChars(cp))
            if (s in MOOD_EMOJIS) {
                grouped.getOrPut(d) { HashMap() }.merge(s, 1) { a, b -> a + b }
            }
            i += Character.charCount(cp)
        }
    }
    return (6 downTo 0).map { off ->
        val d = today.minusDays(off.toLong())
        d to (grouped[d]?.toMap() ?: emptyMap())
    }
}

private fun moodSummary(counts: Map<String, Int>): String =
    if (counts.isEmpty()) "·" else counts.entries.joinToString("") { "${it.key}${it.value}" }

// ---------- 卡片文案 ----------

/** 首行（≤16字）当标题，剩余当摘要 */
private fun splitTitleSummary(text: String): Pair<String, String> {
    val lines = text.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return "" to ""
    val first = lines[0].trim()
    val title = if (first.length > 16) first.take(16) + "…" else first
    val rest = (lines.drop(1).joinToString(" ")).ifBlank {
        if (first.length > 16) first.drop(16) else ""
    }
    return title to rest.trim().take(64)
}

/** 搜索命中高亮（琥珀底，同 PC 面板） */
private fun highlighted(text: String, query: String): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return AnnotatedString(text)
    val lower = text.lowercase()
    val qLower = q.lowercase()
    return buildAnnotatedString {
        append(text)
        var i = 0
        while (true) {
            val found = lower.indexOf(qLower, i)
            if (found < 0) break
            addStyle(
                SpanStyle(background = Color(0xFFFEF08A), color = Color(0xFF713F12)),
                found, found + q.length
            )
            i = found + q.length
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(
    vm: LuyuanViewModel,
    onRecord: () -> Unit,
    onDetail: (String) -> Unit,
    onSettings: () -> Unit,
    onTrash: () -> Unit,
    onReview: () -> Unit = {}
) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    val moodEnabled by vm.moodEnabled.collectAsStateWithLifecycle()
    // 搜索词由悬浮胶囊的搜索态提供（v1.14.2 起主页不再有常驻搜索框/输入框）
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    // 多选状态上报 VM（路河拍板：多选时 MainActivity 收起悬浮胶囊）
    LaunchedEffect(selecting) { vm.setMultiSelect(selecting) }
    // 多选界面返回手势 = 退多选，不退出应用（路河 09-10 反馈）
    androidx.activity.compose.BackHandler(enabled = selecting) {
        selecting = false
        selected = emptySet()
    }
    val context = LocalContext.current
    val allFilesGranted = PermissionHelper.hasAllFiles(context)
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    // 消息待办 · 待确认（立项单 T3）：备用机监听到的消息经云端抽取后先落这里，
    // 用户点 ✓ 才写成正式 todo_<id>.json 同步回电脑；✗ 直接丢。
    var pendingTodos by remember { mutableStateOf(com.luyuan.data.PendingMessageTodoStore.list(context)) }

    fun confirmPendingTodo(p: com.luyuan.data.PendingMessageTodo) {
        try {
            val t = com.luyuan.data.TodoStore.fromPending(p)
            com.luyuan.data.TodoStore.write(context, t)
        } catch (_: Exception) {
        }
        com.luyuan.data.PendingMessageTodoStore.remove(context, p.id)
        pendingTodos = com.luyuan.data.PendingMessageTodoStore.list(context)
        vm.refresh()
    }

    fun discardPendingTodo(p: com.luyuan.data.PendingMessageTodo) {
        com.luyuan.data.PendingMessageTodoStore.remove(context, p.id)
        pendingTodos = com.luyuan.data.PendingMessageTodoStore.list(context)
    }

    // 每次回到列表都重新读盘，授权后/同步后立刻可见
    LaunchedEffect(Unit) {
        vm.refresh()
        pendingTodos = com.luyuan.data.PendingMessageTodoStore.list(context)
    }

    val filtered = remember(notes, query) {
        if (query.isBlank()) notes else notes.filter {
            it.text.contains(query, ignoreCase = true) ||
                    it.tags.any { t -> t.contains(query, ignoreCase = true) }
        }
    }
    val groups = remember(filtered) { groupByDay(filtered) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selecting) "已选 ${selected.size}" else "路远 · 记事",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = {
                            selected = if (selected.size == filtered.size) emptySet()
                            else filtered.map { it.id }.toSet()
                        }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "全选/全不选")
                        }
                        // 多选分享：把选中笔记按时间顺序拼成纯文本，走系统分享（微信/QQ等都能收）
                        IconButton(
                            enabled = selected.isNotEmpty(),
                            onClick = {
                                val body = notes.filter { it.id in selected }
                                    .sortedByDescending { it.created_at }
                                    .joinToString("\n\n") { n ->
                                        val dt = try {
                                            OffsetDateTime.parse(n.created_at).toLocalDateTime()
                                                .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
                                        } catch (_: Exception) {
                                            ""
                                        }
                                        (if (dt.isNotBlank()) "〔$dt〕" else "") + n.text
                                    }
                                try {
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, body)
                                            },
                                            "分享 ${selected.size} 条笔记"
                                        )
                                    )
                                } catch (_: Exception) {
                                }
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "分享选中")
                        }
                        IconButton(
                            enabled = selected.size >= 2,
                            onClick = {
                                // 一键整理：合并选中笔记为一条（原笔记进回收站可恢复）
                                vm.mergeNotes(selected)
                                selected = emptySet()
                                selecting = false
                            }
                        ) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = "整理合并")
                        }
                        IconButton(
                            enabled = selected.isNotEmpty(),
                            onClick = {
                                vm.deleteMany(selected)
                                selected = emptySet()
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "删除选中")
                        }
                    }
                    IconButton(onClick = {
                        selecting = !selecting
                        if (!selecting) selected = emptySet()
                    }) {
                        Icon(
                            if (selecting) Icons.Default.Close else Icons.Default.Checklist,
                            contentDescription = "多选"
                        )
                    }
                    IconButton(onClick = { vm.toggleMood() }) {
                        Icon(
                            Icons.Default.Mood,
                            contentDescription = "心情时间线开关",
                            tint = if (moodEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onReview) {
                        Icon(Icons.Default.BarChart, contentDescription = "本周回顾")
                    }
                    IconButton(onClick = onTrash) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "回收站")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { padding ->
        val refreshing by vm.refreshing.collectAsStateWithLifecycle()
        val refreshDone by vm.refreshDone.collectAsStateWithLifecycle()

        // 下拉刷新：松手超阈值触发 vm.refresh()；刷新完成（refreshDone 变化）自动收回
        val density = LocalDensity.current
        val thresholdPx = remember(density) { with(density) { 110.dp.toPx() } }
        val indicatorSizePx = remember(density) { with(density) { 44.dp.toPx() } }
        val pullConn = remember(thresholdPx) { PullRefreshConnection(thresholdPx) { vm.refresh() } }
        LaunchedEffect(refreshDone) { pullConn.reset() }

        Box(modifier = Modifier.fillMaxSize().nestedScroll(pullConn)) {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (!allFilesGranted) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "需要「所有文件访问」权限才能读取 Syncthing 同步目录，否则看不到电脑同步来的笔记。",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(6.dp))
                            Button(onClick = {
                                context.startActivity(PermissionHelper.allFilesSettingsIntent())
                            }) { Text("去开启") }
                        }
                    }
                }
                // 搜索态（视觉规范稿 search-and-overlay.html A）：绿描边胶囊 + 命中数徽章 + ✕ 一键清词
                if (query.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(999.dp))
                            .border(
                                1.5.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(999.dp)
                            )
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.size(9.dp))
                        Text(
                            highlighted(query, query),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = LuyuanColors.Ink1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "命中 ${filtered.size} / ${notes.size}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(999.dp)
                            ).padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                        Spacer(Modifier.size(8.dp))
                        Box(
                            modifier = Modifier.size(20.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    CircleShape
                                )
                                .clickable { vm.setSearchQuery("") },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "清空搜索",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
                if (moodEnabled) {
                    MoodBar(notes)
                }
                if (refreshing) {
                    Text(
                        "刷新中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                        .offset { IntOffset(0, pullConn.overPull.roundToInt()) } // 皮筋跟手：顶端下拖时整列随指下移
                        .padding(horizontal = 12.dp)
                        .padding(top = 4.dp)
                ) {
                    // 消息待办 · 待确认（置顶：需要用户裁决，别让它在列表里被淹没）
                    if (pendingTodos.isNotEmpty()) {
                        item(key = "pending_todos_${pendingTodos.size}") {
                            PendingTodoSection(
                                items = pendingTodos,
                                onConfirm = { confirmPendingTodo(it) },
                                onDiscard = { discardPendingTodo(it) }
                            )
                        }
                    }
                    for (g in groups) {
                        item(key = "h_${g.label}_${g.notes.size}") {
                            // 多选态：点日期分组头 = 选/不选这一整天（稿 multiselect.html「按日期全选」）
                            val dayIds = g.notes.map { it.id }
                            val dayAllIn = selecting && dayIds.isNotEmpty() && dayIds.all { it in selected }
                            Row(
                                modifier = Modifier
                                    .padding(top = 10.dp, bottom = 2.dp)
                                    .then(
                                        if (selecting) Modifier.clickable {
                                            selected = if (dayAllIn) selected - dayIds else selected + dayIds
                                        } else Modifier
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    g.label,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF374151),
                                    fontSize = 14.sp
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    "${g.notes.size} 条",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (selecting) {
                                    Spacer(Modifier.size(8.dp))
                                    Text(
                                        if (dayAllIn) "✓ 取消这天" else "选这一整天",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = LuyuanColors.Green700
                                    )
                                }
                            }
                        }
                        items(g.notes, key = { it.id }) { note ->
                            NoteCard(
                                note = note,
                                query = query,
                                selecting = selecting,
                                isSelected = note.id in selected,
                                onClick = { onDetail(note.id) },
                                onLongClick = {
                                    // 长按 = 进入多选并选中这条（等效点多选按钮）
                                    if (!selecting) {
                                        selecting = true
                                        selected = selected + note.id
                                    } else {
                                        selected = if (note.id in selected) selected - note.id
                                        else selected + note.id
                                    }
                                },
                                onToggleSelect = {
                                    selected = if (note.id in selected) selected - note.id
                                    else selected + note.id
                                }
                            )
                        }
                    }
                    if (groups.isEmpty()) {
                        item {
                            if (query.isNotBlank())
                                EmptyState(EmptyIconSearch, "没有匹配的笔记", "换个关键词试试")
                            else
                                EmptyState(EmptyIconNote, "还没有笔记", "在顶部输入框随手记一条，回车即存")
                        }
                    }
                }
            }
            if (pullConn.overPull > 2f) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset {
                            IntOffset(
                                0,
                                (pullConn.overPull - indicatorSizePx).roundToInt().coerceAtLeast(0)
                            )
                        }
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(8.dp).size(28.dp),
                        strokeWidth = 3.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun MoodBar(notes: List<Note>) {
    val days = remember(notes) { moodByDay(notes) }
    if (days.all { it.second.isEmpty() }) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            for ((date, counts) in days) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        shortDayLabel(date),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(moodSummary(counts), fontSize = 13.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    note: Note,
    query: String = "",
    selecting: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onToggleSelect: () -> Unit = {}
) {
    val (title, summary) = remember(note.text) { splitTitleSummary(note.text) }
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = {
                if (selecting) onToggleSelect() else onClick()
            },
            onLongClick = onLongClick
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (selecting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
                    Text(
                        if (isSelected) "已选择" else "选择",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (title.isNotEmpty()) {
                Text(
                    text = highlighted(title, query),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFF111827),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (summary.isNotEmpty()) {
                Text(
                    text = highlighted(summary, query),
                    color = Color(0xFF6B7280),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 7.dp)
            ) {
                Badge(
                    if (note.source == "voice") "语音" else "手动",
                    if (note.source == "voice") Color(0xFF059669) else Color(0xFF1D4ED8)
                )
                if (note.device == "phone") Badge("手机", Color(0xFF6B7280))
                if (note.transcribed == false) Badge("⏳ 待转写", Color(0xFFD97706))
                if (note.audio != null && note.transcribed == true) Badge("🎙 原声", Color(0xFF059669))
                remindBadge(note)
                for (t in note.tags.take(3)) Badge(t, Color(0xFF6B7280))
                Spacer(Modifier.size(2.dp))
                Text(
                    formatTime(note.created_at),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 提醒徽章：读 SYNC_FORMAT 的 remind_at（电脑端设的提醒同步过来也能看到） */
@Composable
private fun remindBadge(note: Note) {
    val ra = note.remind_at ?: return
    if (ra.isBlank()) return
    val shown: String = try {
        OffsetDateTime.parse(ra).toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("M.d HH:mm"))
    } catch (_: Exception) {
        try {
            LocalDateTime.parse(ra).format(DateTimeFormatter.ofPattern("M.d HH:mm"))
        } catch (_: Exception) {
            ra // 兜底显示原文
        }
    }
    Badge("⏰ $shown", Color(0xFFD97706))
}

// ---------- 消息待办 · 待确认（立项单 2026-09-13 T3） ----------

/**
 * 置顶的待确认区：备用机监听到的微信/QQ 消息，经云端抽取后落在这里。
 * 用户点 ✓ 才写成正式 todo_<id>.json（同步回电脑），✗ 直接丢。分级确认是全项目命门。
 */
@Composable
private fun PendingTodoSection(
    items: List<com.luyuan.data.PendingMessageTodo>,
    onConfirm: (com.luyuan.data.PendingMessageTodo) -> Unit,
    onDiscard: (com.luyuan.data.PendingMessageTodo) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "📬 消息待办 · 待确认",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.5.sp,
                color = LuyuanColors.Amber
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "${items.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        for (p in items) {
            PendingTodoCard(p, onConfirm, onDiscard)
        }
    }
}

@Composable
private fun PendingTodoCard(
    p: com.luyuan.data.PendingMessageTodo,
    onConfirm: (com.luyuan.data.PendingMessageTodo) -> Unit,
    onDiscard: (com.luyuan.data.PendingMessageTodo) -> Unit
) {
    val srcName = if (p.source == "wechat") "微信" else "QQ"
    val time = runCatching {
        OffsetDateTime.parse(p.created_at).toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrDefault("")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, LuyuanColors.Amber, RoundedCornerShape(12.dp))
    ) {
        // 头：来源行（琥珀底）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .background(
                    LuyuanColors.AmberBg,
                    RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Box(Modifier.size(6.dp).background(LuyuanColors.Amber, CircleShape))
            Spacer(Modifier.size(6.dp))
            Text(
                "来自 $srcName" +
                    (if (p.sender.isNotBlank()) " · ${p.sender}" else "") +
                    (if (time.isNotBlank()) " · $time" else ""),
                fontSize = 9.5.sp,
                color = LuyuanColors.Amber
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 抽出来的事项（主角）
            Text(
                p.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = LuyuanColors.Ink1
            )
            if (p.whenText.isNotBlank()) {
                Text(
                    "时间：${p.whenText}",
                    fontSize = 11.sp,
                    color = LuyuanColors.Ink2
                )
            }
            // 原文（核对用，最多两行）
            if (p.raw.isNotBlank()) {
                Text(
                    "原文：${p.raw}",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Button(
                    onClick = { onConfirm(p) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    Text("✓ 收下", fontSize = 12.sp)
                }
                Button(
                    onClick = { onDiscard(p) },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = LuyuanColors.Green50,
                        contentColor = LuyuanColors.Ink2
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    Text("✗ 不要", fontSize = 12.sp)
                }
            }
        }
    }
}
