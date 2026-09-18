package com.obsession.schedule.widget

/**
 * 课程卡取色。
 *
 * 把「单色模式 / 课程配色模式」和「已结束降透明」这两件事收在一处，
 * 四个组件的渲染代码就不用各写一遍判断了。
 */
object WidgetCards {

    /** 卡片底色：单色模式下统一用强调色，否则用课程色压暗（保证白字可读） */
    fun bg(card: CourseCard, prefs: WidgetStylePrefs, c: WColors, night: Boolean): Int =
        if (prefs.colorMode == WidgetStylePrefs.COLOR_SINGLE) c.cardBg
        else WidgetTheme.cardBg(card.colorArgb, night)

    /** 左侧竖条 / 列顶色条：比卡片底更亮 */
    fun bar(card: CourseCard, prefs: WidgetStylePrefs, c: WColors, night: Boolean): Int =
        if (prefs.colorMode == WidgetStylePrefs.COLOR_SINGLE) c.accent
        else WidgetTheme.bar(card.colorArgb, night)

    /** 已结束的整卡透明度，其余 255 */
    fun alpha(card: CourseCard): Int =
        if (card.state == STATE_PAST) WidgetTheme.PAST_ALPHA else 255

    fun isOngoing(card: CourseCard): Boolean = card.state == STATE_ONGOING

    fun isPast(card: CourseCard): Boolean = card.state == STATE_PAST

    /** 卡片主文字：白 */
    val onCard: Int get() = WidgetTheme.onCard

    /** 卡片次级文字：白字降透明 */
    val onCardDim: Int get() = WidgetTheme.onCardDim
}
