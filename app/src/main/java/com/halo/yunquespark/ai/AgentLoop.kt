package com.halo.yunquespark.ai

import org.json.JSONArray
import org.json.JSONObject

/** Agent 循环事件，驱动 UI 展示。 */
sealed class AgentEvent {
    data class Text(val delta: String) : AgentEvent()
    data class Thinking(val delta: String) : AgentEvent()
    data class ToolStart(val name: String, val args: JSONObject) : AgentEvent()
    data class ToolEnd(val name: String, val ok: Boolean, val summary: String) : AgentEvent()
    data class TurnDone(val turn: Int) : AgentEvent()
    data class Notice(val message: String) : AgentEvent()
}

/**
 * Agent 主循环（借鉴 Operit EnhancedAIService 的骨架，走原生 Anthropic tool_use 协议）：
 * 请求 → tool_use → 执行（顺序，错误封在文本里回喂）→ 结果回填 → 再请求；
 * 无工具调用即结束；上下文超预算时裁剪最老的整轮（保留首个用户消息）。
 */
class AgentLoop(
    private val client: AnthropicClient,
    private val registry: ToolRegistry,
    private val system: String,
    private val model: String,
    private val maxTurns: Int = 24,
    private val budgetChars: Int = 220_000,   // 粗估上下文预算（CJK ≈ 2 chars/token 上限）
    private val maxHistoryUserTurns: Int = 40,
) {
    val messages = JSONArray()
    var usageIn = 0; var usageOut = 0
        private set

    suspend fun run(
        userText: String,
        onEvent: (AgentEvent) -> Unit,
        isCancelled: () -> Boolean = { false },
        historySeed: List<Pair<String, String>> = emptyList(),
    ): String {
        if (messages.length() == 0 && historySeed.isNotEmpty()) {
            for ((role, content) in historySeed.takeLast(maxHistoryUserTurns)) {
                messages.put(JSONObject().put("role", role).put("content", content))
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))
        trim()

        var finalText = ""
        for (turn in 1..maxTurns) {
            if (isCancelled()) { onEvent(AgentEvent.Notice("已取消")); break }
            val result = client.stream(
                model = model, system = system, messages = messages, tools = registry.toAnthropicTools(),
                onText = { onEvent(AgentEvent.Text(it)) },
                onThinking = { onEvent(AgentEvent.Thinking(it)) },
            )
            usageIn += result.inputTokens; usageOut += result.outputTokens

            // 记录 assistant 轮（含 thinking/tool_use 原块，兼容回传要求）
            val contentBlocks = JSONArray()
            for (b in result.blocks) {
                contentBlocks.put(when (b.type) {
                    "text" -> JSONObject().put("type", "text").put("text", b.text ?: "")
                    "thinking" -> JSONObject().put("type", "thinking").put("thinking", b.thinking ?: "").put("signature", "sk")
                    "tool_use" -> JSONObject().put("type", "tool_use").put("id", b.id).put("name", b.name).put("input", b.input ?: JSONObject())
                    else -> JSONObject().put("type", "text").put("text", "")
                })
            }
            if (contentBlocks.length() > 0) {
                messages.put(JSONObject().put("role", "assistant").put("content", contentBlocks))
            }

            if (result.stopReason != "tool_use" || result.toolUses.isEmpty()) {
                finalText = result.text
                onEvent(AgentEvent.TurnDone(turn))
                break
            }

            // 执行本批全部工具调用，回填 tool_result
            val results = JSONArray()
            for (call in result.toolUses) {
                if (isCancelled()) break
                val name = call.name ?: "?"
                val input = call.input ?: JSONObject()
                onEvent(AgentEvent.ToolStart(name, input))
                val def = registry.get(name)
                val tr = if (def == null) {
                    ToolResult(false, "未知工具: $name。可用工具: ${registry.names().joinToString()}")
                } else {
                    // 工具可能做网络/磁盘 IO，强制 IO 线程，调用方在主线程也安全
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try { def.executor(input) } catch (e: Exception) {
                            ToolResult(false, "工具执行异常: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                }
                onEvent(AgentEvent.ToolEnd(name, tr.ok, tr.text.take(160)))
                results.put(JSONObject().put("type", "tool_result")
                    .put("tool_use_id", call.id)
                    .put("content", if (tr.ok) ToolRegistry.truncate(tr.text) else "ERROR: ${ToolRegistry.truncate(tr.text, 3000)}"))
            }
            messages.put(JSONObject().put("role", "user").put("content", results))
            trim()
            onEvent(AgentEvent.TurnDone(turn))
        }
        return finalText
    }

    /** 超预算裁剪：保留首轮用户消息，删除最老的一轮（user+assistant 对），插入裁剪标记。 */
    private fun trim() {
        var total = messages.toString().length
        if (total <= budgetChars) return
        var idx = 0
        while (total > budgetChars && messages.length() > 4) {
            val first = messages.optJSONObject(0)
            if (first != null && idx == 0) {
                // 保留第一条用户消息（任务目标）
                val second = messages.optJSONObject(1)
                if (second != null && second.optString("role") == "assistant") {
                    messages.remove(1); total -= second.toString().length
                } else if (second != null && second.optString("role") == "user"
                    && second.optJSONArray("content")?.optJSONObject(0)?.optString("type") == "tool_result") {
                    messages.remove(1); total -= second.toString().length
                } else break
            } else {
                val cur = messages.optJSONObject(0) ?: break
                messages.remove(0); total -= cur.toString().length
            }
        }
        // 末尾补一条裁剪说明，避免模型困惑
        messages.put(JSONObject().put("role", "user").put("content",
            "[系统提示：较早期的工具输出已按上下文预算裁剪，任务目标保持不变，请继续。]"))
    }
}
