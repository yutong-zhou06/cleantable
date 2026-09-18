package com.obsession.schedule.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import com.obsession.schedule.R

/**
 * 近日课程 4×2：头部 + 今天｜明天两栏，各自独立滚动（两个 ListView、两个工厂）。
 */
class UpcomingWidgetProvider : BaseWidgetProvider() {
    override fun fallbackLayout(): Int = R.layout.widget_upcoming
    override fun render(context: Context, manager: AppWidgetManager, widgetId: Int) =
        UpcomingWidgetRenderer.update(context, manager, widgetId)
}

internal object UpcomingWidgetRenderer {

    fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val snapshot = WidgetData.load(context)
        val c = WidgetTheme.colors(context)
        val views = Rv.of(context, R.layout.widget_upcoming)

        // 头部
        views.text(R.id.up_title, snapshot.headerLeft)
        views.text(R.id.up_date, snapshot.shortHeaderRight)

        // 两栏小标题：今天（强调色）｜明天 · 周X
        views.text(R.id.up_t1, "今天")
        views.text(R.id.up_t2, "明天 · ${snapshot.tomorrowLabel}")

        // 今天栏
        val today = Intent(context, WidgetListService::class.java).apply {
            action = WidgetFactory.factoryAction(WidgetFactory.KIND_UP_TODAY, widgetId)
            putExtra(WidgetFactory.EXTRA_WIDGET_ID, widgetId)
            putExtra(WidgetFactory.EXTRA_KIND, WidgetFactory.KIND_UP_TODAY)
        }
        views.setRemoteAdapter(R.id.up_list_t, today)
        views.setEmptyView(R.id.up_list_t, R.id.up_empty_t)
        views.setPendingIntentTemplate(R.id.up_list_t, Rv.openApp(context, widgetId))

        // 明天栏
        val tomorrow = Intent(context, WidgetListService::class.java).apply {
            action = WidgetFactory.factoryAction(WidgetFactory.KIND_UP_TOMORROW, widgetId)
            putExtra(WidgetFactory.EXTRA_WIDGET_ID, widgetId)
            putExtra(WidgetFactory.EXTRA_KIND, WidgetFactory.KIND_UP_TOMORROW)
        }
        views.setRemoteAdapter(R.id.up_list_m, tomorrow)
        views.setEmptyView(R.id.up_list_m, R.id.up_empty_m)
        views.setPendingIntentTemplate(R.id.up_list_m, Rv.openApp(context, widgetId))

        manager.updateAppWidget(widgetId, views)
        manager.notifyAppWidgetViewDataChanged(widgetId, R.id.up_list_t)
        manager.notifyAppWidgetViewDataChanged(widgetId, R.id.up_list_m)
    }
}
