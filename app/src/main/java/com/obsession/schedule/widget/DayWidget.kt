package com.obsession.schedule.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import com.obsession.schedule.R

/**
 * 日视图 4×2：头部 + 今日课程列表（上下滚动）。
 * 每行：淡课程底 + 左侧色条 + 「节次 课程名 时间段」+「教室 教师」。
 */
class DayWidgetProvider : BaseWidgetProvider() {
    override fun fallbackLayout(): Int = R.layout.widget_day
    override fun render(context: Context, manager: AppWidgetManager, widgetId: Int) =
        DayWidgetRenderer.update(context, manager, widgetId)
}

internal object DayWidgetRenderer {

    fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val snapshot = WidgetData.load(context)
        val c = WidgetTheme.colors(context)
        val views = Rv.of(context, R.layout.widget_day)

        // 头部
        views.text(R.id.day_date, snapshot.shortDate)
        views.text(
            R.id.day_sub,
            listOf(snapshot.headerLeft, snapshot.weekLabel, snapshot.dayLabel)
                .filter { it.isNotBlank() }.joinToString(" | ")
        )
        views.tint(R.id.day_ic_set, c.textDim)
        views.tint(R.id.day_ic_open, c.textDim)
        views.clickIcon(context, R.id.day_ic_set, widgetId, Rv.ACTION_SETTINGS)
        views.clickIcon(context, R.id.day_ic_open, widgetId, Rv.ACTION_HOME)
        // 点组件任意位置都进入应用（此前只有头部小图标能点）
        views.clickOpen(context, widgetId)

        views.setEmptyView(R.id.day_list, R.id.day_empty)

        val adapter = Intent(context, WidgetListService::class.java).apply {
            action = WidgetFactory.factoryAction(WidgetFactory.KIND_DAY, widgetId)
            putExtra(WidgetFactory.EXTRA_WIDGET_ID, widgetId)
            putExtra(WidgetFactory.EXTRA_KIND, WidgetFactory.KIND_DAY)
        }
        views.setRemoteAdapter(R.id.day_list, adapter)
        views.setPendingIntentTemplate(R.id.day_list, Rv.openApp(context, widgetId))

        manager.updateAppWidget(widgetId, views)
        manager.notifyAppWidgetViewDataChanged(widgetId, R.id.day_list)
    }
}
