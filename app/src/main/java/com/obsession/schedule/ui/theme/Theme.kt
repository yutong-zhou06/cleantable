package com.obsession.schedule.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 主色：蓝紫。
// 浅色主题用饱和度高一点的 #4C6FFF，深色主题提亮到 #6B8AFF，
// 因为同一个蓝在深色底上会显得发闷、对比度不够。
private val Brand = Color(0xFF4C6FFF)
private val BrandDark = Color(0xFF6B8AFF)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4EAFF),
    onPrimaryContainer = Color(0xFF14265C),
    secondary = Color(0xFF6B7A99),
    onSecondary = Color.White,
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171A21),
    surfaceVariant = Color(0xFFEEF0F5),
    onSurfaceVariant = Color(0xFF5C6472),
    background = Color(0xFFF5F6F9),
    onBackground = Color(0xFF171A21),
    outline = Color(0xFFE4E7EE),
    outlineVariant = Color(0xFFEFF1F6),
    error = Color(0xFFF0616D),
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = BrandDark,
    onPrimary = Color(0xFF0B1430),
    primaryContainer = Color(0xFF23335F),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFF8A96AD),
    onSecondary = Color(0xFF0B1430),
    surface = Color(0xFF171A21),
    onSurface = Color(0xFFE9ECF2),
    surfaceVariant = Color(0xFF1E222B),
    onSurfaceVariant = Color(0xFF98A1B0),
    background = Color(0xFF0E1015),
    onBackground = Color(0xFFE9ECF2),
    outline = Color(0xFF232833),
    outlineVariant = Color(0xFF2B313D),
    error = Color(0xFFF0616D),
    onError = Color(0xFF2A0A0E)
)

/**
 * 柔光（浅色）／墨影（深色）两套主题。
 *
 * 明暗由 [ThemeController.mode] 决定：跟随系统 / 强制浅色 / 强制深色。
 * mode 是 Compose 状态，设置页里切换会立即触发全局重组，无需重建 Activity；
 * 跟随系统模式下 [isSystemInDarkTheme] 本身也是状态驱动的，系统外观变化同样实时生效。
 */
@Composable
fun ObsessionTheme(
    darkTheme: Boolean = appDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}

/**
 * 全局统一的「当前是否深色」判定。
 *
 * 页面内需要按明暗微调颜色的地方（课表网格、导入预览、背景蒙层等）
 * 一律调这个，不要再直接用 isSystemInDarkTheme —— 否则用户强制选了
 * 浅色/深色后，那些地方会和主题脱节。
 */
@Composable
fun appDarkTheme(): Boolean = when (ThemeController.mode.value) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}
