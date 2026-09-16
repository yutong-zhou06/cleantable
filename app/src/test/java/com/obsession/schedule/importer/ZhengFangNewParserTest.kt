package com.obsession.schedule.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正方教务课表页解析器的单元测试。
 *
 * 夹具来自用户真实导出的教务页面（山西工学院 / 正方 jwglxt 新版），
 * 不是手搓的理想 HTML —— 真实页面里那些不规整之处才是解析器最容易错的地方：
 * 跨行 td、同一格里叠两门不同周次的课、`教师 ：`（冒号前有空格）、
 * 课表模式里标签藏在 title 属性上、底部「实践课程」附注。
 *
 * 这些测试跑在纯 JVM 上（jsoup 是纯 Java，解析层不碰 Android API），
 * 所以不必连真机就能验证解析正确性。
 */
class ZhengFangNewParserTest {

    // ---------------------------------------------------------------------
    // 来源识别
    // ---------------------------------------------------------------------

    @Test
    fun `识别出正方新版列表模式`() {
        val doc = org.jsoup.Jsoup.parse(readUtf8("zhengfang_list.html"))
        assertEquals(ScheduleSource.ZHENG_FANG_NEW_LIST, ZhengFangNewParser.detect(doc))
    }

    @Test
    fun `识别出正方新版课表模式`() {
        val doc = org.jsoup.Jsoup.parse(readUtf8("zhengfang_grid.html"))
        assertEquals(ScheduleSource.ZHENG_FANG_NEW_GRID, ZhengFangNewParser.detect(doc))
    }

    @Test
    fun `不认识的页面归为 UNKNOWN`() {
        val doc = org.jsoup.Jsoup.parse("<html><body><p>hello</p></body></html>")
        assertEquals(ScheduleSource.UNKNOWN, ZhengFangNewParser.detect(doc))
    }

    // ---------------------------------------------------------------------
    // 列表模式
    // ---------------------------------------------------------------------

    @Test
    fun `列表模式解析出全部 22 门课`() {
        val result = parseList()
        assertEquals(22, result.courses.size)
    }

    @Test
    fun `列表模式读出学期名`() {
        assertEquals("2026-2027学年第1学期", parseList().termName)
    }

    @Test
    fun `教室与教师不会互相串味`() {
        // 回归测试：最初的正则把「教师 ：李婷」一起圈进了教室字段，
        // 因为两者之间只有一个空格，边界很窄。
        val c = parseList().courses.single {
            it.name == "软件工程" && it.dayOfWeek == 1 && it.startNode == 1
        }
        assertEquals("图书馆303计算机软件开放实验室", c.room)
        assertEquals("李婷", c.teacher)
    }

    @Test
    fun `解析出正确的节次跨度与连堂数`() {
        val c = parseList().courses.single {
            it.name == "虚拟现实技术及应用" && it.dayOfWeek == 3 && it.startWeek == 9
        }
        assertEquals(10, c.startNode)
        assertEquals(3, c.step)          // 10-12 节，连堂 3 节
        assertEquals(12, c.endNode)
    }

    @Test
    fun `未排地点这类占位词归零而不是原样写入`() {
        val c = parseList().courses.single { it.name == "大学体育5" }
        assertEquals("", c.room)
        assertEquals("郭凯杰", c.teacher)
    }

    @Test
    fun `同一格里叠着的两门不同周次课都被解析出来`() {
        // 周二 1-2 节：软件工程 1-12 周 + 现代通信技术 13-14 周。
        // 这不是冲突，是两条互不重叠的周次，缺一条就等于课丢了。
        val sameSlot = parseList().courses.filter { it.dayOfWeek == 2 && it.startNode == 1 }
        assertEquals(2, sameSlot.size)
        assertEquals(setOf("软件工程", "现代通信技术"), sameSlot.map { it.name }.toSet())
        assertEquals(setOf(1 to 12, 13 to 14), sameSlot.map { it.startWeek to it.endWeek }.toSet())
    }

    @Test
    fun `课程分布的课程名计数正确`() {
        val counts = parseList().courses.groupingBy { it.name }.eachCount()
        assertEquals(4, counts["软件工程"])
        assertEquals(4, counts["虚拟现实技术及应用"])
        assertEquals(4, counts["现代通信技术"])
        assertEquals(3, counts["计算机组成原理"])
        assertEquals(3, counts["操作系统"])
        assertEquals(2, counts["习近平新时代中国特色社会主义思想概论"])
        assertEquals(1, counts["形势与政策5"])
        assertEquals(1, counts["大学体育5"])
    }

