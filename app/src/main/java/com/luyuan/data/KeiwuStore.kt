package com.luyuan.data

import android.content.Context
import com.luyuan.platform.StorageLocator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 课务系统（PC）→ App 的打包参考数据（keiwu_*.json，vc85 · 2026-09-16 手机版批）。
 *
 * PC 课务 server 的 keiwu_export.py 在数据每次变动后往同步根导出四件打包：
 *   keiwu_events.json（校历+学期锚点）/ keiwu_grades.json（成绩）
 *   / keiwu_ledger.json（综测加分台账）/ keiwu_ref.json（CSU 速查：奖学金/竞赛/综测/推免）。
 * 随 Syncthing 到手机，学业页的 校历/成绩/速查 三视图只读消费；课表/作业走既有
 * course_k*.json / todo_k*.json 单件契约（V2EntityRepository / TodoStore），这里不重复。
 *
 * 兼容铁律同全库：ignoreUnknownKeys；坏文件/缺文件整包返回 null（视图显示「等电脑同步」，
 * 绝不抛异常炸页面）。
 */
@Serializable
data class KeiwuEvent(
    val id: String = "",
    val name: String = "",
    val date: String = "",
    val end: String = "",
    val type: String = "其他",
    val remind_days_before: Int = 3,
    val source: String = "",
    val note: String = "",
    val deleted: Boolean = false
)

@Serializable
data class KeiwuClassPeriod(
    val period: Int = 0,
    val time: String = ""
)

@Serializable
data class KeiwuEventsBundle(
    val kind: String = "",
    val schema: Int = 1,
    val updated_at: String = "",
    val semester_start: String = "",
    val total_weeks: Int = 20,
    val class_periods: List<KeiwuClassPeriod> = emptyList(),
    val items: List<KeiwuEvent> = emptyList()
)

@Serializable
data class KeiwuGrade(
    val id: String = "",
    val course_name: String = "",
    val credits: Double = 0.0,
    val grade: String = "",
    val grade_type: String = "percent",
    val attempt: String = "first",
    val semester: String = "",
    val note: String = "",
    val deleted: Boolean = false
)

@Serializable
data class KeiwuGradesBundle(
    val kind: String = "",
    val schema: Int = 1,
    val updated_at: String = "",
    val items: List<KeiwuGrade> = emptyList()
)

@Serializable
data class KeiwuLedgerItem(
    val id: String = "",
    val item: String = "",
    val category: String = "",
    val points: Double = 0.0,
    val source: String = "",
    val note: String = "",
    val semester: String = "",
    val date: String = "",
    val deleted: Boolean = false
)

@Serializable
data class KeiwuLedgerBundle(
    val kind: String = "",
    val schema: Int = 1,
    val updated_at: String = "",
    val items: List<KeiwuLedgerItem> = emptyList()
)

@Serializable
data class KeiwuScholarship(
    val name: String = "",
    val level: String = "",
    val amount: Double? = null,
    val amount_min: Double? = null,
    val amount_max: Double? = null,
    val requirement: String = "",
    val window: String = "",
    val quota: String = ""
)

@Serializable
data class KeiwuCompetition(
    val name: String = "",
    val level: String = "",
    val window: String = "",
    val organizer: String = "",
    val team: String = "",
    val csu_key_funded_64: Boolean = false,
    val csu_strength: Boolean = false
)

@Serializable
data class KeiwuBonus(
    val item: String = "",
    val points: Double = 0.0,
    val cap: String = ""
)

@Serializable
data class KeiwuComprehensive(
    val formula: String = "",
    val applicable_cohorts: String = "",
    val bonuses: List<KeiwuBonus> = emptyList()
)

@Serializable
data class KeiwuTuimianStage(
    val window: String = "",
    val stage: String = "",
    val note: String = ""
)

@Serializable
data class KeiwuTuimian(
    val formula: String = "",
    val veto: List<String> = emptyList(),
    val timeline: List<KeiwuTuimianStage> = emptyList()
)

@Serializable
data class KeiwuRefBundle(
    val kind: String = "",
    val schema: Int = 1,
    val updated_at: String = "",
    val disclaimer: String = "",
    val scholarships: List<KeiwuScholarship> = emptyList(),
    val competitions: List<KeiwuCompetition> = emptyList(),
    val comprehensive: KeiwuComprehensive = KeiwuComprehensive(),
    val tuimian: KeiwuTuimian = KeiwuTuimian()
)

object KeiwuStore {

    /** isLenient：PC 侧个别数字可能带引号，双兼容 */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun read(root: java.io.File, name: String): String? = try {
        val f = java.io.File(root, name)
        if (f.isFile) f.readText(Charsets.UTF_8) else null
    } catch (_: Exception) {
        null
    }

    fun events(context: Context): KeiwuEventsBundle? = try {
        read(StorageLocator.getRoot(context), "keiwu_events.json")
            ?.let { json.decodeFromString(KeiwuEventsBundle.serializer(), it) }
    } catch (_: Exception) {
        null
    }

    fun grades(context: Context): KeiwuGradesBundle? = try {
        read(StorageLocator.getRoot(context), "keiwu_grades.json")
            ?.let { json.decodeFromString(KeiwuGradesBundle.serializer(), it) }
    } catch (_: Exception) {
        null
    }

    fun ledger(context: Context): KeiwuLedgerBundle? = try {
        read(StorageLocator.getRoot(context), "keiwu_ledger.json")
            ?.let { json.decodeFromString(KeiwuLedgerBundle.serializer(), it) }
    } catch (_: Exception) {
        null
    }

    fun ref(context: Context): KeiwuRefBundle? = try {
        read(StorageLocator.getRoot(context), "keiwu_ref.json")
            ?.let { json.decodeFromString(KeiwuRefBundle.serializer(), it) }
    } catch (_: Exception) {
        null
    }
}
