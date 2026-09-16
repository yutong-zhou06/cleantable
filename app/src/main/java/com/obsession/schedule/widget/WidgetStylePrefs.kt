package com.obsession.schedule.widget

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * 桌面小组件样式偏好（v0.5.0）。
 *
 * 存储：本地 SharedPreferences，重启保持、对所有小组件生效。
 * 消费方：WidgetSnapshotRenderer 画快照前读取一次 —— 只替换颜色/圆角参数，
 * 画图逻辑与「ImageView 显示位图」的快照管线完全不变。
 *
 * -1 为哨兵值 = 自动 / 跟随默认。
 */
data class WidgetStylePrefs(
    /** 组件主题：0=跟随应用 1=强制浅色 2=强制深色 */
    val themeMode: Int = THEME_AUTO,
    /** 自定义背景色（ARGB），-1 = 自动（跟随组件主题） */
    val bgColor: Int = -1,
    /** 背景不透明度 20–100（%），仅自定义背景时生效 */
    val bgAlpha: Int = 100,
    /** 自定义强调色（ARGB），-1 = 自动 */
    val accentColor: Int = -1,
    /** 卡片圆角 dp，-1 = 跟随默认（今日/下一节 14，简洁条 10） */
    val cornerRadiusDp: Int = -1
) {
    val isCustomBg: Boolean get() = bgColor != -1
    val isCustomAccent: Boolean get() = accentColor != -1
    val isCustomRadius: Boolean get() = cornerRadiusDp != -1

    /** 背景不透明度换算成 alpha 通道（0–255） */
    val bgAlphaInt: Int get() = (bgAlpha.coerceIn(20, 100) * 255 / 100)

    fun save(context: Context) {
        sp(context).edit()
            .putInt(KEY_THEME, themeMode)
            .putInt(KEY_BG, bgColor)
            .putInt(KEY_ALPHA, bgAlpha)
            .putInt(KEY_ACCENT, accentColor)
            .putInt(KEY_RADIUS, cornerRadiusDp)
            .apply()
    }

    companion object {
        const val THEME_AUTO = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        private const val FILE = "widget_style"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_BG = "bg_color"
        private const val KEY_ALPHA = "bg_alpha"
        private const val KEY_ACCENT = "accent_color"
        private const val KEY_RADIUS = "corner_radius"

        private fun sp(context: Context): SharedPreferences =
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        fun load(context: Context): WidgetStylePrefs {
            val p = sp(context)
            return WidgetStylePrefs(
                themeMode = p.getInt(KEY_THEME, THEME_AUTO),
                bgColor = p.getInt(KEY_BG, -1),
                bgAlpha = p.getInt(KEY_ALPHA, 100),
                accentColor = p.getInt(KEY_ACCENT, -1),
                cornerRadiusDp = p.getInt(KEY_RADIUS, -1)
            )
        }

        /** 十六进制 "#RRGGBB" → ARGB Int；格式非法返回 null */
        fun parseHex(input: String): Int? = runCatching {
            Color.parseColor(input.trim())
        }.getOrNull()
    }
}