    @Test
    fun `底部的实践课程如实报告为未导入`() {
        val skipped = parseList().skipped
        assertTrue("应报告实践课程未导入，实际：$skipped", skipped.any { it.contains("实践课程") })
        assertTrue(skipped.any { it.contains("操作系统课程设计") })
    }

    // ---------------------------------------------------------------------
    // 课表模式
    // ---------------------------------------------------------------------

    @Test
    fun `课表模式同样解析出 22 门课`() {
        val outcome = HtmlScheduleImporter.parse(readUtf8("zhengfang_grid.html"))
        val success = outcome as ParseOutcome.Success
        assertEquals(22, success.courses.size)
    }

    @Test
    fun `两种模式对课程名与周次的判断一致`() {
        val fromList = parseList().courses
        val grid = (HtmlScheduleImporter.parse(readUtf8("zhengfang_grid.html"))
            as ParseOutcome.Success).courses
        assertEquals(fromList.map { it.name }.sorted(), grid.map { it.name }.sorted())
        assertEquals(
            fromList.map { "${it.dayOfWeek}-${it.startNode}-${it.startWeek}-${it.endWeek}" }.sorted(),
            grid.map { "${it.dayOfWeek}-${it.startNode}-${it.startWeek}-${it.endWeek}" }.sorted()
        )
    }

    @Test
    fun `课表模式会剥掉教室前面的校区名`() {
        // 课表模式的原文是「山西工学院  图书馆303计算机软件开放实验室」（中间两个空格）
        val grid = (HtmlScheduleImporter.parse(readUtf8("zhengfang_grid.html"))
            as ParseOutcome.Success).courses
        val c = grid.single { it.name == "软件工程" && it.dayOfWeek == 1 && it.startNode == 1 }
        assertEquals("图书馆303计算机软件开放实验室", c.room)
        assertEquals("李婷", c.teacher)
    }

    // ---------------------------------------------------------------------
    // 编码探测
    // ---------------------------------------------------------------------

    @Test
    fun `GBK 文件按声明解码而不是按 UTF-8 硬读`() {
        val decoded = HtmlTextDecoder.decode(readBytes("zhengfang_gbk.html"))
        assertTrue(
            "应识别为 GB 系编码，实际：${decoded.charsetName}",
            decoded.charsetName.contains("GB", ignoreCase = true)
        )
        assertTrue("课程名应可读", decoded.text.contains("高等数学（上）"))
    }

    @Test
    fun `UTF-8 文件不会被误判成 GBK`() {
        val decoded = HtmlTextDecoder.decode(readBytes("zhengfang_list.html"))
        assertEquals("UTF-8", decoded.charsetName)
        assertTrue(decoded.text.contains("软件工程"))
    }

    @Test
    fun `无 BOM 且无声明时按严格 UTF-8 尝试`() {
        val bytes = "<html><body>纯ASCII内容</body></html>".toByteArray(Charsets.UTF_8)
        val decoded = HtmlTextDecoder.decode(bytes)
        assertEquals("UTF-8", decoded.charsetName)
    }

