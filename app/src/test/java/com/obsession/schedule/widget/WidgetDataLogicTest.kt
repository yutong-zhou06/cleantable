package com.obsession.schedule.widget

import com.obsession.schedule.data.CourseEntity
import com.obsession.schedule.data.TimeSlotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 小组件数据层的纯逻辑测试。
 *
 * 这一层刻意不碰 Android API（不查库、不取资源），所以能用普通 JVM 单测覆盖 ——
 * 旧的 Canvas 渲染器正是因为把布局和绘制揉在一起，才一行都测不了。
 */
class WidgetDataLogicTest {

    // ----------------------------------------------------------------
    // 时间解析
    // ----------------------------------------------------------------

    @Test
    fun `分钟解析 合法时间`() {
        assertEquals(0, WidgetData.minutesOf("00:00"))
        assertEquals(480, WidgetData.minutesOf("08:00"))
        assertEquals(23 * 60 + 59, WidgetData.minutesOf("23:59"))
    }

    @Test
    fun `分钟解析 非法输入一律返回 null`() {
        assertNull("占位时间", WidgetData.minutesOf("--:--"))
        assertNull("段数不对", WidgetData.minutesOf("8:0:0"))
        assertNull("小时越界", WidgetData.minutesOf("24:00"))
        assertNull("分钟越界", WidgetData.minutesOf("08:60"))
        assertNull("空串", WidgetData.minutesOf(""))
        assertNull("非数字", WidgetData.minutesOf("ab:cd"))
    }

    // ----------------------------------------------------------------
    // 状态判定
    // ----------------------------------------------------------------

    @Test
    fun `状态 已结束`() {
        // 现在 10:00，课是 08:00-09:40
        assertEquals(STATE_PAST, WidgetData.stateOf(600, "08:00", "09:40"))
    }

    @Test
    fun `状态 正在上`() {
        // 现在 09:00，课是 08:00-09:40
        assertEquals(STATE_ONGOING, WidgetData.stateOf(540, "08:00", "09:40"))
    }

    @Test
    fun `状态 未开始`() {
        // 现在 07:00，课是 08:00-09:40
        assertEquals(STATE_UPCOMING, WidgetData.stateOf(420, "08:00", "09:40"))
    }

    @Test
    fun `状态 恰好在下课那一刻算已结束`() {
        assertEquals(STATE_PAST, WidgetData.stateOf(580, "08:00", "09:40"))
    }

    @Test
    fun `状态 恰好在上课那一刻算正在上`() {
        assertEquals(STATE_ONGOING, WidgetData.stateOf(480, "08:00", "09:40"))
    }

    @Test
    fun `状态 作息没配时算未开始而不是已结束`() {
        // 时间解析不出来时宁可当成「即将到来」，也不要淡出成一门上过的课
        assertEquals(STATE_UPCOMING, WidgetData.stateOf(600, "--:--", "--:--"))
    }

    // ----------------------------------------------------------------
    // 星期序号
    // ----------------------------------------------------------------

    @Test
    fun `星期序号 周一为 1 周日为 7`() {
        assertEquals(1, WidgetData.weekdayIndex(calendarOn(Calendar.MONDAY)))
        assertEquals(4, WidgetData.weekdayIndex(calendarOn(Calendar.THURSDAY)))
        assertEquals(6, WidgetData.weekdayIndex(calendarOn(Calendar.SATURDAY)))
        assertEquals(7, WidgetData.weekdayIndex(calendarOn(Calendar.SUNDAY)))
    }

    // ----------------------------------------------------------------
    // 课程卡文案
    // ----------------------------------------------------------------

    @Test
    fun `单节课的节次标签`() {
        val card = card(node = 3, endNode = 3, startTime = "08:00", endTime = "08:45")
        assertEquals("第3节", card.nodeLabel)
        assertEquals("第3节 08:00", card.nodeTimeLabel)
        assertEquals("08:00-08:45", card.timeRange)
    }

    @Test
    fun `连堂课的节次标签是区间`() {
        val card = card(node = 3, endNode = 4, startTime = "08:00", endTime = "09:40")
        assertEquals("第3-4节", card.nodeLabel)
        assertEquals("第3-4节 08:00", card.nodeTimeLabel)
    }

    @Test
    fun `教室为空时退回节次 不留空行`() {
        val blank = card(node = 2, endNode = 2, room = "")
        assertEquals("第2节", blank.roomOrNode)

        val withRoom = card(node = 2, endNode = 2, room = "A101")
        assertEquals("A101", withRoom.roomOrNode)
    }

    // ----------------------------------------------------------------
    // 课程 → 卡片
    // ----------------------------------------------------------------

    @Test
    fun `卡片按起始节次排序`() {
        val cards = WidgetData.buildCards(
            listOf(
                course(name = "下午课", startNode = 5),
                course(name = "早课", startNode = 1),
                course(name = "上午课", startNode = 3)
            ),
            slots = slots(),
            nowMinutes = null
        )
        assertEquals(listOf("早课", "上午课", "下午课"), cards.map { it.name })
    }

    @Test
    fun `连堂课的结束时间取末节下课 而不是首节`() {
        // 第 1-2 节连堂：开始时间取第 1 节，结束时间必须取第 2 节的下课时间
        val cards = WidgetData.buildCards(
            listOf(course(name = "连堂", startNode = 1, step = 2)),
            slots = slots(),
            nowMinutes = null
        )
        assertEquals("08:00", cards[0].startTime)
        assertEquals("09:40", cards[0].endTime)
        assertEquals(1, cards[0].node)
        assertEquals(2, cards[0].endNode)
    }

