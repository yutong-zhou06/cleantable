package com.obsession.schedule.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 一门课在界面上需要的三种颜色。
 *
 * 核心思路：数据库里存的仍然是 **ARGB 整数**（这样才能兼容 WakeUp 的
 * `.wakeup_schedule` 文件，也允许用户选任意颜色），但**渲染时不直接拿它填块**，
 * 而是先提取它的色相 H，再用 H 重新生成一组「浅底 + 深字 + 强调条」。
 *
 * 这样做换来三件事：
 *  1. 同一份数据在明暗两套主题下都好看，不必维护两份色板；
 *  2. 深色主题里不会出现某个课程块亮得刺眼（原色直接铺满就会这样）；
 *  3. 颜色数量再多也不会互相打架，因为统一走同一套明度规则。
 */
data class CourseColors(
    /** 块背景，半透明，叠在网格上 */
    val container: Color,
    /** 块内文字 */
    val content: Color,
    /** 左侧色条与边框 */
    val accent: Color
)

/**
 * 由课程原始 ARGB 推导出当前主题下该用的颜色。
 *
 * 原色只用来提供色相——饱和度和明度一律由主题决定，用户选的颜色再鲜艳，
 * 落进深色主题也是柔和的。
 */
fun courseColors(argb: Int, dark: Boolean): CourseColors {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    val h = hsv[0]

    return if (dark) {
        CourseColors(
            container = Color.hsl(h, 0.62f, 0.55f, 0.17f),
            content = Color.hsl(h, 0.70f, 0.80f),
            accent = Color.hsl(h, 0.60f, 0.62f)
        )
    } else {
        CourseColors(
            container = Color.hsl(h, 0.68f, 0.53f, 0.13f),
            content = Color.hsl(h, 0.52f, 0.28f),
            accent = Color.hsl(h, 0.66f, 0.56f)
        )
    }
}

/**
 * 编辑器色板里显示的色块。
 *
 * 这里刻意用**原色**而不是推导后的颜色：用户是在挑色相，
 * 得让他看到自己选的是什么，否则点哪个都差不多。
 */
fun paletteSwatch(argb: Int): Color = Color(argb)
