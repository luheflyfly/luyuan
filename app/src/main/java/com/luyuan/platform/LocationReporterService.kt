package com.luyuan.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.luyuan.MainActivity
import com.luyuan.R
import com.luyuan.data.LocationSettings
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * vc130 位置上报前台服务（2026-11-01 路河派单：把实时位置报给北极星）。
 *
 * **为什么必须是前台服务**：普通后台进程会被系统（尤其 vivo/OriginOS 省电策略）
 * 几分钟内清掉，`LocationManager` 的更新随之停摆。前台服务 + 常驻通知 = 系统认可
 * "用户知道它在跑"，活得下来；顺带满足后台取位的语义 —— 有 location 类型的前台服务
 * 在跑时 `ACCESS_FINE_LOCATION` 就够用，不必再逼用户去系统设置点「始终允许」。
 *
 * **为什么用系统 LocationManager**：这台 vivo V2509A 没有 GMS（见 build.gradle.kts
 * 里 ML Kit 那段注释），FusedLocationProviderClient 依赖 Google Play 服务，装了也拿不到点。
 *
 * 上报节奏：每 [LocationSettings.INTERVAL_MS] = 5 分钟一条；期间位移超过
 * [LocationSettings.MOVED_METERS] = 100 m 立刻补报。失败**静默重试**（5s/15s/45s），
 * 三次都失败就排队到下个周期补发，全程不弹窗。
 *
 * 关闭三条路，都即时生效：
 *   1. 通知栏「关闭」按钮（ACTION_STOP，同时把开关置 false）
 *   2. 设置页「位置共享」开关（走 [applySwitch]）
 *   3. 系统强行停止 App（Android 自身兜底）
 */
class LocationReporterService : Service() {

