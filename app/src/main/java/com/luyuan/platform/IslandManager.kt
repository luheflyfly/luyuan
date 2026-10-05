package com.luyuan.platform

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.luyuan.MainActivity
import com.luyuan.data.IslandSettings

/**
 * vc111 假灵动岛（2026-09-28 路河派单）：黑色悬浮胶囊贴摄像头挖孔，轻提示三类事件——
 * 录音计时（常驻，LuyuanService 驱动）/ 待办收录（可直操已完成/不要）/ 提醒与简报（可跳 App）。
 *
 * 设计约束：
 * - 无 Service：直接 WindowManager 加窗。悬浮窗权限（SYSTEM_ALERT_WINDOW）没授就加不上，
 *   静默不显示（平板没授权=自动没有）；事件源（监听器/Receiver/前台服务）各自带上下文进来，
 *   避开"后台不能 startService"的系统限制。
 * - 原生 View 不上 Compose：悬浮窗没有 lifecycle owner，原生件最稳、不依赖 App 界面存活。
 * - 进程被整体杀掉时悬浮窗随进程自动消失，不会留鬼影。
 * - 任何异常一律吞掉：胶囊只是提示层，绝不因为它炸到录音/待办主链路。
 */
object IslandManager {

    private val main = Handler(Looper.getMainLooper())

    private var appCtx: Context? = null
    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var added = false

    private enum class Mode { RECORDING, TODO, REMINDER, BRIEF }

    private var mode: Mode? = null

    // 录音态（LuyuanService 驱动；数据只在主线程读写）
    private var recording = false
    private var recordPaused = false
    private var recordStartMs = 0L
    private var pausedAtMs = 0L

    // 瞬态事件数据
    private var todoWho = ""
    private var todoBody = ""
    private var todoAutoAdded = true
    private var todoPendingId: String? = null
    private var todoId: String? = null
    private var remTitle = ""
    private var remBody = ""
    private var remDetailId: String? = null
    private var briefBody = ""

    private var pillText: TextView? = null
    private var hider: Runnable? = null
    private var ticker: Runnable? = null

    // ---------- 事件入口（外部只认这五个） ----------

    fun recordingStart(ctx: Context) {
        prep(ctx)
        main.post {
            try {
                if (!IslandSettings.enabled(appCtx!!)) return@post
                recording = true; recordPaused = false; pausedAtMs = 0L
                recordStartMs = System.currentTimeMillis()
                show(Mode.RECORDING, 0L)
            } catch (_: Throwable) { }
        }
    }

    fun recordingPause() {
        main.post {
            try {
                if (recording && !recordPaused) {
                    recordPaused = true; pausedAtMs = System.currentTimeMillis()
                    show(Mode.RECORDING, 0L)
                }
            } catch (_: Throwable) { }
        }
    }

    fun recordingResume() {
        main.post {
            try {
                if (recording && recordPaused) {
                    recordPaused = false
                    recordStartMs += System.currentTimeMillis() - pausedAtMs
                    pausedAtMs = 0L
                    show(Mode.RECORDING, 0L)
                }
            } catch (_: Throwable) { }
        }
    }

    fun recordingStop() {
        main.post {
            try {
                recording = false
                if (mode == Mode.RECORDING) hideNow()
            } catch (_: Throwable) { }
        }
    }

    /** vc112：设置里拨 off 立即收起已显示的胶囊（热刷新反馈，路河 2026-09-28 体验反馈） */
    fun offNow() {
        main.post {
            try {
                if (!IslandSettings.enabled(appCtx!!)) hideNow()
            } catch (_: Throwable) { }
        }
    }

    /** 待办收录。autoAdded=true=已入库（todoId 非空，胶囊键直操正式库）；false=待确认存量（三键老路径） */
    fun todoCaptured(ctx: Context, who: String, text: String, autoAdded: Boolean, pendingId: String?, todoId: String?) {
        prep(ctx)
        main.post {
            try {
                if (!IslandSettings.enabled(appCtx!!) || recording) return@post
                todoWho = who; todoBody = text
                todoAutoAdded = autoAdded; todoPendingId = pendingId
                this@IslandManager.todoId = todoId   // 参数遮蔽单例字段，必须限定 this
                show(Mode.TODO, 6000L)
            } catch (_: Throwable) { }
        }
    }

