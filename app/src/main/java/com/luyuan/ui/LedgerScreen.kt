package com.luyuan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luyuan.data.Expense
import com.luyuan.data.PendingExpense
import com.luyuan.data.PendingExpenseStore
import com.luyuan.data.v2Json
import com.luyuan.platform.PaymentNotificationListener
import com.luyuan.platform.StorageLocator
import com.luyuan.ui.CountUpText
import com.luyuan.ui.LuyuanMotion
import com.luyuan.ui.rememberPressScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton

/**
 * 记账页（手机 UI 方案 A · B1 只读）：月份 chips + 深绿统计卡 + 分类占比 + 按天流水。
 * 数据 = SYNC_FORMAT v2 kind:"expense"（PC 端视觉管线/手动补记产出，Syncthing 同步）。
 * 动效（count-up / 占比条展开）按合同归 B2，本版静态呈现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(vm: LuyuanViewModel, onAsk: () -> Unit, onTrash: () -> Unit) {
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    var month by remember { mutableStateOf(YearMonth.now()) }
    // ① 通知自动记账 · 待确认（ledger-extras.html）：监听到的支付先落这里，用户点 ✓ 才真入账
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf(PendingExpenseStore.list(ctx)) }
    val listenerOn = remember { PaymentNotificationListener.enabled(ctx) }

    fun confirmPending(p: PendingExpense, category: String) {
        try {
            val now = OffsetDateTime.now().toString()
            val e = Expense(
                kind = "expense", id = p.id, amount = p.amount, item = p.merchant,
                category = category, spent_at = now, source = "notification",
                device = "phone", created_at = now, updated_at = now
            )
            java.io.File(StorageLocator.getRoot(ctx), "expense_${p.id}.json")
                .writeText(v2Json.encodeToString(Expense.serializer(), e))
        } catch (_: Exception) {
        }
        PendingExpenseStore.remove(ctx, p.id)
        pending = PendingExpenseStore.list(ctx)
        vm.refreshV2()
    }

    fun discardPending(p: PendingExpense) {
        PendingExpenseStore.remove(ctx, p.id)
        pending = PendingExpenseStore.list(ctx)
    }

    val months = remember(expenses) {
        val s = LinkedHashSet<YearMonth>()
        for (e in expenses) expenseMonth(e)?.let { s.add(it) }
        s.add(YearMonth.now())
        s.sortedDescending().take(4)
    }
    val monthExpenses = remember(expenses, month) {
        expenses.filter { expenseMonth(it) == month }
    }
    val total = remember(monthExpenses) { monthExpenses.sumOf { it.amount } }
    val byCat = remember(monthExpenses) {
        monthExpenses.groupBy { it.category.ifBlank { "其他" } }
            .mapValues { v -> v.value.sumOf { it.amount } }
            .toList()
            .sortedByDescending { it.second }
    }
    val byDay = remember(monthExpenses) {
        monthExpenses.groupBy { expenseDate(it) }
            .toSortedMap(compareByDescending<LocalDate?> { it ?: LocalDate.MIN })
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                title = {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("记账", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${month.monthValue}月 · ${monthExpenses.size}笔",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // 09-15 路河：拒 emoji 图标 → Material（问路远=机器人 / 回收站=垃圾桶）
                    IconButton(onClick = onAsk) { Icon(Icons.Default.SmartToy, contentDescription = "问路远") }
                    IconButton(onClick = onTrash) { Icon(Icons.Default.Delete, contentDescription = "回收站") }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 14.dp)
        ) {
            // ① 通知自动记账 · 待确认区（样式对齐 ledger-extras.html：琥珀左边条 + ✓入账/✕丢弃）
            if (!listenerOn) {
                item {
                    Text(
                        "想让支付通知自动进「待确认」？点这里去系统设置给路远开「通知使用权」。",
                        fontSize = 11.sp,
                        color = LuyuanColors.Ink3,
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                runCatching {
                                    ctx.startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    )
                                }
                            }
                            .padding(vertical = 4.dp)
                    )
                }
            }
            items(pending, key = { it.id }) { p ->
                PendingCard(
                    p = p,
                    onConfirm = { cat -> confirmPending(p, cat) },
                    onDiscard = { discardPending(p) }
                )
            }

            if (expenses.isEmpty() && pending.isEmpty()) {
                item { LedgerEmpty() }
            }

            // 月份 chips + 同步小字
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (m in months) {
                            val selected = m == month
                            val chipSrc = remember { MutableInteractionSource() }
                            val chipScale = rememberPressScale(chipSrc)
                            Text(
                                "${m.monthValue}月",
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Color.White else LuyuanColors.Ink2,
                                modifier = Modifier
                                    .clickable(interactionSource = chipSrc, indication = null, onClick = { month = m })
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surface,
                                        RoundedCornerShape(999.dp)
                                    )
                                    .padding(horizontal = 16.dp, vertical = 7.dp)
                                    .graphicsLayer { scaleX = chipScale; scaleY = chipScale }
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "电脑端自动同步✓",
                        fontSize = 10.5.sp,
                        color = LuyuanColors.Ink3
                    )
                }
            }

            // 深绿统计卡
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(LuyuanColors.GradGreenStart, LuyuanColors.GradGreenEnd)
                            ),
                            RoundedCornerShape(22.dp)
                        )
                        .padding(20.dp)
                ) {
                    Text("本月支出", fontSize = 12.sp, color = Color(0xFFCFE0D6))
                    CountUpText(
                        target = total,
                        format = { "¥" + fmtMoney(it) },
                        style = androidx.compose.ui.text.TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        "${month.monthValue}月共 ${monthExpenses.size} 笔",
                        fontSize = 12.sp,
                        color = Color(0xFFCFE0D6)
                    )
                }
            }

            // 分类占比（有数据才显示）
            if (byCat.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Text("分类占比", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(10.dp))
                        for ((cat, sum) in byCat.take(5)) {
                            val frac = if (total > 0) (sum / total).toFloat() else 0f
                            val fracAnim by animateFloatAsState(
                                targetValue = frac,
                                animationSpec = tween(LuyuanMotion.BarExpand, easing = LuyuanMotion.Flow)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Text(cat, fontSize = 12.sp, color = LuyuanColors.Ink2, modifier = Modifier.width(36.dp))
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(6.dp)
                                        .background(LuyuanColors.WarmBg, RoundedCornerShape(999.dp))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(fracAnim.coerceIn(0.02f, 1f))
                                            .height(6.dp)
                                            .background(
                                                LuyuanColors.categoryColor(cat),
                                                RoundedCornerShape(999.dp)
                                            )
                                    )
                                }
                                Text(
                                    "${(frac * 100).toInt()}%",
                                    fontSize = 11.sp,
                                    color = LuyuanColors.Ink3,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                    modifier = Modifier.width(38.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 按天分组流水
            for ((day, list) in byDay) {
                item(key = "hdr_" + (day?.toString() ?: "none")) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            dayHeaderLabel(day),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("${list.size}笔", fontSize = 11.sp, color = LuyuanColors.Ink3)
                    }
                }
                items(list, key = { it.id }) { e ->
                    ExpenseRow(e)
                }
            }
        }
    }
}

@Composable
private fun ExpenseRow(e: Expense) {
    val cat = e.category.ifBlank { "其他" }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .background(LuyuanColors.categoryBg(cat), RoundedCornerShape(10.dp))
            ) {
                Icon(catIcon(cat), contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    e.item.ifBlank { "（无名目）" },
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Text(
                    buildString {
                        append(cat)
                        if (e.source.isNotBlank()) {
                            append(" · ")
                            append(if (e.source == "screenshot") "截图识别" else "手动")
                        }
                    },
                    fontSize = 11.sp,
                    color = LuyuanColors.Ink3
                )
            }
            Text(
                "-" + fmtMoney(e.amount),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun LedgerEmpty() {
    EmptyState(
        icon = EmptyIconLedger,
        title = "还没有账单",
        subtitle = "电脑端截图识别 / 手动补记的账，会自动同步到这里"
    )
}

// ---------- ① 通知自动记账 · 待确认卡（ledger-extras.html） ----------

private val PEND_CATS = listOf("餐饮", "日用", "学习", "娱乐", "其他")

@Composable
private fun PendingCard(p: PendingExpense, onConfirm: (String) -> Unit, onDiscard: () -> Unit) {
    var catIdx by remember(p.id) {
        mutableStateOf(PEND_CATS.indexOf(p.category).takeIf { it >= 0 } ?: 0)
    }
    val srcName = if (p.pkg == "com.tencent.mm") "微信支付" else "支付宝"
    val time = runCatching {
        OffsetDateTime.parse(p.created_at).toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrDefault("")

    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, LuyuanColors.AmberBg, RoundedCornerShape(12.dp))
    ) {
        // 头：来源行（琥珀底）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .background(
                    LuyuanColors.AmberBg,
                    RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Box(
                Modifier.size(6.dp).background(LuyuanColors.Amber, CircleShape)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "来自通知 · $srcName · $time",
                fontSize = 9.5.sp,
                color = LuyuanColors.Amber
            )
        }
        // 中：金额 + 商户 + 类别（点一下循环切换）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                "¥" + fmtMoney(p.amount),
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = LuyuanColors.Ink1
            )
            Spacer(Modifier.width(8.dp))
            Text(
                p.merchant,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = LuyuanColors.Ink1,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                PEND_CATS[catIdx],
                fontSize = 10.5.sp,
                color = LuyuanColors.Ink2,
                modifier = Modifier
                    .clickable { catIdx = (catIdx + 1) % PEND_CATS.size }
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        // 底：✓入账 / ✕不是支出
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
        ) {
            Text(
                "✓ 入账",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f)
                    .clickable { onConfirm(PEND_CATS[catIdx]) }
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp))
                    .padding(vertical = 6.dp)
            )
            Text(
                "✕ 不是支出",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = LuyuanColors.Ink3,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f)
                    .clickable { onDiscard() }
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
                    .padding(vertical = 6.dp)
            )
        }
    }
}

// ---------- 纯函数（无状态，供本页与测试复用） ----------

/** ISO8601 → LocalDate，解析失败返回 null */
internal fun expenseDate(e: Expense): LocalDate? {
    val s = e.spent_at.ifBlank { e.created_at }
    if (s.isBlank()) return null
    return try {
        OffsetDateTime.parse(s).toLocalDate()
    } catch (_: Exception) {
        try {
            LocalDateTime.parse(s).toLocalDate()
        } catch (_: Exception) {
            null
        }
    }
}

