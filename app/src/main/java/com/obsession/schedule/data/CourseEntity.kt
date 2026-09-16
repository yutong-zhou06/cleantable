package com.obsession.schedule.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 每周都上 */
const val WEEK_TYPE_ALL = 0

/** 单周 */
const val WEEK_TYPE_ODD = 1

/** 双周 */
const val WEEK_TYPE_EVEN = 2

@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val teacher: String = "",
    val room: String = "",
    /** 1 = 周一 … 7 = 周日 */
    val dayOfWeek: Int,
    /** 起始节次，从 1 开始 */
    val startNode: Int,
    /** 连续节数，"连堂" */
    val step: Int = 1,
    val startWeek: Int = 1,
    val endWeek: Int = 16,
    val weekType: Int = WEEK_TYPE_ALL,
    val colorArgb: Int = 0xFF1D9E75.toInt(),
    val note: String = "",
    /** 所属课表。默认 1 = v0.2 迁移过来的主课表 */
    val timetableId: Long = 1L
) {
    /** 结束节次（含） */
    val endNode: Int get() = startNode + step - 1

    /** 该课程在第 [week] 周是否要上 */
    fun activeIn(week: Int): Boolean {
        if (week < startWeek || week > endWeek) return false
        return when (weekType) {
            WEEK_TYPE_ODD -> week % 2 == 1
            WEEK_TYPE_EVEN -> week % 2 == 0
            else -> true
        }
    }

    /** 用于展示的周次文字，例如 "1-16周 单周" */
    fun weekLabel(): String {
        val span = if (startWeek == endWeek) "第${startWeek}周" else "$startWeek-$endWeek 周"
        return when (weekType) {
            WEEK_TYPE_ODD -> "$span 单"
            WEEK_TYPE_EVEN -> "$span 双"
            else -> span
        }
    }
}
