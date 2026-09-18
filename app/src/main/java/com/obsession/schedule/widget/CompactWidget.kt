package com.obsession.schedule.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import com.obsession.schedule.R

/**
 * 今日课程 2×2 紧凑版：极小头部 + 今日课程列表（上下滚动）。
 * 每行：色条 + 课程名(+时间段) + 教室；无图标。
 */
class CompactWidgetProvider : BaseWidgetProvider() {
    override fun fallbackLayout(): Int = R.layout.widget_compact
    override fun render(context: Context, manager: AppWidgetManager, widgetId: Int) =
        CompactWidgetRenderer.update(context, manager, widgetId)
}

internal object CompactWidgetRenderer {

    fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val snapshot = WidgetData.load(context)
        val views = Rv.of(context, R.layout.widget_compact)

        // 头部：极小字
        views.text(R.id.cp_title, snapshot.headerLeft)
        views.text(R.id.cp_date, snapshot.shortDayLabel)

        views.setEmptyView(R.id.cp_list, R.id.cp_empty)

        val adapter = Intent(context, WidgetListService::class.java).apply {
            putExtra(WidgetFactory.EXTRA_WIDGET_ID, widgetId)
            putExtra(WidgetFactory.EXTRA_KIND, WidgetFactory.KIND_COMPACT)
        }
        views.setRemoteAdapter(R.id.cp_list, adapter)
        views.setPendingIntentTemplate(R.id.cp_list, Rv.openApp(context, widgetId))

        manager.updateAppWidget(widgetId, views)
        manager.notifyAppWidgetViewDataChanged(widgetId, R.id.cp_list)
    }
}
