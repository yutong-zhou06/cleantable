package com.obsession.schedule.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Locale

/**
 * 一天中的时刻，只到分钟。
 *
 * 单独抽出来是因为作息时间要反复做「比较大小」「加 N 分钟」，
 * 直接对 "08:00" 这种字符串操作既啰嗦又容易写错。
 */
data class TimeText(val hour: Int, val minute: Int) : Comparable<TimeText> {

    val totalMinutes: Int get() = hour * 60 + minute

    override fun compareTo(other: TimeText): Int = totalMinutes - other.totalMinutes

    /**
     * 统一补零输出，保证能直接写回数据库与课表文件。
     *
     * 必须锁定 [Locale.ROOT]：`%02d` 走的是默认 Locale，
     * 在阿拉伯语等环境下会输出阿拉伯数字，存进库里就再也解析不回来了。
     */
    override fun toString(): String = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

    /** 往后推 [delta] 分钟，跨天自动绕回 */
    fun plusMinutes(delta: Int): TimeText {
        val raw = (totalMinutes + delta).mod(MINUTES_PER_DAY)
        return TimeText(raw / 60, raw % 60)
    }

    companion object {
        private const val MINUTES_PER_DAY = 24 * 60

        /** 容错解析：接受 8:5、08：05（中文冒号）等写法；非法一律返回 null */
        private val PATTERN = Regex("""^(\d{1,2})\s*[:：]\s*(\d{1,2})$""")

        fun parse(raw: String): TimeText? {
            val match = PATTERN.find(raw.trim()) ?: return null
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return TimeText(hour, minute)
        }
    }
}

/**
 * 作息体检发现的一条问题。
 *
 * [blocking] 为 true 表示必须改好才能保存（格式错、下课早于上课）；
 * 为 false 表示只是提醒，比如两节课时间重叠——这属于用户的自由，不强行拦。
 */
data class SlotIssue(
    val node: Int,
    val message: String,
    val blocking: Boolean
)

/**
 * 第 [node] 节课的起止时间。
 *
 * 注意 [node] 同时也是课表网格的行号，删掉中间某一节后整列会重新编号。
 * v0.3 起支持多课表，同一节次会在不同课表里各有一份时间，
 * 因此主键是 (timetableId, node) 复合主键。
 */
@Entity(tableName = "time_slots", primaryKeys = ["timetableId", "node"])
data class TimeSlotEntity(
    val node: Int,
    val startTime: String,
    val endTime: String,
    val timetableId: Long = 1L
) {
    val start: TimeText? get() = TimeText.parse(startTime)
    val end: TimeText? get() = TimeText.parse(endTime)

    companion object {
        const val DEFAULT_LESSON_MINUTES = 45
        const val DEFAULT_BREAK_MINUTES = 10

        /** 默认作息：12 节，可在「每节课时间」里改 */
        fun defaults(): List<TimeSlotEntity> = listOf(
            TimeSlotEntity(1, "08:00", "08:45"),
            TimeSlotEntity(2, "08:55", "09:40"),
            TimeSlotEntity(3, "10:00", "10:45"),
            TimeSlotEntity(4, "10:55", "11:40"),
            TimeSlotEntity(5, "14:00", "14:45"),
            TimeSlotEntity(6, "14:55", "15:40"),
            TimeSlotEntity(7, "16:00", "16:45"),
            TimeSlotEntity(8, "16:55", "17:40"),
            TimeSlotEntity(9, "19:00", "19:45"),
            TimeSlotEntity(10, "19:55", "20:40"),
            TimeSlotEntity(11, "20:50", "21:35"),
            TimeSlotEntity(12, "21:45", "22:30")
        )

        /** 逐节体检，返回全部问题（按节次顺序） */
        fun inspect(slots: List<TimeSlotEntity>): List<SlotIssue> {
            val issues = mutableListOf<SlotIssue>()
            var previousEnd: TimeText? = null

            for (slot in slots.sortedBy { it.node }) {
                val start = slot.start
                val end = slot.end

                if (start == null) {
                    issues += SlotIssue(slot.node, "第 ${slot.node} 节上课时间要填成 HH:mm", true)
                }
                if (end == null) {
                    issues += SlotIssue(slot.node, "第 ${slot.node} 节下课时间要填成 HH:mm", true)
                }
                if (start != null && end != null) {
                    if (end <= start) {
                        issues += SlotIssue(slot.node, "第 ${slot.node} 节下课时间要晚于上课时间", true)
                    } else {
                        val previous = previousEnd
                        if (previous != null && start < previous) {
                            issues += SlotIssue(slot.node, "第 ${slot.node} 节和上一节时间重叠了", false)
                        }
                    }
                }
                if (end != null) previousEnd = end
            }
            return issues
        }

        /** 按已有节次推算下一节：上一节下课后休息 [DEFAULT_BREAK_MINUTES] 分钟 */
        fun suggestNext(existing: List<TimeSlotEntity>): TimeSlotEntity {
            val last = existing.maxByOrNull { it.node }
            val start = last?.end?.plusMinutes(DEFAULT_BREAK_MINUTES) ?: TimeText(8, 0)
            return TimeSlotEntity(
                node = (last?.node ?: 0) + 1,
                startTime = start.toString(),
                endTime = start.plusMinutes(DEFAULT_LESSON_MINUTES).toString()
            )
        }

        /**
         * v0.6：在 [previousEnd]（前一节的下课时间）之后插入一节的默认起止。
         * 上课 = 上一节下课 + 课间；下课 = 上课 + 时长。解析失败从默认第 1 节时间起算。
         */
        fun suggestAfter(previousEnd: String?): Pair<String, String> {
            val start = TimeText.parse(previousEnd.orEmpty())?.plusMinutes(DEFAULT_BREAK_MINUTES)
                ?: TimeText(8, 0)
            return start.toString() to start.plusMinutes(DEFAULT_LESSON_MINUTES).toString()
        }

        /** v0.6：把 "HH:mm" 后移 [minutes] 分钟（跨天绕回）；解析失败原样返回 */
        fun shiftTime(time: String, minutes: Int): String =
            TimeText.parse(time)?.plusMinutes(minutes)?.toString() ?: time
    }
}
