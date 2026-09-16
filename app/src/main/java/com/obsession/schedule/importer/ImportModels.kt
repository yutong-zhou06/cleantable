package com.obsession.schedule.importer

import com.obsession.schedule.data.WEEK_TYPE_EVEN
import com.obsession.schedule.data.WEEK_TYPE_ODD

/**
 * HTML 导入过程中用到的数据模型。
 *
 * 刻意不直接复用 [com.obsession.schedule.data.CourseEntity]：解析层不改动
 * 数据库模型，也就能在纯 JVM 单元测试里跑（不需要 Android 运行时）。
 * 两者的转换放在 ViewModel 里做。
 */

/** 解析出的一门课。已按「连续周次」拆过 —— 见 [WeekSpec] */
data class ParsedCourse(
    val name: String,
    /** 1 = 周一 … 7 = 周日 */
    val dayOfWeek: Int,
    val startNode: Int,
    val step: Int,
    val startWeek: Int,
    val endWeek: Int,
    /**
     * 与 [com.obsession.schedule.data.CourseEntity.weekType] 取值一致。
     * 这里直接用那边的常量，避免两套「单双周」定义各自演化。
     */
    val weekType: Int,
    val room: String,
    val teacher: String
) {
    val endNode: Int get() = startNode + step - 1

    /** 形如「周一 1-4节 13-14周」，用于导入预览 */
    fun previewLine(): String {
        val nodeText = if (step == 1) "${startNode}节" else "$startNode-${endNode}节"
        val w = when (weekType) {
            WEEK_TYPE_ODD -> "$startWeek-$endWeek 周(单)"
            WEEK_TYPE_EVEN -> "$startWeek-$endWeek 周(双)"
            else -> if (startWeek == endWeek) "第$startWeek 周" else "$startWeek-$endWeek 周"
        }
        return "${DAY_NAMES[dayOfWeek - 1]} $nodeText · $w"
    }

    companion object {
        val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }
}

/**
 * 周次描述。
 *
 * 教务系统里会出现 `1-12周`、`1-12周(单)`、`1,3,5-7周` 这些写法。
 * 前两种能直接映射成 [startWeek]/[endWeek] + 单双周；
 * 第三种是不连续的周次，只能拆成多条课记录，所以先解析成一个区间列表。
 */
data class WeekSpec(
    /** 按顺序排列的连续区间，(start, end) 闭区间 */
    val spans: List<IntRange>,
    val weekType: Int,
    val raw: String
) {
    val maxWeek: Int get() = spans.maxOfOrNull { it.last } ?: 1

    /** 展开成逐周的周次集合 */
    fun weeks(): List<Int> = spans.flatMap { it.toList() }

    companion object {
        private val RANGE = Regex("""(\d{1,2})\s*[-–—~]\s*(\d{1,2})""")
        private val SINGLE = Regex("""\d{1,2}""")
        private val PARITY = Regex("""[（(]\s*(单|双)\s*[)）]""")

        /**
         * 从一段文本里抽出周次。
         *
         * 兼容两种写法：
         * - 列表模式 `周数：1-12周`
         * - 课表模式 `(1-4节)13-14周`
         *
         * 解析不出来时返回 null，由调用方决定是丢弃还是保留原文。
         */
        fun parse(text: String): WeekSpec? {
            // 先定位「周数：」后面的内容；没有该前缀就从「N周」的第一次出现处截取
            val tail = Regex("""周数[：:]\s*([^教师\s]*)""").find(text)?.groupValues?.get(1)
                ?: Regex("""([0-9][0-9,，\-–—~\s]*)\s*周""").find(text)?.groupValues?.get(1)
                ?: return null

            val spec = tail.trim()
            if (spec.isEmpty()) return null

            val weekType = when (PARITY.find(text)?.groupValues?.get(1)) {
                "单" -> WEEK_TYPE_ODD
                "双" -> WEEK_TYPE_EVEN
                else -> 0
            }

            val spans = mutableListOf<IntRange>()
            for (piece in spec.split(',', '，')) {
                val p = piece.trim()
                if (p.isEmpty()) continue
                val r = RANGE.find(p)
                if (r != null) {
                    val a = r.groupValues[1].toInt()
                    val b = r.groupValues[2].toInt()
                    // 上限 60 是防呆：教务系统偶尔会印出年份之类的怪数字
                    if (a in 1..60 && b in 1..60 && a <= b) spans.add(a..b)
                    continue
                }
                val s = SINGLE.find(p)
                if (s != null) {
                    val v = s.value.toInt()
                    if (v in 1..60) spans.add(v..v)
                }
            }
            if (spans.isEmpty()) return null

            return WeekSpec(
                spans = spans.sortedBy { it.first },
                weekType = weekType,
                raw = text.trim()
            )
        }
    }
}

/** 识别到的教务系统类型 */
enum class ScheduleSource(val label: String) {
    ZHENG_FANG_NEW_LIST("正方教务（新版 · 列表模式）"),
    ZHENG_FANG_NEW_GRID("正方教务（新版 · 课表模式）"),
    ZHENG_FANG_OLD("正方教务（旧版）"),
    QIANG_ZHI("强智教务"),
    URP("URP 教务"),
    UNKNOWN("未识别的来源");

    val supported: Boolean
        get() = this == ZHENG_FANG_NEW_LIST || this == ZHENG_FANG_NEW_GRID
}

/** 解析结果 */
sealed interface ParseOutcome {

    data class Success(
        val source: ScheduleSource,
        /** 页面里写的学期名，例如「2026-2027学年第1学期」 */
        val termName: String?,
        val courses: List<ParsedCourse>,
        /** 识别到了但没能导入的内容说明（例如实践课程） */
        val skipped: List<String>
    ) : ParseOutcome

    /**
     * 认出了教务系统，但当前版本还没写对应解析器。
     *
     * 这里刻意不做「尽力解析」——解析错但看起来正常的结果，比明确说不支持更危险，
     * 用户会在核对预览时被一堆看似合理的错课骗过去。
     */
    data class Unsupported(
        val source: ScheduleSource,
        val hint: String
    ) : ParseOutcome

    data class Failure(val reason: String) : ParseOutcome
}

/** 解码后的 HTML 文本，附带编码信息，便于在预览里回显（排查乱码时有用） */
data class DecodedHtml(
    val text: String,
    val charsetName: String,
    val fromBom: Boolean
)
