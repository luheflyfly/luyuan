package com.luyuan.data

import android.content.Context
import com.luyuan.platform.StorageLocator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * SYNC_FORMAT v2 实体（§十）：消费 / 课程。PC 端产出到同步根目录（expense_<id8>.json / course_<id8>.json），
 * 随 Syncthing 到手机。B1 只读（记账/课程页展示），写回（手动补记/改课）后批补。
 * 兼容铁律：未知字段一律忽略（ignoreUnknownKeys）；坏文件跳过；.sync-conflict 副本跳过。
 */
@Serializable
data class Expense(
    val kind: String = "",
    val id: String,
    val amount: Double = 0.0,
    val item: String = "",
    val category: String = "",      // 缺省归「其他」
    val spent_at: String = "",
    val source: String = "",        // screenshot | manual
    val image: String? = null,
    val tags: List<String> = emptyList(),
    val device: String = "",
    val deleted: Boolean = false,
    val created_at: String = "",
    val updated_at: String = ""
)

@Serializable
data class Course(
    val kind: String = "",
    val id: String,
    val name: String = "",
    val teacher: String = "",
    val place: String = "",
    val weekday: Int = 1,           // 1=周一 … 7=周日
    val start: String = "",         // "08:00"
    val end: String = "",           // "09:40"
    val weeks: String = "",         // "1-16"（周次过滤 PC 侧配 semester_start，App B1 不过滤）
    val semester: String = "",
    val category: String = "",      // vc85：PC 课务导出的课目分类（数学/思政/外语…）；空=旧文件走课名归类
    val color: String = "",         // vc85：PC 课务导出的课目色 "#1d4ed8"；空=走四分类色
    val source: String = "",        // vc85：keiwu=PC 课务系统导出
    val device: String = "",
    val schema: Int = 2,            // SYNC_FORMAT §10.1 课表实体格式版本
    val deleted: Boolean = false,
    val created_at: String = "",
    val updated_at: String = ""
)

val v2Json: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = true
}

object V2EntityRepository {

    private fun scan(context: Context, prefix: String): List<File> = try {
        val dir: File = StorageLocator.getRoot(context)
        val files: Array<File> = dir.listFiles() ?: emptyArray()
        files.filter {
            it.isFile && it.name.startsWith(prefix, true) && !it.name.contains(".sync-conflict")
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** 全部未删消费，按 spent_at（缺省 created_at）新→旧 */
    fun listExpenses(context: Context): List<Expense> = try {
        scan(context, "expense_").mapNotNull { f ->
            try {
                val e = v2Json.decodeFromString(Expense.serializer(), f.readText(Charsets.UTF_8))
                if (e.kind == "expense" && !e.deleted) e else null
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.spent_at.ifBlank { it.created_at } }
    } catch (_: Exception) {
        emptyList()
    }

    /** 全部未删课程，按 周几 + 开始时间 排序 */
    fun listCourses(context: Context): List<Course> = try {
        scan(context, "course_").mapNotNull { f ->
            try {
                val c = v2Json.decodeFromString(Course.serializer(), f.readText(Charsets.UTF_8))
                if (c.kind == "course" && !c.deleted) c else null
            } catch (_: Exception) {
                null
            }
        }.sortedWith(compareBy({ it.weekday }, { it.start }))
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * 手机端加课（木案课表「点空格子=加课」，B5 2026-09-13）：写 course_<id前8位>.json 到同步根目录，
     * 与 PC 导入产出的文件同格式同目录，Syncthing 双向互不冲突（各自独立文件）。
     */
    fun saveCourse(
        context: Context,
        name: String,
        teacher: String,
        place: String,
        weekday: Int,
        start: String,
        end: String,
        weeks: String
    ): Course {
        val now = java.time.OffsetDateTime.now().toString()
        val c = Course(
            kind = "course",
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            teacher = teacher,
            place = place,
            weekday = weekday,
            start = start,
            end = end,
            weeks = weeks,
            device = "phone",
            created_at = now,
            updated_at = now
        )
        val f = File(StorageLocator.getRoot(context), "course_" + c.id.take(8) + ".json")
        f.writeText(v2Json.encodeToString(Course.serializer(), c), Charsets.UTF_8)
        return c
    }
}
