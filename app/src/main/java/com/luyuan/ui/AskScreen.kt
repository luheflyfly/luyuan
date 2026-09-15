package com.luyuan.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.luyuan.data.AskRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 问路远（类 Chatbox 对话页）：
 * - 顶部模型选择器：官方模型目录（快答/深思/视觉/Pro），思考开关与视觉能力一目了然
 * - 图片通道：选图自动压缩成 base64（data URL），仅视觉模型可发
 * - 上下文 = 本机笔记 + 待办（私密不外发）；会话历史存本机私有目录，退出 App 不丢
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskScreen(vm: LuyuanViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val messages = remember {
        mutableStateListOf<AskRemote.Turn>().apply { addAll(AskRemote.loadHistory(context)) }
    }
    var modelKey by remember { mutableStateOf(AskRemote.loadConfig(context).modelKey) }
    var model by remember { mutableStateOf(AskRemote.modelByKey(modelKey)) }
    var showModelDialog by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    // 私密内容外发授权（立项单 2026-09-13 红线变更）：默认不勾，勾了本次才把「私密」标签笔记一并发出
    var includePrivate by remember { mutableStateOf(false) }

    data class PendingImg(val bmp: Bitmap, val dataUrl: String)
    val pending = remember { mutableStateListOf<PendingImg>() }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val p = compressImage(context, uri)
                if (p != null && pending.size < 4) pending.add(PendingImg(p.first, p.second))
            }
        }
    }

    fun send() {
        val q = question.trim()
        if ((q.isBlank() && pending.isEmpty()) || busy) return
        val imgs = pending.map { it.dataUrl }
        if (imgs.isNotEmpty() && !model.vision) {
            error = "当前模型（${model.label}）不支持图片，点顶部切换到视觉模型"
            return
        }
        val history = messages.toList()
        messages.add(AskRemote.Turn("user", if (q.isBlank()) "（图片）" else q))
        question = ""
        pending.clear()
        busy = true
        error = null
        scope.launch {
            val answer = try {
                withContext(Dispatchers.IO) {
                    val cfg = AskRemote.loadConfig(context)
                    AskRemote.ask(cfg, model, q, imgs, history, AskRemote.buildContext(context, includePrivate))
                }
            } catch (e: Exception) {
                error = e.message ?: "请求失败"
                ""
            }
            busy = false
            if (answer.isNotBlank()) {
                messages.add(AskRemote.Turn("assistant", answer))
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            AskRemote.saveHistory(context, messages.toList())
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("问路远", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // 历史已持久化（v1.10.0），误触清空损失变大——先确认
                        if (messages.isNotEmpty()) confirmClear = true
                    }) {
                        Text("新对话", fontSize = 13.sp)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // vc80：.imePadding() 换 imeLiftPadding()——vivo 上 ime insets 恒 0，由实测键盘高兜底
                // （vc77 胶囊同款套路收编为公共助手）
                .then(imeLiftPadding())
        ) {
            // ---------- 模型选择器（点击切换，状态常显） ----------
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showModelDialog = true }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        "${model.label}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (model.vision) "可发图" else "纯文字",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("  ▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (messages.isEmpty() && !busy) {
                Text(
                    "问点什么，我会参考你本机最近的笔记和待办（私密标签的笔记默认不发出去，\n" +
                        "需要时可在输入框上方勾选『包含私密内容』）。\n" +
                        "要发图片请先切到视觉模型。API Key 在「设置 → 问路远」里填。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp)
                )
            }
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                items(messages) { m ->
                    val isUser = m.role == "user"
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            m.content,
                            fontSize = 15.sp,
                            lineHeight = 21.sp,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart)
                                .widthIn(max = 320.dp)
                                .background(
                                    if (isUser) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(
                                        14.dp, 14.dp,
                                        if (isUser) 4.dp else 14.dp,
                                        if (isUser) 14.dp else 4.dp
                                    )
                                )
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                        )
                    }
                }
                if (busy) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                "思考中…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            error?.let {
                Text(
                    "$it",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            // ---------- 待发图片缩略条 ----------
            if (pending.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    for (p in pending) {
                        Image(
                            bitmap = p.bmp.asImageBitmap(),
                            contentDescription = "待发图片，点按移除",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { pending.remove(p) }
                        )
                    }
                }
            }
            // ---------- 私密内容外发授权（立项单红线变更：默认不外发，用户勾了本次才发） ----------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                Checkbox(
                    checked = includePrivate,
                    onCheckedChange = { includePrivate = it },
                    modifier = Modifier.size(30.dp)
                )
                Column {
                    Text(
                        "包含私密内容",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (includePrivate) LuyuanColors.Amber
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (includePrivate) "本次会把「私密」标签的笔记一起发给云端"
                        else "默认只发普通笔记，「私密」标签的不外发",
                        fontSize = 9.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // ---------- 输入行：🖼 图片 + 文本 + 发送 ----------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                IconButton(
                    onClick = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !busy
                ) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = "添加图片",
                        tint = if (model.vision) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    placeholder = {
                        Text(if (model.vision) "发图片或问点什么…" else "问点什么…")
                    },
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { send() },
                    enabled = (question.isNotBlank() || pending.isNotEmpty()) && !busy,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }

    // 清空确认（历史持久化后误触损失变大）
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空这段对话？", fontWeight = FontWeight.Bold) },
            text = { Text("历史会一并删除，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    messages.clear()
                    AskRemote.clearHistory(context)
                    confirmClear = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("算了") }
            }
        )
    }

    // ---------- 模型选择弹窗 ----------
    if (showModelDialog) {
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text("选择模型", fontWeight = FontWeight.Bold) },
            confirmButton = {
                TextButton(onClick = { showModelDialog = false }) { Text("关闭") }
            },
            text = {
                Column {
                    for (m in AskRemote.CATALOG) {
                        val selected = m.key == modelKey
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    modelKey = m.key
                                    model = m
                                    AskRemote.saveModelKey(context, m.key)
                                    showModelDialog = false
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                (if (selected) "● " else "○ ") + m.label,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 15.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                buildString {
                                    if (m.vision) append("可发图 ")
                                    append(if (m.thinking == true) "会深思" else "秒回")
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        "深思模式回答慢但更聪明；图片只支持视觉模型（实验）。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        )
    }
}

/** 相册图 → 压缩（最长边 1280，JPEG 85）→ base64 data URL；返回 (缩略图, dataUrl) */
private suspend fun compressImage(context: Context, uri: android.net.Uri): Pair<Bitmap, String>? =
    withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return@withContext null
            val scale = 1280f / maxOf(bmp.width, bmp.height)
            val out = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else bmp
            val baos = java.io.ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val b64 = android.util.Base64.encodeToString(
                baos.toByteArray(),
                android.util.Base64.NO_WRAP
            )
            Pair(out, "data:image/jpeg;base64,$b64")
        } catch (_: Exception) {
            null
        }
    }
