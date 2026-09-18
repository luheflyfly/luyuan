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
import com.luyuan.domain.Note
import com.luyuan.data.ContactRepository
import com.luyuan.data.NoteRepository

/** 系统通知：渠道三分（手机二期 #7 通知三渠道）——提醒/联系人待办/日记各自可被用户在系统设置分控 */
object ReminderNotifications {
    const val CHANNEL_ID = "luyuan_reminders"
    const val CHANNEL_TODO = "luyuan_contact_todo"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 无 null 判断：同 id 重复 create 会更新名称/描述（vc85 渠道更名走这里生效）
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "笔记提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "到点的笔记/校历提醒"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TODO, "待办提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "联系人待人办与微信/QQ 消息待办"
            }
        )
    }

    fun fire(context: Context, noteId: String, title: String, body: String, channelId: String = CHANNEL_ID, tapDetail: Boolean = false) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // 2026-09-18 检查批：笔记提醒点通知本体直达该笔记详情（此前只落主页）；
            // 联系人待办等非笔记件不传 tapDetail，仍落主页
            if (tapDetail) putExtra("detailId", noteId)
        }
        val pi = PendingIntent.getActivity(
            context, noteId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // vc85 通知重绘：正文去掉「[校历] 」这类内部标签前缀，标签进 subtext（右侧小字），不再顶在最前
        var text = body
        var sub: String? = null
        val m = RX_TAG_PREFIX.find(text)
        if (m != null) {
            sub = m.groupValues[1]
            text = text.substring(m.value.length).trim()
        }
        val n = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_luyuan)
            .setColor(0xFF224A3A.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(sub)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(context)
                .notify(noteId.hashCode(), n)
        } catch (_: SecurityException) {
            // 无通知权限：静默跳过（设置页/录音页有引导授权）
        }
    }

    /** 「[校历] 」「[待办] 」等 1-6 字标签前缀（PC 端播种提醒的内部记号，展示层剥掉） */
    private val RX_TAG_PREFIX = Regex("^\\[([^\\]]{1,6})]\\s*")
}

/** 到点响铃 + 错过补弹的统一入口 */
private fun fireReminder(context: Context, note: Note) {
    ReminderNotifications.fire(context, note.id, "路远提醒", note.text.take(200), tapDetail = true)
    NoteRepository.markReminderFired(context, note.id)
}

/**
 * 提醒调度：每条未触发的 remind_at 挂一个 AlarmManager 闹钟（最多 20 条最近即将到点的）。
 * 电脑端设的提醒随 Syncthing 同步过来，App 打开/开机时 rescheduleAll 即可接管。
 */
