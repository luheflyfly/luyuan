package com.luyuan.platform

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import com.luyuan.MainActivity

/**
 * 无障碍服务全局按键监听（安卓唯一合法的全局按键通道，无需 adb）。
 * 唤录音组合键（路河 2026-09-10 拍板）：**同时按「音量加 + 音量减」**。
 * 旧「电源键+音量加」弃用（部分 ROM 不下发电源键事件给第三方，实锤无反应）。
 * 后台拉起界面依赖「显示在其他应用上层」权限（Settings.canDrawOverlays）。
 */
class VolumeKeyService : AccessibilityService() {

    private var volUpDown = false
    private var volDownDown = false


    override fun onKeyEvent(event: KeyEvent): Boolean {
        // 长按的 repeat 连发不算（修"调音量误触"根因）
        if (event.repeatCount > 0) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val up = event.action == KeyEvent.ACTION_UP
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (down) {
                    volUpDown = true
                    if (volDownDown) { fire(); return true }
                } else if (up) {
                    volUpDown = false
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (down) {
                    volDownDown = true
                    if (volUpDown) { fire(); return true }
                } else if (up) {
                    volDownDown = false
                }
            }
        }
        return false
    }

    /** 两键同按成功：消费按键（不让音量变）并唤起录音 */
    private fun fire() {
        volUpDown = false
        volDownDown = false
        launchRecord()
    }

    private fun launchRecord() {
        if (!Settings.canDrawOverlays(this)) {
            // vc115：原来静默 return，用户按了组合键没任何反馈、无法自查
            try { android.widget.Toast.makeText(this, "先给路远开「显示在其他应用上层」权限，组合键录音才生效", android.widget.Toast.LENGTH_LONG).show() } catch (_: Throwable) { }
            return
        }
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("auto", "record")
        }
        try {
            startActivity(i)
        } catch (_: Exception) {
        }
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