    fun reminder(ctx: Context, title: String, body: String, detailId: String?) {
        prep(ctx)
        main.post {
            try {
                if (!IslandSettings.enabled(appCtx!!) || recording) return@post
                remTitle = title; remBody = body; remDetailId = detailId
                show(Mode.REMINDER, 8000L)
            } catch (_: Throwable) { }
        }
    }

    fun brief(ctx: Context, body: String) {
        prep(ctx)
        main.post {
            try {
                if (!IslandSettings.enabled(appCtx!!) || recording) return@post
                briefBody = body
                show(Mode.BRIEF, 8000L)
            } catch (_: Throwable) { }
        }
    }

    // ---------- 骨架 ----------

    private fun prep(ctx: Context) {
        if (appCtx == null) {
            appCtx = ctx.applicationContext
            wm = appCtx!!.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }
    }

    private fun show(m: Mode, autoHideMs: Long) {
        mode = m
        unpost(hider); unpost(ticker)
        hider = null; ticker = null
        // 本机实测（MagicOS/OriginOS 2026-09-28）：悬浮窗原地换内容必塌宽（连计时器刷文字
        // 都可能触发），唯一稳定路径=新挂窗的第一次布局。所以一切形态切换都整窗拆掉重挂。
        removeWindowNow()
        if (!ensureWindow()) return
        rebuild()
        pop()
        if (autoHideMs > 0L) {
            val run = Runnable { try { if (mode == m) hideNow() } catch (_: Throwable) { } }
            hider = run
            main.postDelayed(run, autoHideMs)
        }
    }

    /** 点胶囊=直接开 App（展开态在本机悬浮窗测量有系统级坑，2026-09-28 拍板砍掉，下批再战） */
    private fun removeWindowNow() {
        val box = root ?: return
        root = null; added = false; pillText = null
        try { wm?.removeView(box); log("window removed") } catch (e: Throwable) { log("removeView threw: $e") }
    }

    /** 加窗。悬浮窗权限没授/系统拒绝=返回 false，胶囊整体静默。 */
    private fun ensureWindow(): Boolean {
        val c = appCtx ?: return false
        val w = wm ?: return false
        if (added && root != null) return true
        val canDraw = try { Settings.canDrawOverlays(c) } catch (_: Throwable) { false }
        if (!canDraw) return false
        val box = LinearLayout(c)
        box.orientation = LinearLayout.VERTICAL
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        // 定位在状态栏下方，不进摄像头挖孔保护区——真机实锤（2026-09-28 平板横屏）：
        // 窗口压住挖孔区会被系统钳成挖孔旁的保护形状（恒定 71x262 细柱，内容怎么改都一样），
        // 且系统钳制时机不定（先正常后突变）。让开挖孔 = 稳定显示。
        lp.y = statusBarPx(c) + dip(2f)
        return try {
            w.addView(box, lp)
            root = box; added = true
            true
        } catch (_: Throwable) {
            root = null; added = false
            false
        }
    }

