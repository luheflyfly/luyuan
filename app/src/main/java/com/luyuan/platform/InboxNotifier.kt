package com.luyuan.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.luyuan.MainActivity
import com.luyuan.R
import com.luyuan.data.InboxSettings
import com.luyuan.data.LocationSettings
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * vc131 收件箱通知（2026-11-02 路河派单：北极星问一句 → 路远 App 弹系统通知 → 点按钮 = 回答）。
 *
 * 契约见 `Polaris/_refactor/product/inbox-api.md`（定稿）：
 *   GET  /api/inbox/pending   拉未答未过期（旧→新）
 *   POST /api/inbox/answer    {"id":"...","option_id":"..."}
 *
 * 四条设计口径，每一条都是"别打扰他"：
 *  1. **不新建网络栈**：地址一律走 [LocationSettings.apiBase] —— 与位置上报同一个 baseUrl，
 *     设置页改一次两边都跟着变；HTTP 用系统 HttpURLConnection（同 LocationReporterService），
 *     不引第三方库。
 *  2. **不阻塞位置上报**：拉取在自己的单线程池里跑，调用方（前台服务的定时器）立刻返回；
 *     上一轮没回来就直接跳过这一轮（[busy]），不叠加请求。
 *  3. **只弹一次**：`id` 去重表在 [InboxSettings]（最近 20 个）。服务端就算重复给同一条，
 *     也只会有一条通知。
 *  4. **静默失败**：断网/服务没起/HTTP 4xx5xx → 什么都不做，下次 5 分钟后再试。
 *     不弹错误提示、不做重试风暴、不写崩溃日志（这是预期内的离线状态，不是 bug）。
 *
 * 按钮：`options` 每项一个 action button（Android 通知上限 3 个，服务端也已挡在 3 个）。
 * 点按钮 → [InboxActionReceiver] → 先撤通知（他要的"立刻消失"）→ 再后台 POST 回答。
 */
object InboxNotifier {

    const val CHANNEL_ID = "luyuan_inbox"
    const val TAG = "inbox"

    /** Android 通知最多 3 个 action button —— 与接口文档一致，多的截断（服务端本来就不给）。 */
    private const val MAX_ACTIONS = 3

    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 6_000

    private const val PATH_PENDING = "/api/inbox/pending"
    private const val PATH_ANSWER = "/api/inbox/answer"

    /** 单线程：拉取与回填串行执行，天然不会并发打北极星。 */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "inbox-poll").apply { isDaemon = true }
    }

    private val busy = AtomicBoolean(false)

    // ── 轮询（由位置上报前台服务每 5 分钟顺手调） ─────────────

    /** 拉一次待答并发通知。**立即返回**，不抛异常。 */
    fun poll(ctx: Context) {
        val app = ctx.applicationContext
        if (!InboxSettings.enabled(app)) return
        if (!busy.compareAndSet(false, true)) return       // 上一轮还在路上，这轮跳过
        try {
            worker.execute {
                try {
                    doPoll(app)
                } catch (_: Throwable) {
                    // 静默：断网/服务没起都是常态
                } finally {
                    busy.set(false)
                }
            }
        } catch (_: Throwable) {
            busy.set(false)
        }
    }

    private fun doPoll(ctx: Context) {
        if (!InboxSettings.enabled(ctx)) return
        val raw = getJson(ctx, PATH_PENDING) ?: return
        val items: JSONArray = try {
            JSONObject(raw).optJSONArray("items") ?: return
        } catch (_: Throwable) {
            return
        }
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val id = it.optString("id").trim()
            if (id.isEmpty()) continue
            if (InboxSettings.alreadyNotified(ctx, id)) continue
            val title = it.optString("title").trim().ifBlank { "北极星有个问题" }
            val text = it.optString("body").trim()
            val options = parseOptions(it.optJSONArray("options"))
            if (showNotification(ctx, id, title, text, options)) {
                InboxSettings.markNotified(ctx, id)
            }
        }
    }

    private data class Opt(val id: String, val label: String)

    private fun parseOptions(arr: JSONArray?): List<Opt> {
        if (arr == null) return emptyList()
        val out = ArrayList<Opt>(3)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val oid = o.optString("id").trim()
            val label = o.optString("label").trim()
            if (oid.isEmpty() || label.isEmpty()) continue
            out.add(Opt(oid, label))
        }
        return out
    }

    // ── 发通知 ──────────────────────────────────────────────

    private fun ensureChannel(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID, "北极星消息", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "北极星问你一件要你拍板的事，点按钮就回答"
            setShowBadge(true)
        }
        mgr.createNotificationChannel(ch)
    }

    private fun buildNotification(
        ctx: Context, id: String, title: String, text: String, options: List<Opt>
    ): Notification {
        val open = PendingIntent.getActivity(
            ctx, id.hashCode(),
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.ifBlank { title }))
            .setSmallIcon(R.drawable.ic_stat_luyuan)
            .setLargeIcon(NotiStyle.brandLargeIcon(ctx))
            .setColor(NotiStyle.BRAND_GREEN)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)

        // 每个选项一个按钮：点了就走 InboxActionReceiver（不打开 App）
        for (o in options.take(MAX_ACTIONS)) {
            val i = Intent(ctx, InboxActionReceiver::class.java).apply {
                action = InboxActionReceiver.ACTION_ANSWER
                putExtra(InboxActionReceiver.EXTRA_ID, id)
                putExtra(InboxActionReceiver.EXTRA_OPTION_ID, o.id)
            }
            val pi = PendingIntent.getBroadcast(
                ctx, (id + "|" + o.id).hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(0, o.label.take(24), pi)
        }
        return b.build()
    }

    /** 返回 true = 已交给系统（用于写去重表）；权限没给/系统拒绝 → false，下轮再试。 */
    private fun showNotification(
        ctx: Context, id: String, title: String, text: String, options: List<Opt>
    ): Boolean {
        return try {
            ensureChannel(ctx)
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (mgr == null) false
            else {
                mgr.notify(TAG, id.hashCode(), buildNotification(ctx, id, title, text, options))
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun cancel(ctx: Context, id: String) {
        try {
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
            mgr.cancel(TAG, id.hashCode())
        } catch (_: Throwable) {
        }
    }

    // ── 回答（被通知按钮调用） ───────────────────────────────

    /**
     * 他点了某个按钮：**先撤通知**（立刻消失，不等网络），再后台 POST。
     * POST 失败就让它去 —— 服务端那条会自己过期（按 skip 处理），不重试、不弹错误。
     */
    fun submitAnswer(ctx: Context, id: String, optionId: String) {
        val app = ctx.applicationContext
        cancel(app, id)
        InboxSettings.markNotified(app, id)      // 别再弹回来（他既然点了，就别再打扰第二次）
        try {
            worker.execute {
                try {
                    val body = JSONObject().put("id", id).put("option_id", optionId).toString()
                    postJson(app, PATH_ANSWER, body)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    // ── HTTP（HttpURLConnection，和 LocationReporterService 同一套写法） ──

    private fun getJson(ctx: Context, path: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(LocationSettings.apiUrl(ctx, path)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "LuyuanApp/inbox")
            }
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Throwable) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun postJson(ctx: Context, path: String, body: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(LocationSettings.apiUrl(ctx, path)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "LuyuanApp/inbox")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (_: Throwable) {
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }
}
