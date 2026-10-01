package com.halo.yunquespark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.halo.yunquespark.App
import com.halo.yunquespark.data.Note
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

fun fmtDay(ts: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(ts))

/** 轻量 Markdown 渲染：标题/粗体/yunque://note 链接/[[wiki 链接]]/代码块退化处理。 */
@Composable
fun funMarkdown(body: String, onOpenNote: (String) -> Unit = {}, onOpenWiki: (String) -> Unit = {}) {
    val lines = body.split("\n")
    val inCode = remember { mutableStateOf(false) }
    val linkColor = LinkColor
    Column {
        for (raw in lines) {
            var line = raw
            if (line.trimStart().startsWith("```")) { inCode.value = !inCode.value; continue }
            if (line.startsWith("#")) {
                val level = line.takeWhile { it == '#' }.length
                line = line.dropWhile { it == '#' }.trim()
                Text(
                    buildAnnotatedString { appendInline(line, onOpenNote, onOpenWiki, linkColor) },
                    style = if (level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            } else {
                Text(
                    buildAnnotatedString { appendInline(line, onOpenNote, onOpenWiki, linkColor) },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

private fun AnnotatedString.Builder.appendInline(
    line: String,
    onOpenNote: (String) -> Unit,
    onOpenWiki: (String) -> Unit,
    linkColor: Color,
) {
    // [text](yunque://note/<id>) 与 [[slug|text]]
    val rx = Regex("""\[([^\]]+)]\((yunque://note/[^)]+)\)|\[\[([^\]|]+)\|([^\]]+)]]""")
    var i = 0
    for (m in rx.findAll(line)) {
        if (m.range.first > i) append(line.substring(i, m.range.first))
        if (m.groupValues[1].isNotEmpty()) {
            val noteId = m.groupValues[2].substringAfterLast('/')
            withLink(LinkAnnotation.Clickable(m.groupValues[2]) { onOpenNote(noteId) }) {
                withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium)) { append(m.groupValues[1]) }
            }
        } else {
            val slug = m.groupValues[3]; val text = m.groupValues[4]
            withLink(LinkAnnotation.Clickable("wiki:$slug") { onOpenWiki(slug) }) {
                withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium)) { append(text) }
            }
        }
        i = m.range.last + 1
    }
    if (i < line.length) append(line.substring(i))
}

/** 笔记详情整页（全 app 复用：笔记库/卡片出处/聊天引用/知识库来源）。 */
@Composable
fun NoteDetailScreen(noteId: String, onBack: () -> Unit, onOpenNote: (String) -> Unit = {}) {
    val db = App.instance.db
    val note = remember(noteId) { db.getNote(noteId) }
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // 顶栏
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Text("←", style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    note?.title ?: "笔记不存在",
                    style = MaterialTheme.typography.titleMedium, maxLines = 1,
                )
                if (note != null) {
                    Text(
                        "${note.folder} · ${fmtDay(note.createTs)} · ${note.aiCategory ?: "未分类"}" +
                                if (note.aiTags.isNotEmpty()) " · ${note.aiTags.joinToString("/")}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
            }
            if (note != null) {
                Text("#${note.id.takeLast(6)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
        // 正文整页
        if (note == null) {
            Text("找不到这篇笔记：$noteId", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                funMarkdown(note.content, onOpenNote = onOpenNote)
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

/** 统计小胶囊 */
@Composable
fun StatChip(label: String, value: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
