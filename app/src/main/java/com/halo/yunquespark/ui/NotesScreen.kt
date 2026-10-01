package com.halo.yunquespark.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.halo.yunquespark.App
import com.halo.yunquespark.data.Note

/** 笔记库：文件夹筛选 + 搜索 + 列表。AI 分类结果以徽标显示。 */
@Composable
fun NotesScreen(onOpenNote: (String) -> Unit) {
    val db = App.instance.db
    var folder by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
    var onlyUnclassified by remember { mutableStateOf(false) }
    val folders = remember { db.folderCounts() }

    fun load() {
        notes = if (query.isBlank()) {
            db.listNotes(folder, 0, 100, "create_desc", onlyUnclassified)
        } else {
            db.searchNotes(query, folder, 60)
        }
    }
    LaunchedEffect(folder, query, onlyUnclassified) { load() }

    Column(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        Text("笔记库", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            placeholder = { Text("搜索 1700 篇笔记…") },
            singleLine = true, shape = MaterialTheme.shapes.extraLarge,
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清空") } },
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = folder == null, onClick = { folder = null }, label = { Text("全部 ${db.noteCount()}") })
            FilterChip(selected = onlyUnclassified, onClick = { onlyUnclassified = !onlyUnclassified }, label = { Text("待分类") })
            for ((f, c) in folders) {
                FilterChip(selected = folder == f, onClick = { folder = if (folder == f) null else f }, label = { Text("$f $c") })
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(notes, key = { it.id }) { n ->
                Card(Modifier.fillMaxWidth().clickable { onOpenNote(n.id) }) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(n.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(fmtDay(n.createTs), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(n.snippet(90), style = MaterialTheme.typography.bodySmall, maxLines = 2,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Row {
                            AssistChip(onClick = {}, label = { Text(n.folder, style = MaterialTheme.typography.bodySmall) })
                            if (n.aiCategory != null && n.aiCategory != n.folder) {
                                Spacer(Modifier.width(6.dp))
                                AssistChip(onClick = {},
                                    label = { Text("→ ${n.aiCategory}", style = MaterialTheme.typography.bodySmall) })
                            }
                        }
                    }
                }
            }
            if (notes.isEmpty()) {
                item { Text("没有匹配的笔记", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
