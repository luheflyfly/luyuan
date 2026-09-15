package com.luyuan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.luyuan.data.NoteRepository
import com.luyuan.platform.StorageLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * 记事详情 v2（任务单 §一-8 基础重做）：方案 A 卡片语言——
 * 📝 内容（编辑+配图）/ 🎙 原声 / 📜 原始识别 / 🏷 标签 / ⏰ 提醒 五张卡；保存按钮沉底主色。
 * 逻辑与原版逐行等价（编辑保存/配图/播放/留底/提醒全链/删除/日期时间选择器）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailEditScreen(
    vm: LuyuanViewModel,
    noteId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    var text by remember { mutableStateOf("") }
    var tagsText by remember { mutableStateOf("") }
    var remindAt by remember { mutableStateOf<String?>(null) }
    var audioRel by remember { mutableStateOf<String?>(null) }
    var images by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewingImage by remember { mutableStateOf<String?>(null) }
    var createdAt by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var device by remember { mutableStateOf("") }
    var rawText by remember { mutableStateOf<String?>(null) }
    var showRaw by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<java.time.LocalDate?>(null) }

    val isRecording by vm.isRecording.collectAsStateWithLifecycle()

    LaunchedEffect(noteId) {
        withContext(Dispatchers.IO) {
            val n = NoteRepository.getNote(context, noteId)
            n?.let {
                text = it.text
                tagsText = it.tags.joinToString(", ")
                remindAt = it.remind_at
                audioRel = it.audio
                images = it.images
                createdAt = it.created_at
                source = it.source
                device = it.device
                rawText = it.raw_text
                loaded = true
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                title = { Text("记事详情", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { pendingDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除")
                    }
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                ) { Text("取消") }
                Button(
                    onClick = {
                        val tags = tagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        vm.updateNote(noteId, text, tags)
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.weight(2f)
                ) { Text("保存") }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .alpha(if (pendingDelete) 0.55f else 1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- 📝 内容卡 ----------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 元信息行：时间 + 来源/设备徽章
                if (createdAt.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            createdAt.take(16).replace("T", " "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (source == "voice") MetaBadge("语音")
                        if (tagsText.contains("分享")) MetaBadge("分享")
                        if (device == "phone") MetaBadge("手机")
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("内容") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp),
                    singleLine = false
                )

                // 配图（图片分享/日记配图共用 images/；此前详情页从未渲染 images 字段，P0 已修）
                if (images.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        for (rel in images) {
                            Box {
                                AsyncImage(
                                    model = java.io.File(StorageLocator.getRoot(context), rel),
                                    contentDescription = "配图",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(84.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { viewingImage = rel }
                                )
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(20.dp)
                                        .background(Color(0x88000000), CircleShape)
                                        .clickable {
                                            images = images - rel
                                            val tags = tagsText.split(",").map { it.trim() }
                                                .filter { it.isNotEmpty() }
                                            vm.updateNote(noteId, text, tags, images)
                                        }
                                ) {
                                    Text("✕", color = Color.White, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text("标签（逗号分隔，可选）") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ---------- 🎙 原声卡 ----------
            if (audioRel != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("原声", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val rel = audioRel!!
                    val f = remember(rel) {
                        java.io.File(StorageLocator.getRoot(context), rel)
                    }
                    if (f.exists()) {
                        var playing by remember(rel) { mutableStateOf(false) }
                        val player = remember(rel) {
                            android.media.MediaPlayer().apply {
                                setOnCompletionListener { playing = false }
                            }
                        }
                        DisposableEffect(rel) {
                            onDispose {
                                try { if (player.isPlaying) player.stop() } catch (_: Exception) {}
                                try { player.release() } catch (_: Exception) {}
                            }
                        }
                        AssistChip(
                            onClick = {
                                try {
                                    if (playing) {
                                        player.pause()
                                        playing = false
                                    } else {
                                        if (player.isPlaying) {
                                            player.start()
                                        } else {
                                            player.reset()
                                            player.setDataSource(f.absolutePath)
                                            player.prepare()
                                            player.start()
                                        }
                                        playing = true
                                    }
                                } catch (_: Exception) {
                                    playing = false
                                }
                            },
                            leadingIcon = {
                                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            label = { Text(if (playing) "暂停原声" else "播放原声") }
                        )
                    } else {
                        Text(
                            "这条带原声录音（音频还没同步到手机）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---------- 📜 原始识别卡（电脑纠错前原稿，转写不准时可对照） ----------
            rawText?.takeIf { it.isNotBlank() && it != text }?.let { raw ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("原始识别", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    AssistChip(
                        onClick = { showRaw = !showRaw },
                        label = { Text(if (showRaw) "收起原稿" else "看电脑纠错前的原稿") }
                    )
                    if (showRaw) {
                        Text(
                            raw,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // ---------- ⏰ 提醒卡 ----------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("提醒", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(
                        text = remindAt?.let { "已设 ${formatRemind(it)}" } ?: "未设置",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (remindAt != null) LuyuanColors.Green700 else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("这里设的到点手机响，电脑同步后也能看到。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {
                        val iso = isoInHours(1)
                        vm.setReminder(noteId, iso)
                        remindAt = iso
                    }, label = { Text("+1小时") })
                    AssistChip(onClick = {
                        val iso = isoInHours(3)
                        vm.setReminder(noteId, iso)
                        remindAt = iso
                    }, label = { Text("+3小时") })
                    AssistChip(onClick = {
                        val iso = isoTomorrowAt(9, 0)
                        vm.setReminder(noteId, iso)
                        remindAt = iso
                    }, label = { Text("明早9点") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {
                        // 一步选具体时间（任务单 App-3：默认今天，拨盘直接选时分）
                        pickedDate = LocalDate.now()
                        showTimePicker = true
                    }, label = { Text("选具体时间") })
                    AssistChip(onClick = {
                        val now = LocalDateTime.now()
                        val iso = isoTomorrowAt(now.hour, now.minute)
                        vm.setReminder(noteId, iso)
                        remindAt = iso
                    }, label = { Text("明天此时") })
                    AssistChip(onClick = { showDatePicker = true }, label = { Text("自定义日期…") })
                    if (remindAt != null) {
                        AssistChip(onClick = {
                            vm.setReminder(noteId, null)
                            remindAt = null
                        }, label = { Text("取消提醒") })
                    }
                }
            }
        }
    }

    if (pendingDelete) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            UndoBar(
                message = "已删除 1 条",
                onUndo = { pendingDelete = false },
                onCommit = {
                    vm.deleteNote(noteId)
                    onBack()
                }
            )
        }
    }
    }

    // 配图大图查看
    viewingImage?.let { rel ->
        AlertDialog(
            onDismissRequest = { viewingImage = null },
            title = { Text("配图", fontWeight = FontWeight.Bold) },
            confirmButton = {
                TextButton(onClick = { viewingImage = null }) { Text("关闭") }
            },
            text = {
                AsyncImage(
                    model = java.io.File(StorageLocator.getRoot(context), rel),
                    contentDescription = "配图大图",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }


    // 自定义提醒：日历选日期 → 拨盘选时间
    if (showDatePicker) {
        val dateState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = System.currentTimeMillis()
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { ms ->
                        pickedDate = java.time.Instant.ofEpochMilli(ms)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        showDatePicker = false
                        showTimePicker = true
                    }
                }) { Text("下一步") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) {
            androidx.compose.material3.DatePicker(state = dateState)
        }
    }
    if (showTimePicker) {
        val timeState = androidx.compose.material3.rememberTimePickerState(
            initialHour = 9, initialMinute = 0, is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("选时间") },
            confirmButton = {
                TextButton(onClick = {
                    val d = pickedDate
                    if (d != null) {
                        val iso = d.atTime(timeState.hour, timeState.minute)
                            .atOffset(OffsetDateTime.now().offset).toString()
                        vm.setReminder(noteId, iso)
                        remindAt = iso
                    }
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("取消") }
            },
            text = { androidx.compose.material3.TimePicker(state = timeState) }
        )
    }
}

private fun formatRemind(iso: String): String = try {
    OffsetDateTime.parse(iso).toLocalDateTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("M.d HH:mm"))
} catch (_: Exception) {
    try {
        LocalDateTime.parse(iso)
            .format(java.time.format.DateTimeFormatter.ofPattern("M.d HH:mm"))
    } catch (_: Exception) {
        iso
    }
}

private fun isoInHours(h: Long): String =
    LocalDateTime.now().plusHours(h).atOffset(OffsetDateTime.now().offset).toString()

private fun isoTomorrowAt(hour: Int, minute: Int): String =
    LocalDate.now().plusDays(1).atTime(hour, minute)
        .atOffset(OffsetDateTime.now().offset).toString()

@Composable
private fun MetaBadge(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}
