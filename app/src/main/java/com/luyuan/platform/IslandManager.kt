package com.luyuan.platform

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
    private var expanded = false

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
        mode = m; expanded = false
        unpost(hider); unpost(ticker)
        hider = null; ticker = null
        if (!ensureWindow()) return
        rebuild()
        pop()
        if (autoHideMs > 0L) {
            val run = Runnable { try { if (mode == m) hideNow() } catch (_: Throwable) { } }
            hider = run
            main.postDelayed(run, autoHideMs)
        }
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
        lp.y = dip(6f)
        // 贴挖孔：允许窗体伸进刘海/状态栏带（API 28+ 字段；低版本设备本就加不上窗，静默）
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return try {
            w.addView(box, lp)
            root = box; added = true
            true
        } catch (_: Throwable) {
            root = null; added = false
            false
        }
    }

    private fun rebuild() {
        val c = appCtx ?: return
        val box = root ?: return
        box.removeAllViews()
        pillText = null
        when (mode) {
            Mode.RECORDING -> if (expanded) recExpanded(c, box) else recCompact(c, box)
            Mode.TODO -> if (expanded) todoExpanded(c, box) else textCompact(c, box, todoBody)
            Mode.REMINDER -> if (expanded) remExpanded(c, box) else textCompact(c, box, remBody)
            Mode.BRIEF -> if (expanded) briefExpanded(c, box) else textCompact(c, box, briefBody)
            null -> Unit
        }
        // 悬浮窗换内容后强制重排：WRAP_CONTENT 窗口在部分系统上不会按新内容自动重测，
        // 会塌成最小宽一条（2026-09-28 平板真机实锤），updateViewLayout 强制走一遍测量
        try { wm?.updateViewLayout(box, box.layoutParams) } catch (_: Throwable) { }
    }

    private fun hideNow() {
        unpost(hider); unpost(ticker)
        hider = null; ticker = null
        val box = root ?: return
        root = null; added = false
        expanded = false; mode = null; pillText = null
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
        row.setPadding(dip(13f), dip(7f), dip(15f), dip(7f))
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(dot(c))
        row.addView(gap(7))
        val tv = label(c, recLabel(), bold = true)
        pillText = tv
        row.addView(tv)
        row.setOnClickListener { v ->
            try { expanded = true; rebuild(); pop() } catch (_: Throwable) { }
        }
        box.addView(row)
        startTicker()
    }

    private fun recExpanded(c: Context, box: LinearLayout) {
        val card = vcol(c)
        card.background = cardBg()
        card.setPadding(dip(14f), dip(12f), dip(14f), dip(12f))
        val head = hrow(c); head.gravity = Gravity.CENTER_VERTICAL
        head.addView(dot(c)); head.addView(gap(8))
        head.addView(label(c, if (recordPaused) "录音已暂停" else "路远正在录音", bold = true))
        card.addView(head)
        card.addView(gapV(6))
        card.addView(sub(c, "停止录音在 App 里；点空白处收起胶囊"))
        card.addView(gapV(10))
        val btns = hrow(c)
        btns.addView(button(c, "打开路远", BTN_GREEN) { v -> openApp(null) })
        card.addView(btns)
        card.setOnClickListener { v ->
            try { expanded = false; rebuild(); pop() } catch (_: Throwable) { }
        }
        box.addView(card)
    }

    private fun textCompact(c: Context, box: LinearLayout, body: String) {
        val row = hrow(c)
        row.background = pillBg()
        row.setPadding(dip(10f), dip(7f), dip(14f), dip(7f))
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(leaf(c, dip(22f)))
        row.addView(gap(8))
        val tv = label(c, body.replace("\n", " "), bold = false)
        tv.maxWidth = dip(250f)
        pillText = tv
        row.addView(tv)
        row.setOnClickListener { v ->
            try { expanded = true; rebuild(); pop() } catch (_: Throwable) { }
        }
        box.addView(row)
    }

    private fun todoExpanded(c: Context, box: LinearLayout) {
        val card = vcol(c)
        card.background = cardBg()
        card.setPadding(dip(14f), dip(12f), dip(14f), dip(12f))
        val head = hrow(c); head.gravity = Gravity.CENTER_VERTICAL
        head.addView(leaf(c, dip(20f))); head.addView(gap(8))
        head.addView(label(c, "待办已收录 · $todoWho", bold = true))
        card.addView(head)
        card.addView(gapV(6))
        card.addView(sub(c, todoBody))
        card.addView(gapV(10))
        val btns = hrow(c)
        btns.addView(button(c, "已完成", BTN_GREEN) { v -> act(TodoActionReceiver.ACTION_DONE) })
        btns.addView(gap(8))
        btns.addView(button(c, "不要", BTN_GRAY) { v -> act(TodoActionReceiver.ACTION_DROP) })
        if (!todoAutoAdded) {
            btns.addView(gap(8))
            btns.addView(button(c, "收下", BTN_GRAY) { v -> act(TodoActionReceiver.ACTION_KEEP) })
        }
        card.addView(btns)
        card.setOnClickListener { v ->
            try { expanded = false; rebuild(); pop() } catch (_: Throwable) { }
        }
        box.addView(card)
    }

    private fun remExpanded(c: Context, box: LinearLayout) {
        val card = vcol(c)
        card.background = cardBg()
        card.setPadding(dip(14f), dip(12f), dip(14f), dip(12f))
        val head = hrow(c); head.gravity = Gravity.CENTER_VERTICAL
        head.addView(leaf(c, dip(20f))); head.addView(gap(8))
        head.addView(label(c, remTitle, bold = true))
        card.addView(head)
        card.addView(gapV(6))
        card.addView(sub(c, remBody))
        card.addView(gapV(10))
        val btns = hrow(c)
        btns.addView(button(c, "打开", BTN_GREEN) { v -> openApp(remDetailId) })
        card.addView(btns)
        card.setOnClickListener { v ->
            try { expanded = false; rebuild(); pop() } catch (_: Throwable) { }
        }
        box.addView(card)
    }

    private fun briefExpanded(c: Context, box: LinearLayout) {
        val card = vcol(c)
        card.background = cardBg()
        card.setPadding(dip(14f), dip(12f), dip(14f), dip(12f))
        val head = hrow(c); head.gravity = Gravity.CENTER_VERTICAL
        head.addView(leaf(c, dip(20f))); head.addView(gap(8))
        head.addView(label(c, "今日简报", bold = true))
        card.addView(head)
        card.addView(gapV(6))
        card.addView(sub(c, briefBody))
        card.addView(gapV(10))
        val btns = hrow(c)
        btns.addView(button(c, "打开路远", BTN_GREEN) { v -> openApp(null) })
        card.addView(btns)
        card.setOnClickListener { v ->
            try { expanded = false; rebuild(); pop() } catch (_: Throwable) { }
        }
        box.addView(card)
    }

    // ---------- 小件 ----------

    private fun startTicker() {
        val run = object : Runnable {
            override fun run() {
                try {
                    if (!recording || mode != Mode.RECORDING || expanded) return
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

    private fun act(action: String) {
        val c = appCtx
        val pid = todoPendingId
        if (c != null && pid != null) {
            try {
                val i = Intent(action).setPackage(c.packageName)
                    .setClass(c, TodoActionReceiver::class.java)
                    .putExtra(TodoActionReceiver.EXTRA_ID, pid)
                if (todoAutoAdded && todoId != null) i.putExtra(TodoActionReceiver.EXTRA_TODO_ID, todoId)
                c.sendBroadcast(i)
            } catch (_: Throwable) { }
        }
        hideNow()
    }

    private fun openApp(detailId: String?) {
        val c = appCtx ?: return
        try {
            val i = c.packageManager.getLaunchIntentForPackage(c.packageName)
                ?: Intent(c, MainActivity::class.java)
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (detailId != null) i.putExtra("detailId", detailId)
            c.startActivity(i)
        } catch (_: Throwable) { }
        hideNow()
    }

    private fun pillBg(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#F20B0B0B"))
        cornerRadius = dip(999f).toFloat()
    }

    private fun cardBg(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#F20E0E0E"))
        cornerRadius = dip(22f).toFloat()
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
        textSize = 13f
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun sub(c: Context, text: String): TextView = TextView(c).apply {
        this.text = text
        setTextColor(Color.parseColor("#CCFFFFFF"))
        textSize = 13.5f
        maxLines = 5
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = dip(280f)
    }

    private fun button(c: Context, text: String, bg: String, onClick: (View) -> Unit): TextView =
        TextView(c).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bg))
                cornerRadius = dip(999f).toFloat()
            }
            setPadding(dip(14f), dip(6f), dip(14f), dip(6f))
            setOnClickListener { v -> try { onClick(v) } catch (_: Throwable) { } }
        }

    private fun hrow(c: Context): LinearLayout =
        LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }

    private fun vcol(c: Context): LinearLayout =
        LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    private fun gap(dp: Int): View = View(appCtx!!).apply {
        layoutParams = LinearLayout.LayoutParams(dip(dp.toFloat()), 1)
    }

    private fun gapV(dp: Int): View = View(appCtx!!).apply {
        layoutParams = LinearLayout.LayoutParams(1, dip(dp.toFloat()))
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

    // object 内不能嵌 companion（Kotlin 语法），常量直接放对象顶层
    private const val BTN_GREEN = "#FF2D5A48"
    private const val BTN_GRAY = "#26FFFFFF"
}
