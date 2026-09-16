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
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "路远"
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("路远正在录音")
            .setContentText(text)
            // vc85 通知重绘：品牌小图标+主题色（原 android.R 老喇叭与全 App 不搭）
            .setSmallIcon(com.luyuan.R.drawable.ic_stat_luyuan)
            .setColor(0xFF224A3A.toInt())
            .setOngoing(true)
            .build()
        startForeground(NOTI_ID, notification)
        return START_STICKY
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
