package com.luyuan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 悬浮超级终端胶囊 v3（Q13 路河拍板 09-10）：
 * - **收起态**：单行胶囊，只有一个 🎙 语音圆钮（极简）；点文字区 → 向下展开。
 * - **展开态**：输入框（高度够、文字可见）+ 三按钮「日记 / 搜索 / 图片」+ 语音圆钮。
 * - 搜索：展开态下点「搜索」→ 输入框切换为搜索框（实时过滤）；再点「✕」退出搜索。
 * - 草稿由 MainActivity 走 prefs 实时缓存（不在此组件内持久化）。
 */
@Composable
fun TerminalCapsule(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    searchMode: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    onCommitDiary: () -> Unit,
    onSaveDiary: () -> Unit,
    onPickImage: () -> Unit,
    onAsk: () -> Unit = {},
    onRecord: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusReq = remember { FocusRequester() }
    // 焦点请求重试：expanded 变 true 时输入框可能尚未完成 compose，单次 requestFocus 会静默失败
    // （路河真机「打字不显示」的疑因之一）。改为多轮重试，成功即止。
    LaunchedEffect(expanded) {
        if (expanded) {
            repeat(6) { attempt ->
                kotlinx.coroutines.delay(if (attempt == 0) 120L else 80L)
                val ok = runCatching { focusReq.requestFocus() }.isSuccess
                if (ok) return@LaunchedEffect
            }
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .shadow(18.dp, RoundedCornerShape(22.dp), clip = false)
            .background(
                // 改不透明：原 0xF7/0xED 半透明叠在浅色页面上，会拉低输入文字的对比度
                // （路河真机「打字看不见」）→ 统一为不透明纸白。
                Color(0xFFFAF7F0),
                RoundedCornerShape(22.dp)
            )
            .border(1.dp, Color(0x1A224A3A), RoundedCornerShape(22.dp))
            .padding(start = 18.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
    ) {
        if (expanded) {
            // ---------- 展开态：输入框（可切搜索）+ 按钮行 ----------
            // 输入区必须有不透明实底 + 确定的深色文字：路河 09-10 真机反馈「点胶囊打字看不见」，
            // 根因是文字色依赖主题解析 + 背景半透明叠加，真机对比度不足。此处硬性锁死。
            val fieldBg = Color(0xFFFFFFFF)
            if (searchMode) {
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.5.sp, color = LuyuanColors.Ink1),
                    cursorBrush = SolidColor(LuyuanColors.Green700),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .focusRequester(focusReq)
                        .background(fieldBg, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (searchQuery.isEmpty()) {
                                Text("搜索笔记…", fontSize = 14.sp, color = LuyuanColors.Ink3)
                            }
                            inner
                        }
                    }
                )
            } else {
                BasicTextField(
                    value = inputText,
                    onValueChange = onInputTextChange,
                    textStyle = TextStyle(fontSize = 15.5.sp, color = LuyuanColors.Ink1),
                    cursorBrush = SolidColor(LuyuanColors.Green700),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCommitDiary() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 74.dp)
                        .focusRequester(focusReq)
                        .background(fieldBg, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.TopStart) {
                            if (inputText.isEmpty()) {
                                Text("记一笔，回车存笔记；点「日记」存进今天…", fontSize = 14.sp, color = LuyuanColors.Ink3)
                            }
                            inner
                        }
                    }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (searchMode) {
                    // 搜索态：退出搜索
                    ExpandedAction("退出搜索", Icons.Default.Close, onToggleSearch)
                } else {
                    // 09-15 夜 IA 重排：问路远入口迁进胶囊（笔记页顶栏瘦身），与 日记/搜索/图片 同排
                    ExpandedAction("问路远", Icons.Default.SmartToy, onAsk)
                    Spacer(Modifier.width(6.dp))
                    ExpandedAction("日记", Icons.Default.EditNote, onSaveDiary)
                    Spacer(Modifier.width(6.dp))
                    ExpandedAction("搜索", Icons.Default.Search, onToggleSearch)
                    Spacer(Modifier.width(6.dp))
                    ExpandedAction("图片", Icons.Default.Image, onPickImage)
                }
                Spacer(Modifier.weight(1f))
                // ✕ 收起（点空白处也收起，由 MainActivity 外层处理）
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .background(LuyuanColors.Green100, CircleShape)
                        .clickable { onToggleExpanded() }
                ) { Icon(Icons.Default.Close, contentDescription = "收起", tint = LuyuanColors.Ink2, modifier = Modifier.size(17.dp)) }
                Spacer(Modifier.width(8.dp))
                VoiceButton(onRecord)
            }
        } else {
            // ---------- 收起态：占位文案 + 唯一语音圆钮 ----------
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "记一笔笔记 / 说一句 / 问路远…",
                    fontSize = 13.sp,
                    color = LuyuanColors.Ink3,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onToggleExpanded() }
                )
                VoiceButton(onRecord)
            }
        }
    }
}

@Composable
private fun VoiceButton(onRecord: () -> Unit) {
    // 2026-09-15 路河拍板：讯飞语音是电脑端的事，手机胶囊语音钮短按即录音（撤回 vc71 长按代按）。
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(38.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .clickable { onRecord() }
    ) { Icon(Icons.Default.Mic, contentDescription = "录音", tint = Color.White, modifier = Modifier.size(19.dp)) }
}

@Composable
private fun ExpandedAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(LuyuanColors.Green50, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Icon(icon, contentDescription = null, tint = LuyuanColors.Green700, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = LuyuanColors.Green700
        )
    }
}
