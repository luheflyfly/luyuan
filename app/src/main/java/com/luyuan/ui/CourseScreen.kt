package com.luyuan.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luyuan.data.Course
import com.luyuan.data.NoteRepository
import com.luyuan.data.V2EntityRepository
import com.luyuan.domain.Note
import com.luyuan.ui.CountUpText
import com.luyuan.ui.rememberPressScale
import kotlin.math.roundToInt
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * 课程页 · 木案网格版（B5，2026-09-13 按.ui-mobile/v2/course-muan.html 施工）：
 * 下一节深绿卡（旧版保留）→ 周次切换条（◀ 第N周 ▶ / 本周 / 共N周，按 weeks 过滤）
 * → 节次×星期网格（分类色条课块、今天列金高亮、点空格子=加课）→ 图例筛选 → 周负荷柱图 → 课程作业清单。
 * 数据 = SYNC_FORMAT v2 kind:"course"；作业 = 打了课程名标签的笔记（§10.1 归课约定，不新增实体）。
 */

private val CoursePalette = listOf(
    Color(0xFF4F8A73), // 深绿
    Color(0xFF6D28D9), // 紫
    Color(0xFFB45309), // 金
    Color(0xFFBE185D)  // 洋红
)
private const val GRID_CELL_W = 62
private const val GRID_TIME_W = 34
private const val GRID_CELL_H = 56

