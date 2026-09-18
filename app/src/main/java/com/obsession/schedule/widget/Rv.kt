package com.obsession.schedule.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import com.obsession.schedule.MainActivity
import com.obsession.schedule.R

/**
 * RemoteViews 的薄封装。
 *
 * RemoteViews 只能调用「带 @RemotableViewMethod 注解的 setter」，而且要写成
 * 「方法名字符串 + 值」的形式，所以各种样式设置只能长这样。集中封装在这里，
 * 四个组件就都不用再直接碰这些字符串。
 *
 * 注意下面这些是**顶层扩展函数**（不是 object 的成员扩展）——
 * 成员扩展需要 object 作隐式接收者，外部就调不到了。
 */
object Rv {

    fun of(context: Context, @LayoutRes layout: Int) = RemoteViews(context.packageName, layout)

    fun openApp(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 图标点击的目标页面，MainActivity 读取后决定跳哪一屏 */
    const val EXTRA_WIDGET_ACTION = "widget_action"
    const val ACTION_SETTINGS = "settings"
    const val ACTION_HOME = "home"
}

fun RemoteViews.text(id: Int, value: CharSequence?) =
    setTextViewText(id, value?.toString().orEmpty())

fun RemoteViews.textColor(id: Int, color: Int) = setTextColor(id, color)

/** 字号（sp）。配合 WidgetStylePrefs.fontScale 做整体缩放 */
fun RemoteViews.textSize(id: Int, sp: Float) =
    setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, sp)

/**
 * 给 ImageView 的圆角形状着色。
 *
 * `ImageView.setColorFilter(int)` 带 @RemotableViewMethod，API 24 就能用 —— 这是
 * minSdk 24 下实现「圆角 + 任意课程色」的关键：View 自己没有可远程调用的着色方法，
 * 所以彩色卡片统一用「ImageView 的 src 放一张圆角白底 + setColorFilter」来做。
 * 形状必须写在 `android:src` 上 —— setColorFilter 只作用于 ImageView 显示的 drawable。
 */
fun RemoteViews.tint(id: Int, color: Int) = setInt(id, "setColorFilter", color)

/** 整块透明度 0–255（配 tint 做「已结束降透明」） */
fun RemoteViews.alpha(id: Int, alpha: Int) = setInt(id, "setImageAlpha", alpha)

fun RemoteViews.visible(id: Int, isVisible: Boolean) =
    setViewVisibility(id, if (isVisible) View.VISIBLE else View.GONE)

/** 整张卡点击打开应用。requestCode 用 widgetId，多个组件之间不会互相覆盖 */
fun RemoteViews.clickOpen(context: Context, widgetId: Int) =
    setOnClickPendingIntent(R.id.widget_root, Rv.openApp(context, widgetId))

/** 头部小图标点击：带着目标页面把应用拉起来（设置 / 课表主页） */
fun RemoteViews.clickIcon(context: Context, iconId: Int, requestBase: Int, action: String) {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(Rv.EXTRA_WIDGET_ACTION, action)
    }
    setOnClickPendingIntent(
        iconId,
        PendingIntent.getActivity(
            context,
            requestBase + iconId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    )
}
