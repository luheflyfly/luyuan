package com.luyuan.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * 消息窗口缓冲（vc98 抽取架构改造，路河拍板「抽消息以任务为单位、攒够上下文」）：
 * 通知不再逐条送云端，先进这里按聊天攒一个小时间窗（默认 5 分钟），窗口到期一次性抽取——
 * 一段对话/一篇通知被拆成多条消息时，模型能看到完整上下文，合并重复、丢掉碎片。
 *
 * 存 App 私有 filesDir（隐私红线同 PendingMessageTodoStore：不进同步目录）；
 * 进程被杀缓冲不丢（落盘），下一条通知/打开待办页时补抽。
 */
@Serializable
data class BufferedMessage(
    val chat: String,      // 通知标题（群名 / 联系人备注名）
    val sender: String,    // 群消息体「昵称: 内容」里的昵称；私聊 = chat
    val body: String,
    val at: Long           // epoch millis（窗口计时用）
)

object MessageBuffer {

    /** 窗口时长：同聊天首条消息满 5 分钟后可抽（下一条通知到达 / 待办页打开时补抽） */
    const val WINDOW_MS = 5 * 60_000L

    /** 每聊天积到 12 条提前触发（通知风暴场景不必干等满窗） */
    const val EARLY_COUNT = 12

    /** 单聊天抽取上限条数（约束 token；正常 5 分钟窗远到不了） */
    const val MAX_PER_CHAT = 40

    /** 缓冲总上限（防极端刷屏撑文件） */
    private const val CAP = 200

    private fun file(ctx: Context) = File(ctx.filesDir, "message_buffer.json")

    fun list(ctx: Context): List<BufferedMessage> = try {
        val f = file(ctx)
        if (!f.exists()) emptyList()
        else v2Json.decodeFromString(ListSerializer(BufferedMessage.serializer()), f.readText(Charsets.UTF_8))
    } catch (_: Exception) {
        emptyList()
    }

    fun append(ctx: Context, m: BufferedMessage) {
        try {
            val next = (list(ctx) + m).takeLast(CAP)
            file(ctx).writeText(
                v2Json.encodeToString(ListSerializer(BufferedMessage.serializer()), next)
            )
        } catch (_: Exception) {
        }
    }

    /** 是否有消息已满窗（该抽了） */
    fun due(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        val oldest = list(ctx).minByOrNull { it.at } ?: return false
        return now - oldest.at >= WINDOW_MS
    }

    /** 某聊天积满 EARLY_COUNT 条也算该抽 */
    fun anyChatFull(ctx: Context): Boolean =
        list(ctx).groupingBy { it.chat }.eachCount().any { it.value >= EARLY_COUNT }

    /** 全取并清空（调用方按 chat 分组抽取；抽取失败的消息不回滚——微信里本就有原文） */
    fun takeAll(ctx: Context): List<BufferedMessage> = try {
        val cur = list(ctx)
        file(ctx).delete()
        cur
    } catch (_: Exception) {
        emptyList()
    }
}
