package com.luyuan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 超级输入框 · 独立窗口版（09-14 晨，胶囊打字看不见的第三修）：
 * 前两轮（色/焦点 → imePadding → edge-to-edge）都在主窗口里打转，路河 vivo/鸿蒙 4 上
 * 主窗口的 IME insets 始终拿不到。本版换赛道：点胶囊展开时弹**独立窗口**（Compose Dialog），
 * 系统对 Dialog 的键盘避让是窗口级逻辑，所有 ROM 都可靠；输入框再挂 imePadding 双保险。
 * 行为与旧展开态一致：回车=存笔记 / 日记 / 图片 / 录音 / 搜索切换，草稿仍由 MainActivity 存 prefs。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalInputDialog(
    searchMode: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    onCommit: () -> Unit,
    onSaveDiary: () -> Unit,
    onPickImage: () -> Unit,
    onRecord: () -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomStart
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .navigationBarsPadding()
                    .imePadding()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (searchMode) "搜索笔记" else "记一笔",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = LuyuanColors.Green700,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onToggleSearch) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "切换搜索",
                            tint = if (searchMode) LuyuanColors.Green700 else LuyuanColors.Ink3
                        )
                    }
                    IconButton(onClick = onRecord) {
                        Icon(Icons.Default.Mic, contentDescription = "录音", tint = LuyuanColors.Green700)
                    }
                }
                if (searchMode) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        placeholder = { Text("搜笔记内容 / 标签…", fontSize = 13.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onDismiss() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = onInputTextChange,
                        placeholder = { Text("想到什么记什么，回车 = 存一条笔记", fontSize = 13.sp) },
                        minLines = 2,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onCommit() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                    )
                }
                Spacer(Modifier.padding(3.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onCommit,
                        enabled = inputText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = LuyuanColors.Green700),
                        modifier = Modifier.weight(1f)
                    ) { Text("存笔记", fontWeight = FontWeight.Bold) }
                    TextButton(
                        onClick = onSaveDiary,
                        enabled = inputText.isNotBlank()
                    ) { Text("📔 日记") }
                    TextButton(onClick = onPickImage) { Text("🖼 图片") }
                    TextButton(onClick = onDismiss) { Text("收起") }
                }
            }
        }
    }
}