    companion object {
        const val CHANNEL_ID = "luyuan_location"
        const val NOTI_ID = 19468
        const val ACTION_START = "com.luyuan.action.LOCATION_START"
        const val ACTION_STOP = "com.luyuan.action.LOCATION_STOP"

        private const val LOC_INTERVAL_MS = LocationSettings.INTERVAL_MS
        private const val TICK_SECONDS = 60L
        /** vc131 收件箱首次拉取延迟：起服务后很快就能收到第一条问题，不用等满 5 分钟。 */
        private const val INBOX_FIRST_DELAY_SECONDS = 20L
        private val RETRY_WAITS_MS = longArrayOf(5_000L, 15_000L, 45_000L)

        /** 启动服务（已在跑则只是再送一次 START，不会起第二个定位循环）。 */
        fun start(ctx: Context) {
            if (!LocationSettings.enabled(ctx)) return
            val i = Intent(ctx, LocationReporterService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                // Android 12+ 从后台起前台服务会被拒；调用点都在前台（Activity/设置页），
                // 真被拒就记一笔，下次开 App 再起。
                LocationSettings.noteFailure(ctx, "启动服务被系统拒绝：${e.javaClass.simpleName}")
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, LocationReporterService::class.java))
            } catch (_: Exception) {
            }
        }

        /** 设置页开关的唯一入口：开关状态与服务的生死永远同步。 */
        fun applySwitch(ctx: Context, on: Boolean) {
            LocationSettings.setEnabled(ctx, on)
            if (on) start(ctx) else stop(ctx)
        }

        fun hasLocationPermission(ctx: Context): Boolean =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    private lateinit var lm: LocationManager
    private var listener: LocationListener? = null
    private var ticker: ScheduledExecutorService? = null
    private val main = Handler(Looper.getMainLooper())

    /** 单线程：定位回调、补发、重试排队执行，天然不会并发打北极星。 */
    private val io: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "location-upload").apply { isDaemon = true }
        }
    }

    @Volatile
    private var lastFix: Location? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        lm = getSystemService(LocationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 通知栏「关闭」：开关同步关掉，别出现"通知没了但开关还亮着"
            LocationSettings.setEnabled(this, false)
            shutdown()
            return START_NOT_STICKY
        }
        if (!LocationSettings.enabled(this)) {
            shutdown()
            return START_NOT_STICKY
        }
        if (!hasLocationPermission(this)) {
            LocationSettings.noteFailure(this, "没有定位权限，取不到点")
            shutdown()
            return START_NOT_STICKY
        }
        startAsForeground(buildNotification("每 5 分钟上报一次 · 点这里关掉"))
        requestUpdates()
        startTicker()
        return START_STICKY
    }

    override fun onDestroy() {
        stopUpdates()
        ticker?.shutdownNow()
        ticker = null
        io.shutdownNow()
        super.onDestroy()
    }

    // ── 前台通知 ────────────────────────────────────────────

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID, "位置共享", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "把当前坐标报给北极星（每 5 分钟一条）"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, LocationReporterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("路远正在记录位置")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_luyuan)
            .setLargeIcon(NotiStyle.brandLargeIcon(this))
            .setColor(NotiStyle.BRAND_GREEN)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "关闭", stop)          // 一键关：点了立刻停服务并把开关关掉
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startAsForeground(n: Notification) {
        ServiceCompat.startForeground(
            this, NOTI_ID, n,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            else 0
        )
    }

    private fun refreshNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTI_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    // ── 取点 ────────────────────────────────────────────────

    private fun enabledProviders(): List<String> {
        val out = mutableListOf<String>()
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) out += LocationManager.GPS_PROVIDER
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) out += LocationManager.NETWORK_PROVIDER
        } catch (_: Exception) {
        }
        return out
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        if (listener != null) return                    // 已经挂着，别重复注册
        val cb = LocationListener { loc -> onFix(loc) }
        listener = cb
        val providers = enabledProviders()
        if (providers.isEmpty()) {
            LocationSettings.noteFailure(this, "系统定位开关是关的（下拉快捷面板打开「定位」）")
            return
        }
        for (p in providers) {
            try {
                // 5 分钟 / 位移门槛设 0：到点必须有一条，发不发由 onFix 里判重决定
                lm.requestLocationUpdates(p, LOC_INTERVAL_MS, 0f, cb, Looper.getMainLooper())
            } catch (e: Exception) {
                LocationSettings.noteFailure(this, "订阅 $p 失败：${e.javaClass.simpleName}")
            }
        }
        requestOneShot()      // 别等 5 分钟才有第一条：先要一次"当前点"
    }

    @SuppressLint("MissingPermission")
    private fun requestOneShot() {
        val providers = enabledProviders()
        if (providers.isEmpty()) return
        // 先问 network、后问 gps：室内 gps 30 秒都未必给点（vivo 实锤：20:26 那次
        // 一次性取点问了 gps，直接空手而归，于是 5 分钟节奏断了）；network 走 WiFi/基站，
        // 室内也是秒级。两个都问，谁先回谁先算 —— 坐标差异由 onFix 里的判重挡掉。
        val order = providers.sortedBy {
            if (it == LocationManager.NETWORK_PROVIDER) 0 else 1
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (p in order) {
                try {
                    lm.getCurrentLocation(p, null, mainExecutor) { loc ->
                        if (loc != null) onFix(loc)
                    }
                } catch (_: Exception) {
                }
            }
            return
        }
        for (p in order) {
            try {
                val last = lm.getLastKnownLocation(p)
                if (last != null && System.currentTimeMillis() - last.time < 30 * 60_000L) {
                    onFix(last)
                    return
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun stopUpdates() {
        listener?.let {
            try {
                lm.removeUpdates(it)
            } catch (_: Exception) {
            }
        }
        listener = null
    }

    private fun onFix(loc: Location) {
        lastFix = loc
        val now = System.currentTimeMillis()
        val lastOk = LocationSettings.lastOkMs(this)

        // 判重：没动（<100 m）且还没到 5 分钟 → 不发
        if (lastOk > 0) {
            val dist = distanceMeters(
                LocationSettings.lastOkLat(this).toDouble(),
                LocationSettings.lastOkLon(this).toDouble(),
                loc.latitude, loc.longitude
            )
            val due = now - lastOk >= LOC_INTERVAL_MS
            if (!due && dist < LocationSettings.MOVED_METERS) return
        }
        upload(loc.latitude, loc.longitude,
               if (loc.hasAccuracy()) loc.accuracy else null, now)
    }

    // ── 上报 ────────────────────────────────────────────────

    /** 组 body：`{"lat":..,"lon":..,"acc":..,"ts":..}`（规格 §3；ts 用毫秒，服务端会归一）。 */
    private fun bodyOf(lat: Double, lon: Double, acc: Float?, tsMs: Long): String = buildString {
        append("{\"lat\":").append(lat)
        append(",\"lon\":").append(lon)
        append(",\"acc\":").append(acc?.toString() ?: "null")
        append(",\"ts\":").append(tsMs)
        append('}')
    }

    private fun upload(lat: Double, lon: Double, acc: Float?, tsMs: Long) {
        val body = bodyOf(lat, lon, acc, tsMs)
        io.execute {
            var ok = false
            // 静默重试三次，退避 5s/15s/45s；全失败就排队，等下个周期补发
            for (wait in RETRY_WAITS_MS) {
                if (!LocationSettings.enabled(this)) return@execute   // 用户中途关掉，别再发
                ok = postJson(LocationSettings.endpoint(this), body)
                if (ok) break
                try {
                    Thread.sleep(wait)
                } catch (_: InterruptedException) {
                    return@execute
                }
            }
            if (ok) {
                LocationSettings.noteSuccess(this, lat, lon, tsMs)
                LocationSettings.clearPending(this)
                val hhmm = android.text.format.DateFormat.format("HH:mm", tsMs)
                main.post { refreshNotification("上次上报 $hhmm（每 5 分钟一条）") }
            } else {
                LocationSettings.setPendingBody(this, body)
                main.post { refreshNotification("上报失败，下个周期补发") }
            }
        }
    }

    /** 补发上一次没出去的那条。 */
    private fun flushPending() {
        val body = LocationSettings.pendingBody(this)
        if (body.isBlank()) return
        io.execute {
            if (postJson(LocationSettings.endpoint(this), body)) {
                LocationSettings.clearPending(this)
                main.post { refreshNotification("补发成功") }
            }
        }
    }

    private fun postJson(endpoint: String, body: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "LuyuanApp/location")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) true else {
                val err = try {
                    conn.errorStream?.bufferedReader()?.use { r -> r.readText() }?.take(160) ?: ""
                } catch (_: Exception) {
                    ""
                }
                LocationSettings.noteFailure(this, "HTTP $code $err")
                false
            }
        } catch (e: Exception) {
            LocationSettings.noteFailure(this, "${e.javaClass.simpleName}: ${e.message}".take(200))
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    // ── 心跳：provider 静默时兜底 ───────────────────────────

    private fun startTicker() {
        if (ticker != null) return
        ticker = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "location-tick").apply { isDaemon = true }
        }
        // vc131：顺手拉收件箱（同一台北极星、同一个 baseUrl）。每 5 分钟一次，跟位置同频；
        // 首次 20 秒后拉，别等满 5 分钟才弹第一条。InboxNotifier.poll 内部自己起线程 + 静默失败，
        // 所以这一行既不会阻塞位置上报，也不会因为断网把定时器带崩。
        ticker?.scheduleWithFixedDelay({
            try {
                InboxNotifier.poll(this)
            } catch (_: Throwable) {
            }
        }, INBOX_FIRST_DELAY_SECONDS, LOC_INTERVAL_MS / 1000, TimeUnit.SECONDS)
        ticker?.scheduleWithFixedDelay({
            try {
                if (!LocationSettings.enabled(this)) {
                    main.post { shutdown() }
                    return@scheduleWithFixedDelay
                }
                flushPending()
                val lastOk = LocationSettings.lastOkMs(this)
                if (lastOk == 0L || System.currentTimeMillis() - lastOk >= LOC_INTERVAL_MS) {
                    main.post {
                        // 到点还没有新点：主动要一次当前点
                        requestOneShot()
                        // 两轮都拿不到新点，就用最后一个已知点顶上（服务端按 ts 判陈旧）
                        val stale = System.currentTimeMillis() - LocationSettings.lastOkMs(this) >=
                            LOC_INTERVAL_MS * 2
                        val f = lastFix
                        if (stale && f != null) {
                            upload(f.latitude, f.longitude,
                                   if (f.hasAccuracy()) f.accuracy else null,
                                   System.currentTimeMillis())
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS)
    }

    // ── 收尾 ────────────────────────────────────────────────

    private fun shutdown() {
        stopUpdates()
        ticker?.shutdownNow()
        ticker = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 球面距离（米）。用来判断"算不算显著位移"。 */
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1 == 0.0 && lon1 == 0.0) return Double.MAX_VALUE
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.sqrt(a))
    }
}
