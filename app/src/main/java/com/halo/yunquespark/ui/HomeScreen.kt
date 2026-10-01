package com.halo.yunquespark.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halo.yunquespark.App
import com.halo.yunquespark.ai.AgentEvent
import com.halo.yunquespark.ai.Jobs
import com.halo.yunquespark.data.Card
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 首页：打开 app 即见今日灵感卡片（「最后一公里」入口），下方是库状态与往期/收藏。 */
@Composable
fun HomeScreen(onGoto: (Int) -> Unit, onOpenNote: (String) -> Unit, onOpenCalendar: () -> Unit = {}) {
    val db = App.instance.db
    val scope = rememberCoroutineScope()
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date()) }

    var card by remember { mutableStateOf<Card?>(null) }
    var generating by remember { mutableStateOf(false) }
    var rerolling by remember { mutableStateOf(false) }
    var genNote by remember { mutableStateOf("") }
    var noteCount by remember { mutableStateOf(0) }
    var classified by remember { mutableStateOf(0) }
    var kbCount by remember { mutableStateOf(0) }
    var drafts by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var favOnly by remember { mutableStateOf(false) }

    fun refresh() {
        noteCount = db.noteCount()
        classified = db.classifiedProgress().first
        kbCount = db.kbCount()
        drafts = db.draftCount()
    }

    LaunchedEffect(Unit) {
        refresh()
        // 打开 app 静默增量同步云端（已登录且开启时），拉到新笔记自动整理
        if (App.instance.db.getSetting("minote_cookie").isNotBlank() &&
            App.instance.db.getSetting("auto_sync_open", "1") == "1"
        ) {
            App.instance.scope.launch {
                val r = runCatching {
                    com.halo.yunquespark.sync.CloudSync.quiet(App.instance, App.instance.db)
                }.getOrNull()
                com.halo.yunquespark.ai.JobRunner.autoOrganizeAfterSync(App.instance.db, r)
                refresh()
                if (card == null) card = db.cardToday(today)
            }
        } else {
            // 未登录/同步未开：存量待办（种子导入的笔记、未跑过的整理任务）也自动补跑
            com.halo.yunquespark.ai.JobRunner.autoOrganizeBacklog(App.instance.db)
        }
        card = db.cardToday(today)
        card = db.cardToday(today)
        if (card == null && noteCount > 0) {
            generating = true
            // 挂在 App 级作用域：离开首页也不会中断选卡（并发重入由 ensureDailyCard 内部互斥）
            App.instance.scope.launch {
                try {
                    val c = Jobs.ensureDailyCard(App.instance.db) { ev ->
                        if (ev is AgentEvent.ToolStart) genNote = "AI 正在选材：${ev.name}"
                        if (ev is AgentEvent.Text) genNote = "AI 正在思考…"
                    }
                    card = c
                } catch (e: Exception) {
                    genNote = "选卡失败：${e.message}"
                }
                generating = false
                refresh()
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("云雀灵感闪现", style = MaterialTheme.typography.titleLarge)
        Text(
            "让 6 年 1700 篇笔记的价值流进大脑的最后一公里",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- 今日卡片 ----
        when {
            generating -> Card {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(genNote.ifBlank { "正在挑选今天的灵感卡片…" }, style = MaterialTheme.typography.bodySmall)
                }
            }
            card != null -> ElevatedCard(
                Modifier.fillMaxWidth().shadow(6.dp, RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.clickable { onOpenNote(card!!.sourceNoteId) }.padding(22.dp)) {
                    Text("今日灵感 · ${card!!.day}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Text(card!!.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    Text(card!!.body, style = MaterialTheme.typography.bodyMedium, fontSize = 17.sp, lineHeight = 27.sp)
                    if (card!!.comment.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(card!!.comment, style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("轻触查看原文 →", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Row {
                            // 收藏
                            IconButton(onClick = {
                                val fav = !(card!!.favorite)
                                db.cardSetFavorite(card!!.day, fav)
                                card = card!!.copy(favorite = fav)
                            }, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    if (card!!.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                    contentDescription = "收藏",
                                    tint = if (card!!.favorite) Color(0xFFD95B6C) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            // 编辑
                            IconButton(onClick = { editing = true }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.Edit, contentDescription = "编辑",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            }
                            // 换一张
                            TextButton(
                                onClick = {
                                    if (rerolling) return@TextButton
                                    rerolling = true; generating = true
                                    App.instance.scope.launch {
                                        try {
                                            card = Jobs.rerollDailyCard(App.instance.db) { ev ->
                                                if (ev is AgentEvent.ToolStart) genNote = "AI 正在重新选卡：${ev.name}"
                                            }
                                        } catch (e: Exception) {
                                            genNote = "换卡失败：${e.message}"
                                        }
                                        generating = false; rerolling = false; refresh()
                                    }
                                },
                                enabled = !rerolling,
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) { Text(if (rerolling) "换卡中…" else "换一张", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            else -> Card { Text("笔记库为空，先去设置里导入或同步笔记。", Modifier.padding(20.dp)) }
        }

        // ---- 状态 ----
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatChip("笔记", "$noteCount")
            StatChip("已分类", "$classified")
            StatChip("知识库", "$kbCount")
            StatChip("草稿", "$drafts")
        }

        // ---- 快捷入口 ----
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onGoto(1) }, Modifier.weight(1f)) { Text("问云雀") }
            OutlinedButton(onClick = { onGoto(2) }, Modifier.weight(1f)) { Text("任务台") }
        }

        // ---- 往期与收藏 ----
        val past = db.cardRecent(60).filter { it.day != today }.filter { !favOnly || it.favorite }
        if (db.cardCount() > 1) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("往期回顾", style = MaterialTheme.typography.titleMedium)
                FilterChip(
                    selected = favOnly, onClick = { favOnly = !favOnly },
                    label = { Text("只看收藏", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = if (favOnly) {
                        { Icon(Icons.Filled.Favorite, null, tint = Color(0xFFD95B6C), modifier = Modifier.size(14.dp)) }
                    } else null,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "卡片日历 →", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenCalendar() },
                )
            }
            if (past.isEmpty() && favOnly) {
                Text("还没有收藏的卡片。点今日卡片右下角的 ♡ 收藏。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            past.take(6).forEach { c ->
                Card(Modifier.fillMaxWidth().clickable { onOpenNote(c.sourceNoteId) }) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (c.favorite) {
                                Icon(Icons.Filled.Favorite, null, tint = Color(0xFFD95B6C), modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(5.dp))
                            }
                            Text("《${c.title}》· ${c.day}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(c.body, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    // ---- 编辑弹窗 ----
    if (editing && card != null) {
        val c = card!!
        var tTitle by remember { mutableStateOf(c.title) }
        var tBody by remember { mutableStateOf(c.body) }
        var tComment by remember { mutableStateOf(c.comment) }
        AlertDialog(
            onDismissRequest = { editing = false },
            confirmButton = {
                TextButton(onClick = {
                    db.cardUpdate(c.day, tTitle.trim(), tBody.trim(), tComment.trim())
                    card = c.copy(title = tTitle.trim(), body = tBody.trim(), comment = tComment.trim())
                    editing = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } },
            title = { Text("编辑今日卡片", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = tTitle, onValueChange = { tTitle = it },
                        label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = tBody, onValueChange = { tBody = it },
                        label = { Text("正文（≤140 字）") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = tComment, onValueChange = { tComment = it },
                        label = { Text("点评（≤60 字）") }, minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth())
                }
            },
        )
    }
}
