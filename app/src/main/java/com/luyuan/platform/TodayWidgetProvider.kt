package com.luyuan.platform

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import android.view.View
import com.luyuan.MainActivity
import com.luyuan.R
import com.luyuan.data.ContactRepository
import com.luyuan.data.Course
import com.luyuan.data.NoteRepository
import com.luyuan.data.V2EntityRepository
import com.luyuan.domain.Note
import java.util.Calendar
import java.util.Locale

/**
 * 桌面小部件「路远·今日卡」：下一节课 / 今天待办 / 本月支出一眼看全，
 * 4×3 时多显示「最近 3 条」；底部记一笔（桌面速记）/ 说一句（进录音）。
 * 静态部件：updatePeriodMillis=30 分钟（系统兜底自愈），另在添加、重启、调尺寸、或 App 发 WIDGET_REFRESH 时重算。
 * 深链：课程块→page=course，待办→page=notes，支出→page=ledger，最近条→detail/{id}。
 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // 一次性清理：旧版把 widget 日志写进了同步根目录（引发刷新风暴），搬缓存后清掉遗留，
        // 免得 Syncthing 继续来回同步这两个文件。删的只是本 App 自己生成的调试日志，安全可逆（重装即重现）。
        try {
            for (n in listOf("widget_log.txt", "widget_err.txt")) {
                val legacy = java.io.File(StorageLocator.getRoot(context), n)
                if (legacy.exists()) legacy.delete()
            }
        } catch (_: Throwable) {
        }
        logWidget(context, "onUpdate ids=${ids.size} (首次加桌/系统周期刷新都会走这里)")
        for (id in ids) {
            // 先推极简卡：vivo/OriginOS 加桌瞬间只画系统占位圈，首帧不到就一直停在圈上
            pushInitial(context, manager, id)
            renderSafe(context, manager, id)
        }
    }

    /** 推一版「只有两行字」的兜底卡，保证任何 ROM 上都不剩空白/圆圈 */
    private fun pushInitial(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        try {
            manager.updateAppWidget(
                appWidgetId,
                RemoteViews(context.packageName, R.layout.widget_today_initial)
            )
        } catch (e: Throwable) {
            logWidget(context, "pushInitial 失败: $e")
        }
    }

    /** 拖动调整组件大小（4×2 ↔ 4×3）时重算「最近 3 条」显隐 */
    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle
    ) {
        logWidget(context, "onAppWidgetOptionsChanged id=$appWidgetId")
        renderSafe(context, manager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            logWidget(context, "onReceive ACTION_REFRESH")
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
            for (id in ids) renderSafe(context, mgr, id)
        }
    }

    /**
     * 安全渲染入口：widget 进程由系统直接拉起（不经过 MainActivity，CrashLogger 未安装），
     * 任何未捕获异常都会让桌面显示空白且不留日志。这里全量兜底——
     * 崩了也渲染「极简错误卡」，并把异常栈写进共享目录 widget_err.txt（随 Syncthing 回传电脑）。
     */
    private fun renderSafe(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        try {
            render(context, manager, appWidgetId)
        } catch (e: Throwable) {
            logWidgetError(context, e)
            try {
                manager.updateAppWidget(
                    appWidgetId,
                    RemoteViews(context.packageName, R.layout.widget_today_initial)
                )
            } catch (_: Throwable) {
                try {
                    manager.updateAppWidget(
                        appWidgetId,
                        RemoteViews(context.packageName, R.layout.widget_error)
                    )
                } catch (_: Throwable) {
                }
            }
        }
    }

    /**
     * 心跳/诊断日志。🔴 09-14 晨修「非常卡」：日志写应用缓存目录，**绝不写同步目录**——
     * 旧版写同步根目录的 widget_log.txt，被本 App 的文件监听当成笔记变更 → 触发全量刷新 →
     * 组件又写日志 → 永动刷新风暴（外加 Syncthing 来回同步 100KB 文件），整机卡顿的根因。
     * 诊断价值不变：adb / 文件管理器仍可从 cacheDir 取（路河真机不看这个，crash 走 CrashLogger）。
     */
    private fun logWidget(context: Context, msg: String) {
        try {
            val f = java.io.File(context.cacheDir, "widget_log.txt")
            val ts = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
                .format(java.util.Date())
            val prev = if (f.exists() && f.length() < 100_000) f.readText() else ""
            f.writeText((prev + "==== " + ts + " " + msg + "\n").takeLast(100_000))
        } catch (_: Throwable) {
        }
    }

    /** 异常栈落盘到应用缓存目录（同 logWidget：不进同步目录） */
    private fun logWidgetError(context: Context, e: Throwable) {
        try {
            val f = java.io.File(context.cacheDir, "widget_err.txt")
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            val prev = if (f.exists() && f.length() < 100_000) f.readText() else ""
            f.writeText(
                (prev + "==== " + java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
                    .format(java.util.Date()) + "\n" + sw + "\n").takeLast(100_000)
            )
        } catch (_: Throwable) {
        }
    }

    private fun render(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        // ⚠️ 2026-09-10 v2：向手机 UI 视觉稿 ui-mobile/widget/index.html W1 对齐
        //    （v1 扁平两行 → v1.19.3 扁平全量 → 本版 v2 按视觉稿重建：叶子 logo / 左侧三行+右侧大号倒计时 /
        //     两个带圆底图标的统计卡 + 竖分隔线 / 按钮内嵌图标）
        //    vivo 红线照守：只用 LinearLayout/TextView/ImageView、嵌套≤3、无裸 View、属性基础集
        val views = RemoteViews(context.packageName, R.layout.widget_today_v2)

        // 数据逐项防御：任一仓库异常只丢该项数据，不拖垮整卡渲染
        val courses = try { V2EntityRepository.listCourses(context) } catch (_: Throwable) { emptyList() }
        val contacts = try { ContactRepository.listContacts(context) } catch (_: Throwable) { emptyList() }
        val notes = try { NoteRepository.listNotes(context) } catch (_: Throwable) { emptyList() }
        val expenses = try { V2EntityRepository.listExpenses(context) } catch (_: Throwable) { emptyList() }

        // ---- 1 标题行：日期 + 开学第 N 周（锚点=2026-09-14 新生第一周周一，校历 csu_luyuan_data.json，prefers 可覆盖见 domain/Semester.kt） ----
        views.setTextViewText(
            R.id.widget_date,
            try {
                java.text.SimpleDateFormat("M月d日 E", Locale.CHINA).format(java.util.Date()) +
                    " · 第" + com.luyuan.domain.semesterWeekOf(java.time.LocalDate.now()) + "周"
            } catch (_: Exception) { "" }
        )

        // ---- 2 课程块：左三行 + 右大号倒计时（视觉稿 .w-today .course） ----
        val next = nextCourse(courses)
        if (next != null) {
            val isToday = next.weekday == todayLuyuan()
            val mins = minutesUntil(next)
            views.setTextViewText(
                R.id.widget_course_hint,
                "下一节" + if (next.start.isNotBlank()) " · ${next.start}" else ""
            )
            views.setTextViewText(R.id.widget_course_name, next.name.ifBlank { "课程" })
            views.setTextViewText(
                R.id.widget_course_place,
                listOfNotNull(
                    next.place.takeIf { it.isNotBlank() },
                    next.teacher.takeIf { it.isNotBlank() }
                ).joinToString(" · ").ifBlank { " " }
            )
            if (isToday && mins >= 0) {
                views.setTextViewText(R.id.widget_course_countdown, "${mins}′")
                views.setTextViewText(R.id.widget_course_countdown_label, "后上课")
            } else {
                views.setTextViewText(R.id.widget_course_countdown, weekdayName(next.weekday))
                views.setTextViewText(R.id.widget_course_countdown_label, "有课")
            }
        } else {
            // 无课态（视觉稿 W1：「今天没课 🌿 自习好日子」灰绿底）——换 muted 底并把本块文字换深绿系。
            // RemoteViews 每次刷新按 XML 重新 inflate，有课时无需回滚深绿样式
            views.setInt(R.id.widget_course, "setBackgroundResource", R.drawable.widget_course_bg_muted)
            views.setTextColor(R.id.widget_course_hint, 0xFF4F8A73.toInt())
            views.setTextColor(R.id.widget_course_name, 0xFF1A3329.toInt())
            views.setTextColor(R.id.widget_course_place, 0xFF4A5A52.toInt())
            views.setTextColor(R.id.widget_course_countdown, 0xFF4F8A73.toInt())
            views.setTextColor(R.id.widget_course_countdown_label, 0xFF4F8A73.toInt())
            views.setTextViewText(R.id.widget_course_hint, "课程")
            views.setTextViewText(R.id.widget_course_name, "今天没课 · 自习好日子")
            views.setTextViewText(
                R.id.widget_course_place,
                if (courses.isEmpty()) "去课程页添加课表" else "好好休息"
            )
            views.setTextViewText(R.id.widget_course_countdown, "－")
            views.setTextViewText(R.id.widget_course_countdown_label, "自习")
        }

        // ---- 3 统计行：今天待办 / 本月支出（视觉稿两个带圆底图标的 stat） ----
        val undone = contacts.sumOf { c -> c.undoneTodos.size }
        val overdue = contacts.sumOf { c -> c.todos.count { t -> !t.done && isOverdue(t.remind_at) } }
        // 视觉稿 W1：过期数是红字 em（mild 焦虑设计）。单 TextView 走 Spannable 分色，
        // 个别 ROM 不认 span 时自动退化成纯文本，无损
        val todoVal: CharSequence = if (overdue > 0) {
            val plain = "${undone} 件 ${overdue} 件过期"
            val sp = android.text.SpannableStringBuilder(plain)
            sp.setSpan(
                android.text.style.ForegroundColorSpan(0xFFDC2626.toInt()),
                "${undone} 件 ".length, plain.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sp
        } else {
            "${undone} 件"
        }
        views.setTextViewText(R.id.widget_todo_val, todoVal)
        val monthSum = expenses
            .filter { isThisMonth(it.spent_at.ifBlank { it.created_at }) }
            .sumOf { it.amount }
        views.setTextViewText(
            R.id.widget_expense_val,
            "¥" + String.format(Locale.US, "%,.1f", monthSum)
        )

        // ---- 4 最近 3 条（v2 布局：三个独立 TextView，按卡片高度显隐整段） ----
        val showRecent = isTallEnough(context, manager, appWidgetId)
        views.setViewVisibility(R.id.widget_recent, if (showRecent) View.VISIBLE else View.GONE)
        if (showRecent) {
            val recent = try {
                notes.filter { !it.deleted }
                    .sortedByDescending { it.updated_at.ifBlank { it.created_at } }
                    .take(3)
            } catch (_: Throwable) { emptyList() }
            val ids = intArrayOf(
                R.id.widget_recent_tx_0, R.id.widget_recent_tx_1, R.id.widget_recent_tx_2
            )
            for (i in 0..2) {
                val n = recent.getOrNull(i)
                views.setTextViewText(
                    ids[i],
                    if (n == null) {
                        if (i == 0) "· 还没有笔记 · 点下面「说一句」" else ""
                    } else {
                        val t = n.text.replace("\n", " ").trim()
                        "· " + (if (t.length > 22) t.take(22) + "…" else t)
                    }
                )
            }
        }

        // ---- 5 点击（视觉稿点按分区：logo→笔记 课块→课程 待办→笔记 支出→账本 日期→日记 双键→速记/录音） ----
        views.setOnClickPendingIntent(R.id.widget_logo, pagePi(context, "notes", 4100))
        views.setOnClickPendingIntent(R.id.widget_logo_ic, pagePi(context, "notes", 4100))
        views.setOnClickPendingIntent(R.id.widget_course, pagePi(context, "course", 4101))
        views.setOnClickPendingIntent(R.id.widget_course_name, pagePi(context, "course", 4101))
        views.setOnClickPendingIntent(R.id.widget_course_place, pagePi(context, "course", 4101))
        views.setOnClickPendingIntent(R.id.widget_stats, pagePi(context, "notes", 4102))
        views.setOnClickPendingIntent(R.id.widget_todo_val, pagePi(context, "notes", 4102))
        views.setOnClickPendingIntent(R.id.widget_expense_val, pagePi(context, "ledger", 4103))
        views.setOnClickPendingIntent(R.id.widget_date, pagePi(context, "journal", 4104))
        views.setOnClickPendingIntent(R.id.widget_recent_tx_0, pagePi(context, "notes", 4110))
        views.setOnClickPendingIntent(R.id.widget_recent_tx_1, pagePi(context, "notes", 4111))
        views.setOnClickPendingIntent(R.id.widget_recent_tx_2, pagePi(context, "notes", 4112))
        views.setOnClickPendingIntent(R.id.widget_note, quickNotePi(context))
        views.setOnClickPendingIntent(R.id.widget_record, recordPi(context))

        logWidget(
            context,
            "render ok(v2) id=$appWidgetId 课=${courses.size} 笔记=${notes.size} " +
                "联系人=${contacts.size} 支出=${expenses.size} 最近段=$showRecent"
        )
        manager.updateAppWidget(appWidgetId, views)
    }

    /** 卡片高度是否够放「最近 3 条」：4×2 收起、4×3 展开 */
    private fun isTallEnough(context: Context, manager: AppWidgetManager, appWidgetId: Int): Boolean {
        return try {
            val opt = manager.getAppWidgetOptions(appWidgetId)
            val hDp = opt.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            hDp >= RECENT_HEIGHT_THRESHOLD
        } catch (_: Throwable) {
            false
        }
    }

    // ---------- 数据计算 ----------

    private fun todayLuyuan(): Int {
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SUNDAY) 7 else dow - 1
    }

    private fun nextCourse(courses: List<Course>): Course? {
        if (courses.isEmpty()) return null
        val today = todayLuyuan()
        val nowMin = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) * 60 +
                Calendar.getInstance().get(Calendar.MINUTE)
        // 今天尚未开始的课里最早的一节
        courses.filter { it.weekday == today && toMin(it.start) >= nowMin }
            .minByOrNull { toMin(it.start) }?.let { return it }
        // 往后顺延到最近有课的那天
        for (delta in 1..7) {
            val wd = (today + delta - 1) % 7 + 1
            courses.filter { it.weekday == wd }.minByOrNull { toMin(it.start) }?.let { return it }
        }
        return null
    }

    private fun toMin(s: String): Int {
        val p = s.split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: 0
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        return h * 60 + m
    }

    private fun minutesUntil(c: Course): Int {
        val nowMin = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) * 60 +
                Calendar.getInstance().get(Calendar.MINUTE)
        return toMin(c.start) - nowMin
    }

    private fun weekdayName(wd: Int): String =
        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").getOrElse(wd - 1) { "" }

    private fun isOverdue(s: String?): Boolean {
        if (s.isNullOrBlank()) return false
        return try {
            java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() < System.currentTimeMillis()
        } catch (_: Exception) {
            false
        }
    }

    private fun isThisMonth(s: String): Boolean {
        if (s.length < 7) return false
        val cal = Calendar.getInstance()
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        return s.startsWith(String.format(Locale.US, "%04d-%02d", y, m))
    }

    // ---------- PendingIntent ----------

    private fun pagePi(context: Context, page: String, req: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java).putExtra("page", page)
        return PendingIntent.getActivity(
            context, req, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun quickNotePi(context: Context): PendingIntent {
        val i = Intent(context, FloatingQuickNoteService::class.java)
        return PendingIntent.getService(
            context, 4201, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun recordPi(context: Context): PendingIntent {
        val i = Intent(context, MainActivity::class.java).putExtra("auto", "record")
        return PendingIntent.getActivity(
            context, 4202, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_REFRESH = "com.luyuan.WIDGET_REFRESH"

        /** 「最近 3 条」的显示门槛：minHeight ≥ 该值（dp）才展开。4×3 通常 ~180dp，4×2 ~110dp */
        private const val RECENT_HEIGHT_THRESHOLD = 150
    }
}
