package com.obsession.schedule.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.obsession.schedule.R

/** 一套界面配色。明暗两套值写在 values/ 与 values-night/colors.xml 里 */
data class WColors(
    val rootBg: Int,
    val text: Int,
    val textDim: Int,
    val textFaint: Int,
    val accent: Int,
    val divider: Int,
    val chipBg: Int,
    /** 卡片默认底（无课程色 / 单色模式下用） */
    val cardBg: Int,
    /** 「正在上」的浅底 */
    val ongoingTint: Int
)

/**
 * 小组件配色与课程色派生。
 *
 * 深色适配走的是**资源限定符**（values-night）：RemoteViews 没有运行时主题，
 * 这就是它对应 GlanceTheme 的等价做法。要「强制浅色/深色」时，
 * 用 createConfigurationContext 造一个覆盖了 uiMode 的 Context 去读颜色 ——
 * 于是 v0.5 起的「跟随应用 / 强制浅 / 强制深」三段设置仍然有效。
 */
object WidgetTheme {

    /** 当前是否深色（已把用户的强制设置算进去） */
    fun night(context: Context, prefs: WidgetStylePrefs): Boolean = when (prefs.themeMode) {
        WidgetStylePrefs.THEME_LIGHT -> false
        WidgetStylePrefs.THEME_DARK -> true
        else -> systemNight(context)
    }

    fun colors(context: Context, prefs: WidgetStylePrefs): WColors {
        val ctx = themed(context, night(context, prefs))
        return WColors(
            rootBg = ctx.getColor(R.color.w_root_bg),
            text = ctx.getColor(R.color.w_text),
            textDim = ctx.getColor(R.color.w_text_dim),
            textFaint = ctx.getColor(R.color.w_text_faint),
            accent = ctx.getColor(R.color.w_accent),
            divider = ctx.getColor(R.color.w_divider),
            chipBg = ctx.getColor(R.color.w_chip_bg),
            cardBg = ctx.getColor(R.color.w_card_bg),
            ongoingTint = ctx.getColor(R.color.w_ongoing_tint)
        )
    }

    /** 取一个 uiMode 被强制过的 Context；与系统一致时直接返回原对象 */
    private fun themed(context: Context, night: Boolean): Context {
        if (night == systemNight(context)) return context
        val conf = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
        return context.createConfigurationContext(conf)
    }

    fun systemNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // ------------------------------------------------------------------
    // 课程配色：原始颜色只提供色相，明度饱和度按主题重新生成
    // （与 App 内 ui/theme/CourseColors.kt 同一套规则，观感统一）
    // ------------------------------------------------------------------

    /**
     * 课程卡底色。统一压暗，保证白字可读 ——
     * 用户明确要求「用白字时背景不能过浅」。
     */
    fun cardBg(colorArgb: Int, night: Boolean): Int {
        val h = hue(colorArgb)
        return if (night) hsl(h, 0.44f, 0.30f) else hsl(h, 0.60f, 0.40f)
    }

    /** 左侧色条：比底色更亮，作为卡片里的视觉锚点 */
    fun bar(colorArgb: Int, night: Boolean): Int {
        val h = hue(colorArgb)
        return if (night) hsl(h, 0.60f, 0.62f) else hsl(h, 0.72f, 0.62f)
    }

    /** 卡片上的主文字：白色（底色已保证够深） */
    val onCard: Int get() = 0xFFFFFFFF.toInt()

    /** 卡片上的次级文字：白字降透明 */
    val onCardDim: Int get() = 0xD9FFFFFF.toInt()

    /** 已结束的整卡透明度（0–255） */
    const val PAST_ALPHA = 110

    private fun hue(colorArgb: Int): Float {
        val hsv = FloatArray(3)
        Color.colorToHSV(colorArgb, hsv)
        return hsv[0]
    }

    /** 标准 HSL → ARGB（h 单位：度）。android.graphics 只有 HSV，HSL 得自己换算 */
    fun hsl(h: Float, s: Float, l: Float, a: Float = 1f): Int {
        val c = (1f - Math.abs(2f * l - 1f)) * s
        val hp = h / 60f
        val x = c * (1f - Math.abs(hp % 2f - 1f))
        val r: Float
        val g: Float
        val b: Float
        when {
            hp < 1f -> { r = c; g = x; b = 0f }
            hp < 2f -> { r = x; g = c; b = 0f }
            hp < 3f -> { r = 0f; g = c; b = x }
            hp < 4f -> { r = 0f; g = x; b = c }
            hp < 5f -> { r = x; g = 0f; b = c }
            else -> { r = c; g = 0f; b = x }
        }
        val m = l - c / 2f
        fun ch(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return Color.argb((a * 255).toInt(), ch(r), ch(g), ch(b))
    }

    /** 换一个 alpha 通道（0–255） */
    fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
