package com.obsession.schedule.widget

import android.content.Context
import com.obsession.schedule.data.AppDatabase
import com.obsession.schedule.data.ConfigStore
import com.obsession.schedule.data.CourseEntity
import com.obsession.schedule.data.TimeSlotEntity
import com.obsession.schedule.data.mondayOfDay
import kotlinx.coroutines.runBlocking
import java.util.Calendar
import java.util.Locale

/** 课程状态：已结束 / 正在上 / 未开始 */
const val STATE_PAST = 0
const val STATE_ONGOING = 1
const val STATE_UPCOMING = 2

/** 周一 .. 周日 */
val WEEKDAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 一张课程卡需要的数据。
 *
 * 刻意与渲染方式解耦 —— 不管底下是 XML RemoteViews 还是别的东西，这里都是唯一的数据来源，
 * 于是这一层可以脱离 Android 跑单元测试（见 WidgetDataLogicTest）。
 */
data class CourseCard(
    val name: String,
    val teacher: String,
    val room: String,
    val startTime: String,
    val endTime: String,
    /** 起始节次 */
    val node: Int,
    /** 结束节次（连堂时大于 [node]） */
    val endNode: Int,
    /** STATE_PAST / STATE_ONGOING / STATE_UPCOMING */
    val state: Int,
    /** 课表网格里这门课的颜色，渲染时按主题派生具体用色 */
    val colorArgb: Int
) {
    /** 「第3节」，连堂为「第3-4节」 */
    val nodeLabel: String get() = if (endNode > node) "第${node}-${endNode}节" else "第${node}节"

    /** 日视图第一行的「节次 开始时间」 */
    val nodeTimeLabel: String get() = "$nodeLabel $startTime"

    val timeRange: String get() = "$startTime-$endTime"

    /** 教室为空时退回节次，避免卡片里出现空行 */
    val roomOrNode: String get() = room.ifBlank { nodeLabel }
}

/** 周视图里的一列 = 一天 */
data class DayColumn(
    val dayIndex: Int,
    val label: String,
    val isToday: Boolean,
    val courses: List<CourseCard>
)

/**
 * 一次渲染所需的全部数据。
 *
 * 四个组件（周视图 / 日视图 / 近日课程 / 今日课程）共用这一份快照，各取所需，
 * 数据库只查一次。
 */
data class WidgetSnapshot(
    val termName: String,
    /** 「第4周」；没设学期起始日时为空串 */
    val weekLabel: String,
    /** 「2022/3/27」 */
    val dateText: String,
    /** 「周日」 */
    val dayLabel: String,
    val today: List<CourseCard>,
    val tomorrowLabel: String,
    val tomorrow: List<CourseCard>,
    /** 7 列，周视图用 */
    val week: List<DayColumn>
) {
    /** 头部左侧：学期/课表名 */
    val headerLeft: String get() = termName.ifBlank { "我的课表" }

    /** 头部右侧：「3.27 第4周 周日」 */
    val headerRight: String
        get() = listOf(dateText, weekLabel, dayLabel).filter { it.isNotBlank() }.joinToString(" ")

    /** 「9.18」—— 去掉年份的短日期，窄布局（2×2、双栏头部）用 */
    val shortDate: String get() = dateText.split("/").takeLast(2).joinToString(".")

    /** 「9.18 周五」—— 2×2 紧凑版头部右侧 */
    val shortDayLabel: String
        get() = listOf(shortDate, dayLabel).filter { it.isNotBlank() }.joinToString(" ")

    /** 「9.18 第4周 周五」—— 近日课程的两栏已经各占一半宽，头部放不下全年份日期 */
    val shortHeaderRight: String
        get() = listOf(shortDate, weekLabel, dayLabel).filter { it.isNotBlank() }.joinToString(" ")

    val todayCount: Int get() = today.size
    val tomorrowCount: Int get() = tomorrow.size
}

object WidgetData {

