package com.halo.yunquespark.push

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.halo.yunquespark.App
import com.halo.yunquespark.R
import com.halo.yunquespark.ai.Jobs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 闹钟触发后的短命前台服务：确保今天有卡片（AI 没跑就现场生成，失败走随机兜底），
 * 然后发通知。前台服务形态是为了满足后台启动限制；用户已开自启动。
 */
class CardPushService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PushNotifications.ensureChannels(this)
        val fg = NotificationCompat.Builder(this, PushNotifications.CHANNEL_FG)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentText("正在准备今日灵感卡片…")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(PushNotifications.NOTIF_FG_ID, fg, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(PushNotifications.NOTIF_FG_ID, fg)
        }
        scope.launch {
            try {
                // 先静默同步云端新笔记（已登录才生效），拉到新笔记自动进入整理
                val report = runCatching {
                    com.halo.yunquespark.sync.CloudSync.quiet(this@CardPushService, App.instance.db)
                }.getOrNull()
                com.halo.yunquespark.ai.JobRunner.autoOrganizeAfterSync(App.instance.db, report)
                // 卡片已存在则直接用；不存在由 AI 现场选一张，AI 不可用也有随机兜底
                val card = Jobs.ensureDailyCard(App.instance.db) { _ -> }
                PushNotifications.notifyCard(this@CardPushService, card)
            } catch (_: Exception) {
            }
            delay(500) // 给通知发出留出时间
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
