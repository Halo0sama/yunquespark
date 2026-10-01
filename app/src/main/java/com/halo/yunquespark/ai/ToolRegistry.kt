package com.halo.yunquespark.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具结果：永不抛异常穿透，一切失败都以 ok=false + 文本回喂模型
 * （DeepSeek Anthropic 兼容层忽略 is_error，错误信息必须写进 content 文本）。
 */
data class ToolResult(val ok: Boolean, val text: String)

/**
 * 工具定义 = 数据类 + 执行 lambda（借鉴 Operit：不用注解反射）。
 * inputSchema 直接是 Anthropic 的 input_schema JSON。
 */
class ToolDef(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val executor: suspend (JSONObject) -> ToolResult,
)

class ToolRegistry {
    private val tools = LinkedHashMap<String, ToolDef>()

    fun register(def: ToolDef) { tools[def.name] = def }

    fun get(name: String): ToolDef? = tools[name]
    fun names(): List<String> = tools.keys.toList()

    fun toAnthropicTools(): JSONArray {
        val arr = JSONArray()
        for (t in tools.values) {
            arr.put(JSONObject().apply {
                put("name", t.name)
                put("description", t.description)
                put("input_schema", t.inputSchema)
            })
        }
        return arr
    }

    companion object {
        /** 工具结果硬截断，防止结果撑爆上下文（借鉴 Operit ToolExecutionLimits）。 */
        const val MAX_TOOL_CHARS = 6000

        fun truncate(s: String, max: Int = MAX_TOOL_CHARS): String =
            if (s.length <= max) s
            else s.take((max * 0.75).toInt()) + "\n…[中略]…\n" + s.takeLast((max * 0.2).toInt()) + "\n[结果已截断，原长 ${s.length} 字符。可用更精确的查询缩小范围]"

        fun schema(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
            val o = JSONObject()
            val p = JSONObject()
            for ((k, v) in props) p.put(k, v)
            o.put("type", "object")
            o.put("properties", p)
            if (required.isNotEmpty()) o.put("required", JSONArray(required))
            return o
        }

        fun str(desc: String): JSONObject = JSONObject().put("type", "string").put("description", desc)
        fun int(desc: String): JSONObject = JSONObject().put("type", "integer").put("description", desc)
        fun bool(desc: String): JSONObject = JSONObject().put("type", "boolean").put("description", desc)
        fun strArr(desc: String): JSONObject = JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", desc)
    }
}
