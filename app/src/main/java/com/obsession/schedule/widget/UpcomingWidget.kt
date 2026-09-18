package com.obsession.schedule.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import com.obsession.schedule.R

class UpcomingWidgetProvider : BaseWidgetProvider() {
    override fun fallbackLayout(): Int = R.layout.widget_upcoming
    override fun render(context: Context, manager: AppWidgetManager, widgetId: Int) =
        UpcomingWidgetRenderer.update(context, manager, widgetId)
}

internal object UpcomingWidgetRenderer {

    private val TODAY_SLOT = intArrayOf(
        R.id.up_today_1_slot, R.id.up_today_2_slot,
        R.id.up_today_3_slot, R.id.up_today_4_slot
    )
    private val TODAY_BAR = intArrayOf(
        R.id.up_today_1_bar, R.id.up_today_2_bar,
        R.id.up_today_3_bar, R.id.up_today_4_bar
    )
    private val TODAY_NAME = intArrayOf(
        R.id.up_today_1_name, R.id.up_today_2_name,
        R.id.up_today_3_name, R.id.up_today_4_name
    )
    private val TODAY_ROOM = intArrayOf(
        R.id.up_today_1_room, R.id.up_today_2_room,
        R.id.up_today_3_room, R.id.up_today_4_room
    )
    private val TODAY_TIME = intArrayOf(
        R.id.up_today_1_time, R.id.up_today_2_time,
        R.id.up_today_3_time, R.id.up_today_4_time
    )

    private val TMR_SLOT = intArrayOf(
        R.id.up_tmr_1_slot, R.id.up_tmr_2_slot,
        R.id.up_tmr_3_slot, R.id.up_tmr_4_slot
    )
    private val TMR_BAR = intArrayOf(
        R.id.up_tmr_1_bar, R.id.up_tmr_2_bar,
        R.id.up_tmr_3_bar, R.id.up_tmr_4_bar
    )
    private val TMR_NAME = intArrayOf(
        R.id.up_tmr_1_name, R.id.up_tmr_2_name,
        R.id.up_tmr_3_name, R.id.up_tmr_4_name
    )
    private val TMR_ROOM = intArrayOf(
        R.id.up_tmr_1_room, R.id.up_tmr_2_room,
        R.id.up_tmr_3_room, R.id.up_tmr_4_room
    )
    private val TMR_TIME = intArrayOf(
        R.id.up_tmr_1_time, R.id.up_tmr_2_time,
        R.id.up_tmr_3_time, R.id.up_tmr_4_time
    )

    fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val snapshot = WidgetData.load(context)
        val prefs = WidgetStylePrefs.load(context)
        val night = WidgetTheme.night(context, prefs)
        val c = WidgetTheme.colors(context, prefs)
        val views = Rv.of(context, R.layout.widget_upcoming)
        val f = prefs.fontScaleF

        // 头部
        views.text(R.id.up_head_left, snapshot.headerLeft)
        views.textColor(R.id.up_head_left, c.text)
        views.textSize(R.id.up_head_left, 12f * f)
        views.text(R.id.up_head_right, snapshot.shortHeaderRight)
        views.textColor(R.id.up_head_right, c.textDim)
        views.textSize(R.id.up_head_right, 10f * f)

        // 列标题
        views.text(R.id.up_today_title, "今天")
        views.textColor(R.id.up_today_title, c.accent)
        views.textSize(R.id.up_today_title, 10f * f)
        views.text(R.id.up_tmr_title, "明天 · " + snapshot.tomorrowLabel)
        views.textColor(R.id.up_tmr_title, c.accent)
        views.textSize(R.id.up_tmr_title, 10f * f)

        // 左栏：今天
        fillColumn(
            views, snapshot.today, TODAY_SLOT, TODAY_BAR, TODAY_NAME,
            TODAY_ROOM, TODAY_TIME, R.id.up_today_empty, "今天没有课", f, prefs, c, night
        )
        // 右栏：明天（全程未开始）
        fillColumn(
            views, snapshot.tomorrow, TMR_SLOT, TMR_BAR, TMR_NAME,
            TMR_ROOM, TMR_TIME, R.id.up_tmr_empty, "明天没有课", f, prefs, c, night
        )

        views.clickOpen(context, widgetId)
        manager.updateAppWidget(widgetId, views)
    }

    private fun fillColumn(
        views: android.widget.RemoteViews,
        courses: List<CourseCard>,
        slotIds: IntArray,
        barIds: IntArray,
        nameIds: IntArray,
        roomIds: IntArray,
        timeIds: IntArray,
        emptyId: Int,
        emptyText: String,
        f: Float,
        prefs: WidgetStylePrefs,
        c: WColors,
        night: Boolean
    ) {
        for (n in 0 until slotIds.size) {
            val has = n < courses.size
            views.visible(slotIds[n], has)
            if (has) {
                val card = courses[n]
                views.tint(barIds[n], WidgetCards.bar(card, prefs, c, night))
                views.alpha(barIds[n], WidgetCards.alpha(card))
                views.text(nameIds[n], card.name)
                views.textColor(nameIds[n], WidgetCards.onCard)
                views.textSize(nameIds[n], 12f * f)
                views.text(roomIds[n], card.roomOrNode)
                views.textColor(roomIds[n], WidgetCards.onCardDim)
                views.textSize(roomIds[n], 10f * f)
                views.text(timeIds[n], card.timeRange)
                views.textColor(timeIds[n], WidgetCards.onCardDim)
                views.textSize(timeIds[n], 10f * f)
            }
        }
        val empty = courses.isEmpty()
        views.visible(emptyId, empty)
        if (empty) {
            views.text(emptyId, emptyText)
            views.textColor(emptyId, c.textFaint)
            views.textSize(emptyId, 11f * f)
        }
    }
}
