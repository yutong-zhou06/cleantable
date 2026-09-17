package com.obsession.schedule.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import com.obsession.schedule.MainActivity
import com.obsession.schedule.R
import com.obsession.schedule.data.AppDatabase
import com.obsession.schedule.data.ConfigStore
import com.obsession.schedule.data.CourseEntity
import com.obsession.schedule.data.TimeSlotEntity
import com.obsession.schedule.data.mondayOfDay
import com.obsession.schedule.ui.theme.ThemeController
import com.obsession.schedule.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import java.util.Calendar
import java.util.Locale

/**
 * 桌面小组件的数据准备与渲染。
 *
 * 三个小组件共用同一份「今天有什么课」的快照，只是展示粒度不同，
 * 所以数据只加载一次，三种渲染各自取用。
 *
 * 注意 RemoteViews 的硬限制：只能用系统提供的有限控件（TextView / View / LinearLayout…），
 * 不能用 Compose，也不能自定义 View。因此这里的样式全部靠 res/layout 下的 XML，
 * 深浅色则靠 values-night 资源限定符切换 —— 这两条都是绕不过去的。
 */
object ScheduleWidgetRenderer {

    private const val TAG = "ScheduleWidget"

    internal const val STATE_PAST = 0
    internal const val STATE_ONGOING = 1
    internal const val STATE_UPCOMING = 2

    /** 一次最多画几行（今日课表快照），放不下的留给整卡点击进应用查看 */
    internal const val MAX_ROWS = 5

    internal val WEEKDAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    internal data class WidgetCourse(
        val name: String,
        val room: String,
        val startTime: String,
        val endTime: String,
        val node: Int,
        val state: Int,
        /** 课表网格里这门课的颜色（ARGB），组件按色相派生同款配色 */
        val colorArgb: Int
    )

    internal data class TodaySnapshot(
        /** 课表名，标题行左侧显示 */
        val timetableName: String,
        /** 今天日期「M.d」 */
        val dateLabel: String,
        /** 「第 N 周」，未设置学期起始日时为空串 */
        val weekText: String,
        val dayLabel: String,
        val courses: List<WidgetCourse>,
        val ongoing: WidgetCourse?,
        val upcoming: WidgetCourse?,
        /** 明天的星期标签与课程（4×2 双栏） */
        val tomorrowLabel: String,
        val tomorrow: List<WidgetCourse>
    )

    // ------------------------------------------------------------------
    // 渲染（v0.4.3 起全部走「静态快照」：应用内把课表画成位图，
    // 组件端只 setImageViewBitmap —— 启动器零拼装，兼容性最高）
    // ------------------------------------------------------------------

    fun renderToday(context: Context, manager: AppWidgetManager, widgetId: Int) {
        WidgetSnapshotRenderer.render(context, manager, widgetId, WidgetKind.TODAY)
    }

    fun renderNext(context: Context, manager: AppWidgetManager, widgetId: Int) {
        WidgetSnapshotRenderer.render(context, manager, widgetId, WidgetKind.NEXT)
    }

    fun renderBar(context: Context, manager: AppWidgetManager, widgetId: Int) {
        WidgetSnapshotRenderer.render(context, manager, widgetId, WidgetKind.BAR)
    }

