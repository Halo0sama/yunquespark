package com.halo.yunquespark.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.halo.yunquespark.MainActivity
import com.halo.yunquespark.R
import com.halo.yunquespark.data.Card

object PushNotifications {
    const val CHANNEL_PUSH = "card_push"
    const val CHANNEL_FG = "card_push_fg"
    const val NOTIF_CARD_ID = 1001
    const val NOTIF_FG_ID = 2001

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PUSH, "每日灵感卡片", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "定时推送当天选出的灵感卡片"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_FG, "卡片准备中", NotificationManager.IMPORTANCE_LOW).apply {
                description = "AI 正在准备今日卡片时的后台提示"
            }
        )
    }

    /** 点通知回到 app 并直接打开卡片日历。 */
    fun openAppPendingIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 4002,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_calendar", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun notifyCard(ctx: Context, card: Card) {
        ensureChannels(ctx)
        val notif = NotificationCompat.Builder(ctx, CHANNEL_PUSH)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("云雀灵感 · ${card.title.take(24)}")
            .setContentText(card.body.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(card.body))
            .setContentIntent(openAppPendingIntent(ctx))
            .setAutoCancel(true)
            .build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_CARD_ID, notif)
    }
}
