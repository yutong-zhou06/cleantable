package com.obsession.schedule.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一张课表。
 *
 * v0.2 及之前整个应用只有一份课表；v0.3 起支持多学期并存：
 * courses / time_slots 通过 [timetableId] 挂到某张课表下，
 * 学期配置（学期名 / 第一周起始日 / 总周数）也直接内联在这里——
 * 它们本来就是「一张课表的属性」，继续放 SharedPreferences
 * 就要为新课表维护第二份、第三份……生命周期，不如收进库统一管理。
 *
 * v0.2 用户升级时的学期设置由数据库迁移从 SharedPreferences 搬入 id=1 的课表，
 * 数据与配置都不会丢。
 */
@Entity(tableName = "timetables")
data class TimetableEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 显示名，例如「2025–2026 学年 · 秋季学期」 */
    val name: String,
    val termName: String = "",
    /** 第一周周一 0 点的时间戳。0 表示尚未设置，界面按「今天」兜底 */
    val firstWeekStart: Long = 0L,
    val totalWeeks: Int = DEFAULT_TOTAL_WEEKS,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 换算成学期配置对象，供周次计算使用 */
    fun toConfig(): SemesterConfig = SemesterConfig(
        termName = termName.ifBlank { name },
        firstWeekStart = firstWeekStart,
        totalWeeks = totalWeeks
    )
}
