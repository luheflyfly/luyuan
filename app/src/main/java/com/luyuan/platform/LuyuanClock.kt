package com.luyuan.platform

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.luyuan.MainActivity
import com.luyuan.R
import com.luyuan.data.Course
import com.luyuan.data.TodoStore
import com.luyuan.data.V2EntityRepository
import java.util.Calendar

/**
 * 路远时钟（vc103）：三类系统级节拍，全部本地数据、零网络零 AI——
 * ①上课前提醒：当日/未来 7 天的课，开始前 10 分钟 Exact 闹钟，响后自动排下一节；
 * ②桌面小组件保活刷新：每 15 分钟一次（Receiver 内只在 07:00-21:59 真刷，夜间到点跳过省电）；
 * ③晨间简报：每天 07:10 通知「今日课表 + 3 天内截止作业」。
 * 课表/作业数据 = 同步目录 course_*.json / todo_*.json（PC 课务导出为准）。
 * 调度时机：App 打开（LuyuanViewModel.init）、BootReceiver、每次课程提醒响后自排。
 */
object LuyuanClock {
    const val ACTION_COURSE = "com.luyuan.COURSE_REMINDER"
    const val ACTION_WIDGET = "com.luyuan.WIDGET_KEEPALIVE"
    const val ACTION_BRIEF = "com.luyuan.MORNING_BRIEF"
    const val CHANNEL_COURSE = "luyuan_course"
    const val CHANNEL_BRIEF = "luyuan_brief"

