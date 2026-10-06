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
 * vc121（2026-10-06 路河拍板"都做"）：胶囊点按=展开面板（紧凑/面板两形态，中档第一枪）。
 * - 紧凑路径原样保留（vc111 双机验证过的"整窗拆掉重挂"活路，不动）。
 * - 展开窗走 island-demo 验证过的姿势（2026-10-06 实测：定宽 EXACTLY + 显式 measure AT_MOST
 *   不塌、连切 20 次零泄漏；旧案真因=Activity 重建致窗引用孤儿化，IslandManager 是 object
 *   常驻单例天然免疫，但失同步守卫照样上）。
 * - 点胶囊=展开面板（原"直接开 App"退役）；面板内"打开App"键补跳转；点头部/收起键回紧凑。
 * - 展开态不自动隐藏（用户在读）；紧凑态自动隐藏节奏不变。录音控制键（暂停/停止）涉录音线
 *   领地，本批不做，面板只读+跳转。
 *
 * 设计约束（继承 vc111）：
 * - 无 Service：直接 WindowManager 加窗，事件源自带上下文进来。
 * - 原生 View 不上 Compose；进程死窗随进程消失；任何异常吞掉，绝不炸主链路。
 */
object IslandManager {

    private val main = Handler(Looper.getMainLooper())

    private var appCtx: Context? = null
    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var added = false

    private enum class Mode { RECORDING, TODO, REMINDER, BRIEF }

    private var mode: Mode? = null

    // vc121 展开态
    private var expanded = false
    private var expandedRoot: LinearLayout? = null
    private var expandedAdded = false
    private var expLabel: TextView? = null

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

