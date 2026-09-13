package com.luyuan.data

import android.content.Context
import com.luyuan.platform.StorageLocator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.Collator
import java.util.Locale

/**
 * 联系人（PC 端人脉页写入 contacts/ 子目录，随 Syncthing 双向同步）。
 * 文件名约定：字母_姓名.json（如 X_谢凤林.json）；App 端只按 id 读写，原文件名原地覆盖。
 */
@Serializable
data class ContactTodo(
    val id: String,
    val text: String,
    val done: Boolean = false,
    val created_at: String = "",
    // SYNC_FORMAT §8 可选字段（PC 端 09-06 已定义）：提醒时间 / 已弹标记，App 端 v1.11 起支持
    val remind_at: String? = null,
    val reminded: Boolean? = null
)

@Serializable
data class Contact(
    val id: String,
    val name: String,
    val letter: String = "",
    // SYNC_FORMAT v2：PC 端写入的是 initial（后端 pypinyin 算好），
    // letter 缺省时回退用它做字母分组（兼容 v1 的 letter）
    val initial: String = "",
    val phone: String = "",
    val wechat: String = "",
    val qq: String = "",
    val birthday: String = "",
    val info: List<String> = emptyList(),
    // SYNC_FORMAT v3（2026-09-13）：group=分组名（空=未分组），sid=学号（排序取前导数字）。
    // 老文件缺这两个字段——解析层必须给默认值（kotlinx 缺键即抛的铁律）。
    val group: String = "",
    val sid: String = "",
    val todos: List<ContactTodo> = emptyList(),
    val created_at: String = "",
    val updated_at: String = ""
) {
    val undoneTodos: List<ContactTodo> get() = todos.filter { !it.done }
    val displayLetter: String
        get() = letter.ifBlank { initial.trim().uppercase().take(1) }.ifBlank { "#" }

    /** 学号排序键：取前导数字（如「18号」→18）；无数字排最后，与 PC 端 contacts 口径一致 */
    val sidSortKey: Long
        get() = sid.trim().takeWhile { it.isDigit() }.toLongOrNull() ?: Long.MAX_VALUE
}

val contactJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = true
}

object ContactRepository {

    fun contactsDir(context: Context): File =
        File(StorageLocator.getRoot(context), "contacts").also { it.mkdirs() }

