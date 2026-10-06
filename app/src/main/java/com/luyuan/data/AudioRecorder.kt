package com.luyuan.data

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 录制 16kHz 单声道 16-bit PCM → 标准 WAV（audio/<id>.wav），供电脑端复用。
 *
 * vc109 P1 录音崩溃抢救（Diktafon capture_recovery+wav_repair 机制直译）：
 * - 开始**立即写 WAV**：44 字节 RIFF 头先落盘（size 占位 0），PCM 块随后追加——
 *   进程被杀（vivo 杀后台/系统回收）时样本已全在盘上，抢救只需重写头部 8 字节。
 *   旧方案 PCM 先进 .pcm.tmp、正常 stop 才拼 WAV——被杀 = tmp 无人认领 = 录音全丢。
 * - 旁边写 `<id>.wav.capture` 标记（两行文本：startedAtMillis、wav 文件名）；
 *   正常完成（finalize）后删除标记。
 * - `RecordingRescue.scanAndRescue` 在启动时扫残留标记 → 修头复活 → 落「录音中断抢救」笔记。
 *
 * vc109 P6 stop() 竞态：thread.join(2000) 超时后 WAV 可能还没拼完（长录音/慢盘），
 * 转写方紧跟着读文件会踩空——加 [awaitReady] 闩锁，转写前等 finalize 真正完成。
 *
 * @param onPcm PCM 块回调（现管线转写走成品 WAV，此回调仅保留兼容，可为空操作）
 */
class AudioRecorder(
    private val outputWav: File,
    private val onPcm: (ByteArray) -> Unit
) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var running = false
    private var out: FileOutputStream? = null
    private var mark: File? = null
    private var written = 0L

    /** vc110 P5：暂停中——线程照常抽干麦克风缓冲但丢弃样本（恢复不带旧尾巴），盘上不追加 */
    @Volatile
    private var paused = false

    /** finalize（头修好、流关死）后倒计数；转写方 awaitReady 等它（P6） */
    private val done = CountDownLatch(1)

    /**
     * vc117 开录前三重自检（路河拍板"录音之前确认权限是否到位"）：
     * ①写盘（占位头落得进去）②capture 标记（没了它被杀后 P1 抢救不认）③麦克风可初始化（无权限时
     * AudioRecord state=UNINITIALIZED）。任一失败=清理半截文件并返回 false——此前失败被静默吞，
     * 界面照常"录音"，PCM 每块白丢，录 1 小时盘上只有 44 字节空壳（违"原声永不丢"）。
     */
    fun start(): Boolean {
        val sampleRate = SAMPLE_RATE
        val chanCfg = AudioFormat.CHANNEL_IN_MONO
        val fmt = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, chanCfg, fmt)
        // ① 写盘自检：占位头落得进去才有资格录
        val os: FileOutputStream = try {
            FileOutputStream(outputWav).apply {
                write(placeholderHeader(sampleRate))
                flush()
            }
        } catch (_: Exception) {
            try { outputWav.delete() } catch (_: Exception) {}
            return false
        }
        out = os
        // ② capture 标记自检（RecordingRescue 消费：两行文本 = startedAtMillis / wav 文件名）
        mark = try {
            File(outputWav.parentFile, outputWav.name + ".capture").apply {
                writeText(System.currentTimeMillis().toString() + "\n" + outputWav.name)
            }
        } catch (_: Exception) {
            try { os.close() } catch (_: Exception) {}
            try { outputWav.delete() } catch (_: Exception) {}
            out = null
            return false
        }
        // ③ 麦克风自检（无 RECORD_AUDIO 权限时 state=UNINITIALIZED，startRecording 必然白录）
        record = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, chanCfg, fmt, minBuf * 2)
        if (record?.state != AudioRecord.STATE_INITIALIZED) {
            try { record?.release() } catch (_: Exception) {}
            record = null
            try { mark?.delete() } catch (_: Exception) {}
            mark = null
            try { os.close() } catch (_: Exception) {}
            try { outputWav.delete() } catch (_: Exception) {}
            out = null
            return false
        }
        record?.startRecording()
        running = true
        written = 0L
        thread = Thread {
            val buffer = ByteArray(minBuf)
            try {
                while (running) {
                    val read = record?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0 && !paused) {
                        val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                        out?.let { it.write(chunk); it.flush() }
                        written += read
                        onPcm(chunk)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { out?.flush() } catch (_: Exception) {}
                try { out?.close() } catch (_: Exception) {}
                out = null
                fixWavHeader(outputWav, sampleRate)
                mark?.delete()
                mark = null
                done.countDown()
            }
        }
        thread?.start()
        return true
    }

    fun stop() {
        running = false
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        thread?.join(2000)
        thread = null
    }

    /** vc110 P5：暂停写盘（.capture 标记仍在，被杀照走 P1 抢救）；继续=接着追加 */
    fun pause() { paused = true }

    fun resume() { paused = false }


    /** P6：等录音线程真正 finalize（头修好、流关死）。超时也返回（尽力而为）。 */
    fun awaitReady(timeoutMs: Long = 10_000L) {
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val HEADER_LEN = 44

        /** <100ms 的 PCM 没抢救价值（Diktafon 同款阈值） */
        const val MIN_RESCUE_BYTES = 3200L

        /** 44 字节规范 PCM WAV 头（size 字段占位 0，finalize/抢救时用 fixWavHeader 补） */
        fun placeholderHeader(sampleRate: Int): ByteArray {
            val bos = java.io.ByteArrayOutputStream(44)
            fun le16(v: Int) { bos.write(v and 0xFF); bos.write((v ushr 8) and 0xFF) }
            fun le32(v: Int) {
                bos.write(v and 0xFF); bos.write((v ushr 8) and 0xFF)
                bos.write((v ushr 16) and 0xFF); bos.write((v ushr 24) and 0xFF)
            }
            fun ascii(s: String) = bos.write(s.toByteArray(Charsets.US_ASCII))
            ascii("RIFF"); le32(36); ascii("WAVE")
            ascii("fmt "); le32(16); le16(1); le16(1)
            le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
            ascii("data"); le32(0)
            return bos.toByteArray()
        }

        /** 按文件实际长度重写 RIFF/data 两个 size 字段（8 字节复活一个 WAV） */
        fun fixWavHeader(wav: File, sampleRate: Int = SAMPLE_RATE) {
            val total = (wav.length() - HEADER_LEN).coerceAtLeast(0L)
            RandomAccessFile(wav, "rw").use { raf ->
                raf.seek(4);  raf.write(le32Bytes(36 + total.toInt()))
                raf.seek(40); raf.write(le32Bytes(total.toInt()))
            }
        }

        private fun le32Bytes(v: Int): ByteArray = byteArrayOf(
            (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte()
        )
    }
}
