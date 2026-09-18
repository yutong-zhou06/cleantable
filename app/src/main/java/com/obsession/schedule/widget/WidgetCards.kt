package com.obsession.schedule.widget

/**
 * 课程卡取色。
 *
 * v0.7 规格固化：配色一律「按课程区分」（同一门课全局同色），
 * 单色模式已随样式自定义一起移除。
 */
object WidgetCards {

    /** 卡片底色：课程色压暗（保证白字可读） */
    fun bg(card: CourseCard, night: Boolean): Int =
        WidgetTheme.cardBg(card.colorArgb, night)

    /** 左侧竖条 / 色条：比卡片底更亮 */
    fun bar(card: CourseCard, night: Boolean): Int =
        WidgetTheme.bar(card.colorArgb, night)

    /** 已结束的整卡透明度，其余 255 */
    fun alpha(card: CourseCard): Int =
        if (card.state == STATE_PAST) WidgetTheme.PAST_ALPHA else 255

    /**
     * 写在小组件底上的文字颜色：已结束的课程按同比例降透明。
     * [base] 是正常状态的文字色。
     */
    fun textColor(card: CourseCard, base: Int): Int =
        if (card.state == STATE_PAST) WidgetTheme.withAlpha(base, WidgetTheme.PAST_ALPHA) else base

    fun isOngoing(card: CourseCard): Boolean = card.state == STATE_ONGOING

    fun isPast(card: CourseCard): Boolean = card.state == STATE_PAST

    /** 彩色卡片上的主文字：白 */
    val onCard: Int get() = WidgetTheme.onCard

    /** 彩色卡片上的次级文字：白字降透明 */
    val onCardDim: Int get() = WidgetTheme.onCardDim
}
