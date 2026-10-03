package com.halo.yunquespark.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halo.yunquespark.App
import com.halo.yunquespark.ai.JobRunner
import com.halo.yunquespark.ai.Jobs
import kotlinx.coroutines.delay

/** 任务台：五个自治任务的规范、进度与运行入口。任务挂在 App 级作用域，离开页面不中断。 */
@Composable
fun TasksScreen() {
    val db = App.instance.db
    val running by JobRunner.running.collectAsState()
    val logs by JobRunner.logs.collectAsState()
    var tasks by remember { mutableStateOf(db.taskAll().associateBy { it.id }) }

    LaunchedEffect(running) {
        while (true) {
            tasks = db.taskAll().associateBy { it.id }
            delay(2000)
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("任务台", style = MaterialTheme.typography.titleLarge)
            Text(
                "骨架定规范，AI 负责生产：每个任务都是一套提示词 + 一组工具 + 一个循环。" +
                        "任务在后台持续运行（离开本页不中断）；1304 篇未分类建议分多次运行。产物在知识库、草稿与卡片里查看。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(Jobs.ALL.size) { i ->
            val spec = Jobs.ALL[i]
            val t = tasks[spec.id]
            val isRunning = spec.id in running
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(spec.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                isRunning -> "进行中"
                                t == null -> "未运行"
                                else -> when (t.state) {
                                    "done" -> "最近完成"
                                    "error" -> "出错"
                                    "failed" -> "已停止"
                                    "stopped" -> "已停止"
                                    else -> t.state
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (t?.state == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(spec.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (t != null && t.total > 0) {
                        Spacer(Modifier.height(8.dp))
                        LinearWavyProgressIndicator(
                            progress = { if (t.total == 0) 0f else (t.progress.toFloat() / t.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("${t.progress}/${t.total} · ${t.detail}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { JobRunner.start(db, spec.id) }, enabled = !isRunning) {
                            Text(if (isRunning) "运行中…" else "运行")
                        }
                        if (isRunning) {
                            OutlinedButton(onClick = { JobRunner.cancel(spec.id) }) { Text("停止") }
                        }
                    }
                    val jobLog = logs[spec.id]
                    if (jobLog != null && jobLog.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                            Text(
                                jobLog.takeLast(1400), modifier = Modifier.padding(8.dp).heightIn(max = 220.dp),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}