    // ---------- 事件入口（外部只认这五个 + offNow） ----------

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
        if (expanded) {
            // 展开态来了新事件：面板整窗重挂（首布局稳定路径），不自动隐藏
            removeExpandedNow()
            if (!ensureExpandedWindow()) { collapseToCompact(0L); return }
            rebuildExpanded()
            pop(expandedRoot)
            return
        }
        // 本机实测（MagicOS/OriginOS 2026-09-28）：悬浮窗原地换内容必塌宽（连计时器刷文字
        // 都可能触发），唯一稳定路径=新挂窗的第一次布局。紧凑形态一切切换都整窗拆掉重挂。
        removeWindowNow()
        if (!ensureWindow()) return
        rebuild()
        pop(root)
        if (autoHideMs > 0L) {
            val run = Runnable { try { if (mode == m && !expanded) hideNow() } catch (_: Throwable) { } }
            hider = run
            main.postDelayed(run, autoHideMs)
        }
    }

    /** vc121：点紧凑胶囊=展开面板（原"直接开 App"退役，跳转入面板键） */
    private fun expand() {
        try {
            val m = mode ?: return
            expanded = true
            show(m, 0L)
        } catch (e: Throwable) { log("expand threw: $e") }
    }

    /** vc121：回紧凑。自动隐藏类事件回紧凑后重新计时 */
    private fun collapseToCompact(autoHideMs: Long) {
        expanded = false
        expLabel = null
        removeExpandedNow()
        val m = mode ?: return
        show(m, autoHideMs)
    }

    /** vc121：彻底收起（面板与胶囊都收） */
    private fun removeExpandedNow() {
        val box = expandedRoot ?: return
        expandedRoot = null; expandedAdded = false; expLabel = null
        try { wm?.removeView(box); log("expanded removed") } catch (e: Throwable) { log("removeExpanded threw: $e") }
    }

    /** 点胶囊=展开面板（紧凑/面板两形态，vc121）。老注释：展开态测量坑已由 island-demo 姿势破局 */
    private fun removeWindowNow() {
        val box = root ?: return
        root = null; added = false; pillText = null
        try { wm?.removeView(box); log("window removed") } catch (e: Throwable) { log("removeView threw: $e") }
    }

    /** 加窗。悬浮窗权限没授/系统拒绝=返回 false，胶囊整体静默。 */
    private fun ensureWindow(): Boolean {
        val c = appCtx ?: return false
        val w = wm ?: return false
        // 失同步守卫（island-demo 实证姿势）：引用还在但窗已不在 → 重置引用重新挂
        if (added && root?.windowToken == null) { root = null; added = false; pillText = null }
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

    /** vc121 展开窗：定宽（demo 验证姿势）+ 显式 measure，杜绝 wrap_content 自由发挥 */
    private fun ensureExpandedWindow(): Boolean {
        val c = appCtx ?: return false
        val w = wm ?: return false
        if (expandedAdded && expandedRoot?.windowToken == null) { expandedRoot = null; expandedAdded = false; expLabel = null }
        if (expandedAdded && expandedRoot != null) return true
        val canDraw = try { Settings.canDrawOverlays(c) } catch (_: Throwable) { false }
        if (!canDraw) return false
        val box = LinearLayout(c)
        box.orientation = LinearLayout.VERTICAL
        val widthPx = dip(300f)
        val lp = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = statusBarPx(c) + dip(2f)
        return try {
            // 显式 measure：宽 EXACTLY 定值、高 AT_MOST 可用屏高（island-demo 验证不塌的关键一手）
            val availH = c.resources.displayMetrics.heightPixels - statusBarPx(c) - dip(24f)
            box.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(availH, View.MeasureSpec.AT_MOST)
            )
            w.addView(box, lp)
            expandedRoot = box; expandedAdded = true
            true
        } catch (_: Throwable) {
            expandedRoot = null; expandedAdded = false
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

    /** vc121 展开面板：标题行（叶标+标题+收起）+ 正文全文 + 跳转键 */
    private fun rebuildExpanded() {
        val c = appCtx ?: return
        val box = expandedRoot ?: return
        box.removeAllViews()
        expLabel = null
        try {
            val panel = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                background = panelBg()
                setPadding(dip(16f), dip(14f), dip(16f), dip(14f))
            }
            data class Content(val title: String, val body: String, val action: String?, val page: String?)
            val content = when (mode) {
                Mode.RECORDING -> Content(recLabel(), "", null, null)
                Mode.TODO -> Content("待办已收录 · $todoWho", todoBody, "打开待办页", "todos")
                Mode.REMINDER -> Content(remTitle, remBody, "查看详情", null)
                Mode.BRIEF -> Content("晨间简报", briefBody, "打开笔记", "notes")
                null -> return
            }
            // 标题行：叶标 + 标题(占满) + 收起
            val head = hrow(c).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(leaf(c, dip(24f)))
            head.addView(gap(8))
            val title = label(c, content.title, bold = true)
            title.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            head.addView(title)
            head.addView(closeChip(c))
            panel.addView(head)
            // 正文（录音态=计时大字，其余=全文多行）
            if (content.body.isNotEmpty()) {
                panel.addView(gap(8))
                panel.addView(bodyLabel(c, content.body))
            }
            // 录音态：大号计时行（ticker 同步刷）
            if (mode == Mode.RECORDING) {
                panel.addView(gap(6))
                val t = TextView(c).apply {
                    text = recLabel()
                    setTextColor(Color.WHITE)
                    textSize = 26f
                    typeface = Typeface.DEFAULT_BOLD
                }
                expLabel = t
                panel.addView(t)
            }
            // 跳转键
            content.action?.let { act ->
                panel.addView(gap(4))
                panel.addView(actionChip(c, act) {
                    when (mode) {
                        Mode.REMINDER -> openApp(remDetailId)
                        else -> openApp(null, content.page)
                    }
                })
            }
            box.addView(panel)
        } catch (e: Throwable) {
            log("rebuildExpanded THREW mode=$mode: $e")
            for (s in e.stackTrace.take(8)) log("  at $s")
        }
        log("rebuildExpanded mode=$mode childCount=${box.childCount}")
    }

    private fun log(m: String) {
        try { android.util.Log.d("IslandMgr", m) } catch (_: Throwable) { }
    }

    private fun hideNow() {
        unpost(hider); unpost(ticker)
        hider = null; ticker = null
        mode = null; pillText = null
        expanded = false; expLabel = null
        // vc121：两形态一起收（面板在场时先摘面板）
        removeExpandedNow()
        val box = root ?: return
        root = null; added = false
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

    // ---------- 各形态（紧凑） ----------

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
            try { expand() } catch (_: Throwable) { }
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
            try { expand() } catch (_: Throwable) { }
        }
        box.addView(row)
    }

    // ---------- 小件 ----------

    private fun startTicker() {
        val run = object : Runnable {
            override fun run() {
                try {
                    if (!recording || mode != Mode.RECORDING) return
                    val text = recLabel()
                    pillText?.text = text
                    if (expanded) expLabel?.text = text   // vc121 面板里的计时行同步刷
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

    private fun panelBg(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#F20B0B0B"))
        cornerRadius = dip(20f).toFloat()
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

    private fun bodyLabel(c: Context, text: String): TextView = TextView(c).apply {
        this.text = text
        setTextColor(Color.parseColor("#FFD9E2DC"))
        textSize = 13.5f
        maxLines = 6
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(dip(2f).toFloat(), 1f)
    }

    private fun actionChip(c: Context, text: String, onClick: () -> Unit): TextView = TextView(c).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#33224A3A"))
            cornerRadius = dip(999f).toFloat()
            setStroke(dip(1f), Color.parseColor("#66224A3A"))
        }
        setPadding(dip(14f), dip(7f), dip(14f), dip(7f))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dip(10f) }
        setOnClickListener { try { onClick() } catch (_: Throwable) { } }
    }

    private fun closeChip(c: Context): TextView = TextView(c).apply {
        this.text = "收起"
        setTextColor(Color.parseColor("#8FA79B"))
        textSize = 12.5f
        setPadding(dip(8f), dip(4f), dip(8f), dip(4f))
        setOnClickListener { try { collapseToCompact(if (mode == Mode.RECORDING) 0L else 6000L) } catch (_: Throwable) { } }
    }

    private fun hrow(c: Context): LinearLayout =
        LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }

    private fun gap(dp: Int): View = View(appCtx!!).apply {
        layoutParams = LinearLayout.LayoutParams(dip(dp.toFloat()), 1)
    }

    private fun pop(r: LinearLayout?) {
        val target = r ?: return
        target.translationY = -dip(14f).toFloat(); target.alpha = 0.4f
        target.animate().translationY(0f).alpha(1f).setDuration(150L).start()
    }

    private fun dip(v: Float): Int {
        val d = appCtx?.resources?.displayMetrics?.density ?: 2.2f
        return (v * d + 0.5f).toInt()
    }
}
