package com.obsession.schedule.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {

    // ----------------------------------------------------------------
    // 课表本体
    // ----------------------------------------------------------------

    @Query("SELECT * FROM timetables ORDER BY createdAt, id")
    fun observeTimetables(): Flow<List<TimetableEntity>>

    @Query("SELECT * FROM timetables ORDER BY createdAt, id")
    suspend fun allTimetables(): List<TimetableEntity>

    @Query("SELECT * FROM timetables WHERE id = :id")
    suspend fun timetable(id: Long): TimetableEntity?

    @Query("SELECT COUNT(*) FROM timetables")
    suspend fun timetableCount(): Int

    @Insert
    suspend fun insertTimetable(timetable: TimetableEntity): Long

    @Update
    suspend fun updateTimetable(timetable: TimetableEntity)

    @Query("DELETE FROM timetables WHERE id = :id")
    suspend fun deleteTimetableRow(id: Long)

    // ----------------------------------------------------------------
    // 课程
    // ----------------------------------------------------------------

    @Query("SELECT * FROM courses WHERE timetableId = :tid ORDER BY dayOfWeek, startNode")
    fun observeCourses(tid: Long): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses WHERE timetableId = :tid")
    suspend fun allCourses(tid: Long): List<CourseEntity>

    @Upsert
    suspend fun upsertCourse(course: CourseEntity): Long

    @Delete
    suspend fun deleteCourse(course: CourseEntity)

    @Insert
    suspend fun insertCourses(courses: List<CourseEntity>)

    @Query("DELETE FROM courses WHERE timetableId = :tid")
    suspend fun clearCourses(tid: Long)

    /** 按主键批量删除课程（「已添加课程」页的批量删除用） */
    @Query("DELETE FROM courses WHERE id IN (:ids)")
    suspend fun deleteCoursesByIds(ids: List<Long>)

    /** 批量改写课程。逐行 upsert 包在同一事务里，部分失败不会留半截状态 */
    @Transaction
    suspend fun updateCourses(courses: List<CourseEntity>) {
        for (course in courses) {
            upsertCourse(course)
        }
    }

    @Transaction
    suspend fun replaceCoursesOnly(tid: Long, courses: List<CourseEntity>) {
        clearCourses(tid)
        insertCourses(courses.map { it.copy(id = 0L, timetableId = tid) })
    }

    /** 追加课程，保留现有课表 */
    @Transaction
    suspend fun appendCourses(tid: Long, courses: List<CourseEntity>) {
        insertCourses(courses.map { it.copy(id = 0L, timetableId = tid) })
    }

    // ----------------------------------------------------------------
    // 作息
    // ----------------------------------------------------------------

    @Query("SELECT * FROM time_slots WHERE timetableId = :tid ORDER BY node")
    fun observeTimeSlots(tid: Long): Flow<List<TimeSlotEntity>>

    @Query("SELECT * FROM time_slots WHERE timetableId = :tid ORDER BY node")
    suspend fun allTimeSlots(tid: Long): List<TimeSlotEntity>

    @Query("SELECT COUNT(*) FROM time_slots WHERE timetableId = :tid")
    suspend fun timeSlotCount(tid: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimeSlots(slots: List<TimeSlotEntity>)

    @Query("DELETE FROM time_slots WHERE timetableId = :tid")
    suspend fun clearTimeSlots(tid: Long)

    /**
     * 整表替换作息。
     *
     * 用「清空再写入」而不是逐行 upsert，是因为删掉中间某一节后后面的节次会整体前移，
     * 逐行更新会在表里留下编号重复或空洞的行。
     */
    @Transaction
    suspend fun replaceTimeSlots(tid: Long, slots: List<TimeSlotEntity>) {
        clearTimeSlots(tid)
        insertTimeSlots(slots.map { it.copy(timetableId = tid) }.sortedBy { it.node })
    }

    /**
     * 整课表整体替换（导入 `.wakeup_schedule` / 本应用导出的 JSON 时用）。
     * 课程与作息一起换，但只动 [tid] 这一张课表，别的学期不受影响。
     */
    @Transaction
    suspend fun replaceAll(tid: Long, courses: List<CourseEntity>, slots: List<TimeSlotEntity>) {
        clearCourses(tid)
        clearTimeSlots(tid)
        insertTimeSlots(slots.map { it.copy(timetableId = tid) }.sortedBy { it.node })
        insertCourses(courses.map { it.copy(id = 0L, timetableId = tid) })
    }

    /** 删除一张课表挂着的全部数据（删除课表时用） */
    @Transaction
    suspend fun deleteTimetableData(tid: Long) {
        clearCourses(tid)
        clearTimeSlots(tid)
        deleteTimetableRow(tid)
    }
}
