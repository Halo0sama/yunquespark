package com.halo.yunquespark

import android.app.Application
import com.halo.yunquespark.data.Db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {

    lateinit var db: Db
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = Db(this)
        com.halo.yunquespark.push.PushNotifications.ensureChannels(this)
        // 本机控制面（仅 127.0.0.1 可达，供电脑端 CLI 经 adb forward 控制）
        runCatching { com.halo.yunquespark.cli.CliServer.start(this) }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
