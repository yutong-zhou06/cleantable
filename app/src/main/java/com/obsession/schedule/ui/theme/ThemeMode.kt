package com.obsession.schedule.ui.theme

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/** 主题模式：跟随系统 / 强制浅色（柔光）/ 强制深色（墨影） */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色");

    companion object {
        fun from(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * 主题模式的全局状态。
 *
 * 用 Compose 的 [mutableStateOf] 而不是 Flow：主题要在首帧之前确定（避免启动闪一下
 * 错误主题），又要在切换时立即触发全局重组。Application.onCreate 里同步 load，
 * 读取发生在 setContent 组合之前，所以不存在「先浅后深」的闪烁。
 *
 * 存储放在 SharedPreferences（与 ConfigStore 同一份 prefs 文件，键不同互不干扰）。
 */
object ThemeController {

    private const val KEY = "theme_mode"

    /** 当前模式。组合中读取它即可实现实时切换 */
    val mode: MutableState<ThemeMode> = mutableStateOf(ThemeMode.SYSTEM)

    fun load(context: Context) {
        val prefs = context.applicationContext
            .getSharedPreferences("obsession_config", Context.MODE_PRIVATE)
        mode.value = ThemeMode.from(prefs.getString(KEY, null))
    }

    fun save(context: Context, value: ThemeMode) {
        mode.value = value
        context.applicationContext
            .getSharedPreferences("obsession_config", Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, value.name)
            .apply()
    }
}
