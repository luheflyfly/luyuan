package com.luyuan.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 录音崩溃抢救（vc109 P1，Diktafon capture_recovery 机制直译）：
 * 启动时扫 audio/ 目录——
 * ① `*.wav.capture` 标记残留 = 上次录音进程被杀（vivo 杀后台/系统回收）→ WAV 头还占位着，
 *    fixWavHeader 重写 8 字节即复活 → 落「录音中断抢救」笔记（created_at 保留原开始时刻）进
 *    既有「录音待转写」管线，电脑 SenseVoice 接手。
 * ② 旧版遗留的 `*.wav.pcm.tmp` 孤儿（vc109 前的方案只在正常 stop 才拼 WAV）→ 一次性转正。
 * 短于 ~100ms 的放弃；笔记已存在的（崩溃发生在落库后）只修文件不重写。
 *
 * 调用方：MainActivity 启动时后台线程跑一次（UI 起来前完成，绝不阻塞）。
 */
object RecordingRescue {

    /** 抢救结果（给通知/日志用） */
    data class Result(val rescued: Int, val discarded: Int)

    fun scanAndRescue(context: Context): Result {
        val dir = StorageLocator.audioDir(context)
        if (!dir.isDirectory) return Result(0, 0)
        var rescued = 0
        var discarded = 0

        // ① vc109 capture 标记
        for (mark in dir.listFiles { f -> f.isFile && f.name.endsWith(".capture") } ?: emptyArray()) {
            try {
                val lines = mark.readText().lines()
                val startedAt = lines.getOrNull(0)?.toLongOrNull() ?: 0L
                val wavName = lines.getOrNull(1)?.trim().orEmpty()
                if (wavName.isEmpty()) { mark.delete(); continue }
                val wav = File(dir, wavName)
                val id = wavName.removeSuffix(".wav")
                if (!wav.exists()) { mark.delete(); continue }
                if (wav.length() < AudioRecorder.HEADER_LEN + AudioRecorder.MIN_RESCUE_BYTES) {
                    wav.delete(); mark.delete(); discarded += 1; continue
                }
                AudioRecorder.fixWavHeader(wav)
                if (NoteRepository.getNote(context, id) == null) {
                    saveRescuedNote(context, id, wavName, startedAt)
                    rescued += 1
                }
                mark.delete()
            } catch (_: Exception) {
            }
        }

        // ② 旧版 .pcm.tmp 孤儿（vc109 前方案）
        for (tmp in dir.listFiles { f -> f.isFile && f.name.endsWith(".wav.pcm.tmp") } ?: emptyArray()) {
            try {
                val wavName = tmp.name.removeSuffix(".pcm.tmp")
                val wav = File(dir, wavName)
                val id = wavName.removeSuffix(".wav")
                val pcm = tmp.readBytes()
                if (pcm.size < AudioRecorder.MIN_RESCUE_BYTES) {
                    tmp.delete(); discarded += 1; continue
                }
                FileOutputStream(wav).use { os ->
                    os.write(AudioRecorder.placeholderHeader(AudioRecorder.SAMPLE_RATE))
                    os.write(pcm)
                }
                AudioRecorder.fixWavHeader(wav)
                if (NoteRepository.getNote(context, id) == null) {
                    saveRescuedNote(context, id, wavName, tmp.lastModified())
                    rescued += 1
                }
                tmp.delete()
            } catch (_: Exception) {
            }
        }
        if (rescued > 0 || discarded > 0) {
            android.util.Log.i("RecordingRescue", "rescued=$rescued discarded=$discarded")
        }
        return Result(rescued, discarded)
    }

    private fun saveRescuedNote(context: Context, id: String, wavName: String, startedAt: Long) {
        val now = NoteRepository.nowIso()
        val createdAt = if (startedAt > 0) {
            Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } else now
        NoteRepository.saveNote(
            context,
            Note(
                id = id,
                created_at = createdAt,
                updated_at = now,
                text = "（录音中断 · 已抢救原声，等电脑转写）",
                source = "voice",
                tags = listOf("语音抢救"),
                device = "phone",
                audio = "audio/$wavName",
                transcribed = false,
                schema = 1
            )
        )
    }
}
