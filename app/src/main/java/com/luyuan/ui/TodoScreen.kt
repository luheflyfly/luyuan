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
import com.luyuan.data.PendingMessageTodoStore
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
    var pending by remember { mutableStateOf(PendingMessageTodoStore.list(context)) }
    var msgTodos by remember { mutableStateOf(TodoStore.list(context)) }
    var excluded by remember { mutableStateOf(MessageSettings.excludedContacts(context)) }
    var excludeTarget by remember { mutableStateOf<PendingMessageTodo?>(null) }
    // vc98：列表/时间轴视角切换 + 手机端删除/清空已办（路河拍板三项）
    var view by remember { mutableStateOf("list") }
    var deleteTarget by remember { mutableStateOf<Todo?>(null) }
    var clearDoneOpen by remember { mutableStateOf(false) }

    fun reload() {
        // 2026-09-15 治「返回笔记页卡死」：本页动作只局部读盘，不调 vm.refresh()
        pending = PendingMessageTodoStore.list(context)
        msgTodos = TodoStore.list(context)
        excluded = MessageSettings.excludedContacts(context)
        // vc98：进页补抽满窗缓冲——进程被杀后监听器定时器丢了，靠这条把攒着的消息抽掉
        com.luyuan.platform.MessageNotificationListener.flushDueNow(context)
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { reload() }

    fun keep(p: PendingMessageTodo) {
        try {
            // vc98：入库带跨库查重（同文本未办已存在→不再建第二条）
            TodoStore.createFromPending(context, p)
        } catch (_: Exception) {
        }
        PendingMessageTodoStore.remove(context, p.id)
        reload()
    }
    fun discard(p: PendingMessageTodo) {
        PendingMessageTodoStore.remove(context, p.id)
        reload()
    }
    fun completePending(p: PendingMessageTodo) {
        try {
            TodoStore.createFromPending(context, p, done = true)
        } catch (_: Exception) {
        }
        PendingMessageTodoStore.remove(context, p.id)
        reload()
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
        val pendingItem: PendingMessageTodo? = null,
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
                    score = deadlineScore(t.due_at, t.when_text, t.created_at, now),
                    dueLabel = dueLabel(t.due_at.takeIf { it.isNotBlank() }, t.when_text, now),
                    origin = if (t.device == "pc") "电脑" else "消息", // PC msgdigest 同步件标注
                    msgItem = t
                )
            )
        }
        for (p in pending) {
            if (MessageSettings.isExcluded(context, p.sender)) continue
            if (MessageSettings.isExcluded(context, p.who)) continue
            out.add(
                Row(
                    key = "p_${p.id}", text = p.text, who = p.sender.ifBlank { p.who },
                    score = deadlineScore(p.dueIso, p.whenText, p.created_at, now),
                    dueLabel = dueLabel(p.dueIso.takeIf { it.isNotBlank() }, p.whenText, now), origin = "消息",
                    isPending = true, pendingItem = p
                )
            )
        }
        out.sortedBy { it.score }
    }
    val doneMsgs = remember(msgTodos, excluded) {
        msgTodos.filter { it.done }
            .filter { !MessageSettings.isExcluded(context, it.who) && !MessageSettings.isExcluded(context, it.sender) }
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
                            "未办 ${rows.size} 件",
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
            // vc98：列表 / 时间轴视角（时间轴=截止日期小页，路河拍板）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 2.dp)
            ) {
                SegChip("列表", view == "list") { view = "list" }
                Spacer(Modifier.width(8.dp))
                SegChip("时间轴", view == "timeline") { view = "timeline" }
            }
            if (view == "list") {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
        ) {
            item(key = "sec_open") {
                Text(
                    "未办 ${rows.size} 件 · 按截止时间排序",
                    fontWeight = FontWeight.ExtraBold, color = LuyuanColors.Ink2, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                )
            }
            if (rows.isEmpty()) {
                item(key = "empty_hint") {
                    Text(
                        "还没有未办的待办。微信/QQ 消息里的事会自动出现在这里（可点「收下」或直接勾掉）。",
                        fontSize = 12.sp, color = LuyuanColors.Ink4
                    )
                }
                // 09-16 凌晨自提案（自审批）：路河「没消息来没法验证」——空态一键塞示例，
                // 示例可勾掉/✕丢弃，永不误伤真数据（sender=示例）
                item(key = "empty_sample") {
                    Text(
                        "＋ 塞一条示例试试",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LuyuanColors.Green700,
                        modifier = Modifier
                            .background(LuyuanColors.Green50, RoundedCornerShape(999.dp))
                            .clickable {
                                PendingMessageTodoStore.add(
                                    context,
                                    PendingMessageTodo(
                                        id = PendingMessageTodoStore.newId(),
                                        text = "明天下午三点前把班会记录发给辅导员",
                                        who = "辅导员",
                                        whenText = "明天下午三点",
                                        raw = "（示例）记得把周一班会记录整理好发我，明天下午三点前。",
                                        source = "wechat",
                                        sender = "示例",
                                        created_at = PendingMessageTodoStore.nowIso()
                                    )
                                )
                                reload()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            for (r in rows) {
                item(key = r.key) {
                    val overdue = r.score != Long.MAX_VALUE && r.score < System.currentTimeMillis()
                    if (r.isPending) {
                        // ---------- 待确认消息待办（琥珀底：它就是一张待办卡，三动作=通知栏同款） ----------
                        PendingTodoRow(
                            p = r.pendingItem!!,
                            text = r.text,
                            meta = listOfNotNull(
                                r.who.takeIf { it.isNotBlank() },
                                r.dueLabel.takeIf { r.dueLabel != "无期限" }
                            ).joinToString(" · "),
                            overdue = overdue,
                            onKeep = { keep(r.pendingItem) },
                            onDiscard = { discard(r.pendingItem) },
                            onComplete = { completePending(r.pendingItem) },
                            onExclude = { excludeTarget = r.pendingItem }
                        )
                    } else {
                        // ---------- 正式待办（绿圈勾选完成；长按=删除，vc98） ----------
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                                .then(
                                    if (r.key.startsWith("msg_")) Modifier.combinedClickable(
                                        onClick = {},
                                        onLongClick = { r.msgItem?.let { deleteTarget = it } }
                                    ) else Modifier
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(22.dp)
                                    .border(1.6.dp, LuyuanColors.Green700, CircleShape)
                                    .clickable {
                                        if (r.key.startsWith("msg_")) {
                                            r.msgItem?.let { toggleMsg(it) }
                                        } else {
                                            val parts = r.key.removePrefix("ct_").split("_", limit = 2)
                                            if (parts.size == 2) { vm.toggleContactTodo(parts[0], parts[1]); reload() }
                                        }
                                    }
                            ) { Icon(Icons.Default.Check, "完成", tint = LuyuanColors.Green700, modifier = Modifier.size(13.dp)) }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    r.text, fontSize = 13.sp, color = LuyuanColors.Ink1,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Row {
                                    Text(
                                        r.dueLabel, fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (overdue) LuyuanColors.Red else LuyuanColors.Green700
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        r.who + " · " + r.origin, fontSize = 10.sp, color = LuyuanColors.Ink4
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // ---------- 已办（done 的消息待办；可点取消；vc98 加清空） ----------
            if (doneMsgs.isNotEmpty()) {
                item(key = "sec_done") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "已办 ${doneMsgs.size}",
                            fontWeight = FontWeight.ExtraBold, color = LuyuanColors.Ink3, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "清空已办", fontSize = 11.sp, color = LuyuanColors.Ink4,
                            modifier = Modifier
                                .padding(top = 14.dp, bottom = 2.dp)
                                .clickable { clearDoneOpen = true }
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
                            ) { Icon(Icons.Default.Check, "已完成", tint = Color.White, modifier = Modifier.size(13.dp)) }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                t.text, fontSize = 12.5.sp, color = LuyuanColors.Ink3,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
        } else {
            // vc98：时间轴小页（路河拍板）——未办按截止日分桶：逾期→今天→明天→…→无期限
                TimelineView(
                    trows = rows.map {
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

    // ---------- 长按待确认卡：「把 X 加入不计入待办」 ----------
    excludeTarget?.let { target ->
        val name = target.sender.ifBlank { target.who }.ifBlank { "此人" }
        AlertDialog(
            onDismissRequest = { excludeTarget = null },
            title = { Text("不计入待办？", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Text(
                    "「$name」的消息将不再自动变成待办（已有的这条也会移除）。朋友闲聊适用；之后可在 设置 → 消息待办 里移出名单。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    MessageSettings.addExcluded(context, target.sender.ifBlank { target.who })
                    PendingMessageTodoStore.remove(context, target.id)
                    excludeTarget = null
                    reload()
                }) { Text("不计入", color = LuyuanColors.Red, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { excludeTarget = null }) { Text("取消") }
            }
        )
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

/** 待确认消息待办行（琥珀底）：勾选=已完成 / 小字收下=入未办 / ✕=不要 / 长按=不计入此人 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PendingTodoRow(
    p: PendingMessageTodo,
    text: String,
    meta: String,
    overdue: Boolean,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
    onComplete: () -> Unit,
    onExclude: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LuyuanColors.AmberBg, RoundedCornerShape(14.dp))
            .combinedClickable(onClick = {}, onLongClick = onExclude)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 勾选=已完成（一步办掉；与正式待办同语义圈，琥珀色标识来源）
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(22.dp)
                    .border(1.6.dp, LuyuanColors.Amber, CircleShape)
                    .clickable(onClick = onComplete)
            ) { Icon(Icons.Default.Check, "已完成", tint = LuyuanColors.Amber, modifier = Modifier.size(13.dp)) }
            Spacer(Modifier.width(8.dp))
            Text(
                "待确认", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                color = LuyuanColors.Amber,
                modifier = Modifier
                    .background(Color.White, RoundedCornerShape(999.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
            if (overdue) {
                Spacer(Modifier.width(6.dp))
                Text("已过期", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = LuyuanColors.Red)
            }
            Spacer(Modifier.weight(1f))
            // ✕ = 不要
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(24.dp)
                    .clickable(onClick = onDiscard)
            ) { Icon(Icons.Default.Close, "不要", tint = LuyuanColors.Ink3, modifier = Modifier.size(15.dp)) }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LuyuanColors.Ink1,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(meta, fontSize = 10.sp, color = LuyuanColors.Ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        // 收下 = 转正式待办（入未办，不完成）
        Text(
            "收下",
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = LuyuanColors.Green700,
            modifier = Modifier
                .border(1.dp, LuyuanColors.Green100, RoundedCornerShape(999.dp))
                .clickable(onClick = onKeep)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

// ---------- vc98：时间轴小页（路河拍板「截止日期单开一个小页做类似时间轴」） ----------

/** 时间轴行渲染用的轻量结构（Row 是 TodoScreen 内部类，跨 composable 传递用这份影子） */
private data class TRow(
    val key: String, val text: String, val who: String,
    val score: Long, val dueLabel: String, val origin: String,
    val isPending: Boolean
)

/** 列表/时间轴切换胶囊（设计 token：选中=Green50 底+Green700 字） */
@Composable
private fun SegChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = if (active) LuyuanColors.Green700 else LuyuanColors.Ink4,
        modifier = Modifier
            .background(if (active) LuyuanColors.Green50 else Color.Transparent, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 5.dp)
    )
}

/** 时间轴主视图：未办按截止日分桶（逾期→今天→明天→后天→M/d→无期限），桶头=轴点+日期线 */
@Composable
private fun TimelineView(trows: List<TRow>, now: LocalDateTime, onToggle: (String) -> Unit) {
    val buckets = remember(trows, now) {
        val today = now.toLocalDate()
        val out = linkedMapOf<String, MutableList<TRow>>()
        for (r in trows.sortedBy { it.score }) {
            val label = if (r.score == Long.MAX_VALUE) "无期限"
            else {
                val d = java.time.Instant.ofEpochMilli(r.score)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                when {
                    d.isBefore(today) -> "逾期 ${d.monthValue}/${d.dayOfMonth}"
                    d == today -> "今天"
                    d == today.plusDays(1) -> "明天"
                    d == today.plusDays(2) -> "后天"
                    else -> "${d.monthValue}/${d.dayOfMonth}"
                }
            }
            out.getOrPut(label) { mutableListOf() }.add(r)
        }
        out
    }
    LazyColumn(
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
    ) {
        if (buckets.isEmpty()) {
            item(key = "tl_empty") {
                Text(
                    "时间轴空空的。列表里没有未办待办时这里也没内容。",
                    fontSize = 12.sp, color = LuyuanColors.Ink4,
                    modifier = Modifier.padding(top = 24.dp)
                )
            }
        }
        for ((label, items) in buckets) {
            item(key = "tl_sec_$label") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(
                                if (label.startsWith("逾期")) LuyuanColors.Red else LuyuanColors.Green700,
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$label · ${items.size} 件",
                        fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = LuyuanColors.Ink2
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(LuyuanColors.Green100)
                    )
                }
            }
            for (r in items) {
                item(key = "tl_${r.key}") {
                    TimelineRow(r, onToggle)
                }
            }
        }
    }
}

/** 时间轴单行：左侧时刻、中间轴点、右侧内容卡（待确认=琥珀点无勾选；正式=可勾） */
@Composable
private fun TimelineRow(r: TRow, onToggle: (String) -> Unit) {
    val time = if (r.score == Long.MAX_VALUE) "—"
    else {
        val d = java.time.Instant.ofEpochMilli(r.score)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        if (r.dueLabel.contains(":")) String.format("%02d:%02d", d.hour, d.minute)
        else "${d.monthValue}/${d.dayOfMonth}"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            time, fontSize = 10.sp, color = LuyuanColors.Ink4,
            modifier = Modifier.width(36.dp)
        )
        if (r.isPending) {
            Box(
                Modifier
                    .padding(horizontal = 7.dp)
                    .size(8.dp)
                    .background(LuyuanColors.Amber, CircleShape)
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(horizontal = 0.dp)
                    .size(22.dp)
                    .border(1.6.dp, LuyuanColors.Green700, CircleShape)
                    .clickable { onToggle(r.key) }
            ) {
                Icon(
                    Icons.Default.Check, "完成",
                    tint = LuyuanColors.Green700, modifier = Modifier.size(13.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                r.text, fontSize = 12.5.sp, color = LuyuanColors.Ink1,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(
                (r.who + " · " + r.origin).trim(' ', '·'),
                fontSize = 9.5.sp, color = LuyuanColors.Ink4,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (r.isPending) {
            Text(
                "待确认", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = LuyuanColors.Amber
            )
        }
    }
}

// ---------- 截止时间解析（确定性，不做猜测；解析不出 = 无期限排最后） ----------

private val RX_HM = Regex("(\\d{1,2}):(\\d{2})")
private val RX_MD = Regex("(\\d{1,2})月(\\d{1,2})日")
private val RX_D = Regex("(\\d{1,2})日")
private val RX_WEEK = Regex("[周星期礼拜]([一二三四五六日天])")

private val WEEK_MAP = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7)

/** 截止时间分值（毫秒）：remind_at > when_text 显式词；无 → Long.MAX（排最后） */
internal fun deadlineScore(whenText: String, remindAt: String?, createdAt: String, now: LocalDateTime): Long {
    // 1) 联系人待办的 remind_at 是 ISO 时间，最准
    if (!remindAt.isNullOrBlank()) {
        parseIso(remindAt)?.let { return it }
    }
    val t = whenText.trim()
    if (t.isNotEmpty()) {
        val hm = RX_HM.find(t)?.destructured
        val time = hm?.let { try { LocalTime.of(it.component1().toInt(), it.component2().toInt()) } catch (_: Exception) { null } }
        // 2) 今天 / 明天 / 后天
        val day = when {
            t.contains("今天") -> now.toLocalDate()
            t.contains("明天") -> now.toLocalDate().plusDays(1)
            t.contains("后天") -> now.toLocalDate().plusDays(2)
            else -> null
        }
        if (day != null) return at(day, time ?: LocalTime.of(23, 59))
        // 3) 周X/星期X/礼拜X → 下一个该星期几（含今天；与 PC 相对星期口径同向）
        RX_WEEK.find(t)?.groupValues?.get(1)?.let { ch ->
            WEEK_MAP[ch.firstOrNull()]?.let { target ->
                var d = now.toLocalDate()
                var guard = 0
                while (d.dayOfWeek.value != target && guard < 8) {
                    d = d.plusDays(1)
                    guard += 1
                }
                return at(d, time ?: LocalTime.of(23, 59))
            }
        }
        // 4) M月D日
        RX_MD.find(t)?.destructured?.let { d ->
            try {
                return at(LocalDate.of(now.year, d.component1().toInt(), d.component2().toInt()), time ?: LocalTime.of(23, 59))
            } catch (_: Exception) {
            }
        }
        // 5) 纯 HH:MM（今天）
        if (time != null) return at(now.toLocalDate(), time)
    }
    // 6) created_at 兜底不参与排序（避免新建的永远沉底/置顶），判无期限
    return Long.MAX_VALUE
}

private fun at(day: LocalDate, time: LocalTime): Long =
    LocalDateTime.of(day, time).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun parseIso(s: String): Long? = try {
    LocalDateTime.parse(s.take(19), DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
} catch (_: Exception) {
    try {
        LocalDate.parse(s.take(10)).atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

/** 展示用的截止标签 */
internal fun dueLabel(remindAt: String?, whenText: String, now: LocalDateTime): String {
    if (!remindAt.isNullOrBlank()) {
        parseIso(remindAt)?.let {
            val d = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
            return "截止 " + d.monthValue + "/" + d.dayOfMonth + " " + d.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
        }
    }
    val t = whenText.trim()
    if (t.isNotEmpty()) {
        RX_WEEK.find(t)?.value?.let { return "截止 " + t.take(12) }
        if (t.contains("今天") || t.contains("明天") || t.contains("后天")) return "截止 " + t.take(12)
        RX_MD.find(t)?.value?.let { return "截止 " + it }
        RX_HM.find(t)?.value?.let { return "截止 " + it }
    }
    return "无期限"
}
