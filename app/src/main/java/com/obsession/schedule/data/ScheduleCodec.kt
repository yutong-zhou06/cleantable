package com.obsession.schedule.data

import android.graphics.Color
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 课表文件编解码。
 *
 * 支持两种格式：
 * 1. Obsession 自有格式：单个 JSON 对象，含 format / version / courses / times。
 * 2. 兼容格式：WakeUp 课程表 3.x 导出的 `.wakeup_schedule`，实为 5 行 JSON 拼接的文本，
 *    依次为 [时间表信息, 节次时间数组, 课表信息, 课程基础信息数组, 课程详情数组]。
 *
 * 兼容格式的存在让老用户可以直接把历史课表文件导进来，无需重新录入。
 */
object ScheduleCodec {

    /**
     * 自有格式标识。读取时不校验该字段（仅凭首字符 `{` 判定），
     * 因此改这里不会让旧版本导出的文件失效。
     */
    const val FORMAT_TAG = "obsession"
    const val FORMAT_VERSION = 1

    private val pretty = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    // ---------------------------------------------------------------- 导出

    fun toJson(snapshot: ScheduleSnapshot): String {
        val root = JsonObject()
        root.addProperty("format", FORMAT_TAG)
        root.addProperty("version", FORMAT_VERSION)

        val courseArray = JsonArray()
        snapshot.courses.forEach { c ->
            courseArray.add(JsonObject().apply {
                addProperty("name", c.name)
                addProperty("teacher", c.teacher)
                addProperty("room", c.room)
                addProperty("dayOfWeek", c.dayOfWeek)
                addProperty("startNode", c.startNode)
                addProperty("step", c.step)
                addProperty("startWeek", c.startWeek)
                addProperty("endWeek", c.endWeek)
                addProperty("weekType", c.weekType)
                addProperty("colorArgb", c.colorArgb)
                addProperty("note", c.note)
            })
        }
        root.add("courses", courseArray)

        val timeArray = JsonArray()
        snapshot.timeSlots.forEach { t ->
            timeArray.add(JsonObject().apply {
                addProperty("node", t.node)
                addProperty("startTime", t.startTime)
                addProperty("endTime", t.endTime)
            })
        }
        root.add("times", timeArray)

        return pretty.toJson(root)
    }

    // ---------------------------------------------------------------- 导入

    sealed interface ImportResult {
        data class Success(val snapshot: ScheduleSnapshot, val source: String) : ImportResult
        data class Failure(val reason: String) : ImportResult
    }

    fun parse(text: String): ImportResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ImportResult.Failure("文件是空的")

