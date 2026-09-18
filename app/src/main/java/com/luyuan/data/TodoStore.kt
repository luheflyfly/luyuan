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
    val when_text: String = "",     // 时间描述原文（如「明天下午三点」）
    val due_at: String = "",        // vc98：截止绝对时间 ISO8601（窗口抽取产出；空=没识别出截止）
    val raw: String = "",           // 原始消息（溯源用）
    val source: String = "",        // wechat | qq | manual
    val sender: String = "",        // 通知发送者
    val done: Boolean = false,      // 完成标记（与笔记 todo_done 语义一致）
    val done_at: String? = null,
    val device: String = "phone",
    val schema: Int = 3,
    val deleted: Boolean = false,   // 软删墓碑（vc98 起手机端删除也走这里；双端 list 均过滤）
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

    // ---------------- vc98 同步三修：改前必读盘合并，绝不拿旧内存副本整文件覆盖 ----------------
    // 病根（09-18 路河「同步做得很差」诊断）：旧 toggleMsg 直接 write(t.copy(...))——t 是页面
    // 上一次读的副本，PC 侧同步来的新改动（完成/补字段）会被旧副本冲掉。现在所有对既有待办的
    // 改动一律 fresh read → 只覆盖目标字段 → bump updated_at → 写回。

    private fun readFresh(context: Context, id: String): Todo? = try {
        val f = fileFor(context, id)
        if (!f.exists()) null
        else v2Json.decodeFromString(Todo.serializer(), f.readText(Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    /** 完成/取消完成（勾选走这里，不整文件覆盖）。文件不存在返回 false。 */
    fun setDone(context: Context, id: String, done: Boolean): Boolean {
        val disk = readFresh(context, id) ?: return false
        val now = PendingMessageTodoStore.nowIso()
        return write(
            context, disk.copy(
                done = done,
                done_at = if (done) (disk.done_at ?: now) else null,
                updated_at = now
            )
        )
    }

    /** 软删（手机端删除入口 vc98 新增；PC list_todos 本就过滤 deleted，墓碑双端兼容）。 */
    fun deleteSoft(context: Context, id: String): Boolean {
        val disk = readFresh(context, id) ?: return false
        return write(
            context, disk.copy(deleted = true, updated_at = PendingMessageTodoStore.nowIso())
        )
    }

    /** 清空已办：所有 done=true 的未删待办打墓碑。返回清掉条数（已办 36 条堆积的出口）。 */
    fun clearDone(context: Context): Int {
        var n = 0
        for (t in list(context)) {
            if (t.done && deleteSoft(context, t.id)) n += 1
        }
        return n
    }

    /** 确认待办入正式库（带跨库查重：同文本未办已存在 → 不再建第二条，只清待确认队列）。 */
    fun createFromPending(context: Context, p: PendingMessageTodo, done: Boolean = false): Boolean {
        val fresh = textOf(p.text)
        for (t in list(context)) {
            if (!t.done && textOf(t.text) == fresh) return false   // 已有同文本未办：防重复入库
        }
        val now = PendingMessageTodoStore.nowIso()
        val base = fromPending(p)
        return write(
            context,
            if (done) base.copy(done = true, done_at = now) else base
        )
    }

    private fun textOf(s: String): String = s.replace("\\s+".toRegex(), "")

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

    /** 从待确认队列转正式待办（用户点 ✓）。经 createFromPending 调用；直接调用不查重。 */
    fun fromPending(p: PendingMessageTodo): Todo {
        val now = PendingMessageTodoStore.nowIso()
        return Todo(
            id = p.id,
            text = p.text,
            who = p.who,
            when_text = p.whenText,
            due_at = p.dueIso,
            raw = p.raw,
            source = p.source,
            sender = p.sender,
            created_at = now,
            updated_at = now
        )
    }
}
