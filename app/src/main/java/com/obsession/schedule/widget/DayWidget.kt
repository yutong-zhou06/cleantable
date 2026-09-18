package com.obsession.schedule.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import com.obsession.schedule.R

class DayWidgetProvider : BaseWidgetProvider() {
    override fun fallbackLayout(): Int = R.layout.widget_day
    override fun render(context: Context, manager: AppWidgetManager, widgetId: Int) =
        DayWidgetRenderer.update(context, manager, widgetId)
}

internal object DayWidgetRenderer {

    private val ROW_IDS = intArrayOf(
        R.id.day_row_1, R.id.day_row_2, R.id.day_row_3,
        R.id.day_row_4, R.id.day_row_5, R.id.day_row_6
    )
    private val BG_IDS = intArrayOf(
        R.id.day_bg_1, R.id.day_bg_2, R.id.day_bg_3,
        R.id.day_bg_4, R.id.day_bg_5, R.id.day_bg_6
    )
    private val BAR_IDS = intArrayOf(
        R.id.day_bar_1, R.id.day_bar_2, R.id.day_bar_3,
        R.id.day_bar_4, R.id.day_bar_5, R.id.day_bar_6
    )
    private val NT_IDS = intArrayOf(
        R.id.day_nt_1, R.id.day_nt_2, R.id.day_nt_3,
        R.id.day_nt_4, R.id.day_nt_5, R.id.day_nt_6
    )
    private val NAME_IDS = intArrayOf(
        R.id.day_name_1, R.id.day_name_2, R.id.day_name_3,
        R.id.day_name_4, R.id.day_name_5, R.id.day_name_6
    )
    private val ROOM_ICON_IDS = intArrayOf(
        R.id.day_room_icon_1, R.id.day_room_icon_2, R.id.day_room_icon_3,
        R.id.day_room_icon_4, R.id.day_room_icon_5, R.id.day_room_icon_6
    )
    private val ROOM_IDS = intArrayOf(
        R.id.day_room_1, R.id.day_room_2, R.id.day_room_3,
        R.id.day_room_4, R.id.day_room_5, R.id.day_room_6
    )
    private val TEACHER_ICON_IDS = intArrayOf(
        R.id.day_teacher_icon_1, R.id.day_teacher_icon_2, R.id.day_teacher_icon_3,
        R.id.day_teacher_icon_4, R.id.day_teacher_icon_5, R.id.day_teacher_icon_6
    )
    private val TEACHER_IDS = intArrayOf(
        R.id.day_teacher_1, R.id.day_teacher_2, R.id.day_teacher_3,
        R.id.day_teacher_4, R.id.day_teacher_5, R.id.day_teacher_6
    )

    fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val snapshot = WidgetData.load(context)
        val prefs = WidgetStylePrefs.load(context)
        val night = WidgetTheme.night(context, prefs)
        val c = WidgetTheme.colors(context, prefs)
        val views = Rv.of(context, R.layout.widget_day)
        val f = prefs.fontScaleF

        // 头部
        views.text(R.id.day_date, snapshot.dateText)
        views.textColor(R.id.day_date, c.text)
        views.textSize(R.id.day_date, 11f * f)
        views.text(R.id.day_meta, "${snapshot.headerLeft} | ${snapshot.headerRight}")
        views.textColor(R.id.day_meta, c.textDim)
        views.textSize(R.id.day_meta, 9f * f)
        views.tint(R.id.day_filter, c.textDim)
        views.tint(R.id.day_open, c.textDim)
        views.clickIcon(context, R.id.day_filter, 0, Rv.ACTION_SETTINGS)
        views.clickIcon(context, R.id.day_open, 0, Rv.ACTION_HOME)

        // 6 行主体：先全部隐藏，再按课程填充
        for (n in 0..5) {
            views.visible(ROW_IDS[n], false)
            views.visible(BG_IDS[n], false)
        }

        val today = snapshot.today
        for (n in 0 until minOf(today.size, 6)) {
            val card = today[n]
            views.visible(ROW_IDS[n], true)
            views.tint(BAR_IDS[n], WidgetCards.bar(card, prefs, c, night))
            views.alpha(BAR_IDS[n], WidgetCards.alpha(card))
            views.text(NT_IDS[n], card.nodeTimeLabel)
            views.textColor(NT_IDS[n], WidgetCards.onCardDim)
            views.textSize(NT_IDS[n], 9f * f)
            views.text(NAME_IDS[n], card.name)
            views.textColor(NAME_IDS[n], WidgetCards.onCard)
            views.textSize(NAME_IDS[n], 12f * f)

            // 进行中的行：整行淡色底
            if (WidgetCards.isOngoing(card)) {
                views.tint(BG_IDS[n], c.ongoingTint)
                views.visible(BG_IDS[n], true)
            }

            // 教室 + 教师
            views.tint(ROOM_ICON_IDS[n], WidgetCards.onCardDim)
            views.text(ROOM_IDS[n], card.roomOrNode)
            views.textColor(ROOM_IDS[n], WidgetCards.onCardDim)
            views.textSize(ROOM_IDS[n], 9f * f)

            val hasTeacher = card.teacher.isNotBlank()
            views.visible(TEACHER_ICON_IDS[n], hasTeacher)
            views.visible(TEACHER_IDS[n], hasTeacher)
            if (hasTeacher) {
                views.tint(TEACHER_ICON_IDS[n], WidgetCards.onCardDim)
                views.text(TEACHER_IDS[n], card.teacher)
                views.textColor(TEACHER_IDS[n], WidgetCards.onCardDim)
                views.textSize(TEACHER_IDS[n], 9f * f)
            }
        }

        // 无课时提示与课程行互斥
        if (today.isEmpty()) {
            views.text(R.id.day_empty, "今天没有课，好好休息 🌙")
            views.textColor(R.id.day_empty, c.textFaint)
            views.textSize(R.id.day_empty, 12f * f)
            views.visible(R.id.day_empty, true)
        } else {
            views.visible(R.id.day_empty, false)
        }

        views.clickOpen(context, widgetId)
        manager.updateAppWidget(widgetId, views)
    }
}
