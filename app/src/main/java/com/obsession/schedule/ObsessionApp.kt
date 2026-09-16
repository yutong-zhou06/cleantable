package com.obsession.schedule

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.obsession.schedule.ui.theme.ThemeController
import com.obsession.schedule.widget.ScheduleWidgetRenderer
import java.util.Calendar

class ObsessionApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 主题模式必须在首帧组合之前确定：这里同步读 SharedPreferences，
        // setContent 时 ThemeController 已就绪，冷启动不会闪错主题
        ThemeController.load(this)

        // 跨天 / 用户手动改时间时立刻刷新小组件。
        // 以前只在数据变化时刷新，跨天没有触发时机，桌面就会一直显示昨天的课 ——
        // 这是 v0.3 真机反馈的「小组件内容不更新」的直接修复，不新增任何权限。
        ContextCompat.registerReceiver(
            this,
            DateTimeWatcher(),
            IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        scheduleMidnightRefresh(this)
    }

    /** 系统广播的转发器：日期 / 时区变化都意味着「今天」重新算了 */
    private class DateTimeWatcher : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ScheduleWidgetRenderer.refreshAll(context)
        }
    }

    companion object {
        private const val MIDNIGHT_REQUEST = 1001

        /**
         * 把「明天零点刷新小组件」的闹钟续上。
         *
         * setAndAllowWhileIdle 在 Doze 下也会触发，又不是精确闹钟，
         * 所以不需要 SCHEDULE_EXACT_ALARM 之类的任何权限。
         * DATE_CHANGED 广播正常时这条闹钟只是双保险；某些 ROM 广播迟到时靠它兜底。
         */
        fun scheduleMidnightRefresh(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                context,
                MIDNIGHT_REQUEST,
                Intent(context, MidnightRefreshReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val next = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
        }
    }
}

/** 零点闹钟的落点：刷新 + 把明天的闹钟续上 */
class MidnightRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 查库渲染是同步操作，goAsync 给它争取到完整的十秒窗口
        val pending = goAsync()
        Thread {
            try {
                ScheduleWidgetRenderer.refreshAll(context)
            } finally {
                ObsessionApp.scheduleMidnightRefresh(context)
                pending.finish()
            }
        }.start()
    }
}