    /** 系统状态栏高度（读系统 dimen；取不到就给个保守值） */
    private fun statusBarPx(c: Context): Int = try {
        val id = c.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) c.resources.getDimensionPixelSize(id) else dip(28f)
    } catch (_: Throwable) {
        dip(28f)
    }

    private fun rebuild() {
        val c = appCtx ?: return
        val box = root ?: return
        box.removeAllViews()
        pillText = null
        try {
            when (mode) {
                Mode.RECORDING -> recCompact(c, box)
                Mode.TODO -> textCompact(c, box, todoBody)
                Mode.REMINDER -> textCompact(c, box, remBody)
                Mode.BRIEF -> textCompact(c, box, briefBody)
                null -> Unit
            }
        } catch (e: Throwable) {
            log("rebuild THREW mode=$mode: $e")
            for (s in e.stackTrace.take(8)) log("  at $s")
        }
        log("rebuilt mode=$mode childCount=${box.childCount}")
    }

    private fun log(m: String) {
        try { android.util.Log.d("IslandMgr", m) } catch (_: Throwable) { }
    }

    private fun hideNow() {
        unpost(hider); unpost(ticker)
        hider = null; ticker = null
        val box = root ?: return
        root = null; added = false
        mode = null; pillText = null
        try {
            box.animate().translationY(-dip(20f).toFloat()).alpha(0f).setDuration(160L)
                .withEndAction { try { wm?.removeView(box) } catch (_: Throwable) { } }
                .start()
        } catch (_: Throwable) {
            try { wm?.removeView(box) } catch (_: Throwable) { }
        }
    }

    private fun unpost(r: Runnable?) {
        if (r != null) main.removeCallbacks(r)
    }

    // ---------- 各形态 ----------

    private fun recCompact(c: Context, box: LinearLayout) {
        val row = hrow(c)
        row.background = pillBg()
        row.setPadding(dip(14f), dip(12f), dip(16f), dip(12f))
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(dot(c))
        row.addView(gap(8))
        val tv = label(c, recLabel(), bold = true)
        pillText = tv
        row.addView(tv)
        row.setOnClickListener { v ->
            try { openApp(null) } catch (_: Throwable) { }
        }
        box.addView(row)
        startTicker()
    }

    private fun textCompact(c: Context, box: LinearLayout, body: String) {
        val row = hrow(c)
        row.background = pillBg()
        row.setPadding(dip(11f), dip(12f), dip(15f), dip(12f))
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(leaf(c, dip(26f)))
        row.addView(gap(8))
        val tv = label(c, body.replace("\n", " "), bold = false)
        tv.maxWidth = dip(250f)
        pillText = tv
        row.addView(tv)
        row.setOnClickListener { v ->
            try {
                when (mode) {
                    Mode.REMINDER -> openApp(remDetailId)
                    Mode.TODO -> openApp(null, "todos")
                    Mode.BRIEF -> openApp(null, "notes")
                    else -> openApp(null)
                }
            } catch (_: Throwable) { }
        }
        box.addView(row)
    }

    // ---------- 小件 ----------

    private fun startTicker() {
        val run = object : Runnable {
            override fun run() {
                try {
                    if (!recording || mode != Mode.RECORDING) return
                    pillText?.text = recLabel()
                } catch (_: Throwable) { }
                main.postDelayed(this, 500L)
            }
        }
        ticker = run
        main.postDelayed(run, 500L)
    }

    private fun recLabel(): String {
        val nowMs = if (recordPaused && pausedAtMs > 0L) pausedAtMs else System.currentTimeMillis()
        val s = ((nowMs - recordStartMs) / 1000L).toInt().coerceAtLeast(0)
        return (if (recordPaused) "已暂停 " else "录音中 ") + String.format("%02d:%02d", s / 60, s % 60)
    }

    private fun openApp(detailId: String?, page: String? = null) {
        val c = appCtx ?: return
        try {
            val i = c.packageManager.getLaunchIntentForPackage(c.packageName)
                ?: Intent(c, MainActivity::class.java)
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (detailId != null) i.putExtra("detailId", detailId)
            if (page != null) i.putExtra("page", page)
            c.startActivity(i)
        } catch (_: Throwable) { }
        hideNow()
    }

    private fun pillBg(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#F20B0B0B"))
        cornerRadius = dip(999f).toFloat()
    }

    private fun dot(c: Context): View = View(c).apply {
        layoutParams = LinearLayout.LayoutParams(dip(9f), dip(9f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#FFFF453A"))
        }
    }

    private fun leaf(c: Context, sizePx: Int): ImageView = ImageView(c).apply {
        layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
        try { setImageBitmap(NotiStyle.brandLargeIcon(c)) } catch (_: Throwable) { }
    }

    private fun label(c: Context, text: String, bold: Boolean): TextView = TextView(c).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 15f
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun hrow(c: Context): LinearLayout =
        LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }

    private fun gap(dp: Int): View = View(appCtx!!).apply {
        layoutParams = LinearLayout.LayoutParams(dip(dp.toFloat()), 1)
    }

    private fun pop() {
        val r = root ?: return
        r.translationY = -dip(14f).toFloat(); r.alpha = 0.4f
        r.animate().translationY(0f).alpha(1f).setDuration(150L).start()
    }

    private fun dip(v: Float): Int {
        val d = appCtx?.resources?.displayMetrics?.density ?: 2.2f
        return (v * d + 0.5f).toInt()
    }
}
