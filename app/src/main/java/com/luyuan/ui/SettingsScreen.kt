package com.luyuan.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luyuan.BuildConfig
import com.luyuan.data.AskRemote
import com.luyuan.data.MessageSettings
import com.luyuan.data.NoteRepository
import com.luyuan.platform.PermissionHelper
import com.luyuan.platform.StorageLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

/** 设置页 v2（路河拍板重排）：分区卡片化——📁目录/🔐权限/⌨️实体键/📚模型/🤖问路远/📱保活/ℹ️关于 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: LuyuanViewModel, onBack: () -> Unit, onAsk: () -> Unit = {}, onAskKey: () -> Unit = {}) {
    val context = LocalContext.current
    val allFiles = PermissionHelper.hasAllFiles(context)
    val audio = PermissionHelper.hasAudio(context)

    var currentPath by remember { mutableStateOf(StorageLocator.getRoot(context).absolutePath) }
    var candidates by remember { mutableStateOf(listOf<StorageLocator.Candidate>()) }
    var scanning by remember { mutableStateOf(false) }

    // 问路远（A2）：Key 编辑已拆到独立子页 AskKeyScreen（#4），此页只放行跳转


    suspend fun rescan() {
        scanning = true
        val list = withContext(Dispatchers.IO) { StorageLocator.candidates(context) }
        candidates = list
        currentPath = StorageLocator.getRoot(context).absolutePath
        scanning = false
    }

    LaunchedEffect(Unit) { rescan() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- 📁 共享目录 ----------
            SectionCard("📁 共享目录") {
                Text(
                    "当前：$currentPath",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val curCount = candidates.firstOrNull { it.path == currentPath }?.count
                if (curCount != null) {
                    Text(
                        "此目录扫到 $curCount 个笔记文件。" +
                                if (curCount == 0) "如果是 0，多半是选错目录或 Syncthing 还没同步。" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (curCount == 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("点选候选目录（按文件数排序）：", style = MaterialTheme.typography.labelMedium)
                for (c in candidates) {
                    val selected = c.path == currentPath
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // 存完整绝对路径（旧实现只存最后一段名字，选到非顶层目录会丢笔记）
                                StorageLocator.setRoot(context, c.path)
                                currentPath = c.path
                                // 重建文件监听 + 重新读盘：换目录后必须让监听器跟着换，否则新目录变动感知不到
                                vm.onRootChanged()
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) LuyuanColors.Green50
                            else MaterialTheme.colorScheme.background
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                c.path.substringAfterLast('/') +
                                        if (selected) "（当前）" else "",
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Text(
                                "${c.count} 个文件",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (scanning) {
                    Text("扫描中…", style = MaterialTheme.typography.labelSmall)
                }
                Button(onClick = { vm.refresh() }) { Text("重新读取列表") }
                Text(
                    "注意：目录必须与 Syncthing App 里共享的文件夹完全一致（App 只负责读写，搬运交给 Syncthing）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---------- 🔍 同步诊断 ----------
            SectionCard("🔍 同步诊断") {
                Text(
                    "主页笔记数量不对？点下面的按钮体检当前目录，把结果告诉路远即可定位。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                var diag by remember { mutableStateOf<NoteRepository.Diag?>(null) }
                var diagBusy by remember { mutableStateOf(false) }
                val diagScope = rememberCoroutineScope()
                Button(
                    onClick = {
                        diagBusy = true
                        diagScope.launch(Dispatchers.IO) {
                            val d = NoteRepository.diagnose(context)
                            withContext(Dispatchers.Main) {
                                diag = d
                                diagBusy = false
                            }
                        }
                    },
                    enabled = !diagBusy
                ) { Text(if (diagBusy) "体检中…" else "体检当前目录") }
                diag?.let { d ->
                    val ok = d.dirReadable
                    Text(
                        buildString {
                            appendLine("目录：${d.root}")
                            appendLine("目录可读：${if (ok) "✅" else "⚠️ 读不了（权限/路径不存在）"}")
                            appendLine("笔记文件总数：${d.jsonTotal}（含隐藏备份）")
                            appendLine("其中真笔记：${d.notes} 条（主页应显示这么多）")
                            appendLine("日记：${d.diaries} 条（日记页显示，主页不显示）")
                            appendLine("回收站：${d.trashed} 条")
                            appendLine("联系人/课程/账目等实体：${d.entities} 个（不算笔记）")
                            if (d.failed.isNotEmpty()) {
                                appendLine("读不出来的坏文件 ${d.failed.size}+ 个：")
                                appendLine(d.failed.joinToString("\n"))
                            }
                            appendLine("对照：若「真笔记」远小于电脑面板的笔记数，")
                            appendLine("请核对手机 Syncthing App 里共享文件夹的路径是否就是这个目录。")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---------- 🔐 权限 ----------
            SectionCard("🔐 权限") {
                Text("麦克风：${if (audio) "✅ 已授权" else "⚠️ 未授权"}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "所有文件访问：${if (allFiles) "✅ 已授权" else "⚠️ 未授权（需在设置中开启，才能读写 Syncthing 共享目录）"}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!allFiles) {
                    Button(onClick = {
                        context.startActivity(PermissionHelper.allFilesSettingsIntent())
                    }) { Text("去开启所有文件访问") }
                }
            }

            // ---------- ⌨️ 实体键快捷 ----------
            SectionCard("⌨️ 实体键快捷（实验性）") {
                val volumeServiceOn = remember {
                    android.provider.Settings.Secure.getString(
                        context.contentResolver,
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    )?.contains("VolumeKeyService") == true
                }
                val overlayOn = remember {
                    android.provider.Settings.canDrawOverlays(context)
                }
                Text(
                    "同时按「音量加 + 音量减」= 直接开始录音（录完回电脑转文字，不用本地模型）。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "①无障碍「路远·组合键录音」（状态：${if (volumeServiceOn) "✅ 已开启" else "⚠️ 未开启"}）\n" +
                        "②「显示在其他应用上层」（状态：${if (overlayOn) "✅ 已授权" else "⚠️ 未授权"}）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "组合成功时不会改变音量。若没反应或被 vivo 后台清理：请到管家里允许路远自启动并锁定后台，然后反馈给路远。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        )
                    }) { Text("去开无障碍") }
                    Button(onClick = {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:com.luyuan")
                            )
                        )
                    }) { Text("去开悬浮层") }
                }
            }

            // ---------- 📚 语音模型离线导入 ----------
            SectionCard("📚 语音模型离线导入") {
                Text(
                    "在线下载慢？推荐免数据线的方式：\n" +
                        "① 电脑把 vosk-model-small-cn-0.22.zip 放进共享文件夹的 model\\ 子目录（D:\\Luyuan\\data\\notes\\model\\）；\n" +
                        "② 等 Syncthing 同步到手机（AA 路远/model/）；\n" +
                        "③ 重启路远自动识别（zip 或解压后的文件夹都认）。\n" +
                        "备选：数据线把 zip 拷到手机「Download」文件夹也可以。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---------- 🤖 问路远（A2） ----------
            SectionCard("🤖 问路远（AI 问答）") {
                Text(
                    "填一次 OpenAI 兼容接口（默认 DeepSeek），就能随时用对话问它，回答会参考你本机的笔记和待办。" +
                        "Key 只存本机 App 私有目录，不进同步目录；" +
                        "私密标签的笔记默认不发出去，需要时在对话页勾选「包含私密内容」才会发出。" +
                        "模型不在手填——在对话页顶部一键切换（快答/深思/视觉/Pro）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // #4：Key 编辑拆到独立子页（隔离规则收口，聊天历史不进此页）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAskKey() }
                        .padding(vertical = 10.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("问路远 · 钥匙", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LuyuanColors.Ink1)
                        Text("Key / 接口地址 / 模型 / 连通性自检", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = LuyuanColors.Green700)
                }
                Button(
                    onClick = onAsk,
                    enabled = AskRemote.loadConfig(context).ready,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("开始对话") }
            }

            // ---------- 📬 消息待办（立项单 2026-09-13 T4/T5） ----------
            MessageTodoCard()

            // ---------- 📱 vivo 保活指引（v1.12）：提醒/通知失灵的自查路径 ----------
            KeepAliveCard()

            // ---------- ℹ️ 关于 ----------
            SectionCard("ℹ️ 关于") {
                Text(
                    "路远 安卓 App v${BuildConfig.VERSION_NAME} · 去中心化本地记事\n" +
                        "数据按 SYNC_FORMAT 与电脑端双向同步（Syncthing）。\n" +
                        "语音 = 录音待转写 / 离线识别 / 键盘三模式；联系人来自共享目录 contacts/。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 「消息待办」设置卡（立项单 T4/T5）：手机（主力机口径，09-13 拍板）收到微信/QQ 消息 → 命中待办关键词 → 云端抽取 → 待确认。
 * 隐私设计三件：①总开关默认关 ②微信/QQ 各自独立白名单 ③本月外发条数可见。
 */
