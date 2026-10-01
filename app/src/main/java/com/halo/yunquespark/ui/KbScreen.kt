package com.halo.yunquespark.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.halo.yunquespark.App
import com.halo.yunquespark.data.KbPage

/** 知识库：AI 按 LLM Wiki 模式维护的页面，含总目录与日志。 */
@Composable
fun KbScreen(onOpenNote: (String) -> Unit) {
    val db = App.instance.db
    var pages by remember { mutableStateOf(db.kbList()) }
    var openSlug by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(openSlug) { if (openSlug == null) pages = db.kbList() }

    if (openSlug != null) {
        val page = remember(openSlug) { db.kbGet(openSlug!!) }
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { openSlug = null }, modifier = Modifier.padding(start = 8.dp)) { Text("← 返回知识库") }
            if (page == null) Text("页面不存在", Modifier.padding(16.dp))
            else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(page.title, style = MaterialTheme.typography.titleLarge)
                Text("${page.category} · 更新于 ${fmtDay(page.updatedTs)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                funMarkdown(page.body, onOpenNote = onOpenNote, onOpenWiki = { openSlug = it })
                if (page.sources.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("来源笔记", style = MaterialTheme.typography.titleSmall)
                    for (sid in page.sources) {
                        val n = db.getNote(sid)
                        if (n != null) TextButton(onClick = { onOpenNote(sid) }, contentPadding = PaddingValues(4.dp)) {
                            Text("· 《${n.title}》 ${fmtDay(n.createTs)}")
                        }
                    }
                }
                Spacer(Modifier.height(30.dp))
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("知识库", style = MaterialTheme.typography.titleLarge)
        Text("由内置 AI 按 LLM Wiki 模式增量维护：来源不可变，wiki 持续生长。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        if (pages.isEmpty()) {
            Text("还没有页面。去任务台运行「知识库构建」，或直接在对话里让云雀沉淀一个主题。",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(pages, key = { it.slug }) { p ->
                    Card(Modifier.fillMaxWidth().clickable { openSlug = p.slug }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(p.title, style = MaterialTheme.typography.titleSmall)
                            Text("${p.category} · ${p.body.replace(Regex("\\s+"), " ").take(60)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}
