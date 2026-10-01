package com.halo.yunquespark.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 闹钟触发：启动前台服务确保当日卡片存在并发通知。 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PushScheduler.ACTION_CARD_ALARM) return
        context.startForegroundService(Intent(context, CardPushService::class.java))
    }
}