/** 点空格子加课的槽位（列=星期几，行=节次时间） */
private data class AddSlot(val weekday: Int, val start: String, val end: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseScreen(vm: LuyuanViewModel, onAsk: () -> Unit, onTrash: () -> Unit, onDetail: (String) -> Unit = {}) {
    val courses by vm.courses.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("luyuan_prefs", Context.MODE_PRIVATE) }
    val today = remember { LocalDate.now() }

    // 学期周次锚点：App 内保存过就用它（PC config.semester_start 的手机自治兜底=9/1 或 3/1）
    val start = remember(prefs) { semesterStart(today, prefs) }
    val curWeek = remember(start, today) { weekOf(start, today).coerceIn(1, 30) }
    var week by remember { mutableStateOf(curWeek) }
    var selCourse by remember { mutableStateOf<String?>(null) } // 图例筛选（单选，再点取消）
    var addSlot by remember { mutableStateOf<AddSlot?>(null) }
    var hwDialog by remember { mutableStateOf(false) }

    val next = remember(courses) { nextCourse(courses) }
    val weekCourses = remember(courses, week) { courses.filter { courseInWeek(it, week) } }
    val slots = remember(weekCourses) {
        weekCourses.map { it.start }.filter { it.isNotBlank() }.distinct().sorted().take(8)
    }
    val courseNames = remember(courses) {
        courses.map { it.name }.filter { it.isNotBlank() }.distinct().sorted().take(8)
    }
    fun colorOf(name: String): Color =
        CoursePalette[courseNames.indexOf(name).mod(CoursePalette.size)]

    val totalWeeks = remember(courses) {
        var mx = 0
        for (c in courses) for (p in c.weeks.split(',', '，', ';', '；')) {
            val seg = p.trim().split('-', '～', '~')
            val b = (seg.getOrNull(1) ?: seg.getOrNull(0))?.trim()?.toIntOrNull()
            if (b != null && b > mx) mx = b
        }
        if (mx > 0) mx else 20
    }

    // 作业 = 标签里带课程名的笔记（归课约定），最新在前
    val homework = remember(notes, courseNames) {
        if (courseNames.isEmpty()) emptyList()
        else notes.filter { n -> n.tags.any { t -> t.isNotBlank() && courseNames.contains(t) } }
            .sortedByDescending { it.updated_at.ifBlank { it.created_at } }
            .take(8)
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
                        Text("课程表", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "第 $week 周 · ${weekCourses.size}节",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    Text("⤓", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier
                        .clickable {
                            Toast.makeText(context, "在电脑端导入课表，同步后自动出现在这里", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 10.dp))
                    Text("🤖", fontSize = 18.sp, modifier = Modifier
                        .clickable { onAsk() }
                        .padding(horizontal = 10.dp))
                    Text("🗑", fontSize = 17.sp, modifier = Modifier
                        .clickable { onTrash() }
                        .padding(horizontal = 10.dp))
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
            if (courses.isEmpty()) {
                item {
                    EmptyState(
                        icon = EmptyIconCourse,
                        title = "还没有课程表",
                        subtitle = "电脑端导入课表截图后，会自动同步到这里"
                    )
                }
            }

            // 下一节深绿卡（旧版保留，功能不回退）
            if (next != null) {
                val (c, mins) = next
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.linearGradient(
                                    listOf(LuyuanColors.GradGreenStart, LuyuanColors.GradGreenEnd)
                                ),
                                RoundedCornerShape(22.dp)
                            )
                            .padding(18.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "⏰ 下一节 · " + c.start,
                                fontSize = 11.sp,
                                color = Color(0xFFCFE0D6),
                                modifier = Modifier
                                    .background(Color(0x33FFFFFF), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(c.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                coursePlaceLine(c),
                                fontSize = 12.sp,
                                color = Color(0xFFCFE0D6)
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CountUpText(
                                target = mins.toDouble(),
                                format = { it.roundToInt().toString() },
                                style = androidx.compose.ui.text.TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Text("分钟后上课", fontSize = 11.sp, color = Color(0xFFCFE0D6))
                        }
                    }
                }
            }

            // 周次切换条（木案 wkbar：◀ 第N周 ▶ / 本周 / 共N周）
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WeekArrow("◀") { if (week > 1) week -= 1 }
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "第 $week 周",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = LuyuanColors.Green700
                    )
                    Spacer(Modifier.width(7.dp))
                    WeekArrow("▶") { if (week < 30) week += 1 }
                    Spacer(Modifier.width(9.dp))
                    Text(
                        "本周",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (week == curWeek) LuyuanColors.Ink3 else LuyuanColors.Amber,
                        modifier = Modifier
                            .background(
                                if (week == curWeek) Color(0xFFF6F3EB) else LuyuanColors.AmberBg,
                                RoundedCornerShape(999.dp)
                            )
                            .clickable { week = curWeek }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Text("共 $totalWeeks 周", fontSize = 10.sp, color = LuyuanColors.Ink4)
                }
            }

            // 节次×星期网格（横向可滑到周六日；今天列金高亮；空格今天=＋加课）
            if (slots.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Column {
                            // 表头：空角 + 一~日
                            Row {
                                Box(
                                    Modifier.width(GRID_TIME_W.dp).height(26.dp)
                                        .background(MaterialTheme.colorScheme.background)
                                )
                                for (i in 1..7) {
                                    val isToday = i == today.dayOfWeek.value
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .width(GRID_CELL_W.dp)
                                            .height(26.dp)
                                            .background(
                                                if (isToday) LuyuanColors.AmberBg
                                                else MaterialTheme.colorScheme.surface
                                            )
                                    ) {
                                        Text(
                                            "周" + dayShort(i) + if (isToday) " ✓" else "",
                                            fontSize = 10.sp,
                                            fontWeight = if (isToday) FontWeight.ExtraBold else FontWeight.Bold,
                                            color = if (isToday) LuyuanColors.Amber else LuyuanColors.Ink3
                                        )
                                    }
                                }
                            }
                            // 节次行
                            for ((idx, s) in slots.withIndex()) {
                                Row {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .width(GRID_TIME_W.dp)
                                            .height(GRID_CELL_H.dp)
                                            .background(MaterialTheme.colorScheme.surface)
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                "${idx + 1}",
                                                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LuyuanColors.Ink2
                                            )
                                            Text(s, fontSize = 8.sp, color = LuyuanColors.Ink4)
                                        }
                                    }
                                    for (i in 1..7) {
                                        val isToday = i == today.dayOfWeek.value
                                        val cell = weekCourses.firstOrNull {
                                            it.weekday == i && it.start == s
                                        }
                                        val dimmed = selCourse != null && cell != null && cell.name != selCourse
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .width(GRID_CELL_W.dp)
                                                .height(GRID_CELL_H.dp)
                                                .border(
                                                    0.5.dp,
                                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                                )
                                                .background(
                                                    when {
                                                        isToday -> Color(0x1AB45309) // 金色淡染（今天列）
                                                        else -> MaterialTheme.colorScheme.background
                                                    }
                                                )
                                                .clickable(enabled = cell == null && isToday) {
                                                    val end = parseHm(s)?.plusMinutes(45)?.toString()?.take(5)
                                                    addSlot = AddSlot(i, s, end ?: s)
                                                }
                                        ) {
                                            if (cell != null) {
                                                val cc = colorOf(cell.name)
                                                Column(
                                                    Modifier
                                                        .fillMaxSize()
                                                        .padding(3.dp)
                                                        .background(
                                                            cc.copy(alpha = if (dimmed) 0.04f else 0.10f),
                                                            RoundedCornerShape(7.dp)
                                                        )
                                                        .padding(horizontal = 4.dp, vertical = 3.dp)
                                                ) {
                                                    Box(
                                                        Modifier
                                                            .width(14.dp)
                                                            .height(2.dp)
                                                            .background(
                                                                cc.copy(alpha = if (dimmed) 0.3f else 1f),
                                                                RoundedCornerShape(2.dp)
                                                            )
                                                    )
                                                    Spacer(Modifier.height(2.dp))
                                                    Text(
                                                        cell.name,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = if (dimmed) LuyuanColors.Ink4 else LuyuanColors.Ink1,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        lineHeight = 11.sp
                                                    )
                                                    Text(
                                                        "@" + coursePlaceLine(cell),
                                                        fontSize = 8.sp,
                                                        color = LuyuanColors.Ink4,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            } else if (isToday) {
                                                Text("＋", fontSize = 13.sp, color = LuyuanColors.Ink4)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 图例筛选（点课程名 = 只看这门课，再点取消）
            if (courseNames.isNotEmpty()) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 2.dp)
                    ) {
                        for (n in courseNames) {
                            val on = selCourse == n
                            val cc = colorOf(n)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .background(
                                        if (on) cc.copy(alpha = 0.10f) else Color.Transparent,
                                        RoundedCornerShape(999.dp)
                                    )
                                    .border(1.5.dp, cc, RoundedCornerShape(999.dp))
                                    .clickable { selCourse = if (on) null else n }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Box(
                                    Modifier.size(6.dp).background(cc, RoundedCornerShape(3.dp))
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(n, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = cc)
                            }
                        }
                    }
                }
            }

            // 本周课程负荷（各天门数，今天高亮）
            if (weekCourses.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            "本周课程负荷 · 各天门数",
                            fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LuyuanColors.Ink3
                        )
                        Spacer(Modifier.height(8.dp))
                        val counts = (1..7).map { d -> weekCourses.count { it.weekday == d } }
                        val mx = (counts.maxOrNull() ?: 1).coerceAtLeast(1)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.height(56.dp)
                        ) {
                            for (i in 1..7) {
                                val n = counts[i - 1]
                                val isToday = i == today.dayOfWeek.value
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Bottom,
                                    modifier = Modifier.weight(1f).fillMaxSize()
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(0.7f)
                                            .height((56f * n / mx).coerceAtLeast(if (n > 0) 6f else 2f).dp)
                                            .background(
                                                if (isToday) Brush.verticalGradient(
                                                    listOf(LuyuanColors.Green500, LuyuanColors.Green700)
                                                ) else SolidColor(LuyuanColors.Green100),
                                                RoundedCornerShape(4.dp)
                                            )
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        dayShort(i) + " $n",
                                        fontSize = 8.5.sp,
                                        color = if (isToday) LuyuanColors.Green700 else LuyuanColors.Ink4,
                                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 课程作业清单（打课程名标签的笔记；＋记作业）
            if (courseNames.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "课程作业（打课程标签的笔记）",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LuyuanColors.Ink2
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "＋ 记作业",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = LuyuanColors.Green700,
                            modifier = Modifier
                                .clickable { hwDialog = true }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                }
                if (homework.isEmpty()) {
                    item {
                        Text(
                            "还没有作业。记一条带课程标签的笔记（或点「＋ 记作业」），就会出现在这里。",
                            fontSize = 11.sp, color = LuyuanColors.Ink4
                        )
                    }
                }
                for (n in homework) {
                    item(key = "hw_${n.id}") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                .clickable { onDetail(n.id) }
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            val tag = n.tags.firstOrNull { courseNames.contains(it) } ?: ""
                            Text(
                                "[$tag]",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = colorOf(tag)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                n.text.removePrefix("[$tag] ").trim(),
                                fontSize = 12.sp, color = LuyuanColors.Ink1,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                (n.updated_at.ifBlank { n.created_at }).take(10),
                                fontSize = 9.sp, color = LuyuanColors.Ink4
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- 点空格子加课 ----------
    addSlot?.let { slot ->
        AddCourseDialog(
            slot = slot,
            week = week,
            onDismiss = { addSlot = null },
            onSave = { name, teacher, place, weeks ->
                try {
                    V2EntityRepository.saveCourse(
                        context, name, teacher, place, slot.weekday, slot.start, slot.end, weeks
                    )
                    vm.refresh()
                    addSlot = null
                    Toast.makeText(context, "已加课：周" + dayShort(slot.weekday) + " " + slot.start + " " + name, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "加课失败：${e.message ?: "写入同步目录没成功"}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    // ---------- 记作业 ----------
    if (hwDialog) {
        HomeworkDialog(
            courseNames = courseNames,
            onDismiss = { hwDialog = false },
            onSave = { course, text ->
                try {
                    NoteRepository.createManual(context, "[$course] $text", tags = listOf(course))
                    hwDialog = false
                    Toast.makeText(context, "作业已记录并打上「$course」标签", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "没存上：${e.message ?: "写入同步目录没成功"}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }
}

@Composable
private fun WeekArrow(label: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val scale = rememberPressScale(src)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(28.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .clickable(interactionSource = src, indication = null, onClick = onClick)
    ) {
        Text(label, fontSize = 11.sp, color = LuyuanColors.Ink2)
    }
}

@Composable
private fun AddCourseDialog(
    slot: AddSlot,
    week: Int,
    onDismiss: () -> Unit,
    onSave: (name: String, teacher: String, place: String, weeks: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var weeks by remember { mutableStateOf("1-16") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "加课 · 周" + dayShort(slot.weekday) + " " + slot.start + (if (slot.end != slot.start) "~" + slot.end else ""),
                fontWeight = FontWeight.Bold
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onSave(name.trim(), teacher.trim(), place.trim(), weeks.trim()) }
            ) { Text("存入课表") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("算了") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("课程名（必填）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    placeholder = { Text("老师（可不填）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = place,
                    onValueChange = { place = it },
                    placeholder = { Text("教室（可不填）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = weeks,
                    onValueChange = { weeks = it },
                    placeholder = { Text("周次，如 1-16") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (name.isNotBlank()) onSave(name.trim(), teacher.trim(), place.trim(), weeks.trim()) }
                    )
                )
                Text(
                    "存进同步目录后电脑端也会看到；记入第 $week 周视角的格子。",
                    fontSize = 10.sp, color = LuyuanColors.Ink4
                )
            }
        }
    )
}

@Composable
private fun HomeworkDialog(
    courseNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (course: String, text: String) -> Unit
) {
    var sel by remember { mutableStateOf(courseNames.firstOrNull() ?: "") }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记作业", fontWeight = FontWeight.Bold) },
        confirmButton = {
            TextButton(
                onClick = { if (sel.isNotBlank() && text.isNotBlank()) onSave(sel, text.trim()) }
            ) { Text("记下") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("算了") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    for (c in courseNames) {
                        val on = sel == c
                        Text(
                            c,
                            fontSize = 11.sp,
                            fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Normal,
                            color = if (on) MaterialTheme.colorScheme.primary else LuyuanColors.Ink3,
                            modifier = Modifier
                                .background(
                                    if (on) LuyuanColors.Green50 else MaterialTheme.colorScheme.surface,
                                    RoundedCornerShape(999.dp)
                                )
                                .border(
                                    1.dp,
                                    if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(999.dp)
                                )
                                .clickable { sel = c }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("作业内容，如：Unit 3 预习") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (sel.isNotBlank() && text.isNotBlank()) onSave(sel, text.trim()) }
                    )
                )
                Text("存成一条带课程标签的笔记，电脑端课程页同一套口径。", fontSize = 10.sp, color = LuyuanColors.Ink4)
            }
        }
    )
}

// ---------- 纯函数 ----------

internal fun parseHm(s: String): LocalTime? = try {
    LocalTime.parse(s)
} catch (_: Exception) {
    null
}

internal fun dayShort(i: Int): String =
    DayOfWeek.of(i.coerceIn(1, 7)).getDisplayName(TextStyle.SHORT, Locale.CHINA).removeSuffix("周")

/** 「下一节」槽位开始时间转当日分钟数（vc62 修 CI：本文件此前引用了不存在的 nextCourse） */
internal fun slotToMin(s: String): Int {
    val p = s.split(":")
    return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
}

/** 下一节课：今天尚未开始的最早一节 →（课，距开始分钟数）；否则顺延最近有课日（分钟数给 0） */
internal fun nextCourse(courses: List<Course>): Pair<Course, Int>? {
    if (courses.isEmpty()) return null
    val todayDow = LocalDate.now().dayOfWeek.value
    val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
    courses.filter { it.weekday == todayDow && slotToMin(it.start) >= nowMin }
        .minByOrNull { slotToMin(it.start) }
        ?.let { return it to (slotToMin(it.start) - nowMin) }
    for (delta in 1..7) {
        val wd = (todayDow + delta - 1) % 7 + 1
        courses.filter { it.weekday == wd }
            .minByOrNull { slotToMin(it.start) }
            ?.let { return it to 0 }
    }
    return null
}

/** 教室·老师（缺项自动略过） */
internal fun coursePlaceLine(c: Course): String = listOf(c.place, c.teacher)
    .filter { it.isNotBlank() }
    .joinToString(" · ")

/** 周次过滤：weeks 支持 "1-16" / "1,3,5" / "1-8,10-16" 混写；空 = 每周都有 */
internal fun courseInWeek(c: Course, week: Int): Boolean {
    val w = c.weeks.trim()
    if (w.isEmpty()) return true
    for (part in w.split(',', '，', ';', '；')) {
        val p = part.trim()
        if (p.isEmpty()) continue
        val seg = p.split('-', '～', '~')
        if (seg.size == 2) {
            val a = seg[0].trim().toIntOrNull()
            val b = seg[1].trim().toIntOrNull()
            if (a != null && b != null && week in a..b) return true
        } else {
            if (p.toIntOrNull() == week) return true
        }
    }
    return false
}

/** 学期起点：App 内存过的优先（设置里可后续接）；没存过自动兜底——8 月起算今年 9/1，否则今年 3/1 */
internal fun semesterStart(today: LocalDate, prefs: android.content.SharedPreferences): LocalDate {
    val saved = prefs.getString("semester_start", null)
    if (saved != null) {
        try {
            return LocalDate.parse(saved)
        } catch (_: Exception) {
        }
    }
    return if (today.monthValue >= 8) LocalDate.of(today.year, 9, 1)
    else LocalDate.of(today.year, 3, 1)
}

/** 学期第几周（锚点所在周=第 1 周） */
internal fun weekOf(start: LocalDate, today: LocalDate): Int =
    ((today.toEpochDay() - start.toEpochDay()) / 7).toInt() + 1
