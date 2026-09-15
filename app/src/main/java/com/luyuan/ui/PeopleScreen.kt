package com.luyuan.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luyuan.data.Contact
import com.luyuan.data.ContactTodo
import kotlinx.coroutines.launch

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

import com.luyuan.domain.Note

/** 第二屏 · 人脉：待办置顶红板 + 联系人字母索引 + 搜索，数据来自共享目录 contacts/ */

// 自定义矢量图标（微信 / QQ 无 Material 等价物，按 v2 设计稿 1.5px 描边重绘，禁 emoji）
private val WECHAT_PATH = "M8.5 4C5.5 4 3 6 3 8.7c0 1.5.8 2.8 2 3.7L4.4 14.5l2-1a8 8 0 0 0 2.1.3M9.5 8.9c0-2.2 2.2-4 4.9-4s4.9 1.8 4.9 4-2.2 4-4.9 4c-.6 0-1.2-.1-1.7-.2L10.5 14l.5-1.7c-.9-.7-1.5-1.9-1.5-3.4z"
private val QQ_PATH = "M11 3.5c-3 0-4.8 2-4.8 5 0 1.8-.6 3-1.2 4 .4.8 1.6.6 2.4.2.3.8 1.2 1.8 3.6 1.8s3.3-1 3.6-1.8c.8.4 2 .6 2.4-.2-.6-1-1.2-2.2-1.2-4 0-3-1.8-5-4.8-5zM9.5 17.5c.8.6 2.2.6 3 0"

private val WechatIcon: ImageVector by lazy {
    ImageVector.Builder("wechat", 22.dp, 22.dp, 22f, 22f).apply {
        addPath(
            pathData = PathParser().parsePathString(WECHAT_PATH).toNodes(),
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round
        )
    }.build()
}

private val QqIcon: ImageVector by lazy {
    ImageVector.Builder("qq", 22.dp, 22.dp, 22f, 22f).apply {
        addPath(
            pathData = PathParser().parsePathString(QQ_PATH).toNodes(),
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round
        )
    }.build()
}

