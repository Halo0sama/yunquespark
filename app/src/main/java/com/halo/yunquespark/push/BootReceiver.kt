package com.halo.yunquespark.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.halo.yunquespark.App

/** 重启后重排闹钟（系统闹钟不跨重启）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val db = App.instance.db
        if (db.getSetting("card_push_enabled") == "1") {
            val h = db.getSetting("card_push_hour", "9").toIntOrNull() ?: 9
            val m = db.getSetting("card_push_minute", "0").toIntOrNull() ?: 0
            PushScheduler.schedule(context, h, m)
        }
    }
}