    /**
     * 课程增删改后立即刷新所有已放置的小组件。
     *
     * 不能指望 updatePeriodMillis：它最小只能设 30 分钟，用户改完课表
     * 要等半小时才在桌面看到变化，那体验说不过去。
     *
     * 每个组件独立 try-catch：任何一个渲染异常都不能连累其余组件。
     * 调用方负责切到 IO 线程 —— 内部是 runBlocking 查询。
     */
    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)

        refreshSafely(context, manager, TodayWidgetProvider::class.java, R.layout.widget_today) { c, m, id ->
            renderToday(c, m, id)
        }
        refreshSafely(context, manager, NextWidgetProvider::class.java, R.layout.widget_next) { c, m, id ->
            renderNext(c, m, id)
        }
        refreshSafely(context, manager, BarWidgetProvider::class.java, R.layout.widget_bar) { c, m, id ->
            renderBar(c, m, id)
        }
    }

    private fun refreshSafely(
        context: Context,
        manager: AppWidgetManager,
        provider: Class<*>,
        @LayoutRes fallbackLayout: Int,
        render: (Context, AppWidgetManager, Int) -> Unit
    ) {
        try {
            manager.getAppWidgetIds(ComponentName(context, provider))
                .forEach { render(context, manager, it) }
        } catch (e: Exception) {
            // 最后防线：宁可显示空布局，也绝不让启动器停在「加载中」。
            // bareViews 不查库，渲染失败多半就是查库/数据层出的问题
            Log.e(TAG, "小组件渲染失败：${provider.simpleName}", e)
            runCatching {
                manager.getAppWidgetIds(ComponentName(context, provider))
                    .forEach { manager.updateAppWidget(it, bareViews(context, fallbackLayout)) }
            }
        }
    }

    /** 渲染彻底失败时的兜底 RemoteViews：只有背景和点击事件，不碰任何数据。
     *  非 private：onUpdate 后台渲染路径（renderOnBackground）的异常兜底也用它 */
    fun bareViews(context: Context, @LayoutRes layout: Int): RemoteViews =
        RemoteViews(context.packageName, layout).apply {
            setOnClickPendingIntent(R.id.widget_root, openApp(context, 0))
        }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    internal fun load(context: Context): TodaySnapshot {
        val calendar = Calendar.getInstance()
        val todayIndex = weekdayIndex(calendar)
        val nowMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

        val dao = AppDatabase.get(context).scheduleDao()
        // 多课表后小组件跟随「当前正在查看的那张课表」
        val activeId = ConfigStore(context).activeId(1L)
        val timetable = runBlocking {
            dao.timetable(activeId) ?: dao.allTimetables().firstOrNull()
        }
        val baseConfig = timetable?.toConfig() ?: return emptySnapshot(todayIndex)

        // 未设置学期起始日（firstWeekStart=0，新建课表还没进过设置页）时
        // 按「本周一」兜底：不兜底的话 weekOfDay 会算出约三千周，
        // activeIn 对所有课程返回 false，明明有课却显示「今天没有课」
        val now = System.currentTimeMillis()
        val config = if (baseConfig.firstWeekStart > 0) {
            baseConfig
        } else {
            baseConfig.copy(firstWeekStart = mondayOfDay(now))
        }
        val tid = timetable.id
        val slots = runBlocking { dao.allTimeSlots(tid) }.associateBy { it.node }
        val allCourses = runBlocking { dao.allCourses(tid) }

        val week = config.weekOfDay(now)
        val items = buildItems(
            allCourses.filter { it.dayOfWeek == todayIndex && it.activeIn(week) },
            slots, nowMinutes
        )

        // v0.6：明天也一起装进快照（4×2 双栏）。注意周日→周一会跨到下一周，
        // 周次要按「明天的日期」重算，否则周日晚上明天的课会被算丢。
        // 明天的课一律按「未开始」状态画（现在还没到）。
        val tomorrowCal = (calendar.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowIndex = weekdayIndex(tomorrowCal)
        val tomorrowWeek = config.weekOfDay(tomorrowCal.timeInMillis)
        val tomorrowItems = buildItems(
            allCourses.filter { it.dayOfWeek == tomorrowIndex && it.activeIn(tomorrowWeek) },
            slots, nowMinutes = null
        )

        return TodaySnapshot(
            timetableName = timetable.name,
            dateLabel = String.format(
                Locale.ROOT, "%d.%d",
                calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH)
            ),
            weekText = if (baseConfig.firstWeekStart > 0) "第 $week 周" else "",
            dayLabel = WEEKDAY_NAMES[todayIndex - 1],
            courses = items,
            ongoing = items.firstOrNull { it.state == STATE_ONGOING },
            upcoming = items.firstOrNull { it.state == STATE_UPCOMING },
            tomorrowLabel = WEEKDAY_NAMES[tomorrowIndex - 1],
            tomorrow = tomorrowItems
        )
    }

    /** 把课程实体映射成组件行；[nowMinutes] 传 null 表示全部按「未开始」处理（明天的课） */
    private fun buildItems(
        courses: List<CourseEntity>,
        slots: Map<Int, TimeSlotEntity>,
        nowMinutes: Int?
    ): List<WidgetCourse> = courses.sortedBy { it.startNode }.map { course ->
        // 起止时间分别取首节与末节的作息：连堂课（如 1-2 节）的
        // 结束时间应该是第 2 节下课，而不是第 1 节
        val startSlot = slots[course.startNode]
        val endSlot = slots[course.endNode] ?: startSlot
        val start = startSlot?.startTime ?: "--:--"
        val end = endSlot?.endTime ?: "--:--"
        WidgetCourse(
            name = course.name,
            room = course.room,
            startTime = start,
            endTime = end,
            node = course.startNode,
            state = if (nowMinutes == null) STATE_UPCOMING else stateOf(nowMinutes, start, end),
            colorArgb = course.colorArgb
        )
    }

    /** 库里一张课表都没有（理论只在异常迁移时出现）时的空快照 */
    private fun emptySnapshot(todayIndex: Int): TodaySnapshot = TodaySnapshot(
        timetableName = "", dateLabel = "", weekText = "",
        dayLabel = WEEKDAY_NAMES[todayIndex - 1],
        courses = emptyList(),
        ongoing = null,
        upcoming = null,
        tomorrowLabel = "",
        tomorrow = emptyList()
    )

    /**
     * 判定一节课的状态。
     *
     * 作息没配（时间显示 "--:--"）时一律算作「未开始」——
     * 宁可把它当成即将到来的课，也不要因为解析不出时间就判定成已上过而淡出。
     */
    private fun stateOf(nowMinutes: Int, start: String, end: String): Int {
        val startMin = minutesOf(start)
        val endMin = minutesOf(end)
        return when {
            endMin != null && nowMinutes >= endMin -> STATE_PAST
            startMin != null && nowMinutes >= startMin -> STATE_ONGOING
            else -> STATE_UPCOMING
        }
    }

    private fun minutesOf(hhmm: String): Int? {
        val parts = hhmm.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun weekdayIndex(calendar: Calendar): Int {
        val dow = calendar.get(Calendar.DAY_OF_WEEK)
        return if (dow == Calendar.SUNDAY) 7 else dow - 1
    }

    internal fun openApp(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            widgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/**
 * 系统投递 APPWIDGET_UPDATE 时 onUpdate 跑在主线程，而渲染内部用 runBlocking 查库，
 * Room 默认禁止主线程访问数据库 —— 直接渲染会抛 IllegalStateException，
 * 启动器就永远停在「加载中」。所以三个 Provider 统一 goAsync + 后台线程渲染，
 * goAsync 给查询争取到完整的十秒广播窗口。
 *
 * v0.4.2 补上与 [ScheduleWidgetRenderer.refreshSafely] 同级的异常兜底：
 * 此前这条路径只保证 finish()，渲染一旦抛异常就会杀死整个进程，
 * 启动器表现为「载入窗口小组件时出现问题」。现在每个组件独立隔离，
 * 失败时退到 bareViews 空布局，并把真实异常打进 logcat（tag ScheduleWidget）。
 */
private fun renderOnBackground(
    receiver: AppWidgetProvider,
    context: Context,
    ids: IntArray,
    @LayoutRes fallbackLayout: Int,
    render: (Context, AppWidgetManager, Int) -> Unit
) {
    val pending = receiver.goAsync()
    Thread {
        try {
            runCatching {
                val manager = AppWidgetManager.getInstance(context)
                ids.forEach { id ->
                    try {
                        render(context, manager, id)
                    } catch (e: Exception) {
                        Log.e(TAG, "小组件渲染失败：id=$id", e)
                        runCatching {
                            manager.updateAppWidget(id, ScheduleWidgetRenderer.bareViews(context, fallbackLayout))
                        }
                    }
                }
            }
        } finally {
            pending.finish()
        }
    }.start()
}

private const val TAG = "ScheduleWidget"

class TodayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) = renderOnBackground(
        this, context, appWidgetIds, R.layout.widget_today
    ) { c, m, id -> ScheduleWidgetRenderer.renderToday(c, m, id) }

    /** 桌面上拖拽调整组件大小后按新尺寸重画快照 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?
    ) = renderOnBackground(
        this, context, intArrayOf(appWidgetId), R.layout.widget_today
    ) { c, m, id -> ScheduleWidgetRenderer.renderToday(c, m, id) }
}

class NextWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) = renderOnBackground(
        this, context, appWidgetIds, R.layout.widget_next
    ) { c, m, id -> ScheduleWidgetRenderer.renderNext(c, m, id) }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?
    ) = renderOnBackground(
        this, context, intArrayOf(appWidgetId), R.layout.widget_next
    ) { c, m, id -> ScheduleWidgetRenderer.renderNext(c, m, id) }
}

class BarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) = renderOnBackground(
        this, context, appWidgetIds, R.layout.widget_bar
    ) { c, m, id -> ScheduleWidgetRenderer.renderBar(c, m, id) }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?
    ) = renderOnBackground(
        this, context, intArrayOf(appWidgetId), R.layout.widget_bar
    ) { c, m, id -> ScheduleWidgetRenderer.renderBar(c, m, id) }
}

/** 小组件种类：三种共用一条快照管线，各自有不同的默认尺寸与画法 */
internal enum class WidgetKind(
    val label: String,
    val defWidthDp: Int,
    val defHeightDp: Int
) {
    TODAY("今日课表", 250, 110),
    NEXT("下一节课", 110, 110),
    BAR("课表简洁条", 250, 40)
}

/** 「一键更新」里逐组件展示的结果 */
internal data class WidgetRefreshResult(
    val widget: String,
    val ok: Boolean,
    val placed: Boolean,
    val error: String?
)

/**
 * v0.4.3 静态快照渲染器。
 *
 * 背景：此前组件用 XML RemoteViews 动态拼装（逐行 addView / 三态背景切换），
 * 在部分启动器上应用失败且异常栈抓不到（logcat 无 FATAL）。改为应用内用 Canvas
 * 把今天的课表画成一张位图，组件端只 setImageViewBitmap —— 启动器零拼装，
 * setImageViewBitmap 是 RemoteViews 最古老稳定的 API。
 *
 * 另一个收益：画图发生在本进程内，任何异常都能被「一键更新」的结果面板
 * 捕获并直接显示给用户，不再依赖 logcat 取证。
 */
internal object WidgetSnapshotRenderer {

    /** 逐组件刷新并带回结果；应用内「一键更新」入口调用（IO 线程） */
    fun refreshAllWithReport(context: Context): List<WidgetRefreshResult> {
        val manager = AppWidgetManager.getInstance(context)
        return WidgetKind.entries.map { kind ->
            val provider = when (kind) {
                WidgetKind.TODAY -> TodayWidgetProvider::class.java
                WidgetKind.NEXT -> NextWidgetProvider::class.java
                WidgetKind.BAR -> BarWidgetProvider::class.java
            }
            val ids = manager.getAppWidgetIds(ComponentName(context, provider))
            if (ids.isEmpty()) {
                return@map WidgetRefreshResult(kind.label, ok = true, placed = false, error = null)
            }
            var firstError: String? = null
            ids.forEach { id ->
                try {
                    render(context, manager, id, kind)
                } catch (e: Exception) {
                    if (firstError == null) firstError = e.message ?: e.javaClass.simpleName
                    Log.e(TAG, "快照渲染失败：${kind.label} id=$id", e)
                    runCatching {
                        val fallback = when (kind) {
                            WidgetKind.TODAY -> R.layout.widget_today
                            WidgetKind.NEXT -> R.layout.widget_next
                            WidgetKind.BAR -> R.layout.widget_bar
                        }
                        manager.updateAppWidget(id, ScheduleWidgetRenderer.bareViews(context, fallback))
                    }
                }
            }
            WidgetRefreshResult(kind.label, ok = firstError == null, placed = true, error = firstError)
        }
    }

    /** 渲染单个组件：按组件当前尺寸画快照 → RemoteViews 只放一个 ImageView */
    fun render(context: Context, manager: AppWidgetManager, widgetId: Int, kind: WidgetKind) {
        // 尺寸：桌面给的 dp（放不下 options 时退到 provider xml 的默认值）。
        // 手机竖屏下宽度 = MIN_WIDTH、高度 = MAX_HEIGHT（官方文档约定）
        val opts = manager.getAppWidgetOptions(widgetId)
        val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, kind.defWidthDp)
            .coerceAtLeast(24)
        val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, kind.defHeightDp)
            .coerceAtLeast(18)
        val d = context.resources.displayMetrics.density
        val w = (wDp * d).toInt().coerceIn(40, 1600)
        val h = (hDp * d).toInt().coerceIn(30, 1600)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val snapshot = ScheduleWidgetRenderer.load(context)
        // v0.5.0：画图前读一次样式偏好 —— 只替换颜色/圆角，管线不变
        val prefs = WidgetStylePrefs.load(context)
        val dark = isDark(context, prefs)
        when (kind) {
            WidgetKind.TODAY -> drawToday(canvas, snapshot, prefs, dark, d)
            WidgetKind.NEXT -> drawNext(canvas, snapshot, prefs, dark, d)
            WidgetKind.BAR -> drawBar(canvas, snapshot, prefs, dark, d)
        }

        val views = RemoteViews(context.packageName, R.layout.widget_snapshot).apply {
            setImageViewBitmap(R.id.widget_image, bitmap)
            setOnClickPendingIntent(R.id.widget_root, ScheduleWidgetRenderer.openApp(context, widgetId))
        }
        manager.updateAppWidget(widgetId, views)
    }

    // ------------------------------------------------------------------
    // 配色与画笔
    // ------------------------------------------------------------------

    private data class Palette(
        val bg: Int, val title: Int, val dim: Int, val faint: Int, val accent: Int,
        val nowBg: Int, val pastBg: Int, val upBg: Int
    )

    private fun palette(dark: Boolean) = if (dark) Palette(
        bg = 0xFF1B1E28.toInt(), title = 0xFFF2F4F8.toInt(), dim = 0xFF8B93A5.toInt(),
        faint = 0xFF5B6272.toInt(), accent = 0xFF6D8BFF.toInt(),
        nowBg = 0x332F4CC8, pastBg = 0x0AFFFFFF, upBg = 0x14FFFFFF
    ) else Palette(
        bg = 0xFFFBFCFE.toInt(), title = 0xFF20242E.toInt(), dim = 0xFF7A8294.toInt(),
        faint = 0xFFA8AFBF.toInt(), accent = 0xFF4F6BFF.toInt(),
        nowBg = 0x204F6BFF, pastBg = 0x0A1E2432, upBg = 0x0D1E2432
    )

    /** 深浅色：组件样式偏好优先（强制浅/深），否则跟随应用主题设置（跟随系统时看系统夜间模式） */
    private fun isDark(context: Context, prefs: WidgetStylePrefs): Boolean = when (prefs.themeMode) {
        WidgetStylePrefs.THEME_LIGHT -> false
        WidgetStylePrefs.THEME_DARK -> true
        else -> when (ThemeController.mode.value) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    /**
     * v0.5.0：在主题默认配色上叠加用户样式偏好。
     * 只替换颜色/圆角参数，绘制逻辑不变；自定义背景色时文字按背景明暗自动取深/浅，
     * 行底色从文字色派生，保证任意背景上都成立。
     */
    private fun styledPalette(prefs: WidgetStylePrefs, dark: Boolean): Palette {
        val base = palette(dark)
        val accent = if (prefs.isCustomAccent) prefs.accentColor else base.accent
        if (!prefs.isCustomBg) {
            return if (prefs.isCustomAccent) base.copy(accent = accent) else base
        }
        val bgOpaque = prefs.bgColor or 0xFF000000.toInt()
        val textSet = if (luminance(bgOpaque) > 0.55f) palette(false) else palette(true)
        val bgWithAlpha = (prefs.bgColor and 0x00FFFFFF) or (prefs.bgAlphaInt shl 24)
        return Palette(
            bg = bgWithAlpha,
            title = textSet.title,
            dim = textSet.dim,
            faint = textSet.faint,
            accent = accent,
            nowBg = accentAlpha(accent, if (dark) 0.20f else 0.13f),
            pastBg = textSet.pastBg,
            upBg = textSet.upBg
        )
    }

    private fun luminance(color: Int): Float {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    private fun accentAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /** 把任意颜色降透明度，用于分隔线等中性元素 */
    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    // ------------------------------------------------------------------
    // v0.6：每门课独立配色（与课表界面同一套「按色相派生」规则）
    // ------------------------------------------------------------------

    /** 单门课在快照里的一组颜色 */
    private data class CoursePaint(
        val bar: Int,    // 左侧色条 / 时间 / 高亮描边
        val name: Int,   // 课程名
        val sub: Int,    // 教室 · 时间次级文字
        val tint: Int    // 「正在上」整行浅底
    )

    /**
     * 数据库里只存 ARGB 原色（兼容 WakeUp 导入文件），渲染时只用它提供色相，
     * 明度饱和度按主题重新生成 —— 用户选的颜色再鲜艳，落进深色主题也是柔和的。
     * 规则与 ui/theme/CourseColors.kt 保持一致，组件和 App 内观感统一。
     */
    private fun coursePaint(
        course: ScheduleWidgetRenderer.WidgetCourse,
        prefs: WidgetStylePrefs,
        dark: Boolean,
        C: Palette
    ): CoursePaint {
        if (prefs.colorMode == WidgetStylePrefs.COLOR_SINGLE) {
            return CoursePaint(C.accent, C.title, C.dim, C.nowBg)
        }
        val hsv = FloatArray(3)
        Color.colorToHSV(course.colorArgb, hsv)
        val h = hsv[0]
        return if (dark) {
            CoursePaint(
                bar = hsl(h, 0.60f, 0.62f),
                name = hsl(h, 0.70f, 0.80f),
                sub = hsl(h, 0.24f, 0.68f),
                tint = hsl(h, 0.45f, 0.42f, 0.22f)
            )
        } else {
            CoursePaint(
                bar = hsl(h, 0.66f, 0.56f),
                name = hsl(h, 0.52f, 0.28f),
                sub = hsl(h, 0.20f, 0.42f),
                tint = hsl(h, 0.68f, 0.53f, 0.15f)
            )
        }
    }

    /** 标准 HSL → ARGB（h 单位：度）。android.graphics 只有 HSV，HSL 得自己换算 */
    private fun hsl(h: Float, s: Float, l: Float, a: Float = 1f): Int {
        val c = (1f - Math.abs(2f * l - 1f)) * s
        val hp = h / 60f
        val x = c * (1f - Math.abs(hp % 2f - 1f))
        val (r, g, b) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        fun ch(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return Color.argb((a * 255).toInt(), ch(r), ch(g), ch(b))
    }

    private fun text(d: Float, dp: Float, bold: Boolean, color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = dp * d
            isFakeBoldText = bold
        }

    private fun round(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
        canvas.drawRoundRect(RectF(x, y, x + w, y + h), r, r, Paint().apply { this.color = color })
    }

    private fun roundStroke(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, color: Int, d: Float) {
        canvas.drawRoundRect(
            RectF(x, y, x + w, y + h), r, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.2f * d
            }
        )
    }

    /** 文字垂直居中：drawText 的 y 是基线，居中要加回 textSize 的一半减去下沉量 */
    private fun centerY(top: Float, rowH: Float, p: Paint): Float =
        top + rowH / 2 + (p.textSize - p.fontMetrics.bottom - p.fontMetrics.top) / 2 - p.fontMetrics.bottom

    private fun ellipsize(s: String, p: Paint, maxPx: Float): String =
        TextUtils.ellipsize(s, android.text.TextPaint(p), maxPx, TextUtils.TruncateAt.END).toString()

    // ------------------------------------------------------------------
    // 三种画法
    // ------------------------------------------------------------------

    private fun drawToday(
        canvas: Canvas,
        s: ScheduleWidgetRenderer.TodaySnapshot,
        prefs: WidgetStylePrefs,
        dark: Boolean,
        d: Float
    ) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val C = styledPalette(prefs, dark)
        val S = prefs.fontScaleF
        val cardR = (if (prefs.isCustomRadius) prefs.cornerRadiusDp.coerceIn(0, 24) else 14) * d
        val pad = 7 * d
        round(canvas, 0f, 0f, w, h, cardR, C.bg)

        // ---- 标题行：左 = 课表名，右 = 「日期 第N周 · 周X」（周X 用强调色） ----
        val titleP = text(d, 12.5f * S, true, C.title)
        val titleBase = pad + titleP.textSize
        canvas.drawText(
            ellipsize(s.timetableName.ifBlank { "今日课表" }, titleP, w * 0.42f),
            pad + 2 * d, titleBase, titleP
        )
        val dowP = text(d, 9.5f * S, true, C.accent).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText(s.dayLabel, w - pad - 2 * d, titleBase, dowP)
        val headLeft = s.dateLabel + if (s.weekText.isBlank()) "" else "  ${s.weekText}"
        if (headLeft.isNotBlank()) {
            val headP = text(d, 9f * S, false, C.dim).apply { textAlign = Paint.Align.RIGHT }
            val headRight = w - pad - 2 * d - dowP.measureText(s.dayLabel) - 5 * d
            canvas.drawText(
                ellipsize(headLeft, headP, headRight - w * 0.45f),
                headRight, titleBase, headP
            )
        }

        // ---- 双栏：今天 | 明天 ----
        val colGap = 10 * d
        val colW = (w - 2 * pad - colGap) / 2
        val colHeadHeight = 11f * d * S
        val listTop = titleBase + 5 * d + colHeadHeight + 3 * d
        val colH = h - pad - listTop
        if (colH < 12 * d) return   // 异常小尺寸（极端拖拽）时宁可留白也不画叠字

        fun drawColumn(x: Float, label: String, labelColor: Int, list: List<ScheduleWidgetRenderer.WidgetCourse>, emptyText: String) {
            val headP = text(d, 10f * S, true, labelColor)
            canvas.drawText(label, x, listTop - 4 * d, headP)
            if (list.isEmpty()) {
                val eP = text(d, 9.5f * S, false, C.faint)
                canvas.drawText(emptyText, x, listTop + colH / 2, eP)
                return
            }
            // 行数自适应：先按最多 3 行摊，行高不足（字放大后放不下两行文字）就减行
            var count = minOf(list.size, 3)
            val gap = 3f * d
            while (count > 1 && (colH - (count - 1) * gap) / count < 26f * d * S) count--
            val rowH = (colH - (count - 1) * gap) / count
            list.take(count).forEachIndexed { i, c ->
                drawCourseRow(canvas, x, listTop + i * (rowH + gap), colW, rowH, c, prefs, dark, C, S, d)
            }
        }

        drawColumn(pad, "今天", C.accent, s.courses, "今天没有课")
        drawColumn(pad + colW + colGap, "明天 · ${s.tomorrowLabel}".trim(), C.dim, s.tomorrow, "明天没有课")

        // 中缝分隔线
        val lineP = Paint().apply { color = withAlpha(C.dim, 45); strokeWidth = d }
        canvas.drawLine(pad + colW + colGap / 2, listTop, pad + colW + colGap / 2, listTop + colH, lineP)
    }

    /** 双栏里的一行课程：左侧色条 + 课程名 + 「教室 · 时间」，颜色随课程色相派生 */
    private fun drawCourseRow(
        canvas: Canvas,
        x: Float,
        y: Float,
        rowW: Float,
        rowH: Float,
        c: ScheduleWidgetRenderer.WidgetCourse,
        prefs: WidgetStylePrefs,
        dark: Boolean,
        C: Palette,
        S: Float,
        d: Float
    ) {
        val cp = coursePaint(c, prefs, dark, C)
        val past = c.state == ScheduleWidgetRenderer.STATE_PAST
        val rowR = minOf(5 * d, cardRadiusOrDefault(prefs, 14) * d)
        val rowBg = when (c.state) {
            ScheduleWidgetRenderer.STATE_ONGOING -> cp.tint
            ScheduleWidgetRenderer.STATE_PAST -> C.pastBg
            else -> C.upBg
        }
        round(canvas, x, y, rowW, rowH, rowR, rowBg)
        if (c.state == ScheduleWidgetRenderer.STATE_ONGOING) {
            roundStroke(canvas, x, y, rowW, rowH, rowR, cp.bar, d)
        }

        // 左侧色条：这一行的视觉锚点
        val barW = 2.5f * d
        round(
            canvas, x + 3.5f * d, y + 4.5f * d, barW, rowH - 9 * d, barW / 2f,
            if (past) C.faint else cp.bar
        )

        val tx = x + 3.5f * d + barW + 4.5f * d
        val maxW = rowW - (tx - x) - 4 * d
        val subP = text(d, 8f * S, false, if (past) C.faint else cp.sub)
        val room = if (c.room.isBlank()) "第 ${c.node} 节" else c.room
        val subBase = y + rowH - 3 * d
        val nameP = text(d, 10.5f * S, true, if (past) C.faint else cp.name)
        val nameBase = subBase - subP.textSize - 2.5f * d
        canvas.drawText(ellipsize(c.name, nameP, maxW), tx, nameBase, nameP)
        canvas.drawText(
            ellipsize("$room · ${c.startTime}", subP, maxW),
            tx, subBase, subP
        )
    }

    private fun cardRadiusOrDefault(prefs: WidgetStylePrefs, defDp: Int): Int =
        if (prefs.isCustomRadius) prefs.cornerRadiusDp.coerceIn(0, 24) else defDp

    private fun drawNext(
        canvas: Canvas,
        s: ScheduleWidgetRenderer.TodaySnapshot,
        prefs: WidgetStylePrefs,
        dark: Boolean,
        d: Float
    ) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val C = styledPalette(prefs, dark)
        val S = prefs.fontScaleF
        val cardR = cardRadiusOrDefault(prefs, 14) * d
        val pad = 9 * d
        round(canvas, 0f, 0f, w, h, cardR, C.bg)

        val target = s.ongoing ?: s.upcoming
        val labelP = text(d, 8.5f * S, false, C.dim)
        canvas.drawText(
            when {
                target == null -> "今天"
                s.ongoing != null -> "正在上"
                else -> "下一节"
            },
            pad, h * 0.18f, labelP
        )

        if (target == null) {
            val emptyP = text(d, 11f * S, false, C.faint)
            val msg = when {
                s.courses.isEmpty() && s.tomorrow.isEmpty() -> "今天没有课"
                s.courses.isEmpty() -> "今天没有课 · 明天 ${s.tomorrow.size} 门"
                else -> "今天的课都上完了"
            }
            canvas.drawText(ellipsize(msg, emptyP, w - 2 * pad), pad, h * 0.55f, emptyP)
            return
        }

        val cp = coursePaint(target, prefs, dark, C)
        // 顶部色条：颜色随课程（配色模式=单一强调色时就是强调色）
        round(canvas, pad, h * 0.24f, w - 2 * pad, 3f * d, 1.5f * d, cp.bar)

        val timeP = text(d, 18f * S, true, cp.bar)
        canvas.drawText(target.startTime, pad, h * 0.55f, timeP)

        val nameP = text(d, 11.5f * S, true, cp.name)
        canvas.drawText(ellipsize(target.name, nameP, w - 2 * pad), pad, h * 0.75f, nameP)

        val roomP = text(d, 8.5f * S, false, cp.sub)
        val room = if (target.room.isBlank()) "第 ${target.node} 节" else target.room
        canvas.drawText(ellipsize(room, roomP, w - 2 * pad), pad, h * 0.91f, roomP)
    }

    private fun drawBar(
        canvas: Canvas,
        s: ScheduleWidgetRenderer.TodaySnapshot,
        prefs: WidgetStylePrefs,
        dark: Boolean,
        d: Float
    ) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val C = styledPalette(prefs, dark)
        val S = prefs.fontScaleF
        val cardR = cardRadiusOrDefault(prefs, 10) * d
        val pad = 8 * d
        round(canvas, 0f, 0f, w, h, cardR, C.bg)

        val target = s.ongoing ?: s.upcoming
        if (target == null) {
            val p = text(d, 10.5f * S, false, C.dim)
            val msg = when {
                s.courses.isEmpty() && s.tomorrow.isEmpty() -> "今天没有课"
                s.courses.isEmpty() -> "今天没有课 · 明天 ${s.tomorrow.size} 门"
                else -> "今天的课已结束"
            }
            canvas.drawText(ellipsize(msg, p, w - 2 * pad), pad + 2 * d, centerY(0f, h, p), p)
            return
        }

        val cp = coursePaint(target, prefs, dark, C)

        // 左端竖色条：颜色随课程
        val barW = 3f * d
        round(canvas, pad, pad + 2 * d, barW, h - 2 * pad - 4 * d, barW / 2f, cp.bar)

        val timeP = text(d, 14f * S, true, cp.bar)
        val timeX = pad + barW + 6 * d
        canvas.drawText(target.startTime, timeX, centerY(0f, h, timeP), timeP)

        val nameP = text(d, 10.5f * S, true, cp.name)
        val nameX = timeX + timeP.measureText(target.startTime) + 7 * d
        val roomP = text(d, 9f * S, false, cp.sub)
        val room = if (target.room.isBlank()) "" else " · ${target.room}"
        val nameMax = w - nameX - pad - 2 * d

        // v0.4.3 重影 bug 修复保持不变：每个文本只画一次
        val roomW = if (room.isEmpty()) 0f else roomP.measureText(room)
        val nameFit = if (room.isNotEmpty() && nameP.measureText(target.name) + roomW <= nameMax) {
            nameMax - roomW
        } else {
            nameMax
        }
        val drawnName = ellipsize(target.name, nameP, nameFit)
        canvas.drawText(drawnName, nameX, centerY(0f, h, nameP), nameP)
        if (room.isNotEmpty()) {
            canvas.drawText(room, nameX + nameP.measureText(drawnName), centerY(0f, h, roomP), roomP)
        }
    }
}
