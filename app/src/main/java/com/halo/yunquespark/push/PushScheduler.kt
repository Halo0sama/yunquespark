package com.halo.yunquespark.push

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * 每日卡片定时推送。用 setAlarmClock（用户可见的"真实闹钟"：状态栏出闹钟图标、精确触发，
 * 且系统允许其接收器在后台启动前台服务），不依赖 app 存活；重启后由 BootReceiver 重排。
 */
object PushScheduler {
    private const val ALARM_REQUEST_CODE = 4001
    const val ACTION_CARD_ALARM = "com.halo.yunquespark.CARD_ALARM"

    fun nextTrigger(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    private fun alarmPendingIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, ALARM_REQUEST_CODE,
            Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_CARD_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 是否已获精确闹钟授权（Android 14+ 默认拒绝，需用户在设置里允许）。 */
    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    fun schedule(ctx: Context, hour: Int, minute: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmPendingIntent(ctx)
        val trigger = nextTrigger(hour, minute)
        try {
            val info = AlarmManager.AlarmClockInfo(trigger, PushNotifications.openAppPendingIntent(ctx))
            am.setAlarmClock(info, pi)
        } catch (e: SecurityException) {
            // 未授予精确闹钟：降级为窗口闹钟（可能延迟数分钟），绝不崩溃
            am.setWindow(AlarmManager.RTC_WAKEUP, trigger, 10 * 60 * 1000L, pi)
        }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmPendingIntent(ctx))
    }
}
