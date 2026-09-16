package com.obsession.schedule.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TimetableEntity::class, CourseEntity::class, TimeSlotEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun scheduleDao(): ScheduleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room
                .databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "obsession.db"
                )
                .addMigrations(MIGRATION_1_2(context.applicationContext))
                .build()
                .also { INSTANCE = it }
        }

        /**
         * v1 → v2：引入多课表。
         *
         * 迁移要做三件事，全程不丢用户数据：
         * 1. 新建 timetables 表，把 v0.2 存在 SharedPreferences 里的学期设置
         *    搬进 id=1 的「主课表」——升级后打开即见原配置，无需重设；
         * 2. courses 加 timetableId 列，现有课程全部归入主课表；
         * 3. time_slots 的主键从 node 变为 (timetableId, node)——
         *    Room 里改主键必须重建表，靠「建新表 → 搬数据 → 改名」完成。
         */
        private fun MIGRATION_1_2(appContext: Context): Migration =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS timetables (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            termName TEXT NOT NULL,
                            firstWeekStart INTEGER NOT NULL,
                            totalWeeks INTEGER NOT NULL,
                            createdAt INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )

                    // v0.2 的学期设置。没设置过的用户（从没动过设置页）用默认值兜底
                    val prefs = appContext.getSharedPreferences(
                        "obsession_config",
                        Context.MODE_PRIVATE
                    )
                    val legacyTerm = prefs.getString("term_name", null)?.takeIf { it.isNotBlank() }
                        ?: suggestTermName()
                    val fallback = SemesterConfig.default()
                    val firstWeek = prefs.getLong("first_week_start", fallback.firstWeekStart)
                    val totalWeeks = prefs.getInt("total_weeks", fallback.totalWeeks)
                        .coerceIn(1, 40)

                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO timetables
                            (id, name, termName, firstWeekStart, totalWeeks, createdAt)
                        VALUES (1, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf(legacyTerm, legacyTerm, firstWeek, totalWeeks, System.currentTimeMillis())
                    )

                    db.execSQL(
                        "ALTER TABLE courses ADD COLUMN timetableId INTEGER NOT NULL DEFAULT 1"
                    )

                    // 主键变更：重建 time_slots
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS time_slots_new (
                            timetableId INTEGER NOT NULL,
                            node INTEGER NOT NULL,
                            startTime TEXT NOT NULL,
                            endTime TEXT NOT NULL,
                            PRIMARY KEY(timetableId, node)
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        INSERT INTO time_slots_new (timetableId, node, startTime, endTime)
                        SELECT 1, node, startTime, endTime FROM time_slots
                        """.trimIndent()
                    )
                    db.execSQL("DROP TABLE time_slots")
                    db.execSQL("ALTER TABLE time_slots_new RENAME TO time_slots")
                }
            }
    }
}
