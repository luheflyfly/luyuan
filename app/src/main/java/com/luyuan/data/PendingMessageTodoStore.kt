package com.luyuan.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.OffsetDateTime

/**
 * 「消息待办」的待确认队列（立项单 T3）。
 *
 * 分级确认是全项目命门：云端抽出来的待办**绝不直接入正式待办**，一律先落这里，
 * 用户在笔记页顶部逐条 ✓ 确认（→ 写 todo_<id8>.json 同步回电脑）/ ✗ 丢弃。
 *
 * 红线（与 PendingExpenseStore 同款）：存 App 私有 filesDir，**不写共享目录、不进 SYNC_FORMAT**——
 * 只有用户确认了才落正式文件。
 */
@Serializable
data class PendingMessageTodo(
    val id: String,              // 8 位 hex；确认后即 todo_<id>.json 的文件 id
    val text: String,            // 要办的事（模型抽取）
    val who: String = "",        // 谁说的（模型抽取，可空）
    val whenText: String = "",   // 时间描述（模型抽取，原文，可空）
    val raw: String = "",        // 原始消息（给用户核对，必要）
    val source: String = "",     // "wechat" | "qq"
    val sender: String = "",     // 通知里的发送者
    val created_at: String = ""  // ISO 8601
)

object PendingMessageTodoStore {

    private fun file(ctx: Context) = File(ctx.filesDir, "pending_message_todos.json")

    fun list(ctx: Context): List<PendingMessageTodo> = try {
        val f = file(ctx)
        if (!f.exists()) emptyList()
        else v2Json.decodeFromString(
            ListSerializer(PendingMessageTodo.serializer()), f.readText(Charsets.UTF_8)
        ).sortedByDescending { it.created_at }
    } catch (_: Exception) {
        emptyList()
    }

    fun add(ctx: Context, p: PendingMessageTodo) {
        try {
            val cur = runCatching { list(ctx) }.getOrDefault(emptyList())
            // 同一条消息重复抽取保护（监听器已做粗去重，这里再兜一层：同原文同发送者不重复入队）
            if (cur.any { it.raw == p.raw && it.sender == p.sender }) return
            // 队列上限 50 条，防止长期不确认堆积把文件撑大
            val next = (cur + p).takeLast(50)
            file(ctx).writeText(
                v2Json.encodeToString(ListSerializer(PendingMessageTodo.serializer()), next)
            )
        } catch (_: Exception) {
        }
    }

    fun remove(ctx: Context, id: String) {
        try {
            val cur = list(ctx).filter { it.id != id }
            file(ctx).writeText(
                v2Json.encodeToString(ListSerializer(PendingMessageTodo.serializer()), cur)
            )
        } catch (_: Exception) {
        }
    }

    fun newId(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(8)

    fun nowIso(): String = OffsetDateTime.now().toString()
}
