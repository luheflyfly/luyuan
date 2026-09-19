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
import com.luyuan.data.TodoStore
import com.luyuan.data.V2EntityRepository
import java.util.Calendar

/**
 * 路远时钟（vc106 更新）：两类系统级节拍，全部本地数据、零网络零 AI——
 * ①桌面小组件保活刷新：每 15 分钟一次（Receiver 内只在 07:00-21:59 真刷，夜间到点跳过省电）；
 * ②晨间简报：每天 07:10 通知「今日课表 + 今日校历事件 + 3 天内截止作业」。
 * 【vc106 移除上课前提醒】路河 09-19：上课前提醒完全多余——人已经在教室坐着了。
 * 课表/作业数据 = 同步目录 course_*.json / todo_*.json（PC 课务导出为准）。
 * 调度时机：App 打开（LuyuanViewModel.init）、BootReceiver。
 */
object LuyuanClock {
    const val ACTION_WIDGET = "com.luyuan.WIDGET_KEEPALIVE"
    const val ACTION_BRIEF = "com.luyuan.MORNING_BRIEF"
    const val CHANNEL_BRIEF = "luyuan_brief"

    private const val REQ_WIDGET = 9102
    private const val REQ_BRIEF = 9103
    private const val WIDGET_PERIOD_MIN = 15L

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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

        // ① Widget 保活（Receiver 内按时段过滤，夜间到点跳过）
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

    /** 晨间简报正文：今日课表 + 3 天内截止作业 */
    fun briefText(context: Context): String {
        val sb = StringBuilder()
        val courses = try { V2EntityRepository.listCourses(context) } catch (_: Exception) { emptyList() }
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
            sb.append("\n今天：").append(e.name)
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
