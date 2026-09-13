package com.luyuan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luyuan.platform.PermissionHelper
import com.luyuan.platform.StorageLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首启引导三页（v2/onboarding.html 施工，2026-09-13 批）：①我是谁 ②授权+目录防呆 ③三招上手。
 * 只在首次启动出现（luyuan_prefs: onboarding_done）；完成=落笔记页并把胶囊展开（与深链 auto=note 同款）。
 * 不新增任何权限与功能，只把现有步骤排顺。
 */
@Composable
fun OnboardingScreen(vm: LuyuanViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(0) }
    var allFiles by remember { mutableStateOf(PermissionHelper.hasAllFiles(context)) }
    var candidates by remember { mutableStateOf(listOf<StorageLocator.Candidate>()) }
    var scanned by remember { mutableStateOf(false) }
    var pickedPath by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFF6F3EA), MaterialTheme.colorScheme.background, Color(0xFFE9EFE6))
                )
            )
    ) {
        Text(
            "跳过",
            fontSize = 12.sp,
            color = LuyuanColors.Ink4,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 20.dp, end = 22.dp)
                .clickable { onDone() }
        )
        when (page) {
            0 -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 30.dp)
                ) {
                    Text("🌿", fontSize = 64.sp)
                    Spacer(Modifier.height(26.dp))
                    Text(
                        "你好，我是路远",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = LuyuanColors.Green700
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "你的口袋记事搭子：说句话、丢张截图，\n它记好、分好类、到点提醒，\n和电脑上的它悄悄同步，不经云不外传。",
                        fontSize = 13.sp,
                        lineHeight = 24.sp,
                        textAlign = TextAlign.Center,
                        color = LuyuanColors.Ink2
                    )
                }
            }
            1 -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("笔记放在哪，一步讲清", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = LuyuanColors.Green700)
                    Spacer(Modifier.height(14.dp))
                    // 流程图卡：手机 ⇄ 文件夹 ⇄ 电脑（不经云）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        FlowNode("📱", "手机笔记")
                        ArrowLine()
                        FlowNode("📁", "共享文件夹")
                        ArrowLine()
                        FlowNode("🖥", "电脑")
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "全程走手机里的共享文件夹同步，不经过云、不外传。",
                        fontSize = 11.sp, color = LuyuanColors.Ink4, textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    if (!allFiles) {
                        Button(
                            onClick = {
                                try {
                                    context.startActivity(PermissionHelper.allFilesSettingsIntent())
                                } catch (_: Exception) {
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = LuyuanColors.Green700),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text("① 去授权存储", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "授权完回到这里，点下面扫描。",
                            fontSize = 11.sp, color = LuyuanColors.Ink4
                        )
                    } else {
                        Text("✅ 已授权存储", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LuyuanColors.Green700)
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            scanning = true
                            scope.launch {
                                val list = withContext(Dispatchers.IO) { StorageLocator.candidates(context) }
                                candidates = list
                                scanned = true
                                scanning = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LuyuanColors.Green700),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text(if (scanning) "扫描中…" else "② 扫描共享目录", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    if (scanned) {
                        Spacer(Modifier.height(8.dp))
                        if (candidates.isEmpty()) {
                            Text(
                                "没扫到候选目录。检查 Syncthing 是否已把文件夹同步到手机，再重扫。",
                                fontSize = 11.sp, color = LuyuanColors.Amber
                            )
                        }
                        for (c in candidates.sortedByDescending { it.count }.take(5)) {
                            val picked = pickedPath == c.path
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .background(
                                        if (picked) LuyuanColors.Green50 else MaterialTheme.colorScheme.surface,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        StorageLocator.setRoot(context, c.path)
                                        vm.onRootChanged()
                                        pickedPath = c.path
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    c.path.substringAfterLast('/') + if (picked) "（已选）" else "",
                                    fontWeight = if (picked) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text("${c.count} 个文件", fontSize = 11.sp, color = LuyuanColors.Ink4)
                            }
                        }
                    }
                }
            }
            else -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 26.dp)
                ) {
                    Text("三招，今天就用上", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = LuyuanColors.Green700)
                    Spacer(Modifier.height(18.dp))
                    Trick("✏️", "记一笔", "主页下方胶囊点开，打字回车就是一条笔记")
                    Trick("🎙", "说一句", "胶囊的话筒按住说话；或音量加+减一起按，随时随地录音")
                    Trick("🤖", "问路远", "左缘往右滑问它任何事，它读得到你的笔记和待办")
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "装好后长按桌面，还能把「今日卡」拖出去。",
                        fontSize = 11.sp, color = LuyuanColors.Ink4
                    )
                }
            }
        }
        // 底部进度点 + 主按钮
        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 84.dp)
        ) {
            for (i in 0..2) {
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (i == page) 16.dp else 6.dp, 6.dp)
                        .background(
                            if (i == page) LuyuanColors.Green700 else Color(0xFFDDD8CC),
                            RoundedCornerShape(99.dp)
                        )
                )
            }
        }
        Button(
            onClick = {
                if (page < 2) page += 1 else onDone()
            },
            colors = ButtonDefaults.buttonColors(containerColor = LuyuanColors.Green700),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(
                when (page) {
                    0 -> "开始 · 只要 30 秒"
                    1 -> if (pickedPath.isNotBlank()) "下一步" else "先跳过，回头再弄也行"
                    else -> "完成，开始记"
                },
                fontSize = 15.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun FlowNode(emoji: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 22.sp)
        Text(label, fontSize = 10.5.sp, color = LuyuanColors.Ink2)
    }
}

@Composable
private fun ArrowLine() {
    Text("⇄", fontSize = 15.sp, color = LuyuanColors.Green700, modifier = Modifier.padding(horizontal = 6.dp))
}

@Composable
private fun Trick(emoji: String, title: String, desc: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .padding(13.dp)
    ) {
        Text(emoji, fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LuyuanColors.Ink1)
            Text(desc, fontSize = 11.sp, color = LuyuanColors.Ink3, lineHeight = 16.sp)
        }
    }
}
