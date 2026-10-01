package com.halo.yunquespark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halo.yunquespark.App
import com.halo.yunquespark.ai.AgentEvent
import com.halo.yunquespark.ai.Jobs
import com.halo.yunquespark.ai.Prompts
import kotlinx.coroutines.launch

/** 与云雀对话：可追问笔记内容与知识，也可让它结合多条笔记写新东西。 */
@Composable
fun ChatScreen(onOpenNote: (String) -> Unit, prefill: String = "") {
    val db = App.instance.db
    val scope = rememberCoroutineScope()

    data class Msg(
        val role: String, val text: String = "", val tool: String? = null,
        val toolOk: Boolean = true, val streaming: Boolean = false,
    )

    val msgs = remember { mutableStateListOf<Msg>() }
    var input by remember { mutableStateOf(prefill) }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (msgs.isEmpty()) {
            for ((role, content) in db.chatRecent(30)) msgs.add(Msg(role, content))
        }
    }

    fun send() {
        val q = input.trim()
        if (q.isEmpty() || busy) return
        input = ""
        busy = true
        msgs.add(Msg("user", q))
        val idx = msgs.size
        msgs.add(Msg("assistant", streaming = true))
        scope.launch {
            val sb = StringBuilder()
            val registry = com.halo.yunquespark.ai.ToolRegistry()
            Tools_registerChat(registry, db)
            val loop = com.halo.yunquespark.ai.AgentLoop(
                Jobs.aiClient(db), registry, Prompts.chatSystem(db), Jobs.model(db), maxTurns = 20)
            try {
                loop.run(q, onEvent = { ev ->
                    when (ev) {
                        is AgentEvent.Text -> {
                            sb.append(ev.delta)
                            if (idx < msgs.size) msgs[idx] = Msg("assistant", sb.toString(), streaming = true)
                        }
                        is AgentEvent.ToolStart -> {
                            if (idx < msgs.size) msgs[idx] = Msg("assistant", sb.toString())
                            msgs.add(Msg("tool", tool = "${ev.name} ${ev.args.toString().take(120)}"))
                        }
                        is AgentEvent.ToolEnd -> msgs.add(Msg("tool", tool = "${ev.name} ${if (ev.ok) "✓" else "✗"} ${ev.summary}"))
                        is AgentEvent.Notice -> sb.append("\n\n⚠ ").append(ev.message)
                        else -> {}
                    }
                    scope.launch { listState.animateScrollToItem(if (msgs.size > 1) msgs.size - 1 else 0) }
                }, historySeed = db.chatRecent(20).map { it.first to it.second })
                if (idx < msgs.size) msgs[idx] = Msg("assistant", sb.toString().ifBlank { loop.messages.toString().take(0) })
                db.chatAppend("user", null, q)
                db.chatAppend("assistant", null, sb.toString())
            } catch (e: Exception) {
                if (idx < msgs.size) msgs[idx] = Msg("assistant", sb.toString() + "\n\n⚠ ${e.message}")
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 消息列表
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (msgs.isEmpty()) {
                item {
                    Column(Modifier.padding(top = 40.dp)) {
                        Text("问云雀", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "它读得了你的全部笔记与知识库。\n\n试试：\n· 我笔记里关于 AI 本地部署都记过什么？\n· 结合我的点子，2026 年还能做什么新东西？\n· 我有没有反复想过同一件事？\n· 那个「全息芙」到底是什么？",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(msgs.size) { i ->
                val m = msgs[i]
                when (m.role) {
                    "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Surface(color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)) {
                            Text(m.text, Modifier.padding(12.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    "assistant" -> if (m.text.isNotBlank() || m.streaming) {
                        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                if (m.streaming && m.text.isBlank()) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else funMarkdown(m.text, onOpenNote = onOpenNote)
                            }
                        }
                    }
                    "tool" -> Text(
                        m.tool ?: "",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
        // 输入条
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("问点什么，或让它写点什么…") }, maxLines = 4,
                enabled = !busy, shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            if (busy) {
                OutlinedButton(onClick = { busy = false }, enabled = false) { Text("…") }
            } else {
                Button(onClick = { send() }, enabled = input.isNotBlank()) { Text("发送") }
            }
        }
    }
}

private fun Tools_registerChat(registry: com.halo.yunquespark.ai.ToolRegistry, db: com.halo.yunquespark.data.Db) {
    com.halo.yunquespark.ai.Tools.registerChatTools(registry, db, null)
}
