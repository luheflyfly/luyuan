package com.luyuan.data

import android.content.Context
import com.luyuan.domain.Note
import com.luyuan.domain.SyncPolicy
import com.luyuan.domain.noteJson
import com.luyuan.platform.StorageLocator
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * 记事读写层。严格按 SYNC_FORMAT.md：每笔记一个 JSON 文件，软删不物理删。
 * 兼容兜底：电脑端旧笔记缺 updated_at/device/schema 时按规范补默认值。
 */
object NoteRepository {

    fun listNotes(context: Context): List<Note> {
        // 主列表排除日记（带「日记」标签的），日记在日记页单独管理（与 PC 端一致）
        return allDistinct(context).filter { !it.deleted && "日记" !in it.tags }
            .sortedByDescending { it.created_at }
    }

    // ---------- 日记（tags=["日记"]，一天一篇，与 PC 端三页系统对齐） ----------

    /** 全部日记（含今天，新→旧）：日记页「之前的日记」列表与连续天数共用 */
    fun listDiaries(context: Context): List<Note> {
        return allDistinct(context)
            .filter { !it.deleted && it.tags.contains("日记") }
            .sortedByDescending { it.created_at }
    }

    /** 今天的日记（没有则 null） */
    fun todayDiaryNote(context: Context): Note? {
        val today = LocalDate.now().toString()
        return allDistinct(context)
            .filter { !it.deleted && it.tags.contains("日记") && it.created_at.length >= 10 && it.created_at.substring(0, 10) == today }
            .maxByOrNull { it.created_at }
    }

    /** 保存今天的日记：存在则更新，不存在则新建（source=manual, device=phone） */
    fun saveDiary(context: Context, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val existing = todayDiaryNote(context)
        if (existing != null) {
            saveNote(context, existing.copy(text = t, updated_at = nowIso()))
        } else {
            val now = nowIso()
            saveNote(
                context,
                Note(
                    id = UUID.randomUUID().toString(),
                    created_at = now,
                    updated_at = now,
                    text = t,
                    source = "manual",
                    tags = listOf("日记"),
                    device = SyncPolicy.DEVICE_PHONE,
                    schema = SyncPolicy.SCHEMA_VERSION
                )
            )
        }
    }

    /** 设置今天日记的配图列表（相对路径 images/xxx.jpg） */
    fun setDiaryImages(context: Context, images: List<String>) {
        val existing = todayDiaryNote(context) ?: return
        saveNote(context, existing.copy(images = images, updated_at = nowIso()))
    }

