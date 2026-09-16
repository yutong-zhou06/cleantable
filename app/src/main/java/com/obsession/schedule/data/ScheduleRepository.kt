package com.obsession.schedule.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

data class ScheduleSnapshot(
    val courses: List<CourseEntity>,
    val timeSlots: List<TimeSlotEntity>
)

class ScheduleRepository(private val context: Context) {

    private val dao = AppDatabase.get(context).scheduleDao()

    fun observeTimetables(): Flow<List<TimetableEntity>> = dao.observeTimetables()

    suspend fun allTimetables(): List<TimetableEntity> = dao.allTimetables()

    suspend fun timetable(id: Long): TimetableEntity? = dao.timetable(id)

    fun observeCourses(timetableId: Long): Flow<List<CourseEntity>> =
        dao.observeCourses(timetableId)

    fun observeTimeSlots(timetableId: Long): Flow<List<TimeSlotEntity>> =
        dao.observeTimeSlots(timetableId)

    /**
     * 首次启动（v0.3 全新安装，库里一张课表都没有）时写入默认课表 + 默认作息。
     * v0.2 升级用户走数据库迁移，id=1 已存在，这里不会执行。
     */
    suspend fun seedIfNeeded() {
        if (dao.timetableCount() > 0) return
        val config = SemesterConfig.default()
        val id = dao.insertTimetable(
            TimetableEntity(
                name = config.termName,
                termName = config.termName,
                firstWeekStart = config.firstWeekStart,
                totalWeeks = config.totalWeeks
            )
        )
        dao.replaceTimeSlots(id, TimeSlotEntity.defaults())
    }

    suspend fun createTimetable(name: String, config: SemesterConfig): Long =
        dao.insertTimetable(
            TimetableEntity(
                name = name,
                termName = config.termName,
                firstWeekStart = config.firstWeekStart,
                totalWeeks = config.totalWeeks
            )
        )

    suspend fun updateTimetable(timetable: TimetableEntity) = dao.updateTimetable(timetable)

    /** 删除课表及其全部课程 / 作息。调用方负责先确认「不是使用中的那张」 */
    suspend fun deleteTimetable(timetableId: Long) = dao.deleteTimetableData(timetableId)

    suspend fun upsert(course: CourseEntity) = dao.upsertCourse(course)

    suspend fun delete(course: CourseEntity) = dao.deleteCourse(course)

    /** 按主键批量删除课程（「已添加课程」页） */
    suspend fun deleteCoursesByIds(ids: List<Long>) {
        if (ids.isNotEmpty()) dao.deleteCoursesByIds(ids)
    }

    /**
     * 批量改写课程字段（统一改名 / 改色 / 改时间）。
     * [transform] 只动目标字段，其余字段原样保留 —— 改名不会丢周次，改色不会丢教室。
     */
    suspend fun updateCourses(courses: List<CourseEntity>, transform: (CourseEntity) -> CourseEntity) {
        if (courses.isEmpty()) return
        dao.updateCourses(courses.map(transform))
    }

    /** 保存整份作息。调用方负责先通过 [TimeSlotEntity.inspect] 校验 */
    suspend fun saveTimeSlots(timetableId: Long, slots: List<TimeSlotEntity>) {
        dao.replaceTimeSlots(timetableId, slots)
    }

    suspend fun snapshot(timetableId: Long): ScheduleSnapshot =
        ScheduleSnapshot(dao.allCourses(timetableId), dao.allTimeSlots(timetableId))

    suspend fun replaceAll(timetableId: Long, snapshot: ScheduleSnapshot) {
        val slots = snapshot.timeSlots.ifEmpty { TimeSlotEntity.defaults() }
        dao.replaceAll(timetableId, snapshot.courses, slots)
    }

    /**
     * 导入课程。
     *
     * @param keepExisting true = 追加到现有课表；false = 替换全部课程。
     *   两条路径都不动作息设置 —— 几点上课是用户自己配的，教务页面里没有这个信息。
     */
    suspend fun importCourses(
        timetableId: Long,
        courses: List<CourseEntity>,
        keepExisting: Boolean
    ) {
        if (keepExisting) {
            dao.appendCourses(timetableId, courses)
        } else {
            dao.replaceCoursesOnly(timetableId, courses)
        }
    }
}
