package com.halo.yunquespark.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 一条内容块：text / thinking / tool_use。 */
data class Block(
    val type: String,
    val text: String? = null,
    val thinking: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JSONObject? = null,
)

data class MessageResult(
    val blocks: List<Block>,
    val stopReason: String,
    val inputTokens: Int,
    val outputTokens: Int,
) {
    val text: String get() = blocks.filter { it.type == "text" }.joinToString("") { it.text ?: "" }
    val toolUses: List<Block> get() = blocks.filter { it.type == "tool_use" }
    val thinking: String get() = blocks.filter { it.type == "thinking" }.joinToString("") { it.thinking ?: "" }
}

class AiException(message: String, val retryable: Boolean = false) : Exception(message)

/**
 * Anthropic Messages API 客户端（SSE 流式）。
 * 接 DeepSeek 的 Anthropic 兼容层：base = https://api.deepseek.com/anthropic
 * 已实测确认：tool_use / tool_result / thinking 块均支持；is_error 被忽略，
 * 因此工具错误必须以文本形式写进 tool_result.content。
 */
class AnthropicClient(
    private val baseUrl: String,
    private val apiKey: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun stream(
        model: String,
        system: String,
        messages: JSONArray,          // [{role, content:[{type,text|tool_use|tool_result...}]}]
        tools: JSONArray,             // [{name, description, input_schema}]
        maxTokens: Int = 8192,
        temperature: Double = 1.0,
        onText: (String) -> Unit = {},
        onThinking: (String) -> Unit = {},
    ): MessageResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put("system", system)
            put("messages", messages)
            if (tools.length() > 0) put("tools", tools)
            put("stream", true)
        }
        val req = Request.Builder()
            .url("$baseUrl/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val blocks = ArrayList<Block>()
        val partialInput = HashMap<Int, StringBuilder>()
        var stopReason = "end_turn"
        var inTokens = 0; var outTokens = 0

        withContext(Dispatchers.IO) {
            val call = http.newCall(req)
            val resp = try {
                call.execute()
            } catch (e: Exception) {
                throw AiException("网络请求失败: ${e.message}", retryable = true)
            }
            resp.use { r ->
                if (r.code == 401) throw AiException("API Key 无效（401）")
                if (r.code == 429) throw AiException("请求过于频繁（429），稍后重试", retryable = true)
                if (r.code >= 500) throw AiException("服务端错误（${r.code}）", retryable = true)
                if (r.code != 200) throw AiException("API HTTP ${r.code}: ${r.body?.string()?.take(300)}")
                val source = r.body?.source() ?: throw AiException("空响应")
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val ev = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    when (ev.optString("type")) {
                        "error" -> throw AiException("API 错误: ${ev.optJSONObject("error")?.optString("message")}", retryable = true)
                        "content_block_start" -> {
                            val idx = ev.getInt("index")
                            val cb = ev.getJSONObject("content_block")
                            when (cb.optString("type")) {
                                "text" -> blocks.add(Block("text", text = ""))
                                "thinking" -> blocks.add(Block("thinking", thinking = ""))
                                "tool_use" -> {
                                    blocks.add(Block("tool_use", id = cb.optString("id"), name = cb.optString("name"), input = JSONObject()))
                                    partialInput[idx] = StringBuilder()
                                }
                            }
                        }
                        "content_block_delta" -> {
                            val idx = ev.getInt("index")
                            val delta = ev.getJSONObject("delta")
                            when (delta.optString("type")) {
                                "text_delta" -> {
                                    val t = delta.optString("text")
                                    appendText(blocks, idx) { it.copy(text = (it.text ?: "") + t) }
                                    onText(t)
                                }
                                "thinking_delta" -> {
                                    val t = delta.optString("thinking")
                                    appendText(blocks, idx) { it.copy(thinking = (it.thinking ?: "") + t) }
                                    onThinking(t)
                                }
                                "input_json_delta" -> {
                                    partialInput.getOrPut(idx) { StringBuilder() }.append(delta.optString("partial_json"))
                                }
                            }
                        }
                        "content_block_stop" -> {
                            val idx = ev.getInt("index")
                            if (idx < blocks.size && blocks[idx].type == "tool_use") {
                                val sb = partialInput[idx]
                                val parsed = if (sb != null && sb.isNotBlank()) {
                                    runCatching { JSONObject(sb.toString()) }.getOrElse { JSONObject() }
                                } else JSONObject()
                                blocks[idx] = blocks[idx].copy(input = parsed)
                            }
                        }
                        "message_delta" -> {
                            ev.optJSONObject("delta")?.let { stopReason = it.optString("stop_reason", stopReason) }
                            ev.optJSONObject("usage")?.let { outTokens = it.optInt("output_tokens", outTokens) }
                        }
                        "message_start" -> {
                            ev.optJSONObject("message")?.optJSONObject("usage")?.let { inTokens = it.optInt("input_tokens", 0) }
                        }
                    }
                }
            }
        }
        MessageResult(blocks, stopReason, inTokens, outTokens)
    }

    private fun appendText(blocks: MutableList<Block>, idx: Int, f: (Block) -> Block) {
        if (idx in blocks.indices) blocks[idx] = f(blocks[idx])
    }
}