internal fun expenseMonth(e: Expense): YearMonth? = expenseDate(e)?.let { YearMonth.from(it) }

/** 日期头文案：今天·9月8日 周一 / 昨天·… / 9月5日 周五（同年内） */
internal fun dayHeaderLabel(day: LocalDate?): String {
    if (day == null) return "更早"
    val today = LocalDate.now()
    val md = "${day.monthValue}月${day.dayOfMonth}日"
    val wk = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
    return when (day) {
        today -> "今天 · $md $wk"
        today.minusDays(1) -> "昨天 · $md $wk"
        else -> if (day.year == today.year) "$md $wk" else "${day.year}.$md"
    }
}

/** 金额：去尾零 + 千分位（1284.5 → "1,284.5"，12.0 → "12"） */
internal fun fmtMoney(v: Double): String {
    val rounded = Math.round(v * 10) / 10.0
    return if (rounded == Math.floor(rounded)) {
        String.format(Locale.CHINA, "%,.0f", rounded)
    } else {
        String.format(Locale.CHINA, "%,.1f", rounded)
    }
}

/** 分类图标（09-15 路河：拒 emoji，按 v2 语义图标口径换 Material Icons） */
private fun catIcon(c: String): androidx.compose.ui.graphics.vector.ImageVector = when (c) {
    "餐饮" -> Icons.Default.Restaurant
    "学习" -> Icons.Default.MenuBook
    "日用" -> Icons.Default.CleaningServices
    "娱乐" -> Icons.Default.SportsEsports
    "交通" -> Icons.Default.DirectionsBus
    else -> Icons.Default.Category
}