object ReminderScheduler {
    fun rescheduleAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        val upcoming = NoteRepository.pendingReminders(context)
            .mapNotNull { n -> NoteRepository.remindAtMillis(n)?.let { n to it } }
            .filter { (_, at) -> at > now }
            .sortedBy { (_, at) -> at }
            .take(20)
        for ((note, at) in upcoming) {
            val pi = alarmIntent(context, note.id)
            if (canExact(am)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60 * 1000L, pi)
            }
        }
        // 已过期但没响过的（电脑关机时到点/同步晚到）→ 立刻补弹
        val overdue = NoteRepository.pendingReminders(context)
            .mapNotNull { n -> NoteRepository.remindAtMillis(n)?.let { n to it } }
            .filter { (_, at) -> at <= now }
            .sortedBy { (_, at) -> at }
        for ((note, _) in overdue.take(10)) {
            fireReminder(context, note)
        }

        // ---------- 联系人待办提醒（v1.11）：与笔记提醒同机制 ----------
        val todoUpcoming = ContactRepository.pendingTodoReminders(context)
            .mapNotNull { (c, t) -> parseTodoMillis(t.remind_at)?.let { Triple(c, t, it) } }
            .filter { (_, _, at) -> at > now }
            .sortedBy { (_, _, at) -> at }
            .take(20)
        for ((c, t, at) in todoUpcoming) {
            val pi = todoAlarmIntent(context, c.id, t.id)
            if (canExact(am)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60 * 1000L, pi)
            }
        }
        // 过期待办补弹（PC 关机时到点/同步晚到），限 5 条防历史脏数据轰炸
        val todoOverdue = ContactRepository.pendingTodoReminders(context)
            .mapNotNull { (c, t) -> parseTodoMillis(t.remind_at)?.let { Triple(c, t, it) } }
            .filter { (_, _, at) -> at <= now }
            .sortedBy { (_, _, at) -> at }
        for ((c, t, _) in todoOverdue.take(5)) {
            if (t.done || t.reminded == true) continue
            ReminderNotifications.fire(context, "ctodo_${t.id}", "待办 · ${c.name}", t.text.take(200), ReminderNotifications.CHANNEL_TODO)
            ContactRepository.markTodoReminded(context, c.id, t.id)
        }
    }

    fun cancel(context: Context, noteId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent(context, noteId))
    }

    private fun canExact(am: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    private fun alarmIntent(context: Context, noteId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra("note_id", noteId)
        return PendingIntent.getBroadcast(
            context, noteId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun todoAlarmIntent(context: Context, contactId: String, todoId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra("contact_todo_id", todoId)
            .putExtra("contact_id", contactId)
        return PendingIntent.getBroadcast(
            context, ("ctodo_" + todoId).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** ISO8601（带/不带时区）→ epoch 毫秒；解析失败返回 null */
    private fun parseTodoMillis(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return try {
            java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.LocalDateTime.parse(s)
                    .atOffset(java.time.OffsetDateTime.now().offset)
                    .toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** 闹钟到点：响通知 → 标记已触发 → 排下一条 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 联系人待办提醒（v1.11）：note_id 缺省时看 contact_todo_id
        val todoId = intent.getStringExtra("contact_todo_id")
        if (todoId != null) {
            val contactId = intent.getStringExtra("contact_id") ?: return
            val c = ContactRepository.findContact(context, contactId) ?: return
            val t = c.todos.firstOrNull { it.id == todoId } ?: return
            if (t.done || t.reminded == true) return
            ReminderNotifications.fire(
                context, "ctodo_$todoId", "待办 · ${c.name}", t.text.take(200),
                ReminderNotifications.CHANNEL_TODO
            )
            ContactRepository.markTodoReminded(context, contactId, todoId)
            ReminderScheduler.rescheduleAll(context)
            return
        }
        val id = intent.getStringExtra("note_id") ?: return
        val note = NoteRepository.findAny(context, id) ?: return
        if (note.deleted || note.remind_fired == true) return
        fireReminder(context, note)
        rescheduleAll(context)
    }

    private fun rescheduleAll(context: Context) = ReminderScheduler.rescheduleAll(context)
}

/** 开机后重新挂闹钟（重启不丢提醒） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ReminderScheduler.rescheduleAll(context)
            JournalReminder.reschedule(context)
            LuyuanClock.rescheduleAll(context)   // vc103：课程提醒/Widget保活/晨间简报
        }
    }
}

// ---------- 每日日记提醒（负一屏「日记」页） ----------

object JournalReminder {
    const val CHANNEL_ID = "luyuan_journal"
    private const val PREFS = "luyuan_prefs"
    private const val KEY_ENABLED = "journal_enabled"
    private const val KEY_HOUR = "journal_hour"
    private const val KEY_MINUTE = "journal_minute"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun time(context: Context): Pair<Int, Int> =
        prefs(context).getInt(KEY_HOUR, 21) to prefs(context).getInt(KEY_MINUTE, 0)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) reschedule(context) else cancel(context)
    }

    fun setTime(context: Context, hour: Int, minute: Int) {
        prefs(context).edit().putInt(KEY_HOUR, hour).putInt(KEY_MINUTE, minute).apply()
        if (isEnabled(context)) reschedule(context)
    }

    fun reschedule(context: Context) {
        if (!isEnabled(context)) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val (h, m) = time(context)
        val now = java.time.LocalDateTime.now()
        var next = now.toLocalDate().atTime(h, m)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val at = next.atOffset(java.time.OffsetDateTime.now().offset).toInstant().toEpochMilli()
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        val pi = alarmIntent(context)
        if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60 * 1000L, pi)
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent(context))
    }

    private fun alarmIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 2001, Intent(context, JournalReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun fire(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "日记提醒", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val pi = PendingIntent.getActivity(
            context, 2002,
            Intent(context, MainActivity::class.java).putExtra("page", "journal"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_luyuan)
            .setColor(0xFF224A3A.toInt())
            .setContentTitle("该记日记啦")
            .setContentText("今天想记录点什么？点这里打开路远")
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(context).notify(2002, n)
        } catch (_: SecurityException) {
        }
    }
}

/** 日记提醒到点：响一条 + 排明天 */
class JournalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        JournalReminder.fire(context)
        JournalReminder.reschedule(context)
    }
}
