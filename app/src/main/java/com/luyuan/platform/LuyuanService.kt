package com.luyuan.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 录音期间的前台服务：显示常驻通知，防止系统回收录音进程。
 * 真正的录音逻辑在 LuyuanViewModel / RecordScreen 中，本服务只负责通知载体。
 */
class LuyuanService : Service() {
    companion object {
        const val CHANNEL_ID = "luyuan_recording"
        const val NOTI_ID = 1
        const val EXTRA_TEXT = "extra_text"

        /** vc110 P5：true=已暂停——通知改静置样式，跳动计时停住 */
        const val EXTRA_PAUSED = "extra_paused"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val paused = intent?.getBooleanExtra(EXTRA_PAUSED, false) ?: false
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "路远"
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(if (paused) "路远录音已暂停" else "路远正在录音")
            .setContentText(text)
            .setWhen(System.currentTimeMillis())
            // vc85 通知重绘：品牌小图标+主题色（原 android.R 老喇叭与全 App 不搭）
            .setSmallIcon(com.luyuan.R.drawable.ic_stat_luyuan)
            // vc111：vivo 通知卡不解析自适应图标（真机实锤兜底成系统机器人）——自带运行时绘制的大叶盘
            .setLargeIcon(NotiStyle.brandLargeIcon(this))
            .setColor(0xFF224A3A.toInt())
            .setOngoing(true)
        // vc109 P2：通知栏自带跳动计时，不用轮询更新通知（Diktafon 同款）；
        // vc110 P5：暂停时不再计时（计时数字停住=和录音页一致，不骗人）
        builder.setUsesChronometer(!paused)
        val notification = builder.build()
        // vc111 通知线：录音状态同步到灵动岛胶囊（悬浮窗权限没授=内部静默，零打扰）
        if (paused) IslandManager.recordingPause() else IslandManager.recordingStart(this)
        // vc109 P2：显式声明麦克风前台服务类型（manifest 已声明，调用侧配对，targetSdk 34 更稳）
        androidx.core.app.ServiceCompat.startForeground(
            this, NOTI_ID, notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        // vc109 P2：START_STICKY→NOT_STICKY——系统自己拉起的空服务守不住任何东西，
        // 只会挂着「正在录音」的假通知（没在录也说在录）
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // vc111：录音停（含 stopService）→ 胶囊收回；进程被整体杀时悬浮窗随进程自动消失
        IslandManager.recordingStop()
        super.onDestroy()
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID, "录音", NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(ch)
        }
    }
}
