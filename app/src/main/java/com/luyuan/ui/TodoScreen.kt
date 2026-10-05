package com.luyuan.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luyuan.data.MessageSettings
import com.luyuan.data.PendingMessageTodo
import com.luyuan.data.Todo
import com.luyuan.data.TodoStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 待办页（09-14 路河拍板独立成子界面；09-15 夜 vc79 单列表混排重构）：
 * ①单一「未办」列表：待确认消息待办（琥珀底+「待确认」徽章）与正式待办按截止时间混排——
 *   确认卡本身就是一张待办卡（路河 ⑥），勾选=已完成一步办掉，小字「收下」=入未办，✕=不要。
 * ②「已办」分区：done 的消息待办可点取消。
 * ③不计入待办：名单命中（who/sender）整行隐藏（PC 端生成的同样被滤）；待确认卡长按可快捷加入名单。
 * 截止时间 = remind_at（联系人）或 when_text/原文的确定性解析（今天/明天/后天/周X/M月D日/HH:MM…），
 * 解析不出=无期限。到点判断只做展示（红色「已过期」），提醒仍归各自原有管线。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TodoScreen(vm: LuyuanViewModel, onBack: () -> Unit, embedded: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    var msgTodos by remember { mutableStateOf(TodoStore.list(context)) }
    var excluded by remember { mutableStateOf(MessageSettings.excludedContacts(context)) }
    // vc113 路河拍板改版：列表退役，进页即时间轴——他只看今天要做什么；已逾期/已完成各一页。
    // 待确认视角摘掉（vc108 起全量直收，队列只剩历史存量；存量靠长按/通知键处理）
    var view by remember { mutableStateOf("timeline") }
    var deleteTarget by remember { mutableStateOf<Todo?>(null) }
    var clearDoneOpen by remember { mutableStateOf(false) }

    fun reload() {
        // 2026-09-15 治「返回笔记页卡死」：本页动作只局部读盘，不调 vm.refresh()
        msgTodos = TodoStore.list(context)
        excluded = MessageSettings.excludedContacts(context)
        // vc98：进页补抽满窗缓冲——进程被杀后监听器定时器丢了，靠这条把攒着的消息抽掉
        com.luyuan.platform.MessageNotificationListener.flushDueNow(context)
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { reload() }
    // vc108 热刷新两路（路河：通知弹了待办、界面不立即刷新）：
    // ①从通知/别的页回到本页（ON_RESUME）即重读盘；②页面一直开着时每 30s 重读。
    // 只局部读盘，不碰 vm.refresh()（09-15 教训：返回笔记页卡死）。
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            reload()
        }
    }

    fun toggleMsg(t: Todo) {
        try {
            // vc98：fresh 读盘后只改完成态写回——旧写法拿页面旧副本整文件覆盖，会把 PC 侧同步来的改动冲掉
            TodoStore.setDone(context, t.id, !t.done)
        } catch (_: Exception) {
        }
        reload()
    }
    fun deleteMsg(t: Todo) {
        try {
            // vc98：手机端删除=软删墓碑（deleted:true 同步回 PC，双端列表都过滤）
            TodoStore.deleteSoft(context, t.id)
        } catch (_: Exception) {
        }
        reload()
    }

    data class Row(
        val key: String, val text: String, val who: String,
        val score: Long, val dueLabel: String, val origin: String,
        val isPending: Boolean = false,
        val msgItem: Todo? = null
    )

    // 2026-09-18 检查批：now 每 30s 走一次（原 remember 固定在进页时刻，页面久开过期红字不刷新）
    val now by androidx.compose.runtime.produceState(LocalDateTime.now()) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            value = LocalDateTime.now()
        }
    }
    val rows = remember(contacts, msgTodos, pending, excluded, now) {
        val out = mutableListOf<Row>()
        for (c in contacts) {
            for (t in c.undoneTodos) {
                val due = t.remind_at?.takeIf { it.isNotBlank() }
                out.add(
                    Row(
                        key = "ct_${c.id}_${t.id}", text = t.text, who = c.name,
                        score = deadlineScore("", due, t.created_at, now),
                        dueLabel = dueLabel(t.remind_at, "", now), origin = "人脉"
                    )
                )
            }
        }
        for (t in msgTodos.filter { !it.done }) {
            // 不计入名单：who/sender 命中即整行隐藏（含 PC 端同步来的）
            if (MessageSettings.isExcluded(context, t.who)) continue
            if (MessageSettings.isExcluded(context, t.sender)) continue
            out.add(
                Row(
                    key = "msg_${t.id}", text = t.text, who = t.who.ifBlank { t.sender },
                    // vc98：due_at（绝对截止）优先，when_text 原文兜底
                    // vc107 修装反：deadlineScore(whenText, remindAt)=（原文, 绝对时间）——
                    // vc98 起 due_at 被错塞进 whenText 位，ISO 没走到 parseIso，回落把 ISO 里的
                    // "12:00" 当"今天 12:00"（时间轴全进今天桶/未过期标红）；dueLabel 本来就对，
                    // 所以出现"列表日期对、时间轴错、红色误报"三症状一根因
                    score = deadlineScore(t.when_text, t.due_at.takeIf { it.isNotBlank() }, t.created_at, now),
                    dueLabel = dueLabel(t.due_at.takeIf { it.isNotBlank() }, t.when_text, now),
                    origin = if (t.device == "pc") "电脑" else "消息", // PC msgdigest 同步件标注
                    msgItem = t
                )
            )
        }
        out.sortedBy { it.score }
    }
    val doneMsgs = remember(msgTodos, excluded) {
        msgTodos.filter { it.done }
            .filter { !MessageSettings.isExcluded(context, it.who) && !MessageSettings.isExcluded(context, it.sender) }
    }

    // vc108 三视图拆分（路河拍板）：活任务（列表/时间轴）/ 待确认（单开一页）/ 已过期（单开一页）。
    // 待确认从列表/时间轴里整体移出；过期的从主视图移出只在过期页出现。
    val (activeRows, expiredRows) = remember(rows) {
        val ms = System.currentTimeMillis()
        (rows.filter { !it.isPending && (it.score == Long.MAX_VALUE || it.score >= ms) }) to
            (rows.filter { !it.isPending && it.score != Long.MAX_VALUE && it.score < ms })
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("待办", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "未办 ${activeRows.size} 件",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    if (!embedded) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
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
            // vc113：三视角（路河拍板）——时间轴（首页，今天要做什么）/ 已过期 / 已完成
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 2.dp)
            ) {
                SegChip("时间轴", view == "timeline") { view = "timeline" }
                Spacer(Modifier.width(8.dp))
                SegChip("已逾期·${expiredRows.size}", view == "expired") { view = "expired" }
                Spacer(Modifier.width(8.dp))
                SegChip("已完成·${doneMsgs.size}", view == "done") { view = "done" }
            }
            if (view == "expired") {
                ExpiredSection(
                    trows = expiredRows.map {
                        TRow(it.key, it.text, it.who, it.score, it.dueLabel, it.origin, it.isPending)
                    },
                    onToggle = { key ->
                        if (key.startsWith("msg_")) {
                            msgTodos.firstOrNull { it.id == key.removePrefix("msg_") }?.let { toggleMsg(it) }
                        } else {
                            val parts = key.removePrefix("ct_").split("_", limit = 2)
                            if (parts.size == 2) { vm.toggleContactTodo(parts[0], parts[1]); reload() }
                        }
                    },
                    onDelete = { key ->
                        msgTodos.firstOrNull { it.id == key.removePrefix("msg_") }?.let { deleteTarget = it }
                    }
                )
            } else if (view == "done") {
                // vc113：已完成独立页（可点取消完成；清空入口）
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)
                ) {
                    item(key = "done_head") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "已完成 ${doneMsgs.size} 条",
                                fontWeight = FontWeight.ExtraBold, color = LuyuanColors.Ink3, fontSize = 13.sp,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                            )
                            Spacer(Modifier.weight(1f))
                            if (doneMsgs.isNotEmpty()) {
                                Text(
                                    "清空", fontSize = 11.sp, color = LuyuanColors.Ink4,
                                    modifier = Modifier
                                        .padding(top = 10.dp, bottom = 2.dp)
                                        .clickable { clearDoneOpen = true }
                                )
                            }
                        }
                    }
                    if (doneMsgs.isEmpty()) {
                        item(key = "done_empty") {
                            Text(
                                "还没有完成的待办。做完一条它会从时间轴挪到这里。",
                                fontSize = 12.sp, color = LuyuanColors.Ink4
                            )
                        }
                    }
                    for (t in doneMsgs) {
                        item(key = "done_${t.id}") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                                    .padding(horizontal = 12.dp, vertical = 9.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .background(LuyuanColors.Green700, CircleShape)
                                        .clickable { toggleMsg(t) }
                                ) { Icon(Icons.Default.Check, "取消完成", tint = Color.White, modifier = Modifier.size(13.dp)) }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    t.text, fontSize = 12.5.sp, color = LuyuanColors.Ink3,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            } else {
                // vc113：时间轴=首页（路河只看今天要做什么）——未办按截止日分桶
                TimelineView(
                    trows = activeRows.map {
                        TRow(it.key, it.text, it.who, it.score, it.dueLabel, it.origin, it.isPending)
                    },
                    now = now,
                    onToggle = { key ->
                        if (key.startsWith("msg_")) {
                            msgTodos.firstOrNull { it.id == key.removePrefix("msg_") }?.let { toggleMsg(it) }
                        } else {
                            val parts = key.removePrefix("ct_").split("_", limit = 2)
                            if (parts.size == 2) { vm.toggleContactTodo(parts[0], parts[1]); reload() }
                        }
                    }
                )
            }
        }
    }


    // ---------- vc98：长按正式待办 = 删除（软删墓碑，双端消失） ----------
    deleteTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这条待办？", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Text(
                    "「${t.text.take(24)}」将标记删除，电脑端同步消失；不影响其他待办。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteMsg(t); deleteTarget = null }) {
                    Text("删除", color = LuyuanColors.Red, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }

    // ---------- vc98：清空已办（已办 36 条堆积的出口；墓碑同步双端） ----------
    if (clearDoneOpen) {
        AlertDialog(
            onDismissRequest = { clearDoneOpen = false },
            title = { Text("清空已办 ${doneMsgs.size} 条？", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = { Text("已完成且不用留底的待办将标记删除，电脑端同步消失。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        TodoStore.clearDone(context)
                    } catch (_: Exception) {
                    }
                    clearDoneOpen = false
                    reload()
                }) { Text("清空", color = LuyuanColors.Red, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { clearDoneOpen = false }) { Text("取消") } }
        )
    }
}