/** ISO 时间取 MM-DD（来往时间线用） */
private fun md(iso: String): String = if (iso.length >= 10) iso.substring(5, 10) else iso
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(vm: LuyuanViewModel, onNoteClick: (String) -> Unit = {}) {
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<Contact?>(null) }
    // B4（SYNC_FORMAT v3）：分组 chips + 排序。selGroup=null=全部（拼音字母分组）；
    // 选中分组默认按学号升序（无学号排最后），可切回拼音（PC 端同口径）
    var selGroup by remember { mutableStateOf<String?>(null) }
    var bySid by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val filtered = remember(contacts, query) {
        if (query.isBlank()) contacts
        else contacts.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    val todoContacts = filtered.filter { it.undoneTodos.isNotEmpty() }
    val allGroups = remember(contacts) {
        contacts.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val inGroup = remember(filtered, selGroup) {
        if (selGroup == null) filtered else filtered.filter { it.group == selGroup }
    }
    val showSid = selGroup != null && bySid
    val sidSorted = remember(inGroup) {
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
        inGroup.sortedWith(compareBy({ it.sidSortKey }, { collator.getCollationKey(it.name) }))
    }
    val grouped = remember(inGroup) { inGroup.groupBy { it.displayLetter }.toSortedMap() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("人脉", fontWeight = FontWeight.Bold) }) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索联系人…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )
            // ---------- 分组 chips（B4）：全部 / 各分组 / 排序切换 ----------
            if (allGroups.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    GroupChip(label = "全部", selected = selGroup == null) { selGroup = null }
                    for (g in allGroups) {
                        GroupChip(
                            label = g,
                            selected = selGroup == g
                        ) { selGroup = if (selGroup == g) null else g }
                    }
                    if (selGroup != null) {
                        GroupChip(
                            label = if (bySid) "按学号 ✓" else "按拼音",
                            selected = bySid
                        ) { bySid = !bySid }
                    }
                }
            }
            if (inGroup.isEmpty()) {
                EmptyState(
                    icon = if (contacts.isEmpty()) EmptyIconPeople else EmptyIconSearch,
                    title = if (contacts.isEmpty()) "还没有联系人"
                    else if (query.isNotBlank()) "没有匹配「${query.trim()}」的联系人"
                    else "这个分组还没有联系人",
                    subtitle = if (contacts.isEmpty())
                        "电脑端人脉页添加后会自动同步到这里（共享目录 contacts/ 文件夹）。"
                    else null
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)
                ) {
                    // ---------- 待办板块（有未办事项的联系人自动置顶 + 红点） ----------
                    if (todoContacts.isNotEmpty()) {
                        item(key = "sec_todo") {
                            Text(
                                "待办",
                                fontWeight = FontWeight.ExtraBold,
                                color = LuyuanColors.Ink2,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                            )
                        }
                        for (c in todoContacts) {
                            item(key = "todo_${c.id}") {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    modifier = Modifier.fillMaxWidth().clickable { detail = c }
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(9.dp)
                                                    .background(Color(0xFFEF4444), CircleShape)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(c.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        }
                                        for (t in c.undoneTodos) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Checkbox(
                                                    checked = false,
                                                    onCheckedChange = { vm.toggleContactTodo(c.id, t.id) }
                                                )
                                                Text(
                                                    t.text,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.clickable { vm.toggleContactTodo(c.id, t.id) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // ---------- 联系人板块（选分组按学号=平铺无字母头；否则按字母分组） ----------
                    item(key = "sec_all") {
                        Text(
                            if (selGroup == null) "联系人 ${inGroup.size}"
                            else "$selGroup ${inGroup.size} 人 · " + if (showSid) "按学号" else "按拼音",
                            fontWeight = FontWeight.ExtraBold,
                            color = LuyuanColors.Ink2,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                        )
                    }
                    if (showSid) {
                        // 按学号视图（PC 口径：不显示字母头与索引栏）
                        for (c in sidSorted) {
                            item(key = "c_${c.id}") {
                                PeopleContactCard(c = c, showSidBadge = true) { detail = c }
                            }
                        }
                    } else {
                        for ((letter, list) in grouped) {
                            item(key = "letter_$letter") {
                                Text(
                                    letter,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                            for (c in list) {
                                item(key = "c_${c.id}") {
                                    PeopleContactCard(c = c, showSidBadge = false) { detail = c }
                                }
                            }
                        }
                    }
                    item(key = "bottom_pad") { Spacer(Modifier.height(10.dp)) }
                }
                // ---------- 字母索引条（按学号视图隐藏，PC 同口径） ----------
                if (grouped.isNotEmpty() && !showSid) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(28.dp)
                            .background(Color(0x22000000), RoundedCornerShape(14.dp))
                            .padding(vertical = 10.dp)
                    ) {
                        for (letter in grouped.keys) {
                            Text(
                                letter,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable {
                                        // 定位到字母分组头：待办段 + 全部标题 + 前面各组(1头+人数)
                                        var headerIdx =
                                            (if (todoContacts.isNotEmpty()) 1 else 0) + todoContacts.size + 1
                                        for ((l, list) in grouped) {
                                            if (l == letter) break
                                            headerIdx += 1 + list.size
                                        }
                                        scope.launch {
                                            val max = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                                            listState.scrollToItem(headerIdx.coerceIn(0, max))
                                        }
                                    }
                                    .padding(vertical = 1.dp, horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    detail?.let { picked ->
        // contacts 刷新后跟随最新数据（贪睡/勾选后弹窗内即时反映）
        val c = contacts.firstOrNull { it.id == picked.id } ?: picked
        ContactDetailDialog(
            contact = c,
            notes = notes,
            onDismiss = { detail = null },
            onToggleTodo = { todoId -> vm.toggleContactTodo(c.id, todoId) },
            onSnoozeTodo = { todoId, hours -> vm.snoozeContactTodo(c.id, todoId, hours) },
            onAddTodo = { vm.addContactTodo(c.id, it) },
            onBirthdayRemind = { vm.setContactBirthdayReminder(c.id) },
            onNoteClick = onNoteClick
        )
    }
}

/** 联系人卡（B4 从列表内联抽出共用）：学号视图在副标题前加「学号N」，其余同旧版 */
@Composable
private fun PeopleContactCard(c: Contact, showSidBadge: Boolean, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(c.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                val subtitle = listOfNotNull(
                    c.sid.takeIf { it.isNotBlank() && showSidBadge }?.let { "学号$it" },
                    c.phone.ifBlank { null },
                    c.birthday.takeIf { it.isNotBlank() }
                ).joinToString("  ")
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (c.undoneTodos.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(Color(0xFFEF4444), CircleShape)
                )
            }
        }
    }
}

/** 分组 chip（B4）：纸白底圆角胶囊，选中变绿底绿字（沿用设置页目录卡的同款语言） */
@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.primary else LuyuanColors.Ink2,
        modifier = Modifier
            .background(
                if (selected) LuyuanColors.Green50 else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(999.dp)
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(999.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

@Composable
private fun ContactDetailDialog(
    contact: Contact,
    notes: List<Note>,
    onDismiss: () -> Unit,
    onToggleTodo: (String) -> Unit,
    onSnoozeTodo: (String, Int?) -> Unit,
    onAddTodo: (String) -> Unit,
    onBirthdayRemind: () -> Unit,
    onNoteClick: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard: ClipboardManager = LocalClipboardManager.current
    var snoozeTarget by remember { mutableStateOf<ContactTodo?>(null) }
    var draft by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                // ---------- 深绿英雄区 ----------
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.linearGradient(0f to Color(0xFF224A3A), 1f to LuyuanColors.Green700))
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .background(Color.White.copy(alpha = 0.18f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(contact.name.take(1), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(contact.name, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                            val rel = contact.info.firstOrNull()?.takeIf { it.isNotBlank() }
                            val met = contact.created_at.takeIf { it.isNotBlank() }?.let { "认识于 " + it.take(10) }
                            val subtitle = listOfNotNull(rel, met).joinToString(" · ")
                            if (subtitle.isNotBlank()) {
                                Text(subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                        if (contact.birthday.isNotBlank()) {
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp)) {
                                Text("生日", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
                                Text(contact.birthday, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                // ---------- 四快捷键 ----------
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickAction(icon = Icons.Filled.Call, label = "打电话", enabled = contact.phone.isNotBlank()) {
                        if (contact.phone.isNotBlank()) {
                            try { context.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + contact.phone))) } catch (_: Exception) {}
                        }
                    }
                    QuickAction(icon = WechatIcon, label = "微信号", enabled = contact.wechat.isNotBlank()) {
                        if (contact.wechat.isNotBlank()) {
                            clipboard.setText(AnnotatedString(contact.wechat))
                            val launch = context.packageManager.getLaunchIntentForPackage("com.tencent.mm")
                            if (launch != null) { context.startActivity(launch); Toast.makeText(context, "微信号已复制，去微信粘贴搜索", Toast.LENGTH_LONG).show() }
                            else Toast.makeText(context, "微信号已复制（未找到微信）", Toast.LENGTH_LONG).show()
                        }
                    }
                    QuickAction(icon = QqIcon, label = "QQ", enabled = contact.qq.isNotBlank()) {
                        if (contact.qq.isNotBlank()) {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("mqq://im/chat?chat_type=uin&uin=" + contact.qq))) }
                            catch (e: Exception) { Toast.makeText(context, "没有装 QQ 或无法跳转", Toast.LENGTH_SHORT).show() }
                        }
                    }
                    QuickAction(icon = Icons.Filled.Share, label = "分享名片") {
                        val lines = mutableListOf("【${contact.name}】")
                        if (contact.phone.isNotBlank()) lines.add("电话：${contact.phone}")
                        if (contact.wechat.isNotBlank()) lines.add("微信：${contact.wechat}")
                        if (contact.qq.isNotBlank()) lines.add("QQ：${contact.qq}")
                        if (contact.birthday.isNotBlank()) lines.add("生日：${contact.birthday}")
                        for (line in contact.info) lines.add("· $line")
                        try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n")) }, "分享联系人")) } catch (_: Exception) {}
                    }
                }
                // ---------- 可滚动主体 ----------
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    // 待人办
                    SectionCard(icon = Icons.Filled.Notifications, title = "待人办 · ${contact.undoneTodos.size} 件未办") {
                        if (contact.todos.isEmpty()) {
                            Text("还没有待办", color = LuyuanColors.Ink3, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
                        } else {
                            for (t in contact.todos.sortedBy { it.done }) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                                    Box(
                                        modifier = Modifier.size(19.dp)
                                            .background(if (t.done) LuyuanColors.Green700 else Color.Transparent, CircleShape)
                                            .border(1.6.dp, if (t.done) LuyuanColors.Green700 else LuyuanColors.Ink4, CircleShape)
                                            .clickable { onToggleTodo(t.id) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (t.done) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                    }
                                    Spacer(Modifier.width(9.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            t.text,
                                            fontSize = 13.sp,
                                            color = if (t.done) LuyuanColors.Ink3 else LuyuanColors.Ink1,
                                            textDecoration = if (t.done) TextDecoration.LineThrough else null,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (!t.done && !t.remind_at.isNullOrBlank()) {
                                            Text("提醒 " + (t.remind_at ?: "").take(16).replace('T', ' '), fontSize = 11.sp, color = LuyuanColors.Ink3)
                                        }
                                    }
                                    if (!t.done) {
                                        Text("贪睡", fontSize = 12.sp, color = LuyuanColors.Green700, modifier = Modifier.clickable { snoozeTarget = t }.padding(start = 8.dp))
                                    }
                                }
                            }
                            // 加待办（回车即存）
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                OutlinedTextField(
                                    value = draft,
                                    onValueChange = { draft = it },
                                    placeholder = { Text("加一件待办，回车即存", fontSize = 12.sp) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { if (draft.isNotBlank()) { onAddTodo(draft.trim()); draft = "" } }),
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { if (draft.isNotBlank()) { onAddTodo(draft.trim()); draft = "" } }) {
                                    Icon(Icons.Filled.Add, contentDescription = "加待办", tint = LuyuanColors.Green700)
                                }
                            }
                        }
                    }
                    // 资料
                    SectionCard(icon = Icons.Filled.Info, title = "资料") {
                        InfoRowV2("电话", contact.phone, "拨打") { if (contact.phone.isNotBlank()) { try { context.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + contact.phone))) } catch (_: Exception) {} } }
                        InfoRowV2("微信", contact.wechat, "复制并打开") { if (contact.wechat.isNotBlank()) { clipboard.setText(AnnotatedString(contact.wechat)); val launch = context.packageManager.getLaunchIntentForPackage("com.tencent.mm"); if (launch != null) { context.startActivity(launch); Toast.makeText(context, "微信号已复制", Toast.LENGTH_SHORT).show() } else Toast.makeText(context, "微信号已复制（未找到微信）", Toast.LENGTH_SHORT).show() } }
                        InfoRowV2("QQ", contact.qq, "打开") { if (contact.qq.isNotBlank()) { try { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("mqq://im/chat?chat_type=uin&uin=" + contact.qq))) } catch (_: Exception) { Toast.makeText(context, "没有装 QQ 或无法跳转", Toast.LENGTH_SHORT).show() } } }
                        if (contact.birthday.isNotBlank()) {
                            val hasBdayRemind = contact.todos.any { !it.done && it.text.contains("生日") }
                            InfoRowV2("生日", contact.birthday, if (hasBdayRemind) null else "提前 3 天提醒") { if (!hasBdayRemind) onBirthdayRemind() }
                            if (hasBdayRemind) Text("已设提前 3 天提醒", fontSize = 11.sp, color = LuyuanColors.Ink3, modifier = Modifier.padding(start = 40.dp, bottom = 6.dp))
                        }
                        if (contact.info.isNotEmpty()) {
                            InfoRowV2("备注", contact.info.joinToString("；")) { }
                        }
                    }
                    // 来往时间线
                    TimelineCard(contact = contact, notes = notes, onNoteClick = onNoteClick)
                }
            }
        }
    }

    // 贪睡弹窗（v1.11）：推后 1/3 小时或取消提醒
    snoozeTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { snoozeTarget = null },
            title = { Text("提醒设置", fontWeight = FontWeight.Bold) },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { snoozeTarget = null }) { Text("算了") }
            },
            text = {
                Column {
                    Text(
                        "「${t.text.take(14)}${if (t.text.length > 14) "…" else ""}」",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { onSnoozeTodo(t.id, 1); snoozeTarget = null }) { Text("1 小时后提醒") }
                    TextButton(onClick = { onSnoozeTodo(t.id, 3); snoozeTarget = null }) { Text("3 小时后提醒") }
                    TextButton(onClick = { onSnoozeTodo(t.id, null); snoozeTarget = null }) { Text("取消提醒") }
                }
            }
        )
    }
}

