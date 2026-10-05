package com.luyuan.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.luyuan.data.TodoStore
import com.luyuan.data.V2EntityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * vc113 扫描底稿：每张处理过的照片留一条（缩略图+认出的文字+结果），
 * 学业页待办签可查——漏认看得见、能救回，认字质量有据可调。只存本机 prefs（诊断数据，不同步）。
 */
@Serializable
data class ScanRecord(
    val timeMs: Long = 0,       // 照片拍摄时刻
    val file: String = "",      // 原片绝对路径（DCIM/Camera，不移动不复制）
    val course: String = "",    // 扫描时的课程窗口名
    val ocrHead: String = "",   // 认出文字前两行（列表摘要）
    val ocrFull: String = "",   // 认出全文（点开看，限 1200 字）
    val hit: Boolean = false,   // 是否建了待办
    val todoId: String = "",    // 建的待办 id（hit 时有值）
    val status: String = ""     // 已建待办 / 未认出作业 / 重复·同文待办已在 / 没读出来
)

/**
 * vc112 课堂照片自动转作业待办（路河派单：下课扫系统相机新照片，OCR 认字，作业落待办）。
 *
 * 流水线：找照片（DCIM/Camera，拍摄时间落在课堂窗口）→ ML Kit 中文 OCR（离线模型随包，不联网）
 * → 作业关键词分类 → 抽截止（X月X日 / 周X / 明天后天）→ TodoStore.createFromPhoto（自动带截止前1h提醒）。
 * 已处理照片记入 prefs 防重扫；同一照片重扫、跨课程重叠窗口都不会重复建待办。
 * 触发：LuyuanClock 每节课下课后 5 分钟闹钟（ClockReceiver）+ 学业页「待办」签手动补扫。
 *
 * 第一版定位（路河拍板「先做个简单的」）：只做作业待办，不做笔记/知识库；分类纯关键词，
 * 手写板书识别率一般（PPT/打印件好），认不出就静默跳过，不造垃圾数据。
 */
object PhotoScanner {

    data class Outcome(val photosSeen: Int, val todosCreated: Int, val detail: String)

    private const val PREFS = "photo_scan_prefs"
    private const val KEY_SEEN = "seen_files"
    private const val KEY_HISTORY = "scan_history"

    // ---------------- 扫描底稿（vc113） ----------------

    private val REC_LIST = kotlinx.serialization.builtins.ListSerializer(ScanRecord.serializer())

    private fun addRecord(context: Context, r: ScanRecord) {
        try {
            val cur = prefs(context).getString(KEY_HISTORY, null)
            val list: List<ScanRecord> = if (cur.isNullOrBlank()) emptyList()
            else v2Json.decodeFromString(REC_LIST, cur)
            val next = (list + r).takeLast(60)
            prefs(context).edit().putString(KEY_HISTORY, v2Json.encodeToString(REC_LIST, next)).apply()
        } catch (_: Exception) {
        }
    }

    /** 最近扫描底稿（新→旧），学业页待办签展示 */
    fun history(context: Context): List<ScanRecord> = try {
        val s = prefs(context).getString(KEY_HISTORY, null)
        if (s.isNullOrBlank()) emptyList()
        else v2Json.decodeFromString(REC_LIST, s).sortedByDescending { it.timeMs }
    } catch (_: Exception) {
        emptyList()
    }

    private fun head(text: String): String =
        text.split('\n').map { it.trim() }.filter { it.length >= 2 }.take(2).joinToString(" / ")
            .let { if (it.length > 80) it.take(80) + "…" else it }

    /** 作业判定关键词（命中任一即视为作业；宁可漏认不错收，第一批先验证识别质量） */
    private val HW_KEYWORDS = listOf(
        "作业", "习题", "练习", "提交", "截止", "布置", "上交", "交到", "收作业",
        "交作业", "前交", "前完成", "按时完成", "完成并", "课后完成", "书面"
    )

