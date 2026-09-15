package com.luyuan.platform

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.luyuan.R

/**
 * 桌面小部件「记一笔」的真输入框：点组件弹出**悬浮速记条 v2**（不打开 App），
 * 在桌面上直接打字、回车即存（SYNC_FORMAT 直写共享目录：device=phone, source=manual），
 * 存完自动收起。需要「显示在其他应用上层」权限。
 * v2 换皮（search-and-overlay.html B，视觉规范；逻辑零动）：
 * 纸白 97% 全胶囊 + 深绿麦克风圆钮（34dp，点击进录音）+ ✕ 收起 + 贴屏顶部 + 阴影三级。
 */
class FloatingQuickNoteService : Service() {

    private var root: View? = null
    private var input: EditText? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            // 没悬浮权限：退回「打开 App 聚焦输入框」老路径，并提示去开
            try {
                startActivity(Intent(this, com.luyuan.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("auto", "note")
                })
                Toast.makeText(
                    this,
                    "开一次「显示在悬浮层」权限就能在桌面直接记（设置页有直达按钮）",
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (root != null) {
            input?.requestFocus() // 已在显示：聚焦回来即可
            return START_NOT_STICKY
        }
        showBubble()
        return START_NOT_STICKY
    }

    private fun showBubble() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density

        // 输入区：无底无框，胶囊本身当背景
        val input = EditText(this).apply {
            hint = "记一笔，回车即存…"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = null
            setTextColor(Color.parseColor("#1F2937"))
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setOnEditorActionListener { v, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                        event.action == MotionEvent.ACTION_DOWN)
                ) {
                    saveAndClose(v.text.toString())
                    true
                } else {
                    false
                }
            }
        }
        this.input = input

        // 深绿麦克风圆钮（34dp）：点击收起速记条并进录音页
        val mic = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic_white)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#224A3A"))
            }
            val pad = (8 * d).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener {
                try {
                    startActivity(Intent(this@FloatingQuickNoteService, com.luyuan.MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("auto", "record")
                    })
                } catch (_: Exception) {
                }
                close()
            }
        }

        // ✕ 收起小圆钮（26dp）
        val close = android.widget.TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 11f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F3F1E9"))
            }
            setOnClickListener { close() }
        }

        // 胶囊主体：纸白 97%、全圆角（999）、三级阴影
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (9 * d).toInt(), (9 * d).toInt(), (9 * d).toInt())
            background = GradientDrawable().apply {
                setColor(0xF7FFFFFF.toInt()) // 纸白 97%
                cornerRadius = 999f * d
            }
            elevation = 14 * d
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val micLp = LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt())
            micLp.marginStart = (8 * d).toInt()
            addView(mic, micLp)
            val closeLp = LinearLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt())
            closeLp.marginStart = (8 * d).toInt()
            addView(close, closeLp)
            // 触摸悬浮条以外的屏幕区域 = 收起
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_OUTSIDE) {
                    close()
                    true
                } else {
                    false
                }
            }
        }

        // 外层只负责留边（贴屏顶部，左右 10dp、上 14dp），胶囊自身收窄居中
        val wrap = FrameLayout(this).apply {
            setPadding((10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt(), 0)
            addView(pill)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, // 默认可聚焦：能拿输入、能弹键盘
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            wm.addView(wrap, params)
            this.root = wrap
            // 弹键盘：先常规请求，300ms 后没拿到窗口焦点再强制拉一次
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            input.post {
                input.requestFocus()
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
            input.postDelayed({
                if (wrap.isAttachedToWindow && !input.hasWindowFocus()) {
                    imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
                }
            }, 350)
        } catch (e: Exception) {
            Toast.makeText(this, "速记条启动失败：${e.message}", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun saveAndClose(raw: String) {
        val text = raw.trim()
        if (text.isNotEmpty()) {
            try {
                com.luyuan.data.NoteRepository.createManual(this, text)
                Toast.makeText(this, "已记下", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        close()
    }

    private fun close() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            input?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
            root?.let { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) }
        } catch (_: Exception) {
        }
        root = null
        input = null
        stopSelf()
    }
}
