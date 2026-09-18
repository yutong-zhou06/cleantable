package com.obsession.schedule.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import com.obsession.schedule.R

class CompactWidgetProvider : BaseWidgetProvider() {
    override fun fallbackLayout(): Int = R.layout.widget_compact
    override fun render(context: Context, manager: AppWidgetManager, widgetId: Int) =
        CompactWidgetRenderer.update(context, manager, widgetId)
}

internal object CompactWidgetRenderer {

    private val SLOT_IDS = intArrayOf(R.id.cmp_1_slot, R.id.cmp_2_slot, R.id.cmp_3_slot)
    private val BAR_IDS = intArrayOf(R.id.cmp_1_bar, R.id.cmp_2_bar, R.id.cmp_3_bar)
    private val NAME_IDS = intArrayOf(R.id.cmp_1_name, R.id.cmp_2_name, R.id.cmp_3_name)
    private val SUB_IDS = intArrayOf(R.id.cmp_1_sub, R.id.cmp_2_sub, R.id.cmp_3_sub)

    fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val snapshot = WidgetData.load(context)
        val prefs = WidgetStylePrefs.load(context)
        val night = WidgetTheme.night(context, prefs)
        val c = WidgetTheme.colors(context, prefs)
        val views = Rv.of(context, R.layout.widget_compact)

        // 头部：左 学期/课表名，右 日期 星期（无图标）
        // 2×2 太窄，右侧用短日期「9.18 周五」，写全年份会被 ellipsize 掉一截
        views.text(R.id.cmp_head_left, snapshot.headerLeft)
        views.textColor(R.id.cmp_head_left, c.text)
        views.textSize(R.id.cmp_head_left, 8.5f * prefs.fontScaleF)

        views.text(R.id.cmp_head_right, snapshot.shortDayLabel)
        views.textColor(R.id.cmp_head_right, c.textDim)
        views.textSize(R.id.cmp_head_right, 8.5f * prefs.fontScaleF)

        val courses = snapshot.today
        val hasCourse = courses.isNotEmpty()

        // 空状态与课程位互斥
        views.visible(R.id.cmp_empty, !hasCourse)
        views.text(R.id.cmp_empty, "今天没有课")
        views.textColor(R.id.cmp_empty, c.textFaint)
        views.textSize(R.id.cmp_empty, 10f * prefs.fontScaleF)

        SLOT_IDS.forEachIndexed { i, slotId ->
            val card = courses.getOrNull(i)
            if (card == null) {
                views.visible(slotId, false)
                return@forEachIndexed
            }
            views.visible(slotId, true)
            views.tint(BAR_IDS[i], WidgetCards.bar(card, prefs, c, night))
            views.alpha(BAR_IDS[i], WidgetCards.alpha(card))
            views.text(NAME_IDS[i], card.name)
            views.textColor(NAME_IDS[i], WidgetCards.onCard)
            views.textSize(NAME_IDS[i], 11.5f * prefs.fontScaleF)
            views.text(SUB_IDS[i], "${card.roomOrNode} · ${card.timeRange}")
            views.textColor(SUB_IDS[i], WidgetCards.onCardDim)
            views.textSize(SUB_IDS[i], 9.5f * prefs.fontScaleF)
        }

        views.clickOpen(context, widgetId)
        manager.updateAppWidget(widgetId, views)
    }
}
