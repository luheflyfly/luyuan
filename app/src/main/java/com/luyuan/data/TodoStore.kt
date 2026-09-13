package com.luyuan.data

import android.content.Context
import com.luyuan.platform.StorageLocator
import kotlinx.serialization.Serializable
import java.io.File

/**
 * SYNC_FORMAT v3 实体：待办（kind:"todo"）。
 *
 * 落点 = 同步根目录 `todo_<id前8位>.json`（与 expense_/course_ 同款，**不碰 data/notes**），
 * 随 Syncthing 回电脑。手机端由「消息待办」确认后写入；PC 端解析待跟进（未知 kind 安全跳过，
 * 不会把电脑端搞坏 —— 见 SYNC_FORMAT §十 兼容铁律）。
 *
 * ⚠️ 新增 kind 属格式变更：SYNC_FORMAT.md 已同步加 §10.5，PC 端会话需实现读取与展示。
 */
@Serializable
data class Todo(
    val kind: String = "todo",
    val id: String,
    val text: String = "",          // 要办的事
    val who: String = "",           // 谁说的（备注，可空）
    val when_text: String = "",     // 时间描述原文（如「明天下午三点」），不做结构化，避免猜错
    val raw: String = "",           // 原始消息（溯源用）
    val source: String = "",        // wechat | qq | manual
    val sender: String = "",        // 通知发送者
    val done: Boolean = false,      // 完成标记（与笔记 todo_done 语义一致）
    val done_at: String? = null,
    val device: String = "phone",
    val schema: Int = 3,
    val deleted: Boolean = false,
    val created_at: String = "",
    val updated_at: String = ""
)

object TodoStore {

    /** 手机端认的实体前缀（PC 端同款） */
    private const val PREFIX = "todo_"

    fun fileFor(context: Context, id: String): File =
        File(StorageLocator.getRoot(context), "$PREFIX${id.take(8)}.json")

    /** 写入（确认待办时调用）。返回是否成功。 */
    fun write(context: Context, todo: Todo): Boolean = try {
        fileFor(context, todo.id)
            .writeText(v2Json.encodeToString(Todo.serializer(), todo), Charsets.UTF_8)
        true
    } catch (_: Exception) {
        false
    }

    /** 全部未删待办（手机端只读展示用，PC 端也读同一批文件） */
    fun list(context: Context): List<Todo> = try {
        val dir = StorageLocator.getRoot(context)
        (dir.listFiles() ?: emptyArray())
            .filter {
                it.isFile && it.name.startsWith(PREFIX, true) &&
                    !it.name.contains(".sync-conflict")
            }
            .mapNotNull { f ->
                try {
                    val t = v2Json.decodeFromString(Todo.serializer(), f.readText(Charsets.UTF_8))
                    if (t.kind == "todo" && !t.deleted) t else null
                } catch (_: Exception) {
                    null
                }
            }
            .sortedByDescending { it.created_at }
    } catch (_: Exception) {
        emptyList()
    }

    /** 从待确认队列转正式待办（用户点 ✓） */
    fun fromPending(p: PendingMessageTodo): Todo {
        val now = PendingMessageTodoStore.nowIso()
        return Todo(
            id = p.id,
            text = p.text,
            who = p.who,
            when_text = p.whenText,
            raw = p.raw,
            source = p.source,
            sender = p.sender,
            created_at = now,
            updated_at = now
        )
    }
}
