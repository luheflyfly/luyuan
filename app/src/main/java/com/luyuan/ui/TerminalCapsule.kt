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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
            // vc127：投影从 18dp 收到 10dp（纸面不需要重投影），圆角并入 20 档，面用 SurfaceHigh。
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(com.luyuan.ui.design.LuyuanRadius.xl),
                clip = false,
                ambientColor = Color(0x22163026),
                spotColor = Color(0x33163026)
            )
            .background(
                com.luyuan.ui.design.LuyuanPalette.SurfaceHigh,
                RoundedCornerShape(com.luyuan.ui.design.LuyuanRadius.xl)
            )
            .border(
                1.dp,
                com.luyuan.ui.design.LuyuanPalette.Line,
                RoundedCornerShape(com.luyuan.ui.design.LuyuanRadius.xl)
            )
            .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
    ) {
        if (expanded) {
            // ---------- 展开态：输入框（可切搜索）+ 按钮行 ----------
            // vc92 根修（路河 09-17 真机：「打字不显示，但回车能存」）：裸 BasicTextField 换
            // M3 OutlinedTextField——全工程真机一直正常的输入框（笔记编辑器/问路远/联系人/加课）
            // 清一色 OutlinedTextField，胶囊是仅有的两处裸文本布局用法，这台 vivo 对裸路径
            // 文字层不渲染（字进得去状态、整层空白）。09-10「对比度锁死」口径保留在 colors/textStyle。
            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = LuyuanColors.Green700,
                unfocusedBorderColor = Color(0x33224A3A),
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White
            )
            if (searchMode) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    placeholder = { Text("搜索笔记…", fontSize = 14.sp, color = LuyuanColors.Ink3) },
                    textStyle = TextStyle(fontSize = 15.5.sp, color = LuyuanColors.Ink1),
                    colors = fieldColors,
                    shape = RoundedCornerShape(com.luyuan.ui.design.LuyuanRadius.md),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusReq)
                )
            } else {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputTextChange,
                    placeholder = { Text("记一笔，回车存下；点「日记」存进今天…", fontSize = 14.sp, color = LuyuanColors.Ink3) },
                    textStyle = TextStyle(fontSize = 15.5.sp, color = LuyuanColors.Ink1),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCommitDiary() }),
                    maxLines = 4,
                    colors = fieldColors,
                    shape = RoundedCornerShape(com.luyuan.ui.design.LuyuanRadius.md),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 74.dp)
                        .focusRequester(focusReq)
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
                    // 09-15 夜 IA 重排：问路远入口迁进胶囊（笔记页顶栏瘦身），与 日记/搜索/图片 同排。
                    // 注：原行尾「✕ 收起」已删——点胶囊外空白即收起（MainActivity 外层手势层），少一步且防窄屏挤出。
                    ExpandedAction("问路远", Icons.Default.SmartToy, onAsk)
                    Spacer(Modifier.width(4.dp))
                    ExpandedAction("日记", Icons.Default.EditNote, onSaveDiary)
                    Spacer(Modifier.width(4.dp))
                    ExpandedAction("搜索", Icons.Default.Search, onToggleSearch)
                    Spacer(Modifier.width(4.dp))
                    ExpandedAction("图片", Icons.Default.Image, onPickImage)
                }
                Spacer(Modifier.weight(1f))
                VoiceButton(onRecord)
            }
        } else {
            // ---------- 收起态：一支笔 + 一句提示 + 唯一语音圆钮 ----------
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Default.EditNote,
                    contentDescription = null,
                    tint = LuyuanColors.Green700,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    "记一笔，或说一句…",
                    fontSize = 13.5.sp,
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
            .size(40.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .clickable { onRecord() }
    ) { Icon(Icons.Default.Mic, contentDescription = "录音", tint = Color.White, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun ExpandedAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                com.luyuan.ui.design.LuyuanPalette.Green50,
                RoundedCornerShape(com.luyuan.ui.design.LuyuanRadius.pill)
            )
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = LuyuanColors.Green700, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = LuyuanColors.Green700
        )
    }
}