    private const val LEAD_MIN = 10L            // 提前量：开始前 10 分钟
    private const val REQ_COURSE = 9101
    private const val REQ_WIDGET = 9102
    private const val REQ_BRIEF = 9103
    private const val WIDGET_PERIOD_MIN = 15L

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_COURSE, "上课提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "上课前 10 分钟提醒（含教室）"
                enableVibration(true)   // vc104：课堂实录视角——手机在口袋里，震动必须有
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BRIEF, "晨间简报", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "每天 07:10：今日课表与临近截止作业"
            }
        )
    }

    /** 统一重排三类闹钟（幂等：同 PendingIntent 覆盖）。App 打开/开机/提醒响后调用。 */
    fun rescheduleAll(context: Context) {
        ensureChannels(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // ① 下一节课提醒
        nextClass(context)?.let { (c, at) ->
            val i = Intent(context, ClockReceiver::class.java).setAction(ACTION_COURSE)
                .putExtra("name", c.name)
                .putExtra("place", coursePlace(c))
                .putExtra("start", c.start)
            if (setExact(am, at, pi(context, i, REQ_COURSE))) return@let
        }

        // ② Widget 保活（Receiver 内按时段过滤，夜间到点跳过）
        val wpi = pi(context, Intent(context, ClockReceiver::class.java).setAction(ACTION_WIDGET), REQ_WIDGET)
        if (canExact(am)) {
            am.setRepeating(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + WIDGET_PERIOD_MIN * 60_000L,
                WIDGET_PERIOD_MIN * 60_000L, wpi)
        } else {
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + WIDGET_PERIOD_MIN * 60_000L,
                WIDGET_PERIOD_MIN * 60_000L, wpi)
        }

        // ③ 晨间简报（下一个 07:10）
        val briefAt = nextDaily(7, 10)
        val bpi = pi(context, Intent(context, ClockReceiver::class.java).setAction(ACTION_BRIEF), REQ_BRIEF)
        setExact(am, briefAt, bpi)
    }

    /** 按设备能力选 Exact / Window（与 ReminderScheduler 同款双轨） */
    private fun setExact(am: AlarmManager, at: Long, pi: PendingIntent): Boolean {
        if (canExact(am)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60 * 1000L, pi)
        }
        return true
    }

    private fun pi(context: Context, i: Intent, req: Int): PendingIntent =
        PendingIntent.getBroadcast(context, req, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun canExact(am: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    private fun nextDaily(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /** 下一节要上的课（提醒时刻 = 开始-10 分钟；只排未来的，已开课的跳过） */
    private fun nextClass(context: Context): Pair<Course, Long>? {
        val courses = try { V2EntityRepository.listCourses(context) } catch (_: Exception) { emptyList<Course>() }
        if (courses.isEmpty()) return null
        val week = try { com.luyuan.domain.semesterWeekOf(java.time.LocalDate.now()) } catch (_: Exception) { 1 }
        val inWeek = courses.filter { weeksMatch(it.weeks, week) }
        if (inWeek.isEmpty()) return null
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now()
        for (delta in 0..7) {
            val date = today.plusDays(delta.toLong())
            val dayCourses = inWeek.filter { it.weekday == date.dayOfWeek.value && it.start.isNotBlank() }
                .sortedBy { slotToMin(it.start) }
            for (c in dayCourses) {
                val p = slotToMin(c.start)
                val at = date.atTime(p / 60, p % 60).atZone(zone).toInstant().toEpochMilli() -
                        LEAD_MIN * 60_000L
                if (at > System.currentTimeMillis()) return c to at
            }
        }
        return null
    }

    /** weeks "1-16" / "1,3,5" / "2-8,10-16"；空=每周都有（与学业页 courseInWeek 同口径） */
    private fun weeksMatch(spec: String, week: Int): Boolean {
        val w = spec.trim()
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

    private fun coursePlace(c: Course): String =
        listOf(c.place, c.teacher).filter { it.isNotBlank() }.joinToString(" · ")

    /** 晨间简报正文：今日课表 + 3 天内截止作业 */
    fun briefText(context: Context): String {
        val sb = StringBuilder()
        val courses = try { V2EntityRepository.listCourses(context) } catch (_: Exception) { emptyList<Course>() }
        val week = try { com.luyuan.domain.semesterWeekOf(java.time.LocalDate.now()) } catch (_: Exception) { 1 }
        val today = java.time.LocalDate.now()
        val todays = courses.filter { it.weekday == today.dayOfWeek.value && weeksMatch(it.weeks, week) }
            .sortedBy { slotToMin(it.start) }
        if (todays.isEmpty()) {
            sb.append("今天没有课，自习好日子")
        } else {
            sb.append("今天 ").append(todays.size).append(" 节课：")
            for (c in todays.take(4)) {
                sb.append("\n").append(c.start).append(" ").append(c.name)
                if (c.place.isNotBlank()) sb.append(" @").append(c.place)
            }
            if (todays.size > 4) sb.append("\n…等共 ").append(todays.size).append(" 节")
        }
        // vc105：今日校历事件（考试/评奖等，来自 PC 课务导出）
        val events = try {
            com.luyuan.data.KeiwuStore.events(context)?.items
                ?.filter { !it.deleted && it.date.take(10) == today.toString() }
                ?: emptyList()
        } catch (_: Exception) { emptyList() }
        for (e in events.take(3)) {
            sb.append("
今天：").append(e.name)
        }
        val dueSoon = try {
            TodoStore.list(context).filter { t ->
                !t.done && !t.deleted && t.when_text.trim().length >= 10 &&
                    t.when_text.trim().substring(0, 10) <= today.plusDays(3).toString()
            }.sortedBy { it.when_text }.take(2)
        } catch (_: Exception) { emptyList() }
        if (dueSoon.isNotEmpty()) {
            sb.append("\n\n3 天内截止 ").append(dueSoon.size).append(" 项：")
                .append(dueSoon.joinToString("；") { it.text.take(14) })
        }
        return sb.toString()
    }

    private fun slotToMin(s: String): Int {
        val p = s.split(":")
        return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }
}

/** 三类节拍的统一接收器：响铃/刷新/简报，完成后自动重排（课程提醒排下一节）。 */
class ClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            LuyuanClock.ACTION_COURSE -> {
                val name = intent.getStringExtra("name") ?: "课程"
                val place = intent.getStringExtra("place") ?: ""
                val start = intent.getStringExtra("start") ?: ""
                LuyuanClock.ensureChannels(context)
                val page = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("page", "course")
                }
                val ppi = PendingIntent.getActivity(
                    context, name.hashCode(), page,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val n = NotificationCompat.Builder(context, LuyuanClock.CHANNEL_COURSE)
                    .setSmallIcon(R.drawable.ic_stat_luyuan)
                    .setColor(0xFF224A3A.toInt())
                    .setContentTitle("$start 上课 · $name")
                    .setContentText(if (place.isNotBlank()) "10 分钟后 · @$place" else "10 分钟后上课")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(
                        if (place.isNotBlank()) "$name\n@$place" else name))
                    .setContentIntent(ppi)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setAutoCancel(true)
                    .build()
                try {
                    androidx.core.app.NotificationManagerCompat.from(context)
                        .notify(name.hashCode(), n)
                } catch (_: SecurityException) {
                    // 无通知权限：静默跳过
                }
                LuyuanClock.rescheduleAll(context)   // 排下一节
            }
            LuyuanClock.ACTION_WIDGET -> {
                val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (h in 7..21) {                    // 夜间到点跳过，省电
                    context.sendBroadcast(
                        Intent(context, TodayWidgetProvider::class.java).apply {
                            action = TodayWidgetProvider.ACTION_REFRESH
                        }
                    )
                }
            }
            LuyuanClock.ACTION_BRIEF -> {
                LuyuanClock.ensureChannels(context)
                val text = LuyuanClock.briefText(context)
                val page = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("page", "notes")
                }
                val ppi = PendingIntent.getActivity(
                    context, 9201, page,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val n = NotificationCompat.Builder(context, LuyuanClock.CHANNEL_BRIEF)
                    .setSmallIcon(R.drawable.ic_stat_luyuan)
                    .setColor(0xFF224A3A.toInt())
                    .setContentTitle("今日简报")
                    .setContentText(text.replace("\n", " ").take(60))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(ppi)
                    .setAutoCancel(true)
                    .build()
                try {
                    androidx.core.app.NotificationManagerCompat.from(context).notify(9201, n)
                } catch (_: SecurityException) {
                }
                LuyuanClock.rescheduleAll(context)   // 排明天 07:10
            }
        }
    }
}
