package com.halo.yunquespark.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.halo.yunquespark.App
import com.halo.yunquespark.BuildConfig
import com.halo.yunquespark.ai.BuildConfigHelper
import com.halo.yunquespark.ai.JobRunner
import com.halo.yunquespark.push.PushScheduler
import com.halo.yunquespark.sync.CloudSync
import com.halo.yunquespark.sync.MiLogin
import com.halo.yunquespark.sync.MinoteClient
import com.halo.yunquespark.sync.MinoteSync
import com.halo.yunquespark.sync.ObsidianSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 设置：AI 接入、卡片偏好与推送、小米笔记同步（WebView 登录抓 Cookie / 手动粘贴）、数据。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val db = App.instance.db
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf(db.getSetting("api_key")) }
    var model by remember { mutableStateOf(db.getSetting("ai_model").ifBlank { BuildConfigHelper.modelFast() }) }
    var excludeRomance by remember { mutableStateOf(db.getSetting("card_exclude_romance", "1") == "1") }
    var cookie by remember { mutableStateOf(db.getSetting("minote_cookie")) }
    var autoSyncOpen by remember { mutableStateOf(db.getSetting("auto_sync_open", "1") == "1") }
    var autoOrganize by remember { mutableStateOf(db.getSetting("auto_organize", "1") == "1") }
    var syncMsg by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    var showWebView by remember { mutableStateOf(false) }
    var pasteMode by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var showAccountLogin by remember { mutableStateOf(false) }
    var loginBusy by remember { mutableStateOf(false) }

    fun saveCookie(c: String) {
        val cleaned = MinoteClient.cleanCookie(c)
        if (cleaned != null) {
            db.setSetting("minote_cookie", cleaned)
            cookie = cleaned
            syncMsg = "Cookie 已保存 ✓"
        } else syncMsg = "未找到 serviceToken，登录可能未完成"
    }

    // ---- 账号登录对话框 ----
    if (showAccountLogin) {
        var acct by remember { mutableStateOf(db.getSetting("mi_account")) }
        var pwd by remember { mutableStateOf(db.getSetting("mi_password")) }
        var otpCode by remember { mutableStateOf("") }
        var loginState by remember { mutableStateOf(db.getSetting("mi_login_state")) }

        // 登录进行中轮询状态：出现 OTP 等待态则显示验证码输入
        LaunchedEffect(loginBusy) {
            while (loginBusy) {
                loginState = db.getSetting("mi_login_state")
                kotlinx.coroutines.delay(1000)
            }
        }

        AlertDialog(
            onDismissRequest = { if (!loginBusy) showAccountLogin = false },
            confirmButton = {
                if (loginState.startsWith("otp:")) {
                    TextButton(
                        onClick = {
                            if (MiLogin.submitOtp(otpCode.trim())) otpCode = ""
                        },
                        enabled = otpCode.trim().length >= 4,
                    ) { Text("提交验证码") }
                } else {
                    TextButton(
                        onClick = {
                            if (acct.isBlank() || pwd.isBlank() || loginBusy) return@TextButton
                            loginBusy = true; syncMsg = "登录中…"
                            scope.launch(Dispatchers.IO) {
                                val result = runCatching { MiLogin.loginSuspend(App.instance, acct.trim(), pwd) }
                                result.onSuccess { c ->
                                    db.setSetting("mi_account", acct.trim())
                                    db.setSetting("mi_password", pwd)
                                    db.setSetting("minote_cookie", c)
                                    db.setSetting("mi_login_err", "")
                                    cookie = c
                                    syncMsg = "登录成功 ✓ Cookie 已更新，正在同步云端…"
                                    val r = CloudSync.quiet(App.instance, db)
                                    r?.let {
                                        syncMsg = "登录成功 ✓ 同步完成：云端 ${it.enumerated} 篇，新下载 ${it.downloaded}"
                                    } ?: run { syncMsg = "登录成功 ✓（同步未执行，稍后打开 app 会自动同步）" }
                                    JobRunner.autoOrganizeAfterSync(db, r)
                                }
                                result.onFailure { e ->
                                    db.setSetting("mi_login_err", e.message ?: "未知错误")
                                    syncMsg = "登录失败：${e.message}"
                                }
                                loginBusy = false
                            }
                        },
                        enabled = !loginBusy && acct.isNotBlank() && pwd.isNotBlank(),
                    ) { Text(if (loginBusy) "登录中…" else "登录") }
                }
            },
            dismissButton = { TextButton(onClick = { if (!loginBusy) showAccountLogin = false }) { Text("取消") } },
            title = { Text("小米账号登录", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = acct, onValueChange = { acct = it },
                        label = { Text("手机号 / 邮箱 / 小米ID") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loginBusy,
                    )
                    OutlinedTextField(
                        value = pwd, onValueChange = { pwd = it },
                        label = { Text("密码") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loginBusy,
                    )
                    if (loginState.startsWith("otp:")) {
                        Text(
                            loginState.removePrefix("otp: ").ifBlank { "短信验证码已发送至本机，请查收短信" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        OutlinedTextField(
                            value = otpCode, onValueChange = { otpCode = it.filter { c -> c.isDigit() }.take(8) },
                            label = { Text("短信验证码") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (loginBusy) {
                        Text(
                            when {
                                loginState.startsWith("page") -> "已打开小米登录页，正在填写…"
                                loginState.contains("verify") -> "正在进入安全验证…"
                                else -> "登录中…（${loginState.take(40)}）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "密码只保存在本机（app 沙箱内），仅提交给 account.xiaomi.com；" +
                                "之后 Cookie 过期 app 会自动用它续期。首次登录小米可能要求短信验证（验证码发到本机），一次通过后设备受信。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("设置", style = MaterialTheme.typography.titleLarge)

        // ---- AI ----
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("内置 AI（DeepSeek · Anthropic 兼容）", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("API Key（默认已内置，可覆盖）") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = model == BuildConfigHelper.modelFast(),
                        onClick = { model = BuildConfigHelper.modelFast() },
                        label = { Text("v4-flash（快/省）") })
                    FilterChip(selected = model == BuildConfigHelper.modelPro(),
                        onClick = { model = BuildConfigHelper.modelPro() },
                        label = { Text("v4-pro（强）") })
                }
                Button(onClick = {
                    db.setSetting("api_key", apiKey.trim())
                    db.setSetting("ai_model", model.trim())
                    syncMsg = "AI 配置已保存 ✓"
                }) { Text("保存 AI 配置") }
            }
        }

        // ---- 卡片偏好 ----
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("灵感卡片偏好", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("避开感情回忆", style = MaterialTheme.typography.bodyMedium)
                        Text("开启后，每日卡片绝不推送与过去恋情相关的内容（「朝朝暮暮」及感情类笔记），AI 选卡与兜底选卡同时生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(checked = excludeRomance, onCheckedChange = {
                        excludeRomance = it
                        db.setSetting("card_exclude_romance", if (it) "1" else "0")
                    })
                }
            }
        }

        // ---- 每日推送 ----
        Card {
            var pushEnabled by remember { mutableStateOf(db.getSetting("card_push_enabled") == "1") }
            var pushHour by remember { mutableStateOf(db.getSetting("card_push_hour", "9").toIntOrNull() ?: 9) }
            var pushMinute by remember { mutableStateOf(db.getSetting("card_push_minute", "0").toIntOrNull() ?: 0) }
            var showTimePicker by remember { mutableStateOf(false) }

            fun setTime(h: Int, m: Int) {
                pushHour = h; pushMinute = m
                db.setSetting("card_push_hour", "$h"); db.setSetting("card_push_minute", "$m")
                if (pushEnabled) PushScheduler.schedule(App.instance, h, m)
            }

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("每日定时推送", style = MaterialTheme.typography.bodyMedium)
                        Text("系统级真实闹钟：到点自动把当天灵感卡片推送到通知栏（app 不在前台也生效，重启后自动重排）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(checked = pushEnabled, onCheckedChange = { on ->
                        pushEnabled = on
                        db.setSetting("card_push_enabled", if (on) "1" else "0")
                        if (on) PushScheduler.schedule(App.instance, pushHour, pushMinute)
                        else PushScheduler.cancel(App.instance)
                    })
                }
                if (pushEnabled) {
                    val exactOk = remember { mutableStateOf(PushScheduler.canExact(App.instance)) }
                    val nextCal = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.HOUR_OF_DAY, pushHour)
                        set(java.util.Calendar.MINUTE, pushMinute)
                        set(java.util.Calendar.SECOND, 0)
                        if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
                    }
                    val nextText = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(nextCal.time)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "每天 %02d:%02d 推送 · 下次 $nextText".format(pushHour, pushMinute),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { showTimePicker = true },
                            contentPadding = PaddingValues(horizontal = 12.dp)) { Text("改时间") }
                    }
                    if (!exactOk.value) {
                        Text(
                            "未授予「闹钟和提醒」权限，推送可能延迟几分钟。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(onClick = {
                            runCatching {
                                App.instance.startActivity(android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    android.net.Uri.parse("package:com.halo.yunquespark")))
                            }
                            // 从系统设置回来后重新检查（简单起见用停留重试）
                            exactOk.value = PushScheduler.canExact(App.instance)
                        }) { Text("允许精确闹钟") }
                    }
                }
            }

            if (showTimePicker) {
                val state = rememberTimePickerState(
                    initialHour = pushHour, initialMinute = pushMinute, is24Hour = true,
                )
                AlertDialog(
                    onDismissRequest = { showTimePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            setTime(state.hour, state.minute); showTimePicker = false
                        }) { Text("确定") }
                    },
                    dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("取消") } },
                    title = { Text("选择推送时间") },
                    text = { TimePicker(state = state) },
                )
            }
        }

        // ---- Obsidian 同步 ----
        Card {
            var syncingObs by remember { mutableStateOf(false) }
            var obsMsg by remember { mutableStateOf("") }
            var vaultLinked by remember { mutableStateOf(ObsidianSync.configured(App.instance)) }
            var obsAuto by remember { mutableStateOf(ObsidianSync.autoEnabled(App.instance)) }
            val obsPicker = rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    runCatching {
                        App.instance.contentResolver.takePersistableUriPermission(
                            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    db.setSetting("obsidian_tree_uri", uri.toString())
                    vaultLinked = true
                    obsMsg = "vault 已关联，正在同步…"
                    scope.launch(Dispatchers.IO) {
                        val r = runCatching { ObsidianSync.syncAll(App.instance) }
                        obsMsg = r.getOrNull()?.let { "同步完成：新写入 ${it.exported}，无变化 ${it.skipped}" }
                            ?: ("同步失败：" + (r.exceptionOrNull()?.message ?: "未知错误"))
                    }
                }
            }

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("同步到 Obsidian", style = MaterialTheme.typography.titleMedium)
                Text(
                    "把 AI 产出（知识库/草稿/每日卡片/引用原文）以 Markdown 写入手机 Obsidian vault 的 YunqueSpark/ 目录。" +
                            "首次使用：在 Obsidian 中打开你的 vault，然后点下方按钮选中该文件夹。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (vaultLinked) "已关联：${Uri.parse(ObsidianSync.vaultUri(App.instance)).lastPathSegment ?: "vault"}"
                    else "尚未关联 vault",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { obsPicker.launch(null) }) {
                        Text(if (vaultLinked) "重新选择文件夹" else "选择 vault 文件夹")
                    }
                    if (vaultLinked) {
                        OutlinedButton(
                            onClick = {
                                syncingObs = true; obsMsg = "同步中…"
                                scope.launch(Dispatchers.IO) {
                                    val r = runCatching { ObsidianSync.syncAll(App.instance) }
                                    obsMsg = r.getOrNull()?.let { "同步完成：新写入 ${it.exported}，无变化 ${it.skipped}" }
                                        ?: ("同步失败：" + (r.exceptionOrNull()?.message ?: "未知错误"))
                                    syncingObs = false
                                }
                            },
                            enabled = !syncingObs,
                        ) { Text(if (syncingObs) "同步中…" else "立即同步") }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("AI 任务完成后自动同步", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(checked = obsAuto, onCheckedChange = {
                        obsAuto = it
                        db.setSetting("obsidian_auto", if (it) "1" else "0")
                    })
                }
                if (obsMsg.isNotBlank()) Text(obsMsg, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---- 同步 ----
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("小米笔记同步", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (cookie.isBlank()) "尚未登录。登录一次即可自动增量同步云端笔记。"
                    else "已登录（Cookie ${cookie.length} 字符）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!showWebView && !pasteMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showAccountLogin = true }) { Text("账号登录") }
                        OutlinedButton(onClick = { pasteMode = true }) { Text("粘贴 Cookie") }
                        OutlinedButton(onClick = { showWebView = true }) { Text("WebView") }
                    }
                    Text(
                        "推荐「账号登录」：密码只存本机、仅发往小米官方服务器；Cookie 过期后 app 会自动用它在后台续期，无需再管。" +
                                "若提示风控验证，改用电脑浏览器登录 i.mi.com 后「粘贴 Cookie」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    db.getSetting("mi_login_err").takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (pasteMode) {
                    OutlinedTextField(
                        value = pasteText, onValueChange = { pasteText = it },
                        label = { Text("粘贴完整 Cookie 串") }, modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { saveCookie(pasteText); pasteMode = false }) { Text("保存") }
                        OutlinedButton(onClick = { pasteMode = false }) { Text("取消") }
                    }
                }
                if (showWebView) {
                    Text("在下方登录 i.mi.com（扫码/账号均可），登录成功后自动抓取 Cookie。",
                        style = MaterialTheme.typography.bodySmall)
                    WebViewLogin(onDone = { c -> saveCookie(c); showWebView = false })
                }
                Button(onClick = {
                    if (cookie.isBlank()) { syncMsg = "请先登录"; return@Button }
                    syncing = true; syncMsg = "开始同步…"
                    scope.launch(Dispatchers.IO) {
                        val r = CloudSync.quiet(App.instance, db) { p -> syncMsg = p }
                        syncMsg = r?.let {
                            "同步完成：云端 ${it.enumerated} 篇，新下载 ${it.downloaded}，跳过 ${it.skipped}，失败 ${it.failed}" +
                                    (if (it.downloaded > 0 && db.getSetting("auto_organize", "1") == "1") "；新笔记已自动进入整理" else "")
                        } ?: "同步失败（Cookie 可能已失效，请重新登录）"
                        JobRunner.autoOrganizeAfterSync(db, r)
                        syncing = false
                    }
                }, enabled = !syncing) { Text(if (syncing) "同步中…" else "立即同步") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("打开 app 时自动同步", style = MaterialTheme.typography.bodySmall)
                        Text("已登录时，打开 app 静默增量拉取云端新笔记",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoSyncOpen, onCheckedChange = {
                        autoSyncOpen = it
                        db.setSetting("auto_sync_open", if (it) "1" else "0")
                    })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("同步后自动整理新笔记", style = MaterialTheme.typography.bodySmall)
                        Text("拉到新笔记自动依次运行：分类 → 统合 → 知识库 → 扩充（进度见任务台）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoOrganize, onCheckedChange = {
                        autoOrganize = it
                        db.setSetting("auto_organize", if (it) "1" else "0")
                    })
                }
                if (syncMsg.isNotBlank()) Text(syncMsg, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }

        // ---- 数据 ----
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("数据", style = MaterialTheme.typography.titleMedium)
                Text("本地笔记 ${db.noteCount()} 篇 · 知识库 ${db.kbCount()} 页 · 草稿 ${db.draftCount()} 篇 · 卡片 ${db.cardCount()} 张",
                    style = MaterialTheme.typography.bodySmall)
                Text("原始笔记不可变：AI 的一切产出（分类元数据/知识库/草稿/卡片）独立存放，随时可整体删除重来。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

/** 打开 i.mi.com 登录页，检测到 serviceToken Cookie 即回调。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewLogin(onDone: (String) -> Unit) {
    var done by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var currentUrl by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(460.dp),
                factory = { ctx ->
                    WebView(ctx).apply {
                        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                        // HyperOS 上 Chromium 152 的 Vulkan 合成会出现"DOM 在但画面白"的问题，
                        // 强制 WebView 走软件渲染（登录页流量小，性能可接受）
                        setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // 小米账号页对 WebView UA 有风控，伪装标准移动版 Chrome
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
                        // 登录链路跨 account.xiaomi.com / i.mi.com 双域，必须允许第三方 Cookie
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                currentUrl = url
                                if (done) return
                                val cm = CookieManager.getInstance()
                                val c = cm.getCookie("https://i.mi.com") ?: ""
                                if (c.contains("serviceToken=") && c.contains("userId=")) {
                                    done = true
                                    onDone(c)
                                    return
                                }
                                // 白屏检测：DOM 已加载但渲染管线无像素输出（HyperOS + WebView 152 已知问题）
                                view.postDelayed({
                                    if (done || err.isNotBlank()) return@postDelayed
                                    try {
                                        val bmp = android.graphics.Bitmap.createBitmap(60, 100, android.graphics.Bitmap.Config.ARGB_8888)
                                        val canvas = android.graphics.Canvas(bmp)
                                        view.draw(canvas)
                                        var allWhite = true
                                        outer@ for (x in 0 until 60 step 6) for (y in 0 until 100 step 6) {
                                            if (bmp.getPixel(x, y) != -1) { allWhite = false; break@outer }
                                        }
                                        bmp.recycle()
                                        if (allWhite) err = "检测到系统 WebView 渲染异常（页面数据已加载但画面无法绘制，HyperOS 已知问题）。" +
                                                "可尝试：更新/更换「Android System WebView」或安装 Chrome 后在开发者选项切换 WebView 实现；" +
                                                "或用电脑浏览器登录 i.mi.com 抓取 Cookie 后回到这里「粘贴 Cookie」。"
                                    } catch (_: Exception) { }
                                }, 2500)
                            }
                            override fun onReceivedError(view: WebView, code: Int, description: String, failingUrl: String) {
                                err = "页面加载出错：$description"
                            }
                            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                currentUrl = url
                            }
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        loadUrl("https://i.mi.com/note")
                    }
                },
                update = { w -> if (reload > 0) w.reload() },
            )
            if (progress < 100 && err.isBlank()) {
                LinearWavyProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                )
            }
        }
        if (err.isNotBlank()) {
            Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { err = ""; reload++ }) { Text("重新加载") }
        }
        if (currentUrl.isNotBlank()) {
            Text("当前页面: ${currentUrl.take(90)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "登录成功会自动收起。页面一直空白时点「重新加载」，或改用「粘贴 Cookie」。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
