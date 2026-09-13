package com.luyuan

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.luyuan.ui.CourseScreen
import com.luyuan.ui.DetailEditScreen
import com.luyuan.ui.JournalScreen
import com.luyuan.ui.LedgerScreen
import com.luyuan.ui.LuyuanTheme
import com.luyuan.ui.LuyuanColors
import com.luyuan.ui.LuyuanViewModel
import com.luyuan.ui.NoteListScreen
import androidx.compose.ui.graphics.graphicsLayer
import com.luyuan.ui.rememberPressScale
import com.luyuan.ui.PeopleScreen
import com.luyuan.ui.RecordScreen
import com.luyuan.ui.SettingsScreen
import com.luyuan.ui.AskKeyScreen
import com.luyuan.ui.TerminalCapsule
import com.luyuan.ui.TrashScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** 快捷磁贴/通知唤起时的目标页（"record" = 进录音页直接开录） */
    private var autoRoute by mutableStateOf("list")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 09-13 夜修「胶囊打字看不见」复发（vc57 的 imePadding 在路河 vivo 上无效）：
        // Activity 未开 edge-to-edge 时，IME insets 在装饰层就被消费掉，Compose 的
        // WindowInsets.ime 恒为 0；vivo/OriginOS 上 adjustResize 又经常不 resize，
        // 于是「键盘盖住胶囊 + imePadding 不抬」同时发生。开了 edge-to-edge 后
        // imePadding 在任何 ROM 都基于真实键盘高度工作。主题恒浅色 → 图标锁深色。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            )
        )
        com.luyuan.platform.CrashLogger.install(this)
        autoRoute = routeFromIntent(intent)
        setContent {
            LuyuanTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(startDest = autoRoute)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        autoRoute = routeFromIntent(intent)
    }

    /** 快捷磁贴/通知/桌面组件唤起时的目标页。
     *  "record"/"note" 走超级终端；"page"=跳到底栏某页；"detailId"=进某条笔记详情。 */
    private fun routeFromIntent(i: Intent?): String {
        when (i?.getStringExtra("auto")) {
            "record" -> return "record"
            "note" -> return "note"
        }
        i?.getStringExtra("page")?.let { return "tab:$it" }
        i?.getStringExtra("detailId")?.let { return "detail:$it" }
        return "list"
    }
}

