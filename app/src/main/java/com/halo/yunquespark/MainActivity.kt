package com.halo.yunquespark

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.halo.yunquespark.data.SeedImporter
import com.halo.yunquespark.ui.CalendarScreen
import com.halo.yunquespark.ui.ChatScreen
import com.halo.yunquespark.ui.HomeScreen
import com.halo.yunquespark.ui.KbScreen
import com.halo.yunquespark.ui.NotesScreen
import com.halo.yunquespark.ui.NoteDetailScreen
import com.halo.yunquespark.ui.SettingsScreen
import com.halo.yunquespark.ui.TasksScreen
import com.halo.yunquespark.ui.YunqueTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // 通知点击/外部入口要在 onNewIntent 里也能打开日历（app 已在前台时不会走 onCreate）
    private val openCalendarState = mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_calendar", false)) openCalendarState.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = App.instance.db
        openCalendarState.value = intent?.getBooleanExtra("open_calendar", false) == true

        // 通知权限（Android 13+ 运行时申请）
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        // 支持 adb 注入 Cookie：adb shell am start -n .../.MainActivity --es minote_cookie "..."
        intent?.getStringExtra("minote_cookie")?.let { raw ->
            com.halo.yunquespark.sync.MinoteClient.cleanCookie(raw)?.let { cleaned ->
                db.setSetting("minote_cookie", cleaned)
            }
        }
        // 测试辅助：adb shell am start -n .../.MainActivity --ei test_push_minutes 1 → N 分钟后触发推送
        intent?.getIntExtra("test_push_minutes", -1)?.takeIf { it > 0 }?.let { mins ->
            val cal = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.MINUTE, mins)
            }
            db.setSetting("card_push_enabled", "1")
            db.setSetting("card_push_hour", cal.get(java.util.Calendar.HOUR_OF_DAY).toString())
            db.setSetting("card_push_minute", cal.get(java.util.Calendar.MINUTE).toString())
            com.halo.yunquespark.push.PushScheduler.schedule(
                this, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        }

        var importing by mutableStateOf(true)
        var importMsg by mutableStateOf("正在导入历史笔记…")

        App.instance.scope.launch {
            val n = SeedImporter.importIfNeeded(App.instance, App.instance.db) { done, total ->
                importMsg = "正在导入历史笔记… $done/$total"
            }
            if (n >= 0) importMsg = "已导入 $n 篇"
            importing = false
        }

        setContent {
            YunqueTheme {
                var tab by remember { mutableIntStateOf(0) }
                var openNoteId by remember { mutableStateOf<String?>(null) }
                var showCalendar by openCalendarState

                if (importing) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.padding(32.dp),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            androidx.compose.foundation.layout.Spacer(Modifier.padding(12.dp))
                            androidx.compose.material3.Text(importMsg)
                        }
                    }
                } else {
                    Scaffold(
                        bottomBar = {
                            NavigationBar {
                                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                                    icon = { Icon(Icons.Filled.Home, null) }, label = { Text("首页") })
                                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                                    icon = { Icon(Icons.Filled.Chat, null) }, label = { Text("问云雀") })
                                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                                    icon = { Icon(Icons.Filled.AutoAwesome, null) }, label = { Text("任务") })
                                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 },
                                    icon = { Icon(Icons.Filled.Notes, null) }, label = { Text("笔记") })
                                NavigationBarItem(selected = tab == 4, onClick = { tab = 4 },
                                    icon = { Icon(Icons.Filled.LibraryBooks, null) }, label = { Text("知识库") })
                                NavigationBarItem(selected = tab == 5, onClick = { tab = 5 },
                                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("设置") })
                            }
                        },
                    ) { pad ->
                        Surface(Modifier.padding(pad)) {
                            when (tab) {
                                0 -> HomeScreen(onGoto = { tab = it }, onOpenNote = { openNoteId = it },
                                    onOpenCalendar = { showCalendar = true })
                                1 -> ChatScreen(onOpenNote = { openNoteId = it },
                                    prefill = intent?.getStringExtra("chat_prefill") ?: "")
                                2 -> TasksScreen()
                                3 -> NotesScreen(onOpenNote = { openNoteId = it })
                                4 -> KbScreen(onOpenNote = { openNoteId = it })
                                5 -> SettingsScreen()
                            }
                        }
                    }
                }

                openNoteId?.let { id ->
                    BackHandler { openNoteId = null }
                    // Surface 提供背景与文字色：整页覆盖层在 Scaffold 之外，
                    // 没有 Surface 祖先时文字会落到默认纯黑（深色主题下黑底黑字）
                    Surface(Modifier.fillMaxSize()) {
                        NoteDetailScreen(
                            noteId = id,
                            onBack = { openNoteId = null },
                            onOpenNote = { openNoteId = it },
                        )
                    }
                }

                if (showCalendar) {
                    BackHandler { showCalendar = false }
                    Surface(Modifier.fillMaxSize()) {
                        CalendarScreen(
                            onClose = { showCalendar = false },
                            onOpenNote = { openNoteId = it },
                        )
                    }
                }
            }
        }
    }
}