@Composable
private fun MessageTodoCard() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
            var on by remember { mutableStateOf(MessageSettings.enabled(context)) }
            var wechat by remember { mutableStateOf(MessageSettings.wechatOn(context)) }
            var qq by remember { mutableStateOf(MessageSettings.qqOn(context)) }
            var groups by remember { mutableStateOf(MessageSettings.groupsOn(context)) }
    var access by remember { mutableStateOf(MessageSettings.notificationAccess(context)) }
    var sent by remember { mutableStateOf(MessageSettings.sentCountThisMonth(context)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (open) "📬 消息待办（点收起）▴" else "📬 消息待办（手机消息自动变待办）▾",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { open = !open }
        )
        if (open) {
            Text(
                "开启后，命中待办关键词的消息，其原文会发送到 DeepSeek 云端解析成待办；" +
                    "其余消息不出本机。抽出来的待办先落「待确认」，你在笔记页点 ✓ 才真入账。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ① 通知使用权（前置条件）
            Text(
                if (access) "①通知使用权：✅ 已开启"
                else "①通知使用权：⚠️ 未开启（不开启读不到任何消息）",
                fontSize = 12.5.sp,
                color = if (access) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
            )
            if (!access) {
                Button(onClick = {
                    try {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        )
                    } catch (_: Exception) {
                    }
                }) { Text("去开通知使用权") }
                Text(
                    "鸿蒙 4 / 安卓路径：设置 → 通知和状态栏 → 通知管理（或更多通知设置）→ 通知使用权 → 找到「路远消息待办监听」→ 打开。\n" +
                        "找不到入口时：在设置顶部搜索框搜「通知使用权」直接跳。\n" +
                        "开完回到本页，状态会变 ✅（下次进设置页刷新）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("刷新状态", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            access = MessageSettings.notificationAccess(context)
                        })
                    Spacer(Modifier.width(16.dp))
                    Text("重扫外发计数", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            sent = MessageSettings.sentCountThisMonth(context)
                        })
                }
            }

            // ② 总开关
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("②消息待办总开关", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                Switch(
                    checked = on,
                    onCheckedChange = {
                        on = it
                        MessageSettings.setEnabled(context, it)
                    }
                )
            }
            Text(
                if (on) "已开启：命中关键词的消息会送到云端解析" else "已关闭：任何消息都不出本机",
                style = MaterialTheme.typography.labelSmall,
                color = if (on) LuyuanColors.Amber else MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ③ 来源白名单
            Text("③来源（各自独立）", fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("微信", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = wechat,
                    enabled = on,
                    onCheckedChange = {
                        wechat = it
                        MessageSettings.setWechatOn(context, it)
                    }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("QQ", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = qq,
                    enabled = on,
                    onCheckedChange = {
                        qq = it
                        MessageSettings.setQqOn(context, it)
                    }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("群聊（含通知群）", fontSize = 13.sp)
                    Text("开了才读群消息；噪音大可随时关", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = groups,
                    enabled = on,
                    onCheckedChange = {
                        groups = it
                        MessageSettings.setGroupsOn(context, it)
                    }
                )
            }

            // ④ 外发计数（透明可查）
            Text(
                "④本月已外发：$sent 条" +
                    if (sent == 0) "（没有原文出过本机）" else "（这些消息原文发到过云端）",
                fontSize = 12.sp,
                color = if (sent == 0) MaterialTheme.colorScheme.onSurfaceVariant else LuyuanColors.Amber
            )
            Text(
                "只处理私聊；群消息、广告、闲聊一律跳过。误报比漏报更伤，宁可漏。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 分区白卡：方案 A 卡片语言（纸白底/圆角16/淡描边） */
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        content()
    }
}

/** vivo 保活指引卡（可展开；精确闹钟权限检测 + 一键跳转） */
@Composable
private fun KeepAliveCard() {
    val context = LocalContext.current
    var keepAliveOpen by remember { mutableStateOf(false) }
    val am = remember {
        context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
    }
    val exactOk = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (keepAliveOpen) "📱 vivo 保活指引（点收起）▴" else "📱 vivo 保活指引（提醒不响看这里）▾",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { keepAliveOpen = !keepAliveOpen }
        )
        if (keepAliveOpen) {
            if (!exactOk) {
                Text(
                    "⚠️ 精确闹钟权限没开，提醒可能晚几分钟。点这里去开 →",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable {
                            try {
                                context.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        android.net.Uri.parse("package:com.luyuan")
                                    )
                                )
                            } catch (_: Exception) {
                            }
                        }
                )
            } else {
                Text(
                    "✅ 精确闹钟权限已开，提醒准时。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                "提醒/速记条不灵，多半是 vivo 杀了后台。四步设置一次就好：\n" +
                    "1️⃣ 放行自启动：i管家 → 应用管理 → 路远 → 权限 → 开「自启动」\n" +
                    "2️⃣ 允许后台耗电：设置 → 电池 → 后台高耗电 → 路远开\n" +
                    "3️⃣ 后台加锁：多任务界面 → 路远卡片往下拉，出现 🔒\n" +
                    "4️⃣ 允许通知：设置 → 通知与状态栏 → 通知管理 → 路远全开",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