/**
 * 手机 UI 方案 A「五键直达」（2026-09-08 路河拍板，施工合同=交接_App端_手机UI方案A）：
 * 底栏五键（笔记/记账/课程/日记/人脉）+ 悬浮超级终端胶囊（唯一录入口）；
 * 问路远/回收站=右上角 push 进出的独立页。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRoot(startDest: String) {
    val nav = rememberNavController()
    val vm: LuyuanViewModel = viewModel()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0) { 5 }
    val navInteraction = remember { MutableInteractionSource() }
    val navScale = rememberPressScale(navInteraction)
    var searchMode by remember { mutableStateOf(false) }
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val multiSelect by vm.multiSelect.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    // 超级输入框 v3（Q13 拍板）：点胶囊向下展开，展开态才有输入框+三按钮；草稿走 prefs
    var expanded by remember { mutableStateOf(false) }
    val draftPrefs = remember { ctx.getSharedPreferences("luyuan_prefs", android.content.Context.MODE_PRIVATE) }
    var inputText by remember { mutableStateOf(draftPrefs.getString("terminal_draft", "") ?: "") }
    var showSettings by remember { mutableStateOf(false) }
    // 胶囊在根 Box 坐标系里的矩形（用于「点空白收起」判定落点，避免抢胶囊的点击/焦点）
    var capsuleRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    // Q12（路河拍板「入口两个、存储一处」）：终端把内容存进今天日记后，给一条可点回执
    // 「📔 已存入今天的日记 · 去看看」→ 点一下跳日记页，4 秒后自动消失
    val diaryEcho by vm.diaryEcho.collectAsStateWithLifecycle()
    var showDiaryEcho by remember { mutableStateOf(false) }
    LaunchedEffect(diaryEcho) {
        if (diaryEcho > 0) {
            showDiaryEcho = true
            kotlinx.coroutines.delay(4000)
            showDiaryEcho = false
        }
    }

    // 图片按钮（Q13 融图片不融表情）：选图 → 压缩落 images/ → 带配图存一条笔记
    val pickImage = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val rel = com.luyuan.data.NoteRepository.importImage(ctx, uri)
                if (rel != null) {
                    com.luyuan.data.NoteRepository.createManual(
                        ctx, "🖼 图片速记", tags = listOf("分享"), images = listOf(rel)
                    )
                    expanded = false
                    android.widget.Toast.makeText(ctx, "✅ 图片已存入路远", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(startDest) {
        when {
            startDest == "record" -> {
                // 快捷磁贴/音量键/小部件唤起：直接开「录音待转写」
                vm.startWavRecording()
                nav.navigate("record") { launchSingleTop = true }
            }
            startDest == "note" -> {
                // 桌面小部件「记一笔」：落在笔记页并让胶囊直接展开输入
                pagerState.scrollToPage(0)
                expanded = true
            }
            startDest.startsWith("tab:") -> {
                // 桌面组件今日卡：跳到底栏对应页
                val idx = when (startDest.removePrefix("tab:")) {
                    "notes" -> 0
                    "ledger" -> 1
                    "course" -> 2
                    "journal" -> 3
                    "people" -> 4
                    else -> 0
                }
                pagerState.scrollToPage(idx)
            }
            startDest.startsWith("detail:") -> {
                // 桌面组件今日卡「最近」：进对应笔记详情
                pagerState.scrollToPage(0)
                nav.navigate("detail/${startDest.removePrefix("detail:")}") { launchSingleTop = true }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // edge-to-edge 配套：外壳不管状态栏/导航栏 insets——各屏自己的 Scaffold/TopAppBar
        // 本来就按 insets 设计（有 topBar 的屏自己清状态栏，没有的吃系统栏 inset）。
        // 外壳若用默认 contentWindowInsets，嵌套 Scaffold 会双重 padding。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (currentRoute == "home") {
                NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                    val tabs = listOf(
                        Triple(0, "笔记", Icons.AutoMirrored.Filled.Notes),
                        Triple(1, "记账", Icons.Default.Payments),
                        Triple(2, "课程", Icons.Default.CalendarMonth),
                        Triple(3, "日记", Icons.Default.EditNote),
                        Triple(4, "人脉", Icons.Default.People)
                    )
                    for ((page, label, icon) in tabs) {
                    NavigationBarItem(
                        selected = pagerState.currentPage == page,
                        onClick = {
                            if (pagerState.currentPage != page) {
                                // 09-11 修「点击底部标签掉帧」：animateScrollToPage 跨页滚动会
                                // 逐页渲染中间页（笔记→人脉要滚过 3 页），每页现场组合 → 掉帧。
                                // 改瞬移，只渲染目标页 1 页；滑动切页的动画不受影响。
                                scope.launch { pagerState.scrollToPage(page) }
                            }
                        },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                        interactionSource = navInteraction,
                        modifier = Modifier.graphicsLayer { scaleX = navScale; scaleY = navScale }
                    )
                    }
                }
            }
        }
    ) { pad ->
        Box(
            modifier = Modifier
                .padding(pad)
                // 把外壳消费掉的 insets（底栏高度）向下游声明，内层 Scaffold 才不会重复让位
                .consumeWindowInsets(pad)
                .fillMaxSize()
        ) {
            NavHost(
                navController = nav,
                startDestination = "home"
            ) {
                composable("home") {
                    // 09-10 治卡顿：beyondBoundsPageCount 4 -> 1。
                    // 原值 4 = 五页全部常驻渲染，五个完整列表同时占主线程 → 翻页卡顿 + 手势迟钝
                    // （路河反馈「点切页太卡」「滑动很用力才能切页」）。故只保留相邻页。
                    HorizontalPager(
                        state = pagerState,
                        beyondBoundsPageCount = 1,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        when (page) {
                            0 -> NoteListScreen(
                                vm = vm,
                                onRecord = {
                                    vm.startWavRecording()
                                    nav.navigate("record") { launchSingleTop = true }
                                },
                                onDetail = { id -> nav.navigate("detail/$id") },
                                onSettings = { showSettings = true }, // 左滑/顶栏⚙ → 右侧抽屉（不占满全屏）
                                onTrash = { nav.navigate("trash") }
                            )
                            1 -> LedgerScreen(
                                vm = vm,
                                onAsk = { nav.navigate("ask") },
                                onTrash = { nav.navigate("trash") }
                            )
                            2 -> CourseScreen(
                                vm = vm,
                                onAsk = { nav.navigate("ask") },
                                onTrash = { nav.navigate("trash") }
                            )
                            3 -> JournalScreen(vm = vm, onRecord = {
                                vm.startWavRecording()
                                nav.navigate("record") { launchSingleTop = true }
                            })
                            else -> PeopleScreen(vm = vm, onNoteClick = { nav.navigate("detail/$it") })
                        }
                    }
                }
                composable("record") {
                    RecordScreen(vm = vm, onBack = { nav.popBackStack() })
                }
                composable(
                    "detail/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType })
                ) { back ->
                    val id = back.arguments?.getString("id") ?: ""
                    DetailEditScreen(vm = vm, noteId = id, onBack = { nav.popBackStack() })
                }
                composable("settings") {
                    SettingsScreen(
                        vm = vm,
                        onBack = { nav.popBackStack() },
                        onAsk = { nav.navigate("ask") },
                        onAskKey = { nav.navigate("askkey") }
                    )
                }
                composable("askkey") {
                    AskKeyScreen(
                        vm = vm,
                        onBack = { nav.popBackStack() },
                        onAsk = { nav.navigate("ask") }
                    )
                }
                composable("ask") {
                    com.luyuan.ui.AskScreen(vm = vm, onBack = { nav.popBackStack() })
                }
                composable("trash") {
                    TrashScreen(vm = vm, onBack = { nav.popBackStack() })
                }
            }
            // 点击空白处收起展开态 + 收键盘（路河 09-10 反馈：别只靠输入法收起）
            // 09-10 二改：原用无差别 fillMaxSize().clickable()，会与胶囊抢点击 →
            // 路河真机「点胶囊打字不显示」很可能是焦点被本层抢走。
            // 改为 pointerInput 判定落点：落在胶囊矩形内一律放行（不消费），只有点在外围才收起。
            val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
            if (expanded) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val p = down.position
                                val inside = capsuleRect != null && capsuleRect!!.contains(p)
                                if (!inside) {
                                    expanded = false
                                    searchMode = false
                                    focusManager.clearFocus()
                                }
                            }
                        }
                )
            }
            // Q12：存进今天日记后的可点回执（胶囊上方，不挡输入）
            if (showDiaryEcho && currentRoute == "home" && !multiSelect) {
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 82.dp)
                        .background(LuyuanColors.Green700, RoundedCornerShape(999.dp))
                        .clickable {
                            showDiaryEcho = false
                            scope.launch { pagerState.animateScrollToPage(3) } // 3 = 日记页
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        "📔 已存入今天的日记 · 去看看",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            // 悬浮终端胶囊 v3（Q13 拍板：收起态仅语音钮，点开向下展开；多选时收起）
            if (currentRoute == "home" && !multiSelect) {
                TerminalCapsule(
                    expanded = expanded,
                    onToggleExpanded = { expanded = !expanded },
                    searchMode = searchMode,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { vm.setSearchQuery(it) },
                    onToggleSearch = {
                        searchMode = !searchMode
                        if (!searchMode) vm.setSearchQuery("")
                    },
                    inputText = inputText,
                    onInputTextChange = {
                        inputText = it
                        draftPrefs.edit().putString("terminal_draft", it).apply()
                    },
                    onCommitDiary = {
                        // 路河 09-10 口径更正：胶囊是「存笔记」的地方，回车 = 存一条笔记，且不跳页。
                        // 存日记改由展开态的「日记」按钮负责（见 onSaveDiary）。
                        val t = inputText.trim()
                        if (t.isNotBlank()) {
                            vm.addManual(t)
                            inputText = ""
                            draftPrefs.edit().remove("terminal_draft").apply()
                            expanded = false
                            focusManager.clearFocus()
                        }
                    },
                    onSaveDiary = {
                        // 「日记」按钮：存成日记并跳日记页（路河拍板）
                        val t = inputText.trim()
                        if (t.isNotBlank()) {
                            vm.saveDiary(t)
                            inputText = ""
                            draftPrefs.edit().remove("terminal_draft").apply()
                            expanded = false
                            focusManager.clearFocus()
                            scope.launch { pagerState.animateScrollToPage(3) } // 3 = 日记页
                        }
                    },
                    onPickImage = {
                        pickImage.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    onRecord = {
                        vm.startWavRecording()
                        nav.navigate("record") { launchSingleTop = true }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(3f)
                        // 09-11 修「打字看不见」：vivo/OriginOS 上 windowSoftInputMode=adjustResize
                        // 经常不生效（窗口不缩小），键盘直接盖住底部胶囊——字打得进去但看不见。
                        // imePadding 基于 WindowInsets.ime，不依赖窗口 resize，键盘弹出时胶囊自动上移。
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .onGloballyPositioned { coords ->
                            val b = coords.boundsInParent()
                            // 略微外扩 8dp：手指点在胶囊边界附近也算「内」，避免误收起
                            capsuleRect = androidx.compose.ui.geometry.Rect(
                                left = b.left - 8f, top = b.top - 8f,
                                right = b.right + 8f, bottom = b.bottom + 8f
                            )
                        }
                )
            }
            // 笔记页【左缘】右滑 → 设置抽屉（路河 09-10 拍板：从左边呼出，别跟右边记账页手势冲突）
            // 09-10 二改：detectHorizontalDragGestures 会被外层 HorizontalPager 抢走（同为横向拖拽，
            // Pager 层级更深且是滚动容器，必输）→ 改为自行解析指针事件：
            // 只认「首段位移明确横向向右」的滑动，一旦判定纵向就立刻放行给列表，不再 consume。
            // 09-13 夜修「滑动切页不行」：这条覆盖层是 hit-target，压在 Pager 上方——从它起手的
            // 手势（右滑/左滑/竖滑）被 hit-test 全部判给它，Pager 根本收不到，等于屏幕左缘一条
            // 36dp 宽的手势黑洞。收窄到 16dp 只守住系统返回手势的边缘地带；从条上左滑离手时
            // 手动翻到下一页（事件无法转发给 Pager，只能代为执行），右滑逻辑不变。
            if (currentRoute == "home" && pagerState.currentPage == 0 && !showSettings) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(16.dp)
                        .zIndex(5f)
                        // 09-11 修「左缘右滑无反应」：Android 10+ 把「从屏幕左边缘往右滑」当
                        // 系统返回手势拦截，App 内手势写得再好也收不到事件。必须主动向系统
                        // 申请豁免（exclusion），该区域才归 App。（系统限制：排除区最高约 200dp，
                        // 系统会自动截取，够用）
                        .systemGestureExclusion()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial
                                )
                                var totalDx = 0f
                                var totalDy = 0f
                                var decided = false   // 方向是否已判定
                                var isRight = false   // 是否判定为「右滑呼出设置」
                                while (true) {
                                    // Initial pass：抢在子级（HorizontalPager）之前拿到事件，否则必输
                                    val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) {
                                        // 抬手：向右达到阈值呼出抽屉；向左达到阈值翻下一页
                                        if (isRight && totalDx > 40f) {
                                            showSettings = true
                                        } else if (!isRight && totalDx < -60f &&
                                            pagerState.currentPage < 4
                                        ) {
                                            scope.launch {
                                                pagerState.animateScrollToPage(
                                                    pagerState.currentPage + 1
                                                )
                                            }
                                        }
                                        break
                                    }
                                    totalDx += ch.positionChange().x
                                    totalDy += ch.positionChange().y
                                    if (!decided) {
                                        val adx = kotlin.math.abs(totalDx)
                                        val ady = kotlin.math.abs(totalDy)
                                        if (adx > 12f || ady > 12f) {
                                            decided = true
                                            // 横向且向右 → 接管；否则彻底放行（不 consume，交还给 Pager/列表）
                                            isRight = adx > ady && totalDx > 0f
                                        }
                                    }
                                    if (decided && isRight) {
                                        ch.consume()
                                    } else if (decided) {
                                        break
                                    }
                                }
                            }
                        }
                )
            }
        }
    }

    // 设置抽屉（左侧滑入，不占满全屏——路河 09-10 拍板）
    if (showSettings) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color(0x66000000))
                    .clickable { showSettings = false }
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(0.88f)
            ) {
                SettingsScreen(
                    vm = vm,
                    onBack = { showSettings = false },
                    onAsk = { showSettings = false; nav.navigate("ask") },
                    onAskKey = { showSettings = false; nav.navigate("askkey") }
                )
            }
        }
    }
}
