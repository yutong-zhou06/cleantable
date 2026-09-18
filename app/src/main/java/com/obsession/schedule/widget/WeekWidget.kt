package com.obsession.schedule.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import com.obsession.schedule.R

class WeekWidgetProvider : BaseWidgetProvider() {
    override fun fallbackLayout(): Int = R.layout.widget_week
    override fun render(context: Context, manager: AppWidgetManager, widgetId: Int) =
        WeekWidgetRenderer.update(context, manager, widgetId)
}

internal object WeekWidgetRenderer {

    /** 7 列表头 + 7 列色条 + 21 课程位（每列 3 位），按列主序排列，索引 = col*3 + row */
    private val HEAD_IDS = intArrayOf(
        R.id.week_head_1, R.id.week_head_2, R.id.week_head_3, R.id.week_head_4,
        R.id.week_head_5, R.id.week_head_6, R.id.week_head_7
    )
    private val BAR_IDS = intArrayOf(
        R.id.week_bar_1, R.id.week_bar_2, R.id.week_bar_3, R.id.week_bar_4,
        R.id.week_bar_5, R.id.week_bar_6, R.id.week_bar_7
    )
    private val SLOT_IDS = intArrayOf(
        R.id.week_slot_1_1, R.id.week_slot_1_2, R.id.week_slot_1_3,
        R.id.week_slot_2_1, R.id.week_slot_2_2, R.id.week_slot_2_3,
        R.id.week_slot_3_1, R.id.week_slot_3_2, R.id.week_slot_3_3,
        R.id.week_slot_4_1, R.id.week_slot_4_2, R.id.week_slot_4_3,
        R.id.week_slot_5_1, R.id.week_slot_5_2, R.id.week_slot_5_3,
        R.id.week_slot_6_1, R.id.week_slot_6_2, R.id.week_slot_6_3,
        R.id.week_slot_7_1, R.id.week_slot_7_2, R.id.week_slot_7_3
    )
    private val BG_IDS = intArrayOf(
        R.id.week_c1_1_bg, R.id.week_c1_2_bg, R.id.week_c1_3_bg,
        R.id.week_c2_1_bg, R.id.week_c2_2_bg, R.id.week_c2_3_bg,
        R.id.week_c3_1_bg, R.id.week_c3_2_bg, R.id.week_c3_3_bg,
        R.id.week_c4_1_bg, R.id.week_c4_2_bg, R.id.week_c4_3_bg,
        R.id.week_c5_1_bg, R.id.week_c5_2_bg, R.id.week_c5_3_bg,
        R.id.week_c6_1_bg, R.id.week_c6_2_bg, R.id.week_c6_3_bg,
        R.id.week_c7_1_bg, R.id.week_c7_2_bg, R.id.week_c7_3_bg
    )
    private val TIME_IDS = intArrayOf(
        R.id.week_c1_1_time, R.id.week_c1_2_time, R.id.week_c1_3_time,
        R.id.week_c2_1_time, R.id.week_c2_2_time, R.id.week_c2_3_time,
        R.id.week_c3_1_time, R.id.week_c3_2_time, R.id.week_c3_3_time,
        R.id.week_c4_1_time, R.id.week_c4_2_time, R.id.week_c4_3_time,
        R.id.week_c5_1_time, R.id.week_c5_2_time, R.id.week_c5_3_time,
        R.id.week_c6_1_time, R.id.week_c6_2_time, R.id.week_c6_3_time,
        R.id.week_c7_1_time, R.id.week_c7_2_time, R.id.week_c7_3_time
    )
    private val NAME_IDS = intArrayOf(
        R.id.week_c1_1_name, R.id.week_c1_2_name, R.id.week_c1_3_name,
        R.id.week_c2_1_name, R.id.week_c2_2_name, R.id.week_c2_3_name,
        R.id.week_c3_1_name, R.id.week_c3_2_name, R.id.week_c3_3_name,
        R.id.week_c4_1_name, R.id.week_c4_2_name, R.id.week_c4_3_name,
        R.id.week_c5_1_name, R.id.week_c5_2_name, R.id.week_c5_3_name,
        R.id.week_c6_1_name, R.id.week_c6_2_name, R.id.week_c6_3_name,
        R.id.week_c7_1_name, R.id.week_c7_2_name, R.id.week_c7_3_name
    )
    private val ROOM_IDS = intArrayOf(
        R.id.week_c1_1_room, R.id.week_c1_2_room, R.id.week_c1_3_room,
        R.id.week_c2_1_room, R.id.week_c2_2_room, R.id.week_c2_3_room,
        R.id.week_c3_1_room, R.id.week_c3_2_room, R.id.week_c3_3_room,
        R.id.week_c4_1_room, R.id.week_c4_2_room, R.id.week_c4_3_room,
        R.id.week_c5_1_room, R.id.week_c5_2_room, R.id.week_c5_3_room,
        R.id.week_c6_1_room, R.id.week_c6_2_room, R.id.week_c6_3_room,
        R.id.week_c7_1_room, R.id.week_c7_2_room, R.id.week_c7_3_room
    )

    fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val snapshot = WidgetData.load(context)
        val prefs = WidgetStylePrefs.load(context)
        val night = WidgetTheme.night(context, prefs)
        val c = WidgetTheme.colors(context, prefs)
        val views = Rv.of(context, R.layout.widget_week)
        val f = prefs.fontScaleF

        // 头部
        views.text(R.id.week_date, snapshot.dateText)
        views.textColor(R.id.week_date, c.text)
        views.textSize(R.id.week_date, 11f * f)
        views.text(R.id.week_meta, "${snapshot.headerLeft} | ${snapshot.headerRight}")
        views.textColor(R.id.week_meta, c.textDim)
        views.textSize(R.id.week_meta, 9f * f)
        views.tint(R.id.week_settings, c.textDim)
        views.tint(R.id.week_open, c.textDim)
        views.clickIcon(context, R.id.week_settings, 0, Rv.ACTION_SETTINGS)
        views.clickIcon(context, R.id.week_open, 0, Rv.ACTION_HOME)

        // 7 列主体
        val week = snapshot.week
        for (col in 0..6) {
            val day = week[col]
            val headId = HEAD_IDS[col]
            views.text(headId, day.label)
            views.textColor(headId, if (day.isToday) c.accent else c.textDim)
            views.textSize(headId, 9f * f)

            val barId = BAR_IDS[col]
            if (day.isToday) {
                views.tint(barId, c.accent)
                views.visible(barId, true)
            } else {
                views.visible(barId, false)
            }

            val courses = day.courses
            for (row in 0..2) {
                val idx = col * 3 + row
                val card = courses.getOrNull(row)
                if (card == null) {
                    views.visible(SLOT_IDS[idx], false)
                    continue
                }
                views.visible(SLOT_IDS[idx], true)
                views.tint(BG_IDS[idx], WidgetCards.bg(card, prefs, c, night))
                views.alpha(BG_IDS[idx], WidgetCards.alpha(card))
                views.text(TIME_IDS[idx], card.startTime)
                views.textColor(TIME_IDS[idx], WidgetCards.onCardDim)
                views.textSize(TIME_IDS[idx], 7f * f)
                views.text(NAME_IDS[idx], card.name)
                views.textColor(NAME_IDS[idx], WidgetCards.onCard)
                views.textSize(NAME_IDS[idx], 8f * f)
                views.text(ROOM_IDS[idx], card.roomOrNode)
                views.textColor(ROOM_IDS[idx], WidgetCards.onCardDim)
                views.textSize(ROOM_IDS[idx], 7f * f)
            }
        }

        views.clickOpen(context, widgetId)
        manager.updateAppWidget(widgetId, views)
    }
}
