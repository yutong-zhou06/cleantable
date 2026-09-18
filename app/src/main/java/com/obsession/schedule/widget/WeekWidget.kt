package com.obsession.schedule.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import com.obsession.schedule.R

/**
 * 周视图 4×2：头部 + 固定星期栏 + ListView（行槽滚动，格子等大）。
 *
 * 第 N 行 = 周一到周日各自的第 N 门课（WidgetFactory.KIND_WEEK），
 * 一屏显示多少行由设备实际高度决定，放不下的上下滑动查看。
 */
class WeekWidgetProvider : BaseWidgetProvider() {
    override fun fallbackLayout(): Int = R.layout.widget_week
    override fun render(context: Context, manager: AppWidgetManager, widgetId: Int) =
        WeekWidgetRenderer.update(context, manager, widgetId)
}

internal object WeekWidgetRenderer {

    private val HEAD_IDS = intArrayOf(
        R.id.week_head_1, R.id.week_head_2, R.id.week_head_3, R.id.week_head_4,
        R.id.week_head_5, R.id.week_head_6, R.id.week_head_7
    )

    fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val snapshot = WidgetData.load(context)
        val c = WidgetTheme.colors(context)
        val views = Rv.of(context, R.layout.widget_week)

        // 头部
        views.text(R.id.week_date, snapshot.shortDate)
        views.text(
            R.id.week_sub,
            listOf(snapshot.headerLeft, snapshot.weekLabel, snapshot.dayLabel)
                .filter { it.isNotBlank() }.joinToString(" | ")
        )

        // 星期栏：今天那列用强调色
        snapshot.week.forEachIndexed { i, col ->
            views.textColor(HEAD_IDS[i], if (col.isToday) c.accent else c.textDim)
        }

        // 头部图标
        views.tint(R.id.week_ic_set, c.textDim)
        views.tint(R.id.week_ic_open, c.textDim)
        views.clickIcon(context, R.id.week_ic_set, widgetId, Rv.ACTION_SETTINGS)
        views.clickIcon(context, R.id.week_ic_open, widgetId, Rv.ACTION_HOME)

        // 无课提示（AdapterView 空数据时自动显示）
        views.setEmptyView(R.id.week_list, R.id.week_empty)

        // 列表适配器
        val adapter = Intent(context, WidgetListService::class.java).apply {
            action = WidgetFactory.factoryAction(WidgetFactory.KIND_WEEK, widgetId)
            putExtra(WidgetFactory.EXTRA_WIDGET_ID, widgetId)
            putExtra(WidgetFactory.EXTRA_KIND, WidgetFactory.KIND_WEEK)
        }
        views.setRemoteAdapter(R.id.week_list, adapter)
        views.setPendingIntentTemplate(R.id.week_list, Rv.openApp(context, widgetId))

        manager.updateAppWidget(widgetId, views)
        manager.notifyAppWidgetViewDataChanged(widgetId, R.id.week_list)
    }
}