    @Test
    fun `带 BOM 的文件优先按 BOM 判定`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + "<html><body>中文</body></html>".toByteArray(Charsets.UTF_8)
        val decoded = HtmlTextDecoder.decode(bytes)
        assertTrue(decoded.fromBom)
        assertTrue(decoded.text.startsWith("<html>"))
    }

    @Test
    fun `GBK 文件端到端导入成功`() {
        val bytes = readBytes("zhengfang_gbk.html")
        assertTrue(HtmlScheduleImporter.isHtml(bytes))
        val decoded = HtmlTextDecoder.decode(bytes)
        val success = HtmlScheduleImporter.parse(decoded.text) as ParseOutcome.Success

        assertEquals(2, success.courses.size)
        assertEquals("2026-2027学年第2学期", success.termName)

        val math = success.courses.single { it.name == "高等数学（上）" }
        assertEquals(1, math.dayOfWeek)
        assertEquals(1, math.startNode)
        assertEquals(2, math.step)
        assertEquals(1, math.startWeek)
        assertEquals(16, math.endWeek)
        assertEquals("A栋201", math.room)
        assertEquals("张三", math.teacher)

        val physics = success.courses.single { it.name == "大学物理实验" }
        assertEquals(3, physics.dayOfWeek)
    }

    // ---------------------------------------------------------------------
    // 失败与不支持的路径
    // ---------------------------------------------------------------------

    @Test
    fun `空文件给出可读的失败原因`() {
        val outcome = HtmlScheduleImporter.parse("   ")
        assertTrue(outcome is ParseOutcome.Failure)
    }

    @Test
    fun `强智教务被识别但不假装能解析`() {
        val outcome = HtmlScheduleImporter.parse(
            "<html><body><table id=\"kbtable\"><tr><td class=\"kbcontent\">x</td></tr></table></body></html>"
        )
        val unsupported = outcome as ParseOutcome.Unsupported
        assertEquals(ScheduleSource.QIANG_ZHI, unsupported.source)
        assertTrue(unsupported.hint.contains("样本"))
    }

    @Test
    fun `正方旧版被识别但不假装能解析`() {
        val outcome = HtmlScheduleImporter.parse(
            "<html><body><table id=\"Table1\"><tr><td>第1节</td></tr></table></body></html>"
        )
        assertEquals(
            ScheduleSource.ZHENG_FANG_OLD,
            (outcome as ParseOutcome.Unsupported).source
        )
    }

    @Test
    fun `是 HTML 的判断`() {
        assertTrue(HtmlScheduleImporter.isHtml("<!DOCTYPE html><html>".toByteArray()))
        assertTrue(HtmlScheduleImporter.isHtml("  \n<html>".toByteArray()))
        assertTrue(!HtmlScheduleImporter.isHtml("{\"format\":\"obsession\"}".toByteArray()))
    }

    // ---------------------------------------------------------------------
    // 周次解析
    // ---------------------------------------------------------------------

    @Test
    fun `周次区间解析`() {
        val spec = WeekSpec.parse("周数：1-12周")!!
        assertEquals(listOf(1..12), spec.spans)
        assertEquals(0, spec.weekType)
    }

    @Test
    fun `单双周解析`() {
        val odd = WeekSpec.parse("周数：1-16周(单)")!!
        assertEquals(1, odd.weekType)
        assertEquals(listOf(1..16), odd.spans)

        val even = WeekSpec.parse("周数：2-16周（双）")!!
        assertEquals(2, even.weekType)
    }

    @Test
    fun `不连续的周次被拆成多个区间`() {
        // 1,3,5-7 周：模型里 startWeek/endWeek 是一对，不拆就只能丢数据
        val spec = WeekSpec.parse("周数：1,3,5-7周")!!
        assertEquals(listOf(1..1, 3..3, 5..7), spec.spans)
        assertEquals(listOf(1, 3, 5, 6, 7), spec.weeks())
    }

    @Test
    fun `课表模式的 节周 字段也能解析`() {
        val spec = WeekSpec.parse("(1-4节)13-14周")!!
        assertEquals(listOf(13..14), spec.spans)
    }

    @Test
    fun `解析不出周次时返回 null 而不是瞎猜`() {
        assertEquals(null, WeekSpec.parse("软件工程 山西工学院 图书馆303"))
    }

    @Test
    fun `不连续周次会落成多条课记录`() {
        val doc = org.jsoup.Jsoup.parse(
            """
            <html><body><table id="kblist_table">
            <tbody><tr><td colspan="4"><div class="timetable_title">
              <h6 class="pull-left">测试学期</h6></div></td></tr></tbody>
            <tbody id="xq_2"><tr>
              <td id="jc_2-5-6" rowspan="1"><span class="festival">5-6</span></td>
              <td><div class="timetable_con"><span class="title">离散数学</span>
                <p> 周数：1,3,5-7周 校区:某某大学 上课地点：B101 教师 ：王老师</p>
              </div></td>
            </tr></tbody></table></body></html>
            """.trimIndent()
        )
        val success = ZhengFangNewParser.parse(doc, ScheduleSource.ZHENG_FANG_NEW_LIST)!!
        assertEquals(3, success.courses.size)
        assertEquals(
            listOf(1 to 1, 3 to 3, 5 to 7),
            success.courses.map { it.startWeek to it.endWeek }
        )
        assertTrue(success.courses.all { it.dayOfWeek == 2 && it.startNode == 5 && it.step == 2 })
    }

    @Test
    fun `预览文案包含星期节次与周次`() {
        val c = ParsedCourse("操作系统", 3, 6, 2, 9, 14, 0, "", "")
        assertEquals("周三 6-7节 · 9-14 周", c.previewLine())
    }

    // ---------------------------------------------------------------------

    private fun parseList(): ParseOutcome.Success {
        val outcome = HtmlScheduleImporter.parse(readUtf8("zhengfang_list.html"))
        assertNotNull(outcome)
        return outcome as ParseOutcome.Success
    }

    private fun readBytes(name: String): ByteArray {
        val path = "html/$name"
        val stream = javaClass.classLoader?.getResourceAsStream(path)
            ?: javaClass.getResourceAsStream("/$path")
            ?: error("测试夹具不存在：$path（应位于 app/src/test/resources/html/）")
        return stream.use { it.readBytes() }
    }

    private fun readUtf8(name: String): String = readBytes(name).toString(Charsets.UTF_8)
}
