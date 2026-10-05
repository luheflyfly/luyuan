package com.luyuan.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.OffsetDateTime

/**
 * 「消息待办」的待确认队列（立项单 T3）。
 *
 * vc108 起退役：不再有写入路径（抽取结果直接进 TodoStore），本队列只存历史存量，
 * 由通知键「已完成/不要」或代码清理；vc115 起 UI 不再展示（红点已改数正式库未办）。
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
    val dueIso: String = "",     // vc98：截止绝对时间 ISO8601（窗口抽取时由模型锚定当前时间算出，可空）
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
