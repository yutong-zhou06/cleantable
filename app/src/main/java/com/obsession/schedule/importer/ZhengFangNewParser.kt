package com.obsession.schedule.importer

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 正方教务（jwglxt 新版）课表页解析器。
 *
 * 该页面同时提供两张表，内容等价但结构不同：
 * - `#kblist_table`  列表模式：一天一个 tbody，一节课一个 tr。结构规整，优先用。
 * - `#kbgrid_table_0` 课表模式：一张 7 天 × N 节的网格，课块放在 td 里。
 *
 * 两种模式我各写一套提取逻辑，而不是想办法把网格还原成列表 ——
 * 网格模式里 td 的 rowspan 只表示格子占几行，同一格里可能叠着好几门课
 * （不同周次），硬还原容易错位。
 *
 * 版面差异之外还有一处字段差异，这是写解析器时最容易翻车的地方：
 * - 列表模式把标签写成正文：`周数：13-14周`、`上课地点：…`、`教师 ：…`
 * - 课表模式把标签放在 title 属性上：`<span title="节/周">`，值跟在后面
 * 所以下面两条路径的取值方式完全不同，不能共用一套正则。
 */
internal object ZhengFangNewParser {

    private const val LIST_TABLE_ID = "kblist_table"
    private const val GRID_TABLE_PREFIX = "kbgrid_table"

    /** td 的 id 形如 `jc_1-1-4`：第 1 天、第 1 至 4 节 */
    private val NODE_ID = Regex("""jc_(\d{1,2})-(\d{1,2})-(\d{1,2})""")

    /** td 的 id 形如 `1-1`：第 1 天、第 1 节（课表模式） */
    private val GRID_CELL_ID = Regex("""(\d{1,2})-(\d{1,2})""")

    /** 光秃秃的节次文本，例如 `1-4` 或 `6` */
    private val NODE_TEXT = Regex("""(\d{1,2})(?:\s*[-–—]\s*(\d{1,2}))?""")

    /** 课表模式里 `(1-4节)` / `(10-12节)` */
    private val NODE_IN_PAREN = Regex("""[（(]\s*(\d{1,2})\s*[-–—]\s*(\d{1,2})\s*节\s*[)）]""")

    // ---------------------------------------------------------------------
    // 识别
    // ---------------------------------------------------------------------

    fun detect(doc: Document): ScheduleSource = when {
        doc.selectFirst("table#$LIST_TABLE_ID") != null -> ScheduleSource.ZHENG_FANG_NEW_LIST
        doc.selectFirst("table[id^=$GRID_TABLE_PREFIX]") != null -> ScheduleSource.ZHENG_FANG_NEW_GRID
        // 下面几个是「认得出但不支持」的类型，顺序在正方新版之后
        doc.selectFirst("table#Table1") != null -> ScheduleSource.ZHENG_FANG_OLD
        doc.selectFirst("table#kbtable") != null -> ScheduleSource.QIANG_ZHI
        doc.selectFirst("table[bordercolordark]") != null -> ScheduleSource.URP
        doc.selectFirst("div.timetable_con") != null -> ScheduleSource.ZHENG_FANG_NEW_GRID
        else -> ScheduleSource.UNKNOWN
    }

    // ---------------------------------------------------------------------
    // 入口
    // ---------------------------------------------------------------------

    fun parse(doc: Document, source: ScheduleSource): ParseOutcome.Success? {
        val termName = doc.selectFirst("div.timetable_title h6.pull-left")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }

        val result = when (source) {
            ScheduleSource.ZHENG_FANG_NEW_LIST -> parseList(doc)
            ScheduleSource.ZHENG_FANG_NEW_GRID -> parseGrid(doc)
            else -> null
        } ?: return null

        if (result.courses.isEmpty()) return null

