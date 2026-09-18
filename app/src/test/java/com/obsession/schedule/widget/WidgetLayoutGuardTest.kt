package com.obsession.schedule.widget

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 小组件布局的控件白名单守卫。
 *
 * RemoteViews 膨胀布局时，只允许带 `@RemoteView` 注解的类 —— `android.view.View`
 * 就没有这个注解。布局里写了裸 `<View>`（比如拿它当 1dp 分隔线）时，**编译完全正常**，
 * 但启动器膨胀时会抛 `Class not allowed to be inflated`，桌面上表现为「载入失败」。
 * 这个坑极难从代码上看出来，所以用一条测试挡在打包之前。
 */
class WidgetLayoutGuardTest {

    /** RemoteViews 能够膨胀的控件 */
    private val allowed = setOf(
        "FrameLayout", "LinearLayout", "RelativeLayout", "GridLayout",
        "TextView", "ImageView", "Button", "ImageButton", "ProgressBar",
        "Chronometer", "Space", "ViewFlipper", "ListView", "GridView",
        "StackView", "AdapterViewFlipper", "AnalogClock", "ViewStub"
    )

    @Test
    fun `小组件布局只能用 RemoteViews 允许的控件`() {
        val dir = findLayoutDir()
        val widgets = dir.listFiles { f -> f.name.startsWith("widget_") && f.name.endsWith(".xml") }
        assertTrue("没找到小组件布局文件", !widgets.isNullOrEmpty())

        val offenders = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (file in widgets!!) {
            // 先剥掉注释，免得注释里举例说明的控件名被误判
            val stripped = file.readText().replace(Regex("<!--[\\s\\S]*?-->"), "")
            for (match in Regex("<([A-Za-z][A-Za-z0-9_.]*)").findAll(stripped)) {
                val tag = match.groupValues[1]
                seen += tag
                if (tag !in allowed) offenders += "${file.name}: <$tag>"
            }
        }

        // 防止正则写坏导致「一个控件都没解析到」这种假通过
        assertTrue("没从布局里解析出任何控件，测试本身失效了", seen.isNotEmpty())
        assertTrue(
            "以下控件在 RemoteViews 里无法膨胀，会让组件显示「载入失败」：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /** 从测试的工作目录往上找 src/main/res/layout */
    private fun findLayoutDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val candidate = File(dir, "src/main/res/layout")
            if (candidate.isDirectory) return candidate
            dir = dir?.parentFile
        }
        error("定位不到 src/main/res/layout（user.dir=${System.getProperty("user.dir")}）")
    }
}
