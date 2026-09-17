package com.obsession.schedule.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v0.6 作息编辑新增纯逻辑的回归测试：
 * - [TimeSlotEntity.suggestAfter]：中间插入一节时的默认起止时间；
 * - [TimeSlotEntity.shiftTime]：插入后顺延后续节次的时间平移。
 */
class TimeSlotEntityLogicTest {

    @Test
    fun `suggestAfter 从上一节下课加课间起算`() {
        val (start, end) = TimeSlotEntity.suggestAfter("08:45")
        assertEquals("08:55", start)
        assertEquals("09:40", end)
    }

    @Test
    fun `suggestAfter 没有上一节时从默认第1节起算`() {
        val (start, end) = TimeSlotEntity.suggestAfter(null)
        assertEquals("08:00", start)
        assertEquals("08:45", end)
    }

    @Test
    fun `suggestAfter 解析失败也给出合法默认值`() {
        val (start, end) = TimeSlotEntity.suggestAfter("不是时间")
        assertEquals("08:00", start)
        assertEquals("08:45", end)
    }

    @Test
    fun `shiftTime 正常平移并补零`() {
        assertEquals("10:05", TimeSlotEntity.shiftTime("09:00", 65))
        assertEquals("08:55", TimeSlotEntity.shiftTime("8:55", 0))
    }

    @Test
    fun `shiftTime 跨天绕回`() {
        assertEquals("00:30", TimeSlotEntity.shiftTime("23:50", 40))
    }

    @Test
    fun `shiftTime 非法输入原样返回`() {
        assertEquals("--:--", TimeSlotEntity.shiftTime("--:--", 55))
        assertEquals(" garbage ", TimeSlotEntity.shiftTime(" garbage ", 55))
    }

    @Test
    fun `插入顺延的完整场景 - 在第2节后插一节 后续整体后移55分钟`() {
        val delta = TimeSlotEntity.DEFAULT_LESSON_MINUTES + TimeSlotEntity.DEFAULT_BREAK_MINUTES
        assertEquals(55, delta)
        // 原第 3 节 10:00，插入后应为 10:55
        assertEquals("10:55", TimeSlotEntity.shiftTime("10:00", delta))
        // 原第 4 节 10:55，插入后应为 11:50
        assertEquals("11:50", TimeSlotEntity.shiftTime("10:55", delta))
    }
}
