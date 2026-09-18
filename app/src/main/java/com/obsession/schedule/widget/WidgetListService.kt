package com.obsession.schedule.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.obsession.schedule.R

/**
 * 小组件列表（ListView）的行工厂。
 *
 * v0.7 起四个组件全部用系统 ListView 承载上下滚动 —— 这是日历/邮件等
 * 系统组件的标准做法：行由本工厂按需生成（RemoteViewsService + setRemoteAdapter），
 * 与 v0.4.1 崩溃的「应用内逐行 addView」完全是两码事；滚动方向由系统处理，不会反。
 *
 * 每行仍是预声明的固定结构布局（样式全在 XML 里），运行时只填文本/颜色/可见性，
 * 遵守本工程 RemoteViews 的三条铁律：
 * 不用裸 <View>（无 @RemoteView 注解，会「载入失败」）、
 * 彩色圆角只用「ImageView + src 形状 + setColorFilter」、
 * 字号固定最大档（135%，已烘焙进 XML）。
 */
class WidgetListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        WidgetFactory(applicationContext, intent)
}

internal class WidgetFactory(
    private val context: Context,
    private val intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private var snapshot: WidgetSnapshot? = null
    private var colors: WColors? = null
    private var night = false

    override fun onCreate() {}

    /** 数据变化（notifyAppWidgetViewDataChanged）与首次绑定时都会走到这里重新查库 */
    override fun onDataSetChanged() {
        snapshot = WidgetData.load(context)
        night = WidgetTheme.night(context)
        colors = WidgetTheme.colors(context)
    }

    override fun onDestroy() {}

    override fun getCount(): Int {
        val s = snapshot ?: return 0
        return when (kind()) {
            KIND_WEEK -> s.week.maxOfOrNull { it.courses.size } ?: 0
            KIND_UP_TODAY -> s.today.size
            KIND_UP_TOMORROW -> s.tomorrow.size
            else -> s.today.size
        }
    }

    override fun getViewAt(position: Int): RemoteViews {
        val s = snapshot ?: return blank()
        val c = colors ?: return blank()
        return when (kind()) {
            KIND_WEEK -> weekRow(s, c, position)
            KIND_DAY -> dayRow(s.today.getOrNull(position) ?: return blank(), c)
            KIND_UP_TODAY -> upRow(s.today.getOrNull(position) ?: return blank(), c)
            KIND_UP_TOMORROW -> upRow(s.tomorrow.getOrNull(position) ?: return blank(), c)
            else -> compactRow(s.today.getOrNull(position) ?: return blank(), c)
        }
    }

    // ------------------------------------------------------------------
    // 日视图的一行：淡课程底 + 色条 + 节次/课程名(+时间段)/教室·教师
    // ------------------------------------------------------------------
    private fun dayRow(card: CourseCard, c: WColors): RemoteViews {
        val views = Rv.of(context, R.layout.widget_row_day)
        // 淡色底：进行中用强调底，其他用课程色淡底
        val bgColor = if (WidgetCards.isOngoing(card)) c.ongoingTint
        else WidgetTheme.withAlpha(WidgetCards.bg(card, night), 0x2E)
        views.tint(R.id.day_bg, bgColor)
        views.alpha(R.id.day_bg, WidgetCards.alpha(card))
        views.tint(R.id.day_bar, WidgetCards.bar(card, night))
        views.alpha(R.id.day_bar, WidgetCards.alpha(card))
        views.tint(R.id.day_room_ic, c.textDim)
        views.tint(R.id.day_teacher_ic, c.textDim)
        views.text(R.id.day_nt, card.nodeLabel)
        views.textColor(R.id.day_nt, WidgetCards.textColor(card, c.textDim))
        views.text(R.id.day_nm, card.name)
        views.textColor(R.id.day_nm, WidgetCards.textColor(card, c.text))
        views.text(R.id.day_tm, card.timeRange)
        views.textColor(R.id.day_tm, WidgetCards.textColor(card, c.textDim))
        views.text(R.id.day_room, card.roomOrNode)
        views.textColor(R.id.day_room, WidgetCards.textColor(card, c.textDim))
        val teacherBlank = card.teacher.isBlank()
        views.visible(R.id.day_teacher_ic, !teacherBlank)
        views.visible(R.id.day_teacher, !teacherBlank)
        if (!teacherBlank) {
            views.text(R.id.day_teacher, card.teacher)
            views.textColor(R.id.day_teacher, WidgetCards.textColor(card, c.textDim))
        }
        return views
    }

    // ------------------------------------------------------------------
    // 周视图：一行 = 一行槽 = 周一到周日各自的第 N 门课，格子全部等大
    // ------------------------------------------------------------------
    private fun weekRow(s: WidgetSnapshot, c: WColors, row: Int): RemoteViews {
        val views = Rv.of(context, R.layout.widget_row_week)
        for (day in 1..7) {
            val card = s.week[day - 1].courses.getOrNull(row)
            val bg = CELL_BG[day - 1]
            val st = CELL_STROKE[day - 1]
            val em = CELL_EMPTY[day - 1]
            val t = CELL_TIME[day - 1]
            val n = CELL_NAME[day - 1]
            val r = CELL_ROOM[day - 1]
            if (card == null) {
                views.visible(bg, false)
                views.visible(st, false)
                views.visible(em, true)
                views.visible(t, false)
                views.visible(n, false)
                views.visible(r, false)
                continue
            }
            views.visible(bg, true)
            views.tint(bg, WidgetCards.bg(card, night))
            views.alpha(bg, WidgetCards.alpha(card))
            views.visible(st, WidgetCards.isOngoing(card))
            views.visible(em, false)
            views.visible(t, true)
            views.text(t, card.timeRange)
            views.textColor(t, WidgetCards.textColor(card, WidgetCards.onCardDim))
            views.visible(n, true)
            views.text(n, card.name)
            views.textColor(n, WidgetCards.textColor(card, WidgetCards.onCard))
            views.visible(r, true)
            views.text(r, card.roomOrNode)
            views.textColor(r, WidgetCards.textColor(card, WidgetCards.onCardDim))
        }
        return views
    }

    // ------------------------------------------------------------------
    // 近日课程的一行：色条 + 课程名(+时间段) + 教室
    // ------------------------------------------------------------------
    private fun upRow(card: CourseCard, c: WColors): RemoteViews {
        val views = Rv.of(context, R.layout.widget_row_up)
        views.tint(R.id.up_bar, WidgetCards.bar(card, night))
        views.alpha(R.id.up_bar, WidgetCards.alpha(card))
        views.text(R.id.up_nm, card.name)
        views.textColor(R.id.up_nm, WidgetCards.textColor(card, c.text))
        views.text(R.id.up_tm, card.timeRange)
        views.textColor(R.id.up_tm, WidgetCards.textColor(card, c.textDim))
        views.text(R.id.up_room, "📍 ${card.roomOrNode}")
        views.textColor(R.id.up_room, WidgetCards.textColor(card, c.textDim))
        return views
    }

    // ------------------------------------------------------------------
    // 2×2 今日课程的一行：色条 + 课程名(+时间段) + 教室
    // ------------------------------------------------------------------
    private fun compactRow(card: CourseCard, c: WColors): RemoteViews {
        val views = Rv.of(context, R.layout.widget_row_compact)
        views.tint(R.id.cp_bar, WidgetCards.bar(card, night))
        views.alpha(R.id.cp_bar, WidgetCards.alpha(card))
        views.text(R.id.cp_nm, card.name)
        views.textColor(R.id.cp_nm, WidgetCards.textColor(card, c.text))
        views.text(R.id.cp_tm, card.timeRange)
        views.textColor(R.id.cp_tm, WidgetCards.textColor(card, c.textDim))
        views.text(R.id.cp_room, "📍 ${card.roomOrNode}")
        views.textColor(R.id.cp_room, WidgetCards.textColor(card, c.textDim))
        return views
    }

    private fun kind(): String = intent.getStringExtra(EXTRA_KIND) ?: KIND_DAY

    private fun blank(): RemoteViews = Rv.of(context, R.layout.widget_row_compact)

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false

    companion object {
        const val EXTRA_WIDGET_ID = "factory_widget_id"
        const val EXTRA_KIND = "factory_kind"
        const val KIND_WEEK = "week"
        const val KIND_DAY = "day"
        const val KIND_UP_TODAY = "up_today"
        const val KIND_UP_TOMORROW = "up_tomorrow"
        const val KIND_COMPACT = "compact"

        /** 周视图行槽里 7 个格子（等大）各自的控件 id，与 widget_row_week.xml 一一对应 */
        val CELL_BG = intArrayOf(
            R.id.wk_c1_bg, R.id.wk_c2_bg, R.id.wk_c3_bg, R.id.wk_c4_bg,
            R.id.wk_c5_bg, R.id.wk_c6_bg, R.id.wk_c7_bg
        )
        val CELL_STROKE = intArrayOf(
            R.id.wk_c1_st, R.id.wk_c2_st, R.id.wk_c3_st, R.id.wk_c4_st,
            R.id.wk_c5_st, R.id.wk_c6_st, R.id.wk_c7_st
        )
        val CELL_EMPTY = intArrayOf(
            R.id.wk_c1_em, R.id.wk_c2_em, R.id.wk_c3_em, R.id.wk_c4_em,
            R.id.wk_c5_em, R.id.wk_c6_em, R.id.wk_c7_em
        )
        val CELL_TIME = intArrayOf(
            R.id.wk_c1_t, R.id.wk_c2_t, R.id.wk_c3_t, R.id.wk_c4_t,
            R.id.wk_c5_t, R.id.wk_c6_t, R.id.wk_c7_t
        )
        val CELL_NAME = intArrayOf(
            R.id.wk_c1_n, R.id.wk_c2_n, R.id.wk_c3_n, R.id.wk_c4_n,
            R.id.wk_c5_n, R.id.wk_c6_n, R.id.wk_c7_n
        )
        val CELL_ROOM = intArrayOf(
            R.id.wk_c1_r, R.id.wk_c2_r, R.id.wk_c3_r, R.id.wk_c4_r,
            R.id.wk_c5_r, R.id.wk_c6_r, R.id.wk_c7_r
        )
    }
}
