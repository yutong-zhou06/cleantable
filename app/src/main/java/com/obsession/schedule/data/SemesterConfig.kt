package com.obsession.schedule.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

const val DEFAULT_TOTAL_WEEKS = 18

private const val PREFS_NAME = "obsession_config"
private const val KEY_TERM_NAME = "term_name"
private const val KEY_FIRST_WEEK_START = "first_week_start"
private const val KEY_TOTAL_WEEKS = "total_weeks"

/**
 * 学期配置：第一周起始日、总周数、学期名。
 *
 * 为什么用 SharedPreferences 而不是 Room —— 这是「配置」不是「业务数据」，
 * 只有三个字段，塞进 Room 会连带触发数据库 schema 升级，得不偿失。
 *
 * [firstWeekStart] 存的永远是「某个周一」的 0 点时间戳。界面上允许用户选任意日期，
 * 存之前会用 [mondayOfDay] 归一到该日期所在周的周一，这样「第 N 周」的算法就
 * 退化成简单的 7 天步进，不必每次再判断星期。
 */
data class SemesterConfig(
    val termName: String,
    val firstWeekStart: Long,
    val totalWeeks: Int
) {
    /** 第 [week] 周的周一 0 点 */
    fun mondayOf(week: Int): Long = shiftDays(firstWeekStart, (week - 1) * 7)

    /** 第 [week] 周的周一与周日 */
    fun weekRange(week: Int): Pair<Long, Long> {
        val start = mondayOf(week)
        return start to shiftDays(start, 6)
    }

    /**
     * 某一天落在第几周。
     *
     * 刻意不做 clamp：早于第一周返回 0，晚于总周数返回超出值。
     * 调用方需要能区分「在学期内」和「在假期」，硬夹到 1..totalWeeks 会把
     * 假期误判成第一周或最后一周。
     */
    fun weekOfDay(millis: Long): Int {
        val diff = daysBetween(firstWeekStart, mondayOfDay(millis))
        return diff / 7 + 1
    }

    companion object {
        fun default(): SemesterConfig = SemesterConfig(
            termName = suggestTermName(),
            firstWeekStart = mondayOfDay(System.currentTimeMillis()),
            totalWeeks = DEFAULT_TOTAL_WEEKS
        )
    }
}

/**
 * 课表背景配置。图片 URI 与蒙层浓度跟随课表存储（SharedPreferences，
 * 键按课表 id 前缀隔离——只有两个标量字段，不值得为其动 Room schema）。
 *
 * [uri] 是 SAF 返回的 content:// 字符串，且已申请过持久化读权限；
 * [mask] 是蒙层不透明度：浅色主题盖白、深色主题盖黑，数值越大图越淡。
 */
data class BgConfig(
    val uri: String? = null,
    val mask: Float = 0.55f
) {
    val hasImage: Boolean get() = !uri.isNullOrBlank()

    companion object {
        const val MIN_MASK = 0.2f
        const val MAX_MASK = 1.0f
        const val DEFAULT_MASK = 0.55f
    }
}

/** 学期配置的读写。多课表后只负责「当前课表 id」与「背景」这类全局小状态 */
class ConfigStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * v0.2 遗留的学期设置。数据库迁移把它搬进 timetables 表后就不再有写入方，
     * 保留读取只为了在迁移 SQL 里取值。
     */
    fun loadLegacyConfig(): SemesterConfig {
        val fallback = SemesterConfig.default()
        return SemesterConfig(
            termName = prefs.getString(KEY_TERM_NAME, null)?.takeIf { it.isNotBlank() }
                ?: fallback.termName,
            firstWeekStart = prefs.getLong(KEY_FIRST_WEEK_START, fallback.firstWeekStart),
            totalWeeks = prefs.getInt(KEY_TOTAL_WEEKS, fallback.totalWeeks)
                .coerceIn(1, 40)
        )
    }

    /** 当前正在查看的课表 id */
    fun activeId(fallback: Long): Long = prefs.getLong(KEY_ACTIVE_ID, fallback)

    fun setActiveId(id: Long) {
        prefs.edit().putLong(KEY_ACTIVE_ID, id).apply()
    }

    fun loadBackground(timetableId: Long): BgConfig = BgConfig(
        uri = prefs.getString(bgKey(timetableId, KEY_BG_URI), null),
        mask = prefs.getFloat(
            bgKey(timetableId, KEY_BG_MASK),
            BgConfig.DEFAULT_MASK
        ).coerceIn(BgConfig.MIN_MASK, BgConfig.MAX_MASK)
    )

    fun saveBackground(timetableId: Long, config: BgConfig) {
        val mask = config.mask.coerceIn(BgConfig.MIN_MASK, BgConfig.MAX_MASK)
        prefs.edit()
            .putString(bgKey(timetableId, KEY_BG_URI), config.uri)
            .putFloat(bgKey(timetableId, KEY_BG_MASK), mask)
            .apply()
    }

    // ------------------------------------------------------------------
    // v0.6：作息编辑偏好（按课表隔离）
    //
    // 「只填开始时间」模式与每节时长都是「一张课表的作息属性」，但只有两个标量，
    // 不值得为它们动 Room schema（要加字段 + 迁移），沿用背景配置同一套前缀键存储。
    // ------------------------------------------------------------------

    fun slotAutoMode(timetableId: Long): Boolean =
        prefs.getBoolean(slotKey(timetableId, KEY_SLOT_AUTO), false)

    fun setSlotAutoMode(timetableId: Long, enabled: Boolean) {
        prefs.edit().putBoolean(slotKey(timetableId, KEY_SLOT_AUTO), enabled).apply()
    }

    fun slotLessonMinutes(timetableId: Long): Int =
        prefs.getInt(slotKey(timetableId, KEY_SLOT_LESSON), TimeSlotEntity.DEFAULT_LESSON_MINUTES)
            .coerceIn(MIN_LESSON_MINUTES, MAX_LESSON_MINUTES)

    fun setSlotLessonMinutes(timetableId: Long, minutes: Int) {
        prefs.edit()
            .putInt(slotKey(timetableId, KEY_SLOT_LESSON), minutes.coerceIn(MIN_LESSON_MINUTES, MAX_LESSON_MINUTES))
            .apply()
    }

    private fun slotKey(timetableId: Long, suffix: String) = "slot_${timetableId}_$suffix"

    private fun bgKey(timetableId: Long, suffix: String) = "${timetableId}_$suffix"
}