    /** 全部联系人，按字母分组排序（字母取 PC 写入的 letter，缺省归入 #）。
     *  跳过 .sync-conflict 冲突副本；同 id 去重取 updated_at 最新（防 LazyColumn 重复 key 闪退）。 */
    fun listContacts(context: Context): List<Contact> {
        return try {
            val dir = contactsDir(context)
            val out = mutableListOf<Contact>()
            for (f in dir.listFiles() ?: emptyArray()) {
                if (!f.isFile || !f.name.endsWith(".json", true)) continue
                if (f.name.contains(".sync-conflict")) continue
                try {
                    out.add(contactJson.decodeFromString(Contact.serializer(), f.readText(Charsets.UTF_8)))
                } catch (_: Exception) {
                    // 跳过坏文件
                }
            }
            val dedup = out.sortedByDescending { it.updated_at }.distinctBy { it.id }
            val collator = Collator.getInstance(Locale.CHINA)
            dedup.sortedWith(
                compareBy({ it.letter.ifBlank { "#" } }, { collator.getCollationKey(it.name) })
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun findFile(context: Context, contactId: String): Pair<File, Contact>? {
        val dir = contactsDir(context)
        for (f in dir.listFiles() ?: emptyArray()) {
            if (!f.isFile || !f.name.endsWith(".json", true)) continue
            try {
                val c = contactJson.decodeFromString(Contact.serializer(), f.readText(Charsets.UTF_8))
                if (c.id == contactId) return f to c
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** 勾/取消勾选一条联系人待办，原地写回文件（同步回 PC） */
    fun toggleTodo(context: Context, contactId: String, todoId: String) {
        val (file, c) = findFile(context, contactId) ?: return
        val updated = c.copy(
            todos = c.todos.map {
                if (it.id == todoId) it.copy(done = !it.done) else it
            },
            updated_at = NoteRepository.nowIso()
        )
        file.writeText(contactJson.encodeToString(Contact.serializer(), updated), Charsets.UTF_8)
    }

    /**
     * 贪睡（对齐 PC 端 /todos/{tid}/snooze 口径）：提醒推后 hours 小时并复位已弹标记；
     * hours=null = 取消提醒（remind_at 置空）。原地写回，Syncthing 同步回 PC。
     */
    fun snoozeTodo(context: Context, contactId: String, todoId: String, hours: Int?) {
        val (file, c) = findFile(context, contactId) ?: return
        val updated = c.copy(
            todos = c.todos.map {
                if (it.id == todoId) it.copy(
                    remind_at = hours?.let { h ->
                        java.time.OffsetDateTime.now().plusHours(h.toLong()).toString()
                    },
                    reminded = false
                ) else it
            },
            updated_at = NoteRepository.nowIso()
        )
        file.writeText(contactJson.encodeToString(Contact.serializer(), updated), Charsets.UTF_8)
    }

    /** v2 名片页：加一件待办（回车即存），原地写回，同步回 PC */
    fun addTodo(context: Context, contactId: String, text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        val (file, c) = findFile(context, contactId) ?: return
        val newTodo = ContactTodo(
            id = "t_" + System.currentTimeMillis().toString(36) + "_" + (0..9999).random().toString(),
            text = t,
            done = false,
            created_at = NoteRepository.nowIso()
        )
        val updated = c.copy(todos = c.todos + newTodo, updated_at = NoteRepository.nowIso())
        file.writeText(contactJson.encodeToString(Contact.serializer(), updated), Charsets.UTF_8)
    }

    /** v2 名片页：加待办并带提醒时间（生日提前 3 天提醒复用此通道，不新增联系人字段、不改 SYNC_FORMAT） */
    fun addTodoWithRemind(context: Context, contactId: String, text: String, remindAtIso: String) {
        val t = text.trim()
        if (t.isBlank()) return
        val (file, c) = findFile(context, contactId) ?: return
        val newTodo = ContactTodo(
            id = "t_" + System.currentTimeMillis().toString(36) + "_" + (0..9999).random().toString(),
            text = t,
            done = false,
            created_at = NoteRepository.nowIso(),
            remind_at = remindAtIso
        )
        val updated = c.copy(todos = c.todos + newTodo, updated_at = NoteRepository.nowIso())
        file.writeText(contactJson.encodeToString(Contact.serializer(), updated), Charsets.UTF_8)
    }

    fun findContact(context: Context, contactId: String): Contact? =
        findFile(context, contactId)?.second

    /** 到点标记：reminded=true 写回（防重复弹） */
    fun markTodoReminded(context: Context, contactId: String, todoId: String) {
        val (file, c) = findFile(context, contactId) ?: return
        val updated = c.copy(
            todos = c.todos.map {
                if (it.id == todoId) it.copy(reminded = true) else it
            },
            updated_at = NoteRepository.nowIso()
        )
        file.writeText(contactJson.encodeToString(Contact.serializer(), updated), Charsets.UTF_8)
    }

    /** 未完成且带 remind_at 的待办（供提醒调度器挂闹钟），解析失败的时间跳过 */
    fun pendingTodoReminders(context: Context): List<Pair<Contact, ContactTodo>> {
        val out = mutableListOf<Pair<Contact, ContactTodo>>()
        try {
            for (c in listContacts(context)) {
                for (t in c.todos) {
                    if (t.done || t.remind_at.isNullOrBlank() || t.reminded == true) continue
                    out.add(c to t)
                }
            }
        } catch (_: Exception) {
        }
        return out
    }
}