    /**
     * 读一次数据库，拼出快照。
     *
     * 内部用 runBlocking，**调用方必须在 IO 线程**（AppWidgetProvider 走 goAsync 后台线程）。
     */
    fun load(context: Context): WidgetSnapshot {
        val cal = Calendar.getInstance()
        val todayIdx = weekdayIndex(cal)
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        val dao = AppDatabase.get(context).scheduleDao()
        val activeId = ConfigStore(context).activeId(1L)
        val timetable = runBlocking {
            dao.timetable(activeId) ?: dao.allTimetables().firstOrNull()
        } ?: return empty(todayIdx)

        val baseConfig = timetable.toConfig()
        // 没设过学期起始日（firstWeekStart=0）时按「本周一」兜底：不兜底 weekOfDay 会算出
        // 约三千周，activeIn 对所有课程返回 false，明明有课却显示「今天没有课」
        val now = System.currentTimeMillis()
        val hasTerm = baseConfig.firstWeekStart > 0L
        val config = if (hasTerm) baseConfig else baseConfig.copy(firstWeekStart = mondayOfDay(now))
        val week = config.weekOfDay(now)

        val tid = timetable.id
        val slots = runBlocking { dao.allTimeSlots(tid) }.associateBy { it.node }
        val all = runBlocking { dao.allCourses(tid) }

        // 周日 → 周一跨周：明天的周次要按「明天的日期」重算，
        // 否则周日晚上明天的课会被算丢
        val tomorrowCal = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowIdx = weekdayIndex(tomorrowCal)
        val tomorrowWeek = config.weekOfDay(tomorrowCal.timeInMillis)

        val weekColumns = (1..7).map { idx ->
            val isToday = idx == todayIdx
            DayColumn(
                dayIndex = idx,
                label = WEEKDAY_NAMES[idx - 1],
                isToday = isToday,
                courses = buildCards(
                    all.filter { it.dayOfWeek == idx && it.activeIn(week) },
                    slots,
                    // 只有今天按真实时间判状态，其余各天一律「未开始」
                    if (isToday) nowMinutes else null
                )
            )
        }

        return WidgetSnapshot(
            termName = timetable.termName.ifBlank { timetable.name },
            weekLabel = if (hasTerm) "第${week}周" else "",
            dateText = String.format(
                Locale.ROOT, "%d/%d/%d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
            ),
            dayLabel = WEEKDAY_NAMES[todayIdx - 1],
            today = weekColumns[todayIdx - 1].courses,
            tomorrowLabel = WEEKDAY_NAMES[tomorrowIdx - 1],
            tomorrow = buildCards(
                all.filter { it.dayOfWeek == tomorrowIdx && it.activeIn(tomorrowWeek) },
                slots,
                null
            ),
            week = weekColumns
        )
    }

    /**
     * 课程实体 → 课程卡。
     *
     * [nowMinutes] 传 null 表示全部按「未开始」处理（今天以外的日子、明天的课）。
     */
    fun buildCards(
        courses: List<CourseEntity>,
        slots: Map<Int, TimeSlotEntity>,
        nowMinutes: Int?
    ): List<CourseCard> = courses.sortedBy { it.startNode }.map { c ->
        // 连堂课（1-2 节）的结束时间取末节下课，而不是首节
        val startSlot = slots[c.startNode]
        val endSlot = slots[c.endNode] ?: startSlot
        val start = startSlot?.startTime ?: "--:--"
        val end = endSlot?.endTime ?: "--:--"
        CourseCard(
            name = c.name,
            teacher = c.teacher,
            room = c.room,
            startTime = start,
            endTime = end,
            node = c.startNode,
            endNode = c.endNode,
            state = if (nowMinutes == null) STATE_UPCOMING else stateOf(nowMinutes, start, end),
            colorArgb = c.colorArgb
        )
    }

    /**
     * 判定课程状态。
     *
     * 作息没配（时间显示 "--:--"）时一律算「未开始」—— 宁可当成即将到来，
     * 也不要因为解析不出时间就判定成已上过而淡出。
     */
    fun stateOf(nowMinutes: Int, start: String, end: String): Int {
        val s = minutesOf(start)
        val e = minutesOf(end)
        return when {
            e != null && nowMinutes >= e -> STATE_PAST
            s != null && nowMinutes >= s -> STATE_ONGOING
            else -> STATE_UPCOMING
        }
    }

    fun minutesOf(hhmm: String): Int? {
        val parts = hhmm.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /** 周一 = 1 … 周日 = 7 */
    fun weekdayIndex(calendar: Calendar): Int {
        val dow = calendar.get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SUNDAY) 7 else dow - 1
    }

    /** 库里一张课表都没有时的空快照（理论上只在异常迁移时出现） */
    private fun empty(todayIdx: Int) = WidgetSnapshot(
        termName = "",
        weekLabel = "",
        dateText = "",
        dayLabel = WEEKDAY_NAMES[todayIdx - 1],
        today = emptyList(),
        tomorrowLabel = "",
        tomorrow = emptyList(),
        week = (1..7).map { DayColumn(it, WEEKDAY_NAMES[it - 1], it == todayIdx, emptyList()) }
    )
}
