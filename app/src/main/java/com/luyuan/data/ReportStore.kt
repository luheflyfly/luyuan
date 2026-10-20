package com.luyuan.data

import android.content.Context
import com.luyuan.platform.StorageLocator
import kotlinx.serialization.Serializable
import java.io.File

/**
 * SYNC_FORMAT §10.6 实体：路远远汇报（kind:"report"，vc125/v1.48.0 新增）。
 *
 * 落点 = 同步根目录 `report_<id前8位>.json`（与 todo_/expense_ 同款，不碰 data/notes 笔记命名），
 * 由电脑上的路远远（luyuan3\路远助手）产出——原 QQ 私聊播报（晨报/新收录/到点提醒/管家讲解/群简报）
 * 2026-10-19 路河拍板"QQ 不留"，全改写这里；手机与 PC 工作台（8777「路远远」页签）同看。
 *
 * 读侧纪律：未知 kind / 坏文件安全跳过；deleted 墓碑过滤（与 todo 同款）。
 * 写侧纪律：只做已读/未读标记与物理删——**fresh 读盘只改 read/read_at/updated_at**（vc98 纪律），
 * 绝不拿旧副本整文件覆盖；路远远侧只新增文件不改写旧文件，Syncthing 零冲突。
 */
@Serializable
data class Report(
    val kind: String = "report",
    val id: String,
    val title: String = "",
    val summary: String = "",       // vc126：一行摘要（列表第二眼；空=渲染端取正文首行兜底）
    val category: String = "",      // 晨报 / 新收录 / 提醒 / 管家 / 简报 / 来件；未知值前端按「汇报」兜底
    val text: String = "",
    val ref: String? = null,        // 关联实体 id（如来源 todo），仅溯源
    val source: String = "luyuanyuan",
    val read: Boolean = false,
    val read_at: String? = null,
    val device: String = "pc",
    val schema: Int = 3,
    val deleted: Boolean = false,
    val created_at: String = "",
    val updated_at: String = ""
)

object ReportStore {

    private const val PREFIX = "report_"

    fun fileFor(context: Context, id: String): File =
        File(StorageLocator.getRoot(context), "$PREFIX${id.take(8)}.json")

    /** vc115 姿势：tmp+rename 原子写 */
    private fun write(context: Context, report: Report): Boolean = try {
        val f = fileFor(context, report.id)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(v2Json.encodeToString(Report.serializer(), report), Charsets.UTF_8)
        tmp.renameTo(f) || (f.delete() && tmp.renameTo(f))
    } catch (_: Exception) {
        false
    }

    private fun readFresh(context: Context, id: String): Report? = try {
        val f = fileFor(context, id)
        if (!f.exists()) null
        else v2Json.decodeFromString(Report.serializer(), f.readText(Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    /** 全部未删汇报（新在前；连已读一起给——页签要分未读/已看两段） */
    fun list(context: Context): List<Report> = try {
        val dir = StorageLocator.getRoot(context)
        (dir.listFiles() ?: emptyArray())
            .filter {
                it.isFile && it.name.startsWith(PREFIX, true) &&
                    !it.name.contains(".sync-conflict")
            }
            .mapNotNull { f ->
                try {
                    val r = v2Json.decodeFromString(Report.serializer(), f.readText(Charsets.UTF_8))
                    if (r.kind == "report" && !r.deleted) r else null
                } catch (_: Exception) {
                    null
                }
            }
            .sortedByDescending { it.created_at }
    } catch (_: Exception) {
        emptyList()
    }

    fun unreadCount(context: Context): Int = list(context).count { !it.read }

    /** 谁点谁写：fresh 读盘只改 read/read_at，bump updated_at 回写（vc98 纪律） */
    fun setRead(context: Context, id: String, read: Boolean): Boolean {
        val disk = readFresh(context, id) ?: return false
        val now = PendingMessageTodoStore.nowIso()
        return write(
            context, disk.copy(
                read = read,
                read_at = if (read) now else null,
                updated_at = now
            )
        )
    }

    /** 全部已读 */
    fun readAll(context: Context): Int {
        var n = 0
        for (r in list(context)) {
            if (!r.read && setRead(context, r.id, true)) n += 1
        }
        return n
    }

    /** 删一条：物理删（与 PC 端「✗ 删掉」同款；汇报是一次性读物，不留墓碑省同步根） */
    fun discard(context: Context, id: String): Boolean = try {
        fileFor(context, id).delete()
    } catch (_: Exception) {
        false
    }
}
