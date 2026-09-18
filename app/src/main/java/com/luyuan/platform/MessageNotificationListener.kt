package com.luyuan.platform

import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.luyuan.data.BufferedMessage
import com.luyuan.data.MessageBuffer
import com.luyuan.data.MessageSettings
import com.luyuan.data.MessageTodoExtractor
import com.luyuan.data.PendingMessageTodo
import com.luyuan.data.PendingMessageTodoStore
import java.util.concurrent.Executors

/**
 * 「消息待办」监听端（立项单 T1；vc98 路河拍板改窗口抽取）：备用机（鸿蒙 4）上收到微信 / QQ
 * 消息 → 本机粗筛 → **先进消息窗缓冲攒上下文（同批 5 分钟窗）** → 窗口到期整窗送云端
 * 以任务为单位抽取 → 落「待确认」，等用户在手机上 ✓。
 *
 * 为什么改（09-18 路河诊断：抓取敏感性/总结内容/截止日期三处不好用）：
 * 逐条抽取看不见上下文——任务被拆在多条消息里就抽出碎片（「发一下」），一篇通知炸出 N 条重复
 * 待办；窗口版让模型看到整段对话：合并重复、丢弃缺上下文的碎片、截止时间锚定当前时间算成绝对
 * 时间（due_iso）。外发量也从「每条消息一次」降到「每窗一次」。
 *
 * 🔴 与记账 listener 严格隔离：本类**独立**于 PaymentNotificationListener，互不影响。
 * 🔴 误报比漏报更伤：粗筛写在本机、不过网；过排除名单/确认词才进缓冲。
 * 🔴 开关默认全关：MessageSettings 未开启时任何消息都不出网（连信号词匹配都不做）。
 * 🔴 通知解析 / 网络请求任何异常一律静默吞掉，永不干扰系统。
 */
class MessageNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName ?: return
            if (pkg != MessageTodoExtractor.PKG_WECHAT && pkg != MessageTodoExtractor.PKG_QQ) return
            // 总开关 + 来源白名单（默认全关；关了连解析都不做）
            if (!MessageSettings.allowedPackage(this, pkg)) return

            val ex = sbn.notification?.extras ?: return
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            // 不计入待办名单（09-15 路河：朋友闲聊不进工作流）——命中即静默丢，不解析不出网
            if (MessageSettings.isExcluded(this, title)) return
            val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
            var body = when {
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
            // 拆出群昵称，缓冲里带上（抽取端按「谁说的」归并任务）
            var sender = title
            if (isGroup) {
                val (nick, rest) = splitGroup(body)
                sender = nick
                body = rest
            }

            // 09-14 路河拍板 B：取消信号词白名单——群里大量「收到/好的」在本机扔掉，
            // 其余全部进缓冲，窗口到期由云端按上下文定夺有没有事要办
            if (isAckOrNoise(body)) return

            // 60 秒内同发送者 + 同内容只收一次（厂商会连发同内容通知）
            if (isDuplicate(title, body)) return

            MessageBuffer.append(
                this,
                BufferedMessage(chat = title, sender = sender, body = body, at = System.currentTimeMillis())
            )
            scheduleFlush()
        } catch (_: Throwable) {
            // 通知解析永不干扰系统
        }
    }

    /** 拆「昵称: 内容」→ (昵称, 正文)；正则保证能匹配（isGroup 判过） */
    private fun splitGroup(body: String): Pair<String, String> {
        val m = RX_GROUP_PREFIX.find(body) ?: return "" to body
        return m.groupValues[1].trim() to m.groupValues[2].trim()
    }

    /** 排一个最近的冲刷时点：整批最早消息满窗时触发；有聊天积满 EARLY_COUNT 条则 2 秒内就来 */
    private fun scheduleFlush() {
        val msgs = MessageBuffer.list(this)
        if (msgs.isEmpty()) return
        val oldest = msgs.minOf { it.at }
        val full = msgs.groupingBy { it.chat }.eachCount().any { it.value >= MessageBuffer.EARLY_COUNT }
        val delay = if (full) 2_000L
        else maxOf(2_000L, MessageBuffer.WINDOW_MS - (System.currentTimeMillis() - oldest))
        flushHandler.removeCallbacks(flushRunnable)
        flushHandler.postDelayed(flushRunnable, delay)
    }

    private val flushRunnable = Runnable {
        if (MessageBuffer.due(this) || MessageBuffer.anyChatFull(this)) {
            flushNow(this)
        } else {
            scheduleFlush()   // 进程醒着但窗还没满，续约下一次
        }
    }

    override fun onDestroy() {
        try {
            flushHandler.removeCallbacksAndMessages(null)
        } catch (_: Throwable) {
        }
        super.onDestroy()
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

        /** 群聊特征：body 形如「昵称: 内容」/「昵称：内容」；两个捕获组 = 昵称、正文 */
        private val RX_GROUP_PREFIX = Regex("^\\s*([^\\s：:]{1,20})\\s*[：:]\\s*(\\S.*)$")

        /** 单线程串行执行：天然避免并发重复抽取同一个窗口 */
        private val flushExecutor = Executors.newSingleThreadExecutor()
        private val flushHandler = Handler(Looper.getMainLooper())

        /**
         * 窗口冲刷：缓冲整体取走 → 按聊天分组 → 每聊天一次云端抽取 → 任务落待确认 + 通知。
         * 抽取失败该聊天静默放弃（原文在微信里，下一条消息进来又是新窗）。
         */
        fun flushNow(ctx: Context) {
            flushExecutor.execute {
                try {
                    val msgs = MessageBuffer.takeAll(ctx)
                    if (msgs.isEmpty()) return@execute
                    val failed = mutableListOf<BufferedMessage>()
                    for ((chat, lines) in msgs.groupBy { it.chat }) {
                        val tasks = MessageTodoExtractor.extractWindow(ctx, chat, lines)
                        if (tasks == null) {
                            // 抽取失败（断网/无 Key/接口错）：消息放回缓冲，等下一条通知或
                            // 打开待办页时经 due() 自然重试——绝不白丢上下文
                            failed.addAll(lines)
                            continue
                        }
                        for (t in tasks) {
                            val todo = PendingMessageTodo(
                                id = PendingMessageTodoStore.newId(),
                                text = t.text,
                                who = t.who,
                                whenText = t.whenText,
                                dueIso = t.dueIso,
                                raw = lines.takeLast(3).joinToString(" / ") { it.body }.take(200),
                                source = "wechat",
                                sender = chat,
                                created_at = PendingMessageTodoStore.nowIso()
                            )
                            PendingMessageTodoStore.add(ctx, todo)
                            notifyTodoAction(ctx, todo)
                        }
                    }
                    for (m in failed) MessageBuffer.append(ctx, m)
                } catch (_: Throwable) {
                }
            }
        }

        /** App 内补抽入口（vc98）：打开待办页时调用——进程被杀后定时器丢失，靠这条兜底把满窗缓冲抽掉。 */
        fun flushDueNow(ctx: Context) {
            try {
                if (MessageBuffer.due(ctx) || MessageBuffer.anyChatFull(ctx)) flushNow(ctx)
            } catch (_: Throwable) {
            }
        }

        /** 抽中消息待办后发一条带「已完成/收下/不要」的通知（vc85 通知重绘：Compat 构建+品牌图标+深绿主题；
         *  点通知本体直达待办页（09-18 检查批） */
        private fun notifyTodoAction(ctx: Context, p: PendingMessageTodo) {
            try {
                ReminderNotifications.ensureChannel(ctx)
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                val tapIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                    ?: android.content.Intent(ctx, com.luyuan.MainActivity::class.java)
                tapIntent.putExtra("page", "todos")
                val tap = android.app.PendingIntent.getActivity(
                    ctx, 0, tapIntent,
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
                val big = buildString {
                    append(p.text)
                    if (p.whenText.isNotBlank()) append("\n截止：").append(p.whenText)
                }
                val builder = androidx.core.app.NotificationCompat.Builder(ctx, ReminderNotifications.CHANNEL_TODO)
                    .setSmallIcon(com.luyuan.R.drawable.ic_stat_luyuan)
                    .setColor(0xFF224A3A.toInt())
                    .setContentTitle("消息待办 · $who")
                    .setContentText(p.text)
                    .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(big))
                    .setCategory(androidx.core.app.NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(tap)
                    .addAction(android.R.drawable.checkbox_on_background, "已完成", pi(TodoActionReceiver.ACTION_DONE, p.id.hashCode() * 10 + 1))
                    .addAction(android.R.drawable.ic_menu_agenda, "收下", pi(TodoActionReceiver.ACTION_KEEP, p.id.hashCode() * 10 + 3))
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "不要", pi(TodoActionReceiver.ACTION_DROP, p.id.hashCode() * 10 + 2))
                nm.notify(TodoActionReceiver.TAG, p.id.hashCode(), builder.build())
            } catch (_: Throwable) {
                // 发通知失败不影响入库
            }
        }
    }
}
