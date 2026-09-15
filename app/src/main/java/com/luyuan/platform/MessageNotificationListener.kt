package com.luyuan.platform

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.luyuan.data.MessageSettings
import com.luyuan.data.MessageTodoExtractor
import com.luyuan.data.PendingMessageTodo
import com.luyuan.data.PendingMessageTodoStore
import java.util.concurrent.Executors

/**
 * 「消息待办」监听端（立项单 T1）：备用机（鸿蒙 4）上收到微信 / QQ 消息 →
 * 本机粗筛 → 命中才送给云端抽取 → 落「待确认」，等用户在手机上 ✓。
 *
 * 🔴 与记账 listener 严格隔离：本类**独立**于 PaymentNotificationListener，互不影响。
 * 🔴 误报比漏报更伤（与记账同一原则）：粗筛写在本机、不过网；判定为私聊 + 命中信号词才继续。
 * 🔴 开关默认全关：MessageSettings 未开启时任何消息都不出网（连信号词匹配都不做）。
 * 🔴 通知解析 / 网络请求任何异常一律静默吞掉，永不干扰系统。
 */
class MessageNotificationListener : NotificationListenerService() {

    /** 单线程串行执行：天然避免并发重复处理同一条消息 */
    private val worker = Executors.newSingleThreadExecutor()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName ?: return
            if (pkg != MessageTodoExtractor.PKG_WECHAT && pkg != MessageTodoExtractor.PKG_QQ) return
            // 总开关 + 来源白名单（默认全关；关了连解析都不做）
            if (!MessageSettings.allowedPackage(this, pkg)) return

            val ex = sbn.notification?.extras ?: return
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
            val body = when {
                big.isNotBlank() -> big
                text.isNotBlank() -> text
                else -> return
            }
            if (title.isBlank() || body.isBlank()) return
            // 太长的多半是公众号/长文通知，不是聊天消息
            if (body.length > 300) return

            // 群消息 body 一般是「昵称: 内容」/「昵称：内容」，标题是群名：
            // 默认跳过；设置里开了「含群聊（通知群）」才处理（09-14 路河：通知群的待办占大头）
            val isGroup = RX_GROUP_PREFIX.containsMatchIn(body)
            if (isGroup && !MessageSettings.groupsOn(this)) return

            // 09-14 路河拍板 B：取消信号词白名单——群里大量「收到/好的」在本机扔掉，
            // 其余（含转告/通知同学这类没有固定词表的）全部送云端定夺有没有事要办
            if (isAckOrNoise(body)) return

            // 60 秒内同发送者 + 同内容只处理一次（厂商会连发同内容通知）
            if (isDuplicate(title, body)) return

            val source = if (pkg == MessageTodoExtractor.PKG_WECHAT) "wechat" else "qq"
            worker.execute {
                try {
                    val r = MessageTodoExtractor.extract(this, title, body, source) ?: return@execute
                    val todo = PendingMessageTodo(
                        id = PendingMessageTodoStore.newId(),
                        text = r.text,
                        who = r.who,
                        whenText = r.whenText,
                        raw = body.take(200),
                        source = source,
                        sender = title,
                        created_at = PendingMessageTodoStore.nowIso()
                    )
                    PendingMessageTodoStore.add(this, todo)
                    notifyTodoAction(this, todo)   // 2026-09-15：通知栏直接已完成/不要
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
            // 通知解析永不干扰系统
        }
    }

    /** 60 秒内同发送者 + 同内容的暂存记录（内存级，进程重启即清，够用且不写盘） */
    private val recent = HashMap<String, Long>()

    private fun isDuplicate(sender: String, body: String): Boolean {
        val key = sender + "|" + body.take(80)
        val now = System.currentTimeMillis()
        // 顺手清过期（限制 map 大小）
        if (recent.size > 64) {
            val it = recent.entries.iterator()
            while (it.hasNext()) if (now - it.next().value > 60_000) it.remove()
        }
        val last = recent[key]
        if (last != null && now - last < 60_000) return true
        recent[key] = now
        return false
    }

    /** 抽中消息待办后发一条带「已完成/不要」的通知（路河 09-15） */
    private fun notifyTodoAction(ctx: android.content.Context, p: com.luyuan.data.PendingMessageTodo) {
        try {
            val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val tap = android.app.PendingIntent.getActivity(
                ctx, 0,
                ctx.packageManager.getLaunchIntentForPackage(ctx.packageName),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            fun pi(action: String, requestCode: Int) = android.app.PendingIntent.getBroadcast(
                ctx, requestCode,
                android.content.Intent(action).setPackage(ctx.packageName)
                    .putExtra(TodoActionReceiver.EXTRA_ID, p.id)
                    .setClass(ctx, TodoActionReceiver::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val who = if (p.who.isNotBlank()) p.who else if (p.sender.isNotBlank()) p.sender else "消息"
            val builder = if (android.os.Build.VERSION.SDK_INT >= 26)
                android.app.Notification.Builder(ctx, Reminders.CHANNEL_TODO)
            else
                @Suppress("DEPRECATION") android.app.Notification.Builder(ctx)
            builder.setSmallIcon(android.R.drawable.checkbox_on_background)
                .setContentTitle("$who：${p.text.take(30)}")
                .setContentText(if (p.whenText.isNotBlank()) p.whenText else "消息里提到的待办")
                .setAutoCancel(true)
                .setContentIntent(tap)
                .addAction(android.R.drawable.checkbox_on_background, "已完成", pi(TodoActionReceiver.ACTION_DONE, p.id.hashCode() * 10 + 1))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "不要", pi(TodoActionReceiver.ACTION_DROP, p.id.hashCode() * 10 + 2))
            nm.notify(TodoActionReceiver.TAG, p.id.hashCode(), builder.build())
        } catch (_: Throwable) {
            // 发通知失败不影响入库
        }
    }

    override fun onDestroy() {
        try {
            worker.shutdownNow()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    companion object {
        /** 确认词黑名单（trim 后全等才丢；「周五交材料」这类短而重要的不受影响） */
        private val ACK_WORDS = hashSetOf(
            "收到", "好的", "好", "嗯", "嗯嗯", "ok", "OK", "Ok", "行", "可以",
            "谢谢", "明白了", "了解", "已读", "1", "666", "哈哈哈", "哈哈哈哈", "哈哈哈哈哈"
        )

        /** 确认词 / 纯噪声判定：本机完成不过网 */
        private fun isAckOrNoise(body: String): Boolean {
            val b = body.trim()
            if (b.isEmpty()) return true
            if (b in ACK_WORDS) return true
            // 整条没有一个文字（纯数字/标点/表情）→ 噪声
            if (b.all { !it.isLetter() }) return true
            return false
        }

        /** 群聊特征：body 形如「昵称: 内容」/「昵称：内容」（昵称不含空格与标点过长） */
        private val RX_GROUP_PREFIX = Regex("^\\s*[^\\s：:]{1,20}\\s*[：:]\\s*\\S")

        /** 本 App 是否已拿到通知使用权（与记账 listener 同一项系统授权） */
        fun enabled(ctx: android.content.Context): Boolean =
            PaymentNotificationListener.enabled(ctx)
    }
}