/** 每节时长的合理范围（分钟） */
const val MIN_LESSON_MINUTES = 20
const val MAX_LESSON_MINUTES = 120

private const val KEY_ACTIVE_ID = "active_timetable_id"
private const val KEY_BG_URI = "bg_uri"
private const val KEY_BG_MASK = "bg_mask"
private const val KEY_SLOT_AUTO = "auto"
private const val KEY_SLOT_LESSON = "lesson_minutes"

// ---------------------------------------------------------------------------
// 日期工具
//
// 全部走 java.util.Calendar：minSdk 是 24，java.time 要 API 26 或
// core library desugaring。为了两个日期函数再引一个注解处理器不划算。
// ---------------------------------------------------------------------------

private const val MILLIS_PER_DAY = 86_400_000.0

/** 归一到当天 0 点 */
internal fun startOfDay(millis: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = millis
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

/** 指定日期所在周的周一 0 点 */
internal fun mondayOfDay(millis: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = startOfDay(millis)
    val dow = c.get(Calendar.DAY_OF_WEEK) // SUNDAY = 1 … SATURDAY = 7
    val delta = if (dow == Calendar.SUNDAY) -6 else Calendar.MONDAY - dow
    c.add(Calendar.DAY_OF_MONTH, delta)
    return c.timeInMillis
}

/** 加减天数。用 Calendar 而不是毫秒算术，避免夏令时跨日出偏差 */
internal fun shiftDays(millis: Long, days: Int): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = millis
    c.add(Calendar.DAY_OF_MONTH, days)
    return c.timeInMillis
}

/** 两个日期相隔几天。两端都先归一到 0 点，再用取整对抗可能的余数小时 */
internal fun daysBetween(from: Long, to: Long): Int {
    val a = startOfDay(from)
    val b = startOfDay(to)
    return Math.round((b - a) / MILLIS_PER_DAY).toInt()
}

/** 日号，例如 "14"。课表星期栏用 */
fun formatDayNumber(millis: Long): String {
    val c = Calendar.getInstance()
    c.timeInMillis = millis
    return c.get(Calendar.DAY_OF_MONTH).toString()
}

/** 形如「9月14日 – 9月20日」；同月时后半段省略月名，写作「9月14日 – 20日」 */
fun formatWeekRange(start: Long, end: Long): String {
    val cs = Calendar.getInstance().apply { timeInMillis = start }
    val ce = Calendar.getInstance().apply { timeInMillis = end }
    val sameMonth = cs.get(Calendar.YEAR) == ce.get(Calendar.YEAR) &&
        cs.get(Calendar.MONTH) == ce.get(Calendar.MONTH)

    val fmt = SimpleDateFormat("M月d日", Locale.CHINA)
    val head = fmt.format(Date(start))
    val tail = if (sameMonth) {
        "${ce.get(Calendar.DAY_OF_MONTH)}日"
    } else {
        fmt.format(Date(end))
    }
    return "$head – $tail"
}

/** 完整日期，例如「2026年9月1日」。学期设置里用来回显用户选的起始日 */
fun formatFullDate(millis: Long): String =
    SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(millis))

/**
 * 按当前月份猜一个学期名，仅作为默认值。
 * 8 月归到下一学年秋季（开学在即），3–7 月归到春季。
 */
internal fun suggestTermName(): String {
    val c = Calendar.getInstance()
    val year = c.get(Calendar.YEAR)
    val month = c.get(Calendar.MONTH) + 1
    return when (month) {
        in 9..12 -> "$year–${year + 1} 学年 · 秋季学期"
        in 1..2 -> "${year - 1}–$year 学年 · 秋季学期"
        in 3..7 -> "${year - 1}–$year 学年 · 春季学期"
        else -> "$year–${year + 1} 学年 · 秋季学期"
    }
}
