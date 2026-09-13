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

            // 只处理私聊：群消息的 body 一般是「昵称: 内容」或「昵称：内容」，标题是群名
            if (RX_GROUP_PREFIX.containsMatchIn(body)) return

            // 粗筛：命中待办信号词才进入下一步（本机完成，不过网）
            if (!hitSignal(body)) return

            // 60 秒内同发送者 + 同内容只处理一次（厂商会连发同内容通知）
            if (isDuplicate(title, body)) return

            val source = if (pkg == MessageTodoExtractor.PKG_WECHAT) "wechat" else "qq"
            worker.execute {
                try {
                    val r = MessageTodoExtractor.extract(this, title, body, source) ?: return@execute
                    PendingMessageTodoStore.add(
                        this,
                        PendingMessageTodo(
                            id = PendingMessageTodoStore.newId(),
                            text = r.text,
                            who = r.who,
                            whenText = r.whenText,
                            raw = body.take(200),
                            source = source,
                            sender = title,
                            created_at = PendingMessageTodoStore.nowIso()
                        )
                    )
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

    override fun onDestroy() {
        try {
            worker.shutdownNow()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    companion object {
        /** 待办信号词（粗筛，本机不过网）——命中任一才可能送到云端 */
        val SIGNAL_WORDS = listOf(
            "记得", "明天", "后天", "帮我", "麻烦", "要交", "提醒", "别忘了",
            "几点", "去取", "去拿", "之前", "deadline", "截止", "开会", "晚会",
            "别忘", "搞定", "回复我", "联系我", "交材料", "报名", "交作业"
        )

        /** 「周X」/「星期X」也算信号（X 用正则，避免漏「周三」这类） */
        private val RX_WEEKDAY = Regex("周[一二三四五六日天]|星期[一二三四五六日天]|礼拜[一二三四五六日天]")

        /** 粗筛总判定 */
        private fun hitSignal(body: String): Boolean =
            SIGNAL_WORDS.any { body.contains(it) } || RX_WEEKDAY.containsMatchIn(body)

        /** 群聊特征：body 形如「昵称: 内容」/「昵称：内容」（昵称不含空格与标点过长） */
        private val RX_GROUP_PREFIX = Regex("^\\s*[^\\s：:]{1,20}\\s*[：:]\\s*\\S")

        /** 本 App 是否已拿到通知使用权（与记账 listener 同一项系统授权） */
        fun enabled(ctx: android.content.Context): Boolean =
            PaymentNotificationListener.enabled(ctx)
    }
}