    private val IMG_EXT = setOf("jpg", "jpeg", "png", "heic", "webp")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- 照片发现 ----------------

    private fun cameraDir(): File {
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        return File(dcim, "Camera")
    }

    /** 相机目录全部照片（文件名 IMG_yyyyMMdd_HHmmss 优先，解析不出用文件修改时间） */
    private fun listCameraPhotos(): List<Pair<File, Long>> {
        val dir = cameraDir()
        val files = dir.listFiles() ?: return emptyList()
        val out = ArrayList<Pair<File, Long>>()
        for (f in files) {
            if (!f.isFile) continue
            if (f.extension.lowercase() !in IMG_EXT) continue
            out.add(f to photoEpochMs(f))
        }
        return out.sortedBy { it.second }
    }

    private fun photoEpochMs(f: File): Long {
        // IMG_20261002_093015.jpg / IMG_20261002_093015_*.jpg（vivo 分享/编辑后缀）
        val m = Regex("IMG_(\\d{8})_(\\d{6})").find(f.name)
        if (m != null) {
            try {
                val d = LocalDate.parse(m.groupValues[1], DateTimeFormatter.BASIC_ISO_DATE)
                val t = LocalTime.parse(m.groupValues[2], DateTimeFormatter.ofPattern("HHmmss"))
                return LocalDateTime.of(d, t).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {
            }
        }
        return f.lastModified()
    }

    // ---------------- OCR ----------------

    private fun exifDegrees(f: File): Int {
        return try {
            val exif = androidx.exifinterface.media.ExifInterface(f.absolutePath)
            when (exif.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            )) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
    }

    /** 降采样解码（长边 ~1536 足够板书/PPT 认字，省内存省电） */
    private fun decodeScaled(f: File): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opts)
            var sample = 1
            var maxSide = maxOf(opts.outWidth, opts.outHeight)
            while (maxSide / (sample * 2) >= 1536) sample *= 2
            val o = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(f.absolutePath, o)
        } catch (_: Exception) {
            null
        }
    }

    private fun ocrText(f: File): String? {
        val bmp = decodeScaled(f) ?: return null
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return try {
            val img = InputImage.fromBitmap(bmp, exifDegrees(f))
            val txt = Tasks.await(recognizer.process(img), 60, java.util.concurrent.TimeUnit.SECONDS)
            txt.text
        } catch (_: Exception) {
            null
        } finally {
            try { recognizer.close() } catch (_: Exception) {}
            bmp.recycle()
        }
    }

    // ---------------- 分类与截止抽取 ----------------

    private fun isHomework(text: String): Boolean = HW_KEYWORDS.any { text.contains(it) }

    private fun excerpt(text: String): String {
        val lines = text.split('\n').map { it.trim() }.filter { it.length >= 3 }
        val hits = lines.filter { l -> HW_KEYWORDS.any { l.contains(it) } }
        val pick = (if (hits.isNotEmpty()) hits else lines).take(3)
        return pick.joinToString("；") { if (it.length > 42) it.take(42) + "…" else it }
            .ifBlank { "" }
    }

    /** 截止抽取第一版：固定模式（X月X日 / 下周X / 周X / 星期X / 明天 / 后天 / 今天 / X号）。抽不到返回空对。 */
    private fun extractDue(text: String): Pair<String, String>? {
        val today = LocalDate.now()
        var date: LocalDate? = null
        var phrase = ""
        val mDate = Regex("(\\d{1,2})月(\\d{1,2})日").find(text)
        val mNextWeek = Regex("下(周|星期)([一二三四五六日天])").find(text)
        val mWeek = Regex("(周|星期)([一二三四五六日天])").find(text)
        val mRel = Regex("(明天|后天|今天)").find(text)
        val mDay = Regex("(\\d{1,2})[号日]").find(text)
        fun wdOf(c: Char): Int = when (c) {
            '一' -> 1; '二' -> 2; '三' -> 3; '四' -> 4; '五' -> 5; '六' -> 6; else -> 7
        }
        when {
            mDate != null -> {
                val mo = mDate.groupValues[1].toIntOrNull() ?: 0
                val dy = mDate.groupValues[2].toIntOrNull() ?: 0
                if (mo in 1..12 && dy in 1..31) {
                    date = try { LocalDate.of(today.year, mo, dy) } catch (_: Exception) { null }
                    if (date != null && date!!.isBefore(today)) date = date!!.plusYears(1)
                    phrase = mDate.value
                }
            }
            mNextWeek != null -> {
                // 下周X = 下周一（严格在本周之后）再往后数 target-1 天
                val target = wdOf(mNextWeek.groupValues[2][0])
                var nextMonday = today.plusDays(((1 - today.dayOfWeek.value + 7) % 7).toLong())
                if (!nextMonday.isAfter(today)) nextMonday = nextMonday.plusDays(7)
                date = nextMonday.plusDays((target - 1).toLong())
                phrase = mNextWeek.value
            }
            mWeek != null -> {
                val target = wdOf(mWeek.groupValues[2][0])
                var d = today.plusDays(((target - today.dayOfWeek.value + 7) % 7).toLong())
                if (!d.isAfter(today)) d = d.plusDays(7)
                date = d
                phrase = mWeek.value
            }
            mRel != null -> {
                date = when (mRel.value) {
                    "明天" -> today.plusDays(1)
                    "后天" -> today.plusDays(2)
                    else -> today
                }
                phrase = mRel.value
            }
            mDay != null -> {
                val dy = mDay.groupValues[1].toIntOrNull() ?: 0
                if (dy in 1..31) {
                    date = try { LocalDate.of(today.year, today.month, dy) } catch (_: Exception) { null }
                    if (date != null && date!!.isBefore(today)) date = date!!.plusMonths(1)
                    phrase = mDay.value
                }
            }
        }
        if (date == null) return null
        val due = LocalDateTime.of(date, LocalTime.of(23, 59))
            .atZone(java.time.ZoneId.systemDefault())
        return Pair(due.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME), phrase)
    }

    // ---------------- 主流程 ----------------

    /** 扫一个课堂窗口 [startMs, endMs]，返回本窗口新增待办数。 */
    private fun scanWindow(context: Context, courseName: String, startMs: Long, endMs: Long): Outcome {
        val seen = prefs(context).getStringSet(KEY_SEEN, emptySet())?.toMutableSet() ?: mutableSetOf()
        var photos = 0
        var created = 0
        val failed = ArrayList<String>()
        for ((f, t) in listCameraPhotos()) {
            if (t < startMs || t > endMs) continue
            val key = f.absolutePath + "|" + f.length() + "|" + f.lastModified()
            if (key in seen) continue
            val text = ocrText(f)
            if (text == null) {
                addRecord(context, ScanRecord(timeMs = t, file = f.absolutePath, course = courseName, status = "没读出来"))
                failed.add(f.name)
                continue
            }
            seen.add(key)   // OCR 成功即记已处理（无论是否认出作业，不重复烧电）
            photos += 1
            if (!isHomework(text)) {
                addRecord(
                    context, ScanRecord(
                        timeMs = t, file = f.absolutePath, course = courseName,
                        ocrHead = head(text), ocrFull = text.take(1200), status = "未认出作业"
                    )
                )
                continue
            }
            val due = extractDue(text)
            val newId = TodoStore.createFromPhoto(
                context,
                text = excerpt(text),
                course = courseName,
                whenText = due?.second ?: "",
                dueIso = due?.first ?: "",
                raw = "课堂照片 OCR（${f.name}）：\n" + text.take(1500)
            )
            if (newId != null) created += 1
            addRecord(
                context, ScanRecord(
                    timeMs = t, file = f.absolutePath, course = courseName,
                    ocrHead = head(text), ocrFull = text.take(1200),
                    hit = newId != null, todoId = newId ?: "",
                    status = if (newId != null) "已建待办" else "重复·同文待办已在"
                )
            )
        }
        prefs(context).edit().putStringSet(KEY_SEEN, seen).apply()
        val sb = StringBuilder()
        if (photos == 0) sb.append("没找到新课照片")
        else {
            sb.append("扫了 ").append(photos).append(" 张照片")
            if (created > 0) sb.append("，新建 ").append(created).append(" 条作业待办")
            else sb.append("，没认出作业内容")
        }
        if (failed.isNotEmpty()) sb.append("（").append(failed.size).append(" 张没读出来）")
        return Outcome(photos, created, sb.toString())
    }

    /** 定时触发：一节课的窗口（含拖堂缓冲），太久远的跳过（闹钟补跑防陈年重放）。 */
    fun runScheduled(context: Context, courseName: String, startHHmm: String, endHHmm: String): Outcome? {
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val startMs = msOf(today, startHHmm) - 10 * 60_000L
        val endMs = msOf(today, endHHmm) + 15 * 60_000L
        if (endMs <= 0) return null
        if (now - endMs > 75 * 60_000L) return null   // 闹钟迟到太久：照片早该扫过了，静默放弃
        val r = scanWindow(context, courseName, startMs, endMs)
        if (r.photosSeen > 0) {
            val msg = "《" + courseName + "》" + r.detail
            ReminderNotifications.fire(
                context, "photoscan_" + today.toString() + "_" + endHHmm,
                "课堂照片", msg, ReminderNotifications.CHANNEL_TODO
            )
            IslandManager.reminder(context, "课堂照片", msg, null)
        }
        return r
    }

    /**
     * 手动补扫（学业页按钮）：今天所有已下课的课，逐个窗口扫。
     * vc113 修：必须过周次过滤（weeksMatch）——10-05 国庆周 bug：手动扫漏看周次，把假期里
     * 恰好落在课程时钟窗口的照片当课堂照扫了（自动触发路一直有过滤，仅手动路漏）。
     */
    fun scanToday(context: Context, onProgress: (String) -> Unit): Outcome {
        val today = LocalDate.now()
        val week = try { com.luyuan.domain.semesterWeekOf(today) } catch (_: Exception) { 1 }
        val courses = try { V2EntityRepository.listCourses(context) } catch (_: Exception) { emptyList() }
        val todays = courses.filter {
            it.weekday == today.dayOfWeek.value &&
                com.luyuan.domain.weeksMatch(it.weeks, week) && it.end.isNotBlank()
        }
        if (todays.isEmpty()) {
            return Outcome(0, 0, "按课表周次今天没课，没扫（放假的课不算数）")
        }
        var totalPhotos = 0
        var totalCreated = 0
        var windows = 0
        val details = ArrayList<String>()
        for (c in todays) {
            val endMs = msOf(today, c.end) + 15 * 60_000L
            if (endMs <= 0) continue
            onProgress("正在扫《" + c.name + "》…")
            val r = scanWindow(context, c.name, msOf(today, c.start) - 10 * 60_000L, endMs)
            if (r.photosSeen > 0) {
                totalPhotos += r.photosSeen
                totalCreated += r.todosCreated
                details.add("《" + c.name + "》" + r.detail)
                windows += 1
            }
        }
        val det = if (details.isEmpty()) "今天没扫到新照片（拍了的话下课后 5 分钟会自动来）"
        else details.joinToString("；")
        return Outcome(totalPhotos, totalCreated, det)
    }

    private fun msOf(date: LocalDate, hhmm: String): Long {
        val p = hhmm.trim().split(":")
        val h = p.getOrNull(0)?.trim()?.toIntOrNull() ?: return -1
        val m = p.getOrNull(1)?.trim()?.toIntOrNull() ?: return -1
        if (h !in 0..23 || m !in 0..59) return -1
        return LocalDateTime.of(date, LocalTime.of(h, m)).atZone(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
    }
}