        return ParseOutcome.Success(
            source = source,
            termName = termName,
            courses = result.courses,
            skipped = result.skipped
        )
    }

    private class Raw(
        val courses: List<ParsedCourse>,
        val skipped: List<String>
    )

    // ---------------------------------------------------------------------
    // 列表模式
    // ---------------------------------------------------------------------

    private fun parseList(doc: Document): Raw? {
        val table = doc.selectFirst("table#$LIST_TABLE_ID") ?: return null
        val out = mutableListOf<ParsedCourse>()
        val skipped = mutableListOf<String>()

        for (body in table.select("tbody")) {
            if (!body.id().startsWith("xq_")) continue
            val day = body.id().removePrefix("xq_").toIntOrNull() ?: continue
            if (day !in 1..7) continue

            // 节次区间跨行沿用：同一格里的第二门课所在 tr 没有节次 td
            var node: IntRange? = null

            for (tr in body.select("> tr")) {
                val tds = tr.select("> td")

                // 星期有两个来源：tbody 的 id（xq_1 → 周一），以及单元格 id 里的天（jc_1-1-4）。
                // 后者更细，个别改版页面会把一整天拆进多个 tbody，所以优先信它。
                val rowDay = tds.firstNotNullOfOrNull { td ->
                    NODE_ID.find(td.id())?.groupValues?.get(1)
                        ?.toIntOrNull()?.takeIf { it in 1..7 }
                } ?: day

                for (td in tds) {
                    // 节次优先取 id（jc_1-1-4 最可靠）；没有 id 的改版页面退回读单元格文本。
                    // 文本必须「整体」就是节次，否则课程格子里那种长文本会被误判。
                    val idNode = NODE_ID.find(td.id())
                    if (idNode != null) {
                        val s = idNode.groupValues[2].toInt()
                        val e = idNode.groupValues[3].toInt()
                        if (s in 1..20 && e in s..20) node = s..e
                    } else {
                        val whole = NODE_TEXT.matchEntire(td.text().trim())
                        if (whole != null) {
                            val s = whole.groupValues[1].toInt()
                            val e = whole.groupValues[2].ifEmpty { whole.groupValues[1] }.toInt()
                            if (s in 1..20 && e in s..20) node = s..e
                        }
                    }

                    for (div in td.select("div.timetable_con")) {
                        val name = div.selectFirst("span.title")?.text()?.trim().orEmpty()
                        if (name.isEmpty()) continue
                        val range = node ?: continue
                        out += buildListCourse(name, rowDay, range, div, skipped)
                    }
                }
            }
        }

        extractPracticeNote(table, skipped)
        return Raw(out.distinct(), skipped)
    }

    private fun buildListCourse(
        name: String,
        day: Int,
        node: IntRange,
        div: Element,
        skipped: MutableList<String>
    ): List<ParsedCourse> {
        val text = div.text()

        val spec = WeekSpec.parse(text) ?: run {
            skipped += "「$name」未标注周次，已跳过"
            return emptyList()
        }

        val room = Regex("""上课地点[：:]\s*(.*?)(?=\s*教师\s*[：:]|$)""")
            .find(text)?.groupValues?.get(1)?.let(::cleanRoom).orEmpty()
        val teacher = Regex("""教师\s*[：:]\s*(.+)$""")
            .find(text)?.groupValues?.get(1)?.trim().orEmpty()

        return toCourses(name, day, node, spec, room, teacher)
    }

    // ---------------------------------------------------------------------
    // 课表模式
    // ---------------------------------------------------------------------

    private fun parseGrid(doc: Document): Raw? {
        val table = doc.selectFirst("table[id^=$GRID_TABLE_PREFIX]") ?: return null
        val out = mutableListOf<ParsedCourse>()
        val skipped = mutableListOf<String>()

        for (td in table.select("td.td_wrap, td[id]")) {
            val m = GRID_CELL_ID.matchEntire(td.id().trim()) ?: continue
            val day = m.groupValues[1].toInt()
            val cellStart = m.groupValues[2].toInt()
            if (day !in 1..7 || cellStart !in 1..20) continue

            val rowspan = td.attr("rowspan").toIntOrNull()?.coerceAtLeast(1) ?: 1

            for (div in td.select("div.timetable_con")) {
                val name = div.selectFirst("span.title")?.text()?.trim().orEmpty()
                if (name.isEmpty()) continue

                // 该模式下标签在 title 属性上，值在 <p> 的正文里。
                //
                // 这里对「上课地点」特意多取一份 wholeText()：jsoup 的 text() 会把连续空白
                // 压成单个空格，而校区名与教室名之间恰恰是靠两个空格分隔的
                // （「示例大学  图书馆303…」）。用 text() 读会退化成「示例大学 图书馆303…」，
                // 两个名字粘在一起就再也分不开了。wholeText() 不做这层归一化，能保住边界。
                val fields = mutableMapOf<String, String>()
                var roomRaw = ""
                for (p in div.select("p")) {
                    val label = p.selectFirst("span[title]")?.attr("title")?.trim().orEmpty()
                    if (label.isEmpty()) continue
                    fields[label] = p.text().trim()
                    if (label == "上课地点") roomRaw = p.wholeText().trim()
                }

                val nodeText = fields["节/周"].orEmpty()
                val node = NODE_IN_PAREN.find(nodeText)?.let {
                    it.groupValues[1].toInt()..it.groupValues[2].toInt()
                } ?: (cellStart..(cellStart + rowspan - 1))

                val spec = WeekSpec.parse(nodeText)
                if (spec == null) {
                    skipped += "「$name」未标注周次，已跳过"
                    continue
                }

                val room = cleanRoom(roomRaw.ifEmpty { fields["上课地点"].orEmpty() })
                val teacher = fields["教师"].orEmpty().trim()

                out += toCourses(name, day, node, spec, room, teacher)
            }
        }

        extractPracticeNote(table, skipped)
        return Raw(out.distinct(), skipped)
    }

    // ---------------------------------------------------------------------
    // 公共
    // ---------------------------------------------------------------------

    /**
     * 把一条「课 + 周次描述」摊成若干条 ParsedCourse。
     *
     * 为什么按连续区间拆：数据模型是 startWeek/endWeek 一对，
     * 遇到 `1,3,5-7周` 这种不连续周次，只能拆成 1、3、5-7 三条，
     * 合并不进去而不拆就只能丢数据。
     */
    private fun toCourses(
        name: String,
        day: Int,
        node: IntRange,
        spec: WeekSpec,
        room: String,
        teacher: String
    ): List<ParsedCourse> = spec.spans.map { span ->
        ParsedCourse(
            name = name,
            dayOfWeek = day,
            startNode = node.first,
            step = node.last - node.first + 1,
            startWeek = span.first,
            endWeek = span.last,
            weekType = spec.weekType,
            room = room,
            teacher = teacher
        )
    }

    /**
     * 清洗上课地点。
     *
     * 主要目的是剥掉课表模式里的校区前缀：原文是「示例大学  图书馆303…」，
     * 靠两个空格分隔。传入的必须是未经过空白归一化的文本，否则分不开。
     * 顺带把「未排地点」这类占位词归零，避免它们占满课表格子。
     */
    private fun cleanRoom(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        if (t in PLACEHOLDERS) return ""
        if (SEPARATOR.containsMatchIn(t)) {
            val tail = t.split(SEPARATOR).last().trim().replace(WHITESPACE, " ")
            return if (tail in PLACEHOLDERS) "" else tail
        }
        return t.replace(WHITESPACE, " ")
    }

    private val SEPARATOR = Regex("""\s{2,}|\r?\n""")
    private val WHITESPACE = Regex("""\s+""")
    private val PLACEHOLDERS = setOf("未排地点", "未安排", "未安排地点", "无", "-", "—")

    /**
     * 表格底部常有「实践课程：操作系统课程设计…(共1周)/15周」这样的附注。
     *
     * 它没有星期和节次，落不进课表模型，但要如实告诉用户「识别到了、没导入」，
     * 否则用户会以为课程丢了。
     */
    private fun extractPracticeNote(table: Element, skipped: MutableList<String>) {
        for (title in table.select("div.timetable_title")) {
            val t = title.text().trim()
            if (!t.contains("实践课程")) continue
            val detail = t.substringAfter("实践课程").trim(' ', '：', ':')
            if (detail.isNotEmpty()) {
                skipped += "实践课程（无星期/节次，未导入）：$detail"
            }
        }
    }
}