@Composable
private fun SectionCard(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Column(Modifier.padding(12.dp, 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Icon(icon, contentDescription = null, tint = LuyuanColors.Green700, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(title, fontSize = 11.sp, color = LuyuanColors.Ink3, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 9.dp)
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) LuyuanColors.Green700 else LuyuanColors.Ink4, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 10.sp, color = if (enabled) LuyuanColors.Ink2 else LuyuanColors.Ink4, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InfoRowV2(label: String, value: String, actionLabel: String? = null, onClick: () -> Unit = {}) {
    if (value.isBlank()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().then(
            if (actionLabel != null) Modifier.clickable { onClick() } else Modifier
        ).padding(vertical = 7.dp)
    ) {
        Text(label, color = LuyuanColors.Ink3, fontSize = 11.sp, modifier = Modifier.width(40.dp))
        Text(value, fontSize = 13.sp, color = LuyuanColors.Ink1, modifier = Modifier.weight(1f))
        if (actionLabel != null) {
            Text(actionLabel, fontSize = 11.sp, color = LuyuanColors.Green700, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onClick() })
        }
    }
}

@Composable
private fun TimelineCard(contact: Contact, notes: List<Note>, onNoteClick: (String) -> Unit) {
    val items = remember(notes, contact.id) {
        if (contact.name.length >= 2)
            notes.filter { it.text.contains(contact.name, ignoreCase = true) }
                .sortedByDescending { it.updated_at.ifBlank { it.created_at } }
                .take(12)
        else emptyList()
    }
    SectionCard(icon = Icons.Filled.History, title = "来往") {
        if (items.isEmpty()) {
            Text("还没有提到 ${contact.name} 的笔记", color = LuyuanColors.Ink3, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
        } else {
            for (n in items) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onNoteClick(n.id) }.padding(vertical = 7.dp)
                ) {
                    Text(md(n.updated_at.ifBlank { n.created_at }), color = LuyuanColors.Ink3, fontSize = 11.sp, modifier = Modifier.width(42.dp))
                    Text(n.text, fontSize = 13.sp, color = LuyuanColors.Ink1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
