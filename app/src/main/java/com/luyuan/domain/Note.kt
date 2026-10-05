package com.luyuan.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 单条记事。字段严格对齐 D:\Luyuan\SYNC_FORMAT.md（跨端同步数据契约）。
 * - 电脑端现有笔记可能缺 updated_at/device/schema，读取时由 NoteRepository 做兜底兼容。
 * - ignoreUnknownKeys：容忍电脑端未来新增的字段。
 */
@Serializable
data class Note(
    val id: String,
    val created_at: String,
    // 电脑端 store.add_note 建的笔记没有 updated_at（SYNC_FORMAT 允许缺省），
    // 必须给默认值，否则 kotlinx.serialization 解析直接抛异常 → 整条笔记被丢弃。
    // 兜底逻辑（缺省视为 created_at）在 NoteRepository.readNote。
    val updated_at: String = "",
    val text: String,
    val source: String,            // "manual" | "voice"
    // 设备标记：默认空串。App 自建笔记显式传 "phone"；电脑老笔记缺此字段时
    // 由 NoteRepository.readNote 兜底为 "pc"（默认值绝不能写 "phone"，否则电脑笔记全被误标）
    val tags: List<String> = emptyList(),
    val device: String = "",  // "pc" | "phone"
    val audio: String? = null,      // 相对路径 audio/<id>.wav
    val transcribed: Boolean? = null,
    // 原始识别留底（PC 端 SenseVoice 三层纠错前的原稿；App 端详情页可对照查看）
    val raw_text: String? = null,
    val deleted: Boolean = false,   // 软删标记
    // 日记配图（PC 端 2026-09-06 扩展）：相对路径 images/<id>.jpg，文件在共享目录 images/ 子文件夹
    val images: List<String> = emptyList(),
    // 提醒（SYNC_FORMAT 2026-09-06 扩展）：缺省=不提醒；App 端显示徽章，vc113 起本地也响铃（截止前 1 小时）
    val remind_at: String? = null,
    val remind_fired: Boolean? = null,
    val schema: Int = 1
) {
    fun toJson(): String = noteJson.encodeToString(Note.serializer(), this)

    companion object {
        fun fromJson(s: String): Note = noteJson.decodeFromString(Note.serializer(), s)
    }
}

val noteJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false   // null 字段不写出（如 remind_at/transcribed），保持 JSON 干净
    prettyPrint = true
}
