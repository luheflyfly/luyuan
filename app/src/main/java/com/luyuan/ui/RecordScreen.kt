package com.luyuan.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luyuan.platform.PermissionHelper
import kotlinx.coroutines.delay

private const val MODE_RECORD = 1
private const val MODE_OFFLINE = 3

/** 语音记事页：录音待转写（回电脑 SenseVoice）/ 离线识别（本机引擎） 两模式。
 *  即时识别（系统接口被 vivo 封死）与键盘兜底已按用户要求移除；
 *  键盘语音入口在主页「双麦克风」之二（长按空格）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(vm: LuyuanViewModel, onBack: () -> Unit) {
    val recording by vm.isRecording.collectAsStateWithLifecycle()
    val voiceError by vm.voiceError.collectAsStateWithLifecycle()
    val savedMsg by vm.savedMsg.collectAsStateWithLifecycle()
    val diaryMode by vm.diaryMode.collectAsStateWithLifecycle()
    val wavStartedAt by vm.wavStartedAt.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (!PermissionHelper.hasAudio(context)) {
            audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        if (!PermissionHelper.hasPostNotifications(context)) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val offlineOk = remember { vm.offlineBundled() }
    var mode by remember {
        val pref = vm.preferredVoiceMode()
        mutableStateOf(
            if (pref == "offline" && offlineOk) MODE_OFFLINE else MODE_RECORD
        )
    }
    // 进页预热：默认模式是离线时后台先加载模型
    LaunchedEffect(Unit) {
        if (mode == MODE_OFFLINE) vm.warmupOffline()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (diaryMode) "记日记" else "语音记事") },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.cancelRecording()
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 模式切换
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip("录音待转写", mode == MODE_RECORD) {
                    vm.cancelRecording()
                    vm.rememberVoiceMode("record")
                    mode = MODE_RECORD
                }
                if (offlineOk) {
                    ModeChip("离线识别", mode == MODE_OFFLINE) {
                        vm.cancelRecording()
                        vm.rememberVoiceMode("offline")
                        mode = MODE_OFFLINE
                        vm.warmupOffline() // 后台先加载模型，首次识别免等
                    }
                }
            }

            when (mode) {
                MODE_RECORD -> {
                    Spacer(Modifier.height(4.dp))
                    Icon(Icons.Default.Mic, contentDescription = null,
                        modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    // 录音计时
                    var tick by remember { mutableLongStateOf(0L) }
                    LaunchedEffect(wavStartedAt) {
                        if (wavStartedAt > 0L) {
                            while (vm.isRecording.value) {
                                tick = System.currentTimeMillis()
                                delay(500)
                            }
                        }
                    }
                    if (recording) {
                        val secs = ((tick - wavStartedAt) / 1000).coerceAtLeast(0)
                        Text(
                            "● 录音中  %02d:%02d".format(secs / 60, secs % 60),
                            fontSize = 22.sp,
                            color = Color(0xFFEF4444)
                        )
                        Text(
                            "停止后原声自动同步回电脑，转写完成文字就回来了",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { vm.stopWavRecording() },
                            modifier = Modifier.size(120.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                        ) { Text("停止") }
                    } else {
                        Text(
                            if (savedMsg.startsWith("已录音")) savedMsg else "录下原声，回家自动转写",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "电脑开机会用 SenseVoice 自动转写（延迟约十几秒到几分钟）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { vm.startWavRecording(diaryMode) },
                            modifier = Modifier.size(120.dp)
                        ) { Text(if (savedMsg.startsWith("已录音")) "再录一段" else "开始录音") }
                    }
                }

                else -> {
                    // 离线识别
                    Spacer(Modifier.height(4.dp))
                    Icon(Icons.Default.CloudOff, contentDescription = null,
                        modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    val busy by vm.offlineBusy.collectAsStateWithLifecycle()
                    when {
                        busy -> {
                            Text("本机识别中…", fontSize = 20.sp)
                            Text(
                                "首次识别要先加载模型（几秒钟），请稍等",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        recording -> {
                            var tick by remember { mutableLongStateOf(0L) }
                            LaunchedEffect(wavStartedAt) {
                                if (wavStartedAt > 0L) {
                                    while (vm.isRecording.value) {
                                        tick = System.currentTimeMillis()
                                        delay(500)
                                    }
                                }
                            }
                            val secs = ((tick - wavStartedAt) / 1000).coerceAtLeast(0)
                            Text(
                                "● 录音中  %02d:%02d".format(secs / 60, secs % 60),
                                fontSize = 22.sp,
                                color = Color(0xFFEF4444)
                            )
                            Button(
                                onClick = { vm.stopOfflineRecording() },
                                modifier = Modifier.size(120.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                            ) { Text("停止") }
                        }
                        else -> {
                            Text(
                                if (savedMsg.isNotBlank()) "已记下" else "离线识别：不出网也能转文字",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "小模型装在手机里；识别不出来会自动改成录音待电脑转写，内容不丢",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { vm.startWavRecording(diaryMode) },
                                modifier = Modifier.size(120.dp)
                            ) { Text(if (savedMsg.isNotBlank()) "再录一段" else "开始录音") }
                            voiceError?.let {
                                Text(
                                    "$it",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) { Text(label) }
}
