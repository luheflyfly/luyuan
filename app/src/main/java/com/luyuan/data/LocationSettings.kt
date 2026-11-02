package com.luyuan.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 位置上报 + 收件箱的本地设置（北极星与手机之间的那根线）。
 *
 * **为什么默认 8787 不是 8777**：8777 眼下被线上旧系统（`D:\Luyuan\main.py`）占着，
 * 那条链路没有 `/api/location`；北极星大脑这一版起在 8787（`python -m luyuan --port 8787`）。
 * 等 REFACTOR.md 的「切流」把 8777 交给新包之后，在设置页「位置共享 → 接收地址」改回
 * `100.122.162.59:8777` 即可，**不用重装**。
 *
 * 2026-11-02 重写说明：原文件被一次错误的批量替换清空（0 字节），
 * 按编译产物 `LocationSettings.class` 的 javap 签名 + 字节码里的常量逐项重建，
 * 接口与 prefs key 完全对齐（`location_prefs` / enabled / base / last_ok_ms / last_lat /
 * last_lon / last_err / sent_count / pending），并修掉 `apiBase` 的字符串模板 bug（见下）。
 */
object LocationSettings {

    private const val PREFS = "location_prefs"

    /** 北极星大脑的默认地址（PC 的 Tailscale IP）。 */
    const val DEFAULT_BASE = "100.122.162.59:8787"

    /** 上报间隔：5 分钟。 */
    const val INTERVAL_MS = 300_000L

    /** 位移超过这么多米就立刻报一次（不必等满 5 分钟）。 */
    const val MOVED_METERS = 100.0

    /**
     * 用户可能把整条端点填进来（`…/api/location`、`…/api/inbox/pending`），
     * 统一剥到 `host:port`，各接口自己拼路径 —— 位置上报和收件箱共用同一个 baseUrl。
     */
    private val ENDPOINT_SUFFIXES = arrayOf(
        "/api/location",
        "/api/inbox/pending",
        "/api/inbox/answer",
        "/api/inbox",
    )

    private fun p(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── 开关 ──────────────────────────────────────────────

    /** 位置共享总开关（默认开）。关掉 = 不再上报、也不再拉收件箱。 */
    fun enabled(ctx: Context): Boolean = p(ctx).getBoolean("enabled", true)

    fun setEnabled(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("enabled", on).apply()
    }

    // ── 地址 ──────────────────────────────────────────────

    fun baseUrl(ctx: Context): String =
        p(ctx).getString("base", DEFAULT_BASE) ?: DEFAULT_BASE

    fun setBaseUrl(ctx: Context, raw: String) {
        p(ctx).edit().putString("base", raw.trim()).apply()
    }

    /**
     * 真正的 `http://host:port` 前缀（无尾斜杠）。
     *
     * ⚠️ 2026-11-02 修掉的坑：原来写的是 `"http://$b.trimEnd('/')"`。
     * Kotlin 的 `$b` **只吃变量名**，后面的 `.trimEnd('/')` 会被当成**字面量**拼进去，
     * 结果得到 `http://100.122.162.59:8787.trimEnd('/')` —— 解析端口时抛
     * `MalformedURLException: For input string: "8787.trimEnd('"`，
     * 而且上报/拉取都是**静默失败**（只在 prefs 的 last_err 里留一行）。
     * 必须用花括号：`"http://${b.trimEnd('/')}"`。
     */
    fun apiBase(ctx: Context): String {
        var b = baseUrl(ctx).trim()
        if (b.startsWith("https://", ignoreCase = true)) b = b.substring(8)
        else if (b.startsWith("http://", ignoreCase = true)) b = b.substring(7)
        b = b.trimEnd('/')
        for (suffix in ENDPOINT_SUFFIXES) {
            if (b.endsWith(suffix)) {
                b = b.removeSuffix(suffix)
                break
            }
        }
        val hostPort = b.trimEnd('/')
        return "http://${hostPort}"
    }

    /** 位置上报的完整 URL。 */
    fun endpoint(ctx: Context): String = apiBase(ctx) + "/api/location"

    /** 别处（收件箱）拼自己的路径时走这个，避免各自再写一遍剥前缀的逻辑。 */
    fun apiUrl(ctx: Context, path: String): String {
        val q = if (path.startsWith("/")) path else "/$path"
        return apiBase(ctx) + q
    }

    // ── 上报状态（设置页展示用，不打扰用户） ────────────────

    fun lastOkMs(ctx: Context): Long = p(ctx).getLong("last_ok_ms", 0L)

    fun lastOkLat(ctx: Context): Float = p(ctx).getFloat("last_lat", 0f)

    fun lastOkLon(ctx: Context): Float = p(ctx).getFloat("last_lon", 0f)

    fun lastErr(ctx: Context): String = p(ctx).getString("last_err", "") ?: ""

    fun sentCount(ctx: Context): Int = p(ctx).getInt("sent_count", 0)

    /** 成功上报一次：记时间/坐标、计数 +1、清掉上次的错误。 */
    fun noteSuccess(ctx: Context, lat: Double, lon: Double, ts: Long) {
        p(ctx).edit()
            .putLong("last_ok_ms", ts)
            .putFloat("last_lat", lat.toFloat())
            .putFloat("last_lon", lon.toFloat())
            .putInt("sent_count", sentCount(ctx) + 1)
            .remove("last_err")
            .apply()
    }

    /** 失败只留一行字（断网是常态，不弹窗）。 */
    fun noteFailure(ctx: Context, msg: String) {
        p(ctx).edit().putString("last_err", msg.take(200)).apply()
    }

    // ── 待发队列（断网时留一条，下次心跳补发） ──────────────

    fun pendingBody(ctx: Context): String = p(ctx).getString("pending", "") ?: ""

    fun setPendingBody(ctx: Context, body: String) {
        p(ctx).edit().putString("pending", body).apply()
    }

    fun clearPending(ctx: Context) {
        p(ctx).edit().remove("pending").apply()
    }
}