    /**
     * 导入一张图（来自相册/相机）到共享目录 images/ 子文件夹，JPEG 压缩（最长边 1600、质量 85），
     * 与 PC 端上传规格一致；返回相对路径，失败返回 null。
     */
    fun importImage(context: Context, uri: android.net.Uri): String? {
        return try {
            val dir = File(StorageLocator.getRoot(context), "images").also { it.mkdirs() }
            val tmp = File(context.cacheDir, "import_${System.currentTimeMillis()}.tmp")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return null
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(tmp.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                tmp.delete(); return null
            }
            var sample = 1
            var maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxDim / sample > 1600) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeFile(tmp.absolutePath, opts)
            tmp.delete()
            if (bmp == null) return null
            val fname = UUID.randomUUID().toString().take(12) + ".jpg"
            val out = File(dir, fname)
            out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
            bmp.recycle()
            "images/$fname"
        } catch (_: Exception) {
            null
        }
    }

    /** 回收站：只看软删的 */
    fun listTrash(context: Context): List<Note> {
        return allDistinct(context).filter { it.deleted }.sortedByDescending { it.updated_at }
    }

    /** 全量扫描 + 同 id 去重（取 updated_at 最新那份），列表/回收站共用 */
    private fun allDistinct(context: Context): List<Note> {
        val root = StorageLocator.getRoot(context)
        val found = mutableListOf<Note>()
        collectNotes(root, found, 0, 6)
        return found
            .sortedByDescending { it.updated_at.ifBlank { it.created_at } }
            .distinctBy { it.id }
    }

    /** 递归扫描共享根目录：无论 Syncthing 把笔记映射到哪一层（notes/、data/notes/、根目录直放）都能读到 */
    private fun collectNotes(dir: File?, out: MutableList<Note>, depth: Int, maxDepth: Int) {
        if (dir == null || !dir.isDirectory || depth > maxDepth) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                // 跳过隐藏目录（.stversions=Syncthing 版本垃圾桶 / .stfolder=同步标记）：
                // 否则历史版本副本会被当成笔记读进列表，旧版本复活、计数虚高
                if (f.name.startsWith(".")) continue
                collectNotes(f, out, depth + 1, maxDepth)
            } else if (FileNaming.isNoteFile(f.name)) {
                readNote(f)?.let { out.add(it) }
            }
        }
    }

    // ---------- 同步诊断（设置页诊断卡用）：让用户一眼看清共享目录里到底有什么 ----------

    /** 共享目录体检结果 */
    data class Diag(
        val root: String,
        val dirReadable: Boolean,   // 目录能否 listFiles（ false=权限没给够/目录不存在）
        val jsonTotal: Int,         // 全部 json 文件（含隐藏目录，与旧计数口径一致）
        val notes: Int,             // 活跃笔记（主列表应显示的数量）
        val diaries: Int,           // 日记（主列表不显示，日记页显示）
        val trashed: Int,           // 软删进回收站的
        val entities: Int,          // kind 实体（联系人/课程/账目等，不算笔记）
        val failed: List<String>    // 解析失败的文件名（最多列 8 个，空=全部健康）
    )

    /** 扫一遍共享根目录并分类计数。与 collectNotes 同口径但跳过隐藏目录计 notes，jsonTotal 含隐藏目录供对照 */
    fun diagnose(context: Context): Diag {
        val root = StorageLocator.getRoot(context)
        var jsonTotal = 0
        var notes = 0
        var diaries = 0
        var trashed = 0
        var entities = 0
        val failed = mutableListOf<String>()
        var readable = false

        fun walk(dir: File?, depth: Int) {
            if (dir == null || !dir.isDirectory || depth > 6) return
            val files = dir.listFiles() ?: return
            if (depth == 0) readable = true
            for (f in files) {
                if (f.isDirectory) {
                    if (f.name.startsWith(".")) {
                        // 隐藏目录只计入 jsonTotal（对照用），不参与分类
                        jsonTotal += countHiddenJson(f, 0)
                        continue
                    }
                    walk(f, depth + 1)
                } else if (f.name.endsWith(".json", true) && !f.name.contains(".sync-conflict")) {
                    jsonTotal++
                    val n = readNote(f)
                    when {
                        // readNote 返回 null 有两种可能：v2 实体（kind 字段，安全跳过）或真坏文件。
                        // 二次确认 kind，避免把实体误报成"读不出来"
                        n == null -> {
                            if (readEntityKind(f)) entities++
                            else if (failed.size < 8) failed.add(f.name)
                        }
                        n.deleted -> trashed++
                        n.tags.contains("日记") -> diaries++
                        else -> notes++
                    }
                }
            }
        }

        walk(root, 0)
        return Diag(
            root = root.absolutePath,
            dirReadable = readable,
            jsonTotal = jsonTotal,
            notes = notes,
            diaries = diaries,
            trashed = trashed,
            entities = entities,
            failed = failed
        )
    }

    /** 数隐藏目录里的 json（只统计，不解析） */
    private fun countHiddenJson(dir: File, depth: Int): Int {
        if (!dir.isDirectory || depth > 6) return 0
        var n = 0
        for (f in dir.listFiles() ?: return 0) {
            if (f.isDirectory) n += countHiddenJson(f, depth + 1)
            else if (f.name.endsWith(".json", true) && !f.name.contains(".sync-conflict")) n++
        }
        return n
    }

    /** 该 json 是否为带 kind 字段的 v2 实体（联系人/课程/账目等），读失败不算 */
    private fun readEntityKind(file: File): Boolean = try {
        val raw = file.readText(Charsets.UTF_8)
        noteJson.parseToJsonElement(raw).jsonObject.containsKey("kind")
    } catch (_: Exception) {
        false
    }

    fun getNote(context: Context, id: String): Note? {
        // 精确读：直接按 id 定位文件，避免为取单条而全目录递归扫描（详情页进出不再全量重扫）
        val root = StorageLocator.getRoot(context)
        val file = findFileById(root, id, 0, 6) ?: return null
        return readNote(file)
    }

    fun saveNote(context: Context, note: Note) {
        val root = StorageLocator.getRoot(context).also { it.mkdirs() }
        // 已存在则原地覆盖，绝不另起新文件名——否则同一 id 留下多份文件，
        // 经 Syncthing 同步到电脑端也变成重复文件
        val file = findFileById(root, note.id, 0, 6)
            ?: File(root, FileNaming.fileNameFor(note.id, parseCreatedAt(note.created_at)))
        file.writeText(note.toJson(), Charsets.UTF_8)
    }

    /** 按 id 前 8 位找已有文件（文件名约定 ..._<id前8位>.json），递归兼容旧层级 */
    private fun findFileById(dir: File, noteId: String, depth: Int, maxDepth: Int): File? {
        if (!dir.isDirectory || depth > maxDepth) return null
        val suffix = "_" + noteId.take(8).lowercase() + ".json"
        for (f in dir.listFiles() ?: return null) {
            if (f.isDirectory) {
                findFileById(f, noteId, depth + 1, maxDepth)?.let { return it }
            } else if (!f.name.contains(".sync-conflict") &&
                f.name.lowercase().endsWith(suffix)
            ) {
                return f
            }
        }
        return null
    }

    fun updateNote(
        context: Context,
        id: String,
        text: String? = null,
        tags: List<String>? = null,
        images: List<String>? = null
    ) {
        val existing = getNote(context, id) ?: return
        val updated = existing.copy(
            text = text ?: existing.text,
            tags = tags ?: existing.tags,
            images = images ?: existing.images,
            updated_at = nowIso()
        )
        saveNote(context, updated)
    }

    /** 软删：置 deleted=true，不删文件 */
    fun softDelete(context: Context, id: String) {
        val existing = getNote(context, id) ?: return
        saveNote(context, existing.copy(deleted = true, updated_at = nowIso()))
    }

    /** 从回收站恢复：置回 deleted=false，updated_at 刷新使撤销经 Syncthing 传到电脑端 */
    fun restoreNote(context: Context, id: String) {
        val n = findAny(context, id) ?: return
        if (n.deleted) saveNote(context, n.copy(deleted = false, updated_at = nowIso()))
    }

    /** 彻底删除：物理删文件（不可恢复），与电脑端 purge 对齐 */
    fun purgeNote(context: Context, id: String) {
        val root = StorageLocator.getRoot(context)
        findFileById(root, id, 0, 6)?.delete()
    }

    /** 查找含已软删在内的任意一条（getNote 只看未删除的） */
    fun findAny(context: Context, id: String): Note? {
        return allDistinct(context).firstOrNull { it.id == id }
    }

    // ---------- 提醒（SYNC_FORMAT remind_at / remind_fired，两端互通） ----------

    /** 设提醒：remindAtIso 为 null = 取消。新提醒置 remind_fired=false。 */
    fun setReminder(context: Context, id: String, remindAtIso: String?) {
        val existing = findAny(context, id) ?: return
        saveNote(
            context,
            existing.copy(
                remind_at = remindAtIso,
                remind_fired = if (remindAtIso == null) existing.remind_fired else false,
                updated_at = nowIso()
            )
        )
    }

    /** 到点/错过补弹后标记已触发（防重复响） */
    fun markReminderFired(context: Context, id: String) {
        val n = findAny(context, id) ?: return
        if (n.remind_fired != true) {
            saveNote(context, n.copy(remind_fired = true, updated_at = nowIso()))
        }
    }

    /** 待提醒清单：未删 + 设了 remind_at + 还没响过（含已过期未响的，用于错过补弹） */
    fun pendingReminders(context: Context): List<Note> {
        return allDistinct(context).filter {
            !it.deleted && it.remind_fired != true && !it.remind_at.isNullOrBlank()
        }
    }

    /** remind_at → epoch 毫秒（带时区直接转，naive 按本地时区），解析失败返回 null */
    fun remindAtMillis(note: Note): Long? {
        val ra = note.remind_at ?: return null
        return try {
            OffsetDateTime.parse(ra).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                LocalDateTime.parse(ra).atOffset(OffsetDateTime.now().offset)
                    .toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }

    fun createManual(
        context: Context,
        text: String,
        tags: List<String> = emptyList(),
        images: List<String> = emptyList()
    ): Note {
        val now = nowIso()
        val note = Note(
            id = UUID.randomUUID().toString(),
            created_at = now,
            updated_at = now,
            text = text,
            source = "manual",
            tags = tags,
            images = images,
            device = SyncPolicy.DEVICE_PHONE,
            schema = SyncPolicy.SCHEMA_VERSION
        )
        saveNote(context, note)
        return note
    }

    private fun readNote(file: File): Note? = try {
        val raw = file.readText(Charsets.UTF_8)
        // SYNC_FORMAT v2 兼容（A1）：带 kind 字段的实体文件（contact/course/expense…）
        // 一律安全跳过，绝不往笔记解析里塞——否则 PC 端新数据会把手机端搞坏
        if (noteJson.parseToJsonElement(raw).jsonObject.containsKey("kind")) {
            null
        } else {
            val n = Note.fromJson(raw)
            // 兼容兜底：缺字段补默认（updated_at 现已可缺省，这里统一补齐）
            n.copy(
                updated_at = if (n.updated_at.isBlank()) n.created_at else n.updated_at,
                device = if (n.device.isBlank()) SyncPolicy.DEVICE_PC else n.device,
                schema = if (n.schema == 0) SyncPolicy.SCHEMA_VERSION else n.schema
            )
        }
    } catch (e: Exception) {
        null
    }

    /** created_at(ISO8601, 可带时区) → 本地时间，用于生成规范文件名；解析失败退回当前时间 */
    private fun parseCreatedAt(s: String): LocalDateTime = try {
        OffsetDateTime.parse(s).toLocalDateTime()
    } catch (_: Exception) {
        try {
            LocalDateTime.parse(s)
        } catch (_: Exception) {
            LocalDateTime.now()
        }
    }

    fun nowIso(): String {
        val offset = OffsetDateTime.now(ZoneId.systemDefault()).offset
        return LocalDateTime.now().atOffset(offset).toString()
    }
}
