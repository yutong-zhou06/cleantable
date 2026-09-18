package com.obsession.schedule.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import com.obsession.schedule.R

internal const val TAG = "ScheduleWidget"

/**
 * AppWidgetProvider 统一样板。
 *
 * onUpdate 跑在主线程，而渲染要查库（Room 默认禁止主线程访问数据库）→
 * 统一 goAsync + 后台线程，goAsync 给查询争取到完整的十秒广播窗口。
 *
 * 每个组件独立 try-catch：渲染失败退到空布局，宁可空白，
 * 也绝不让启动器停在「加载中」或者弹「载入窗口小组件时出现问题」。
 */
abstract class BaseWidgetProvider : AppWidgetProvider() {

    @LayoutRes
    protected abstract fun fallbackLayout(): Int

    protected abstract fun render(context: Context, manager: AppWidgetManager, widgetId: Int)

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) = runAsync(this, context, appWidgetIds, fallbackLayout()) { c, m, id -> render(c, m, id) }

    /** 桌面上拖拽调整尺寸后按新尺寸重画 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?
    ) = runAsync(this, context, intArrayOf(appWidgetId), fallbackLayout()) { c, m, id ->
        render(c, m, id)
    }
}

/** 渲染彻底失败时的兜底 RemoteViews：只有背景和点击，不碰任何数据 */
fun bareViews(context: Context, @LayoutRes layout: Int): RemoteViews =
    Rv.of(context, layout).apply {
        setOnClickPendingIntent(R.id.widget_root, Rv.openApp(context, 0))
    }

internal fun runAsync(
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
                        Log.e(TAG, "组件渲染失败：id=$id", e)
                        runCatching {
                            manager.updateAppWidget(id, bareViews(context, fallbackLayout))
                        }
                    }
                }
            }
        } finally {
            pending.finish()
        }
    }.start()
}

/** 一个已注册的小组件 */
data class WidgetKindInfo(
    val label: String,
    val provider: Class<*>,
    @LayoutRes val layout: Int,
    val render: (Context, AppWidgetManager, Int) -> Unit
)

/** 「更新桌面小组件」里逐组件展示的结果 */
data class WidgetRefreshResult(
    val widget: String,
    val ok: Boolean,
    val placed: Boolean,
    val error: String?
)

/**
 * 四个小组件的注册表。
 *
 * 刷新入口只认这张表 —— 以后增删组件只改这一处，
 * 不用再去 MainActivity / Application / ViewModel / 设置页挨个改调用点。
 */
object WidgetRegistry {

    val ALL: List<WidgetKindInfo> = listOf(
        WidgetKindInfo("周视图", WeekWidgetProvider::class.java, R.layout.widget_week) { c, m, id ->
            WeekWidgetRenderer.update(c, m, id)
        },
        WidgetKindInfo("日视图", DayWidgetProvider::class.java, R.layout.widget_day) { c, m, id ->
            DayWidgetRenderer.update(c, m, id)
        },
        WidgetKindInfo("近日课程", UpcomingWidgetProvider::class.java, R.layout.widget_upcoming) { c, m, id ->
            UpcomingWidgetRenderer.update(c, m, id)
        },
        WidgetKindInfo("今日课程", CompactWidgetProvider::class.java, R.layout.widget_compact) { c, m, id ->
            CompactWidgetRenderer.update(c, m, id)
        }
    )

    /** 课程增删改后立即刷新所有已放置的组件（调用方负责 IO 线程） */
    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        ALL.forEach { info ->
            manager.getAppWidgetIds(ComponentName(context, info.provider)).forEach { id ->
                try {
                    info.render(context, manager, id)
                } catch (e: Exception) {
                    Log.e(TAG, "组件刷新失败：${info.label} id=$id", e)
                    runCatching { manager.updateAppWidget(id, bareViews(context, info.layout)) }
                }
            }
        }
    }

    /** 「更新桌面小组件」入口用：带回每个组件的结果，失败原因直接展示给用户 */
    fun refreshAllWithReport(context: Context): List<WidgetRefreshResult> {
        val manager = AppWidgetManager.getInstance(context)
        return ALL.map { info ->
            val ids = manager.getAppWidgetIds(ComponentName(context, info.provider))
            if (ids.isEmpty()) {
                WidgetRefreshResult(info.label, ok = true, placed = false, error = null)
            } else {
                var firstError: String? = null
                ids.forEach { id ->
                    try {
                        info.render(context, manager, id)
                    } catch (e: Exception) {
                        if (firstError == null) firstError = e.message ?: e.javaClass.simpleName
                        Log.e(TAG, "组件刷新失败：${info.label} id=$id", e)
                    }
                }
                WidgetRefreshResult(
                    info.label,
                    ok = firstError == null,
                    placed = true,
                    error = firstError
                )
            }
        }
    }
}