        return try {
            if (trimmed.startsWith("{")) parseOwn(trimmed) else parseLegacy(trimmed)
        } catch (e: Exception) {
            ImportResult.Failure("解析失败：${e.message ?: "格式无法识别"}")
        }
    }

    private fun parseOwn(text: String): ImportResult {
        val root = JsonParser.parseString(text).asJsonObject
        if (!root.has("courses")) return ImportResult.Failure("不是有效的课表文件")

        val courses = mutableListOf<CourseEntity>()
        root.getAsJsonArray("courses")?.forEach { el ->
            val o = el.asJsonObject
            val name = o.str("name")
            if (name.isBlank()) return@forEach
            courses.add(
                CourseEntity(
                    name = name,
                    teacher = o.str("teacher"),
                    room = o.str("room"),
                    dayOfWeek = o.int("dayOfWeek", 1).coerceIn(1, 7),
                    startNode = o.int("startNode", 1).coerceAtLeast(1),
                    step = o.int("step", 1).coerceAtLeast(1),
                    startWeek = o.int("startWeek", 1).coerceAtLeast(1),
                    endWeek = o.int("endWeek", 16).coerceAtLeast(1),
                    weekType = o.int("weekType", WEEK_TYPE_ALL).coerceIn(0, 2),
                    colorArgb = o.int("colorArgb", DEFAULT_COLOR),
                    note = o.str("note")
                )
            )
        }

        val slots = mutableListOf<TimeSlotEntity>()
        root.getAsJsonArray("times")?.forEach { el ->
            val o = el.asJsonObject
            val node = o.int("node", 0)
            if (node <= 0) return@forEach
            slots.add(
                TimeSlotEntity(
                    node = node,
                    startTime = o.str("startTime"),
                    endTime = o.str("endTime")
                )
            )
        }

        if (courses.isEmpty()) return ImportResult.Failure("文件里没有课程")
        return ImportResult.Success(
            ScheduleSnapshot(courses, slots.ifEmpty { TimeSlotEntity.defaults() }.sortedBy { it.node }),
            "Obsession 格式"
        )
    }

    /**
     * 解析 `.wakeup_schedule`。容错处理：
     * 行数不足、字段缺失、颜色非法都不致崩，尽量把能读的课程读出来。
     */
    private fun parseLegacy(text: String): ImportResult {
        val lines = text.lines().map { it.trim() }.filter { it.startsWith("[") || it.startsWith("{") }
        if (lines.size < 2) return ImportResult.Failure("不是可识别的课表文件")

        // 找出「课程详情数组」：元素里带 startNode 与 day 的那个数组
        var detailArray: JsonArray? = null
        var baseArray: JsonArray? = null
        var timeArray: JsonArray? = null

        for (line in lines) {
            val arr = runCatching { JsonParser.parseString(line).asJsonArray }.getOrNull() ?: continue
            val first = arr.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            when {
                first.has("startNode") && first.has("day") -> detailArray = arr
                first.has("courseName") && first.has("color") -> baseArray = arr
                first.has("startTime") && first.has("node") -> timeArray = arr
            }
        }

        if (detailArray == null) return ImportResult.Failure("找不到课程数据，可能不是 WakeUp 课程表的导出文件")

        // id -> 课程名/颜色
        val nameById = mutableMapOf<Int, String>()
        val colorById = mutableMapOf<Int, Int>()
        baseArray?.forEach { el ->
            val o = el.asJsonObject
            nameById[o.int("id", -1)] = o.str("courseName")
            colorById[o.int("id", -1)] = parseColor(o.str("color"))
        }

        val courses = mutableListOf<CourseEntity>()
        detailArray.forEach { el ->
            val o = el.asJsonObject
            val id = o.int("id", -1)
            val name = nameById[id]?.takeIf { it.isNotBlank() } ?: "未命名课程"
            courses.add(
                CourseEntity(
                    name = name,
                    teacher = o.str("teacher"),
                    room = o.str("room"),
                    dayOfWeek = o.int("day", 1).coerceIn(1, 7),
                    startNode = o.int("startNode", 1).coerceAtLeast(1),
                    step = o.int("step", 1).coerceAtLeast(1),
                    startWeek = o.int("startWeek", 1).coerceAtLeast(1),
                    endWeek = o.int("endWeek", 16).coerceAtLeast(1),
                    weekType = o.int("type", WEEK_TYPE_ALL).coerceIn(0, 2),
                    colorArgb = colorById[id] ?: DEFAULT_COLOR
                )
            )
        }

        val slots = mutableListOf<TimeSlotEntity>()
        timeArray?.forEach { el ->
            val o = el.asJsonObject
            val node = o.int("node", 0)
            if (node > 0) {
                slots.add(TimeSlotEntity(node, o.str("startTime"), o.str("endTime")))
            }
        }
        // 有课程但没课程名映射时给出提示
        val missingName = courses.count { it.name == "未命名课程" }
        if (courses.isEmpty()) return ImportResult.Failure("文件里没有课程")

        val hint = if (missingName > 0) "（$missingName 条课程缺少名称）" else ""
        return ImportResult.Success(
            ScheduleSnapshot(courses, slots.ifEmpty { TimeSlotEntity.defaults() }.sortedBy { it.node }),
            "WakeUp 课程表格式$hint"
        )
    }

    // ---------------------------------------------------------------- 工具

    private const val DEFAULT_COLOR = 0xFF1D9E75.toInt()

    private fun JsonObject.str(key: String): String =
        runCatching { if (has(key) && !get(key).isJsonNull) get(key).asString else "" }
            .getOrDefault("")

    private fun JsonObject.int(key: String, fallback: Int): Int =
        runCatching { if (has(key) && !get(key).isJsonNull) get(key).asInt else fallback }
            .getOrDefault(fallback)

    private fun parseColor(raw: String): Int {
        if (raw.isBlank()) return DEFAULT_COLOR
        return runCatching { Color.parseColor(raw.trim()) }.getOrDefault(DEFAULT_COLOR)
    }

    /** 供界面展示的可读颜色 */
    fun toHex(argb: Int): String = String.format("#%06X", 0xFFFFFF and argb)
}
