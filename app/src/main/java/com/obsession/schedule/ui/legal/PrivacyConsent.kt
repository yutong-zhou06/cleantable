package com.obsession.schedule.ui.legal

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 隐私政策同意状态的读写 + 政策页地址。
 *
 * 为什么单独抽一个对象：同意状态必须在「首帧组合之前」同步读到（决定先弹窗还是直接进主页），
 * 与 ThemeController 的理由一致，所以同样落在 SharedPreferences 而不是 Room。
 *
 * 存的是「已同意的政策版本号」而不是布尔值：以后政策有实质性变更时把 [CURRENT_VERSION]
 * 递增，老用户会重新看到一次弹窗，满足「政策更新需再次告知」的合规要求。
 */
object PrivacyConsent {

    /**
     * 当前政策版本。政策内容有实质变更时递增 —— 已同意过的用户会重新看到同意弹窗。
     */
    const val CURRENT_VERSION = 1

    /**
     * 隐私政策公开地址（GitHub Pages 托管）。
     * 备案与各家应用商店审核都要求提供可公开访问的隐私政策 URL。
     */
    const val POLICY_URL = "https://yutong-zhou06.github.io/cleantable/privacy.html"

    private const val PREFS = "obsession_config"
    private const val KEY = "privacy_consent_version"

    /** 已同意当前版本政策则返回 true。冷启动时同步调用，早于 setContent。 */
    fun isGranted(context: Context): Boolean =
        prefs(context).getInt(KEY, 0) >= CURRENT_VERSION

    fun grant(context: Context) {
        prefs(context).edit().putInt(KEY, CURRENT_VERSION).apply()
    }

    /**
     * 用系统浏览器打开政策页。
     * 设备上没有可用浏览器时会抛 ActivityNotFoundException，这里直接吞掉 —— 弹窗里
     * 已经写了要点摘要，看不看网页不影响用户完成同意，不该因为打不开网页而崩。
     */
    fun openPolicy(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(POLICY_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