    @Test
    fun `明天的课一律按未开始处理`() {
        val cards = WidgetData.buildCards(
            listOf(course(name = "明天的课", startNode = 1)),
            slots = slots(),
            nowMinutes = null
        )
        assertEquals(STATE_UPCOMING, cards[0].state)
    }

    @Test
    fun `今天的课按当前时间判出真实状态`() {
        val cards = WidgetData.buildCards(
            listOf(
                course(name = "早课", startNode = 1),
                course(name = "上午课", startNode = 3)
            ),
            slots = slots(),
            nowMinutes = 500 // 08:20：第 1 节(08:00-08:45)正在进行，第 3 节还没开始
        )
        assertEquals(STATE_ONGOING, cards[0].state)
        assertEquals(STATE_UPCOMING, cards[1].state)
    }

    @Test
    fun `没有作息数据时时间显示占位符`() {
        val cards = WidgetData.buildCards(
            listOf(course(name = "无作息", startNode = 9)),
            slots = emptyMap(),
            nowMinutes = null
        )
        assertEquals("--:--", cards[0].startTime)
        assertEquals("--:--", cards[0].endTime)
        assertEquals(STATE_UPCOMING, cards[0].state)
    }

    // ----------------------------------------------------------------
    // 快照头部文案
    // ----------------------------------------------------------------

    @Test
    fun `快照头部 缺学期名时退到默认`() {
        val snapshot = WidgetSnapshot(
            termName = "",
            weekLabel = "第4周",
            dateText = "3.27",
            dayLabel = "周日",
            today = emptyList(),
            tomorrowLabel = "周一",
            tomorrow = emptyList(),
            week = emptyList()
        )
        assertEquals("我的课表", snapshot.headerLeft)
        assertEquals("3.27 第4周 周日", snapshot.headerRight)
    }

    @Test
    fun `快照头部 没设学期起始日时不显示周次`() {
        val snapshot = WidgetSnapshot(
            termName = "大二上",
            weekLabel = "",
            dateText = "3.27",
            dayLabel = "周日",
            today = emptyList(),
            tomorrowLabel = "周一",
            tomorrow = emptyList(),
            week = emptyList()
        )
        assertEquals("大二上", snapshot.headerLeft)
        assertEquals("3.27 周日", snapshot.headerRight)
    }

    @Test
    fun `短日期 去掉年份只留月日`() {
        val snapshot = WidgetSnapshot(
            termName = "大二上",
            weekLabel = "第4周",
            dateText = "2026/9/18",
            dayLabel = "周五",
            today = emptyList(),
            tomorrowLabel = "周六",
            tomorrow = emptyList(),
            week = emptyList()
        )
        assertEquals("9.18", snapshot.shortDate)
        assertEquals("9.18 周五", snapshot.shortDayLabel)
        assertEquals("9.18 第4周 周五", snapshot.shortHeaderRight)
    }

    @Test
    fun `短日期 日期缺失时不留下多余空格`() {
        val snapshot = WidgetSnapshot(
            termName = "",
            weekLabel = "",
            dateText = "",
            dayLabel = "周五",
            today = emptyList(),
            tomorrowLabel = "",
            tomorrow = emptyList(),
            week = emptyList()
        )
        assertEquals("", snapshot.shortDate)
        assertEquals("周五", snapshot.shortDayLabel)
        assertEquals("周五", snapshot.shortHeaderRight)
    }

    // ----------------------------------------------------------------
    // 周视图列
    // ----------------------------------------------------------------

    @Test
    fun `周视图 每天一列且标出今天`() {
        val columns = (1..7).map { idx ->
            DayColumn(
                dayIndex = idx,
                label = WEEKDAY_NAMES[idx - 1],
                isToday = idx == 4,
                courses = if (idx == 4) listOf(card(node = 1, endNode = 1)) else emptyList()
            )
        }
        assertEquals(7, columns.size)
        assertEquals("周一", columns.first().label)
        assertEquals("周日", columns.last().label)
        assertEquals(1, columns.count { it.isToday })
        assertTrue(columns[3].isToday)
        assertFalse(columns[0].isToday)
        assertEquals(1, columns[3].courses.size)
    }

    // ----------------------------------------------------------------
    // 夹具
    // ----------------------------------------------------------------

    private fun calendarOn(dayOfWeek: Int): Calendar =
        Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, dayOfWeek) }

    private fun card(
        node: Int,
        endNode: Int,
        room: String = "A101",
        startTime: String = "08:00",
        endTime: String = "08:45"
    ) = CourseCard(
        name = "测试课",
        teacher = "X老师",
        room = room,
        startTime = startTime,
        endTime = endTime,
        node = node,
        endNode = endNode,
        state = STATE_UPCOMING,
        colorArgb = 0xFF1D9E75.toInt()
    )

    private fun course(name: String, startNode: Int, step: Int = 1) = CourseEntity(
        name = name,
        dayOfWeek = 1,
        startNode = startNode,
        step = step
    )

    /** 1-4 节，每节 45 分钟、课间 10 分钟 */
    private fun slots(): Map<Int, TimeSlotEntity> = listOf(
        TimeSlotEntity(node = 1, startTime = "08:00", endTime = "08:45"),
        TimeSlotEntity(node = 2, startTime = "08:55", endTime = "09:40"),
        TimeSlotEntity(node = 3, startTime = "10:00", endTime = "10:45"),
        TimeSlotEntity(node = 4, startTime = "10:55", endTime = "11:40")
    ).associateBy { it.node }
}
