package com.halo.yunquespark.ai

import com.halo.yunquespark.data.Card
import com.halo.yunquespark.data.Db
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 内置工具集。每个任务（job）只装配需要的子集，控制提示词膨胀（借鉴 Operit 按需注入）。
 * 全部工具：错误不抛异常，封进 ToolResult(false, text) 回喂模型。
 */
object Tools {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun fmt(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(ts))

    // ---------- schema 简写 ----------

    private fun str(d: String) = ToolRegistry.str(d)
    private fun int(d: String) = ToolRegistry.int(d)
    private fun bool(d: String) = ToolRegistry.bool(d)
    private fun strArr(d: String) = ToolRegistry.strArr(d)

    private fun schema(vararg props: Pair<String, JSONObject>): JSONObject =
        ToolRegistry.schema(*props)

    private fun tool(
        name: String, description: String, s: JSONObject,
        required: List<String> = emptyList(),
        executor: suspend (JSONObject) -> ToolResult,
    ): ToolDef {
        if (required.isNotEmpty()) s.put("required", JSONArray(required))
        return ToolDef(name, description, s, executor)
    }

    // ---------- 工具集装配 ----------

    /** 聊天（全量工具） */
    fun registerChatTools(r: ToolRegistry, db: Db, taskId: String?) {
        registerNoteTools(r, db)
        registerKbTools(r, db)
        registerDraftTools(r, db)
        registerWebTools(r)
    }

    fun registerNoteTools(r: ToolRegistry, db: Db) {
        r.register(tool(
            "note_search", "全文搜索笔记。返回 id/标题/文件夹/日期/摘要。query 支持中文子串与关键词。", schema(
                "query" to str("搜索关键词"),
                "folder" to str("限定文件夹（可选，如 未分类/点子）"),
                "limit" to int("返回条数，默认 20，最大 50"),
            )) { args ->
            val q = args.optString("query").trim()
            if (q.isEmpty()) return@tool ToolResult(false, "query 不能为空")
            val hits = db.searchNotes(q, args.optString("folder").ifBlank { null },
                args.optInt("limit", 20).coerceIn(1, 50))
            if (hits.isEmpty()) return@tool ToolResult(true, "无匹配笔记。可尝试更短的关键词。")
            val sb = StringBuilder("共 ${hits.size} 条：\n")
            for (n in hits) sb.append("• [${n.id}] 《${n.title}》 folder=${n.folder} ${fmt(n.createTs)} 长度${n.plainLength}\n  ${n.snippet(100)}\n")
            ToolResult(true, sb.toString())
        })

        r.register(tool(
            "note_read", "读取一篇笔记的完整原文（Markdown）。", schema(
                "noteId" to str("笔记 id，来自 note_search/note_list"),
            ), required = listOf("noteId")) { args ->
            val n = db.getNote(args.optString("noteId"))
            if (n == null) ToolResult(false, "笔记不存在: ${args.optString("noteId")}")
            else ToolResult(true, "《${n.title}》 id=${n.id} folder=${n.folder} 创建=${fmt(n.createTs)}\n分类=${n.aiCategory ?: "未分类"} 标签=${n.aiTags}\n----\n${n.content}")
        })

        r.register(tool(
            "note_list", "按文件夹/分类列出笔记（标题+摘要，不含全文）。", schema(
                "folder" to str("文件夹名（可选）"),
                "onlyUnclassified" to bool("只看未分类的笔记"),
                "offset" to int("偏移量，默认 0"),
                "limit" to int("条数，默认 20，最大 60"),
                "order" to str("create_desc(默认) / create_asc / modify_desc / len_asc(短笔记优先)"),
            )) { args ->
            val list = db.listNotes(
                args.optString("folder").ifBlank { null },
                args.optInt("offset", 0), args.optInt("limit", 20).coerceIn(1, 60),
                args.optString("order", "create_desc"), args.optBoolean("onlyUnclassified", false))
            if (list.isEmpty()) return@tool ToolResult(true, "该范围内无笔记（offset 可能超界，可用 note_stats 查看分布）。")
            val sb = StringBuilder("共 ${list.size} 条：\n")
            for (n in list) sb.append("• [${n.id}] 《${n.title}》 folder=${n.folder} ${fmt(n.createTs)} 长度${n.plainLength}${if (n.classified) " 已分类" else ""}\n  ${n.snippet(80)}\n")
            ToolResult(true, sb.toString())
        })

        r.register(tool(
            "note_stats", "笔记库总览：总数、文件夹分布、时间跨度、分类进度、知识库/卡片/草稿统计。", schema()
        ) { _ ->
            val (done, total, byCat) = db.classifiedProgress()
            val range = db.dateRange()
            val cats = if (byCat.isEmpty()) "（尚未分类）" else byCat.entries.joinToString { "${it.key}:${it.value}" }
            ToolResult(true, "总笔记数: $total\n文件夹分布: ${db.folderCounts().entries.joinToString { "${it.key}:${it.value}" }}\n" +
                    "时间跨度: ${range?.let { "${fmt(it.first)} ~ ${fmt(it.second)}" } ?: "无"}\n" +
                    "分类进度: $done/$total\n已分类分布: $cats\n" +
                    "知识库页面: ${db.kbCount()}  每日卡片: ${db.cardCount()}  草稿: ${db.draftCount()}")
        })

        r.register(tool(
            "note_tag", "写入一条笔记的分类结论（不动原文，只写元数据）。", schema(
                "noteId" to str("笔记 id"),
                "category" to str("类别名，必须来自 taxonomy_get 里的类别"),
                "tags" to strArr("3~6 个关键词标签"),
                "reason" to str("一句话理由"),
            ), required = listOf("noteId", "category")) { args ->
            val id = args.optString("noteId")
            val ok = db.tagNote(id, args.optString("category"),
                args.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                args.optString("reason", ""))
            if (ok) ToolResult(true, "已分类: $id → ${args.optString("category")}")
            else ToolResult(false, "笔记不存在: $id")
        })

        r.register(tool(
            "taxonomy_get", "读取当前分类体系（类别名与描述）。", schema()
        ) { _ ->
            val t = db.taxonomyJson()
            if (t.isBlank()) ToolResult(true, "尚未建立分类体系。请先调研后用 taxonomy_set 创建。")
            else ToolResult(true, t)
        })

        r.register(tool(
            "taxonomy_set", "创建/替换分类体系。类别数 6~14 个，必须有且仅有一个兜底类别「其他」，并参考现有文件夹名。", schema(
                "categories" to JSONObject().put("type", "array").put("description", "类别数组").put("items",
                    JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("name", str("类别名"))
                        .put("description", str("该类别收什么、不收什么（一句话）")))),
            ), required = listOf("categories")) { args ->
            val arr = args.optJSONArray("categories") ?: return@tool ToolResult(false, "categories 缺失")
            val names = HashSet<String>()
            for (i in 0 until arr.length()) {
                val name = arr.getJSONObject(i).optString("name").trim()
                if (name.isEmpty() || !names.add(name)) return@tool ToolResult(false, "类别名重复或为空: $name")
            }
            db.setTaxonomyJson(JSONObject().put("updatedAt", System.currentTimeMillis())
                .put("categories", arr).toString())
            ToolResult(true, "分类体系已保存，共 ${arr.length()} 类")
        })
    }

    fun registerKbTools(r: ToolRegistry, db: Db) {
        r.register(tool(
            "kb_write", "创建/更新一个知识库（wiki）页面。body 用 Markdown；引用笔记写成 [标题](yunque://note/<id>)；页面间引用写成 [[slug|显示名]]。slug=_index 时为总目录页。", schema(
                "slug" to str("页面 slug，小写英文与连字符，如 deepseek-local-deploy"),
                "title" to str("页面标题"),
                "category" to str("页面类别：concept/entity/topic/work/person/checklist/_meta"),
                "body" to str("页面正文 Markdown"),
                "sources" to strArr("来源笔记 id 数组（可空）"),
            ), required = listOf("slug", "title", "category", "body")) { args ->
            val slug = args.optString("slug").trim()
            if (slug.isEmpty()) return@tool ToolResult(false, "slug 不能为空")
            db.kbUpsert(slug, args.optString("title"), args.optString("category", "topic"),
                args.optString("body"), args.optJSONArray("sources")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList())
            ToolResult(true, "已写入知识库页面: $slug（总计 ${db.kbCount()} 页）")
        })

        r.register(tool(
            "kb_read", "读取一个知识库页面全文。", schema(
                "slug" to str("页面 slug"),
            ), required = listOf("slug")) { args ->
            val p = db.kbGet(args.optString("slug"))
            if (p == null) ToolResult(false, "页面不存在: ${args.optString("slug")}（可用 kb_list 查看全部）")
            else ToolResult(true, "# ${p.title} (slug=${p.slug} category=${p.category})\n${p.body}")
        })

        r.register(tool(
            "kb_list", "列出知识库页面（slug+标题+类别+摘要）。", schema(
                "category" to str("限定类别（可选）"),
            )) { args ->
            val pages = db.kbList(args.optString("category").ifBlank { null })
            if (pages.isEmpty()) ToolResult(true, "知识库为空。")
            else ToolResult(true, "共 ${pages.size} 页：\n" + pages.joinToString("\n") {
                "• [${it.slug}] ${it.title} (${it.category}) ${it.body.replace(Regex("\\s+"), " ").take(60)}"
            })
        })

        r.register(tool(
            "kb_search", "在知识库页面中全文搜索。", schema(
                "query" to str("关键词"),
            ), required = listOf("query")) { args ->
            val hits = db.kbSearch(args.optString("query"))
            if (hits.isEmpty()) ToolResult(true, "知识库中无匹配。")
            else ToolResult(true, hits.joinToString("\n") { "[${it.slug}] ${it.title} — ${it.body.replace(Regex("\\s+"), " ").take(80)}" })
        })

        r.register(tool(
            "kb_log", "向知识库日志（log.md）追加一条记录：ingest/lint/query 等操作。", schema(
                "operation" to str("操作类型，如 ingest/lint/query"),
                "summary" to str("一句话记录做了什么"),
            ), required = listOf("operation", "summary")) { args ->
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
            val old = db.kbGet("_log")?.body ?: "# 知识库日志\n"
            db.kbUpsert("_log", "log", "_meta", old + "\n## [$ts] ${args.optString("operation")} | ${args.optString("summary")}\n", emptyList())
            ToolResult(true, "已记录")
        })
    }

    fun registerDraftTools(r: ToolRegistry, db: Db) {
        r.register(tool(
            "draft_write", "保存一篇「扩写/统合」草稿。绝不修改原笔记——草稿独立存放，文末必须附原文链接清单。", schema(
                "kind" to str("expand(扩充)/merge(统合)/idea(新想法)"),
                "title" to str("草稿标题"),
                "body" to str("正文 Markdown。引用笔记写成 [标题](yunque://note/<id>)"),
                "sourceIds" to strArr("来源笔记 id 数组"),
            ), required = listOf("kind", "title", "body")) { args ->
            val id = db.draftAdd(args.optString("kind"), args.optString("title"), args.optString("body"),
                args.optJSONArray("sourceIds")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList())
            ToolResult(true, "草稿已保存（id=$id，共 ${db.draftCount()} 篇）。")
        })

        r.register(tool(
            "draft_list", "列出已保存的草稿（标题+类型+来源数），用于避免重复统合。", schema(
                "limit" to int("条数，默认 50"),
            )) { args ->
            val list = db.draftList(args.optInt("limit", 50))
            if (list.isEmpty()) ToolResult(true, "还没有草稿。")
            else ToolResult(true, "共 ${list.size} 篇：\n" + list.joinToString("\n") {
                "• #${it.id} [${it.kind}] ${it.title}（来源 ${it.sourceIds.size} 篇: ${it.sourceIds.joinToString(",")}）"
            })
        })
    }

    fun registerCardTools(r: ToolRegistry, db: Db, today: String) {
        r.register(tool(
            "card_submit", "提交今日灵感卡片（每天最多一张）。body 为卡片正文（≤140 字，尽量摘录原句保原味），comment ≤60 字。", schema(
                "title" to str("卡片标题，可带出处笔记标题"),
                "body" to str("卡片正文 ≤140 字，扫一眼能进入大脑"),
                "sourceNoteId" to str("来源笔记 id"),
                "comment" to str("为什么值得再看一眼 ≤60 字"),
            ), required = listOf("title", "body", "sourceNoteId", "comment")) { args ->
            val body = args.optString("body")
            if (body.length > 200) return@tool ToolResult(false, "body 超长（${body.length} 字），请压缩到 140 字以内")
            db.cardSave(Card(today, args.optString("title"), body, args.optString("sourceNoteId"), args.optString("comment"), System.currentTimeMillis()))
            ToolResult(true, "今日卡片已提交 ✓")
        })

        r.register(tool(
            "card_recent", "查看最近几天的卡片，避免重复选材。", schema()
        ) { _ ->
            val list = db.cardRecent(7)
            if (list.isEmpty()) ToolResult(true, "近 7 天无卡片记录。")
            else ToolResult(true, "近期卡片（勿重复选材）：\n" + list.joinToString("\n") { "• ${it.day} 《${it.title}》 来自笔记 ${it.sourceNoteId}" })
        })
    }

    fun registerWebTools(r: ToolRegistry) {
        r.register(tool(
            "web_search", "联网搜索（遇到不认识的概念/事物/新技术时先用它）。返回标题+链接+摘要。", schema(
                "query" to str("搜索词"),
                "limit" to int("结果数，默认 8"),
            ), required = listOf("query")) { args ->
            val q = args.optString("query").trim()
            if (q.isEmpty()) return@tool ToolResult(false, "query 不能为空")
            val limit = args.optInt("limit", 8).coerceIn(1, 15)
            val results = bingSearch(q, limit).ifEmpty { ddgSearch(q, limit) }
            if (results.isEmpty()) ToolResult(false, "搜索无结果或被限制，可稍后重试或换关键词。")
            else ToolResult(true, results.joinToString("\n\n") { "【${it.first}】\n${it.second}\n${it.third}" })
        })

        r.register(tool(
            "web_fetch", "抓取一个网页并提取正文文本（自动去标签），用于看搜索结果详情。", schema(
                "url" to str("http/https 链接"),
            ), required = listOf("url")) { args ->
            val url = args.optString("url").trim()
            if (!url.startsWith("http")) return@tool ToolResult(false, "url 非法")
            try {
                val req = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                    .get().build()
                http.newCall(req).execute().use { resp ->
                    if (resp.code != 200) return@tool ToolResult(false, "HTTP ${resp.code}")
                    val bodyStr = resp.body?.string() ?: ""
                    val text = stripHtml(bodyStr)
                    ToolResult(true, ToolRegistry.truncate(text.ifBlank { "（页面无可提取文本）" }, 5000))
                }
            } catch (e: Exception) {
                ToolResult(false, "抓取失败: ${e.message}")
            }
        })
    }

    fun registerJobTool(r: ToolRegistry, db: Db, taskId: String?, jobName: String) {
        if (taskId == null) return
        r.register(tool(
            "job_update", "汇报任务进度（显示在任务台）。每完成一个大步骤调用一次。", schema(
                "progress" to int("已完成数"),
                "total" to int("总数"),
                "detail" to str("当前在做什么（一句话）"),
            )) { args ->
            db.taskUpdate(taskId, jobName, "running",
                if (args.has("progress")) args.optInt("progress") else null,
                if (args.has("total")) args.optInt("total") else null,
                args.optString("detail", "").ifBlank { null })
            ToolResult(true, "进度已更新")
        })
    }

    // ---------- web 抓取实现 ----------

    private fun stripHtml(html: String): String {
        var s = html
        s = Regex("(?is)<(script|style|noscript|svg|iframe)[^>]*>.*?</\\1>").replace(s, " ")
        s = Regex("(?is)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>|</tr>").replace(s, "\n")
        s = Regex("(?s)<[^>]+>").replace(s, " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&ldquo;", "“").replace("&rdquo;", "”")
        return Regex("[ \\t\\x0B\\f\\r]+").replace(s, " ").let { Regex("\\n\\s*\\n+").replace(it, "\n") }.trim()
    }

    private fun bingSearch(query: String, limit: Int): List<Triple<String, String, String>> {
        return try {
            val req = Request.Builder()
                .url("https://cn.bing.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&count=$limit&mkt=zh-CN")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9").get().build()
            http.newCall(req).execute().use { resp ->
                if (resp.code != 200) return emptyList()
                val html = resp.body?.string() ?: return emptyList()
                val out = ArrayList<Triple<String, String, String>>()
                val rx = Regex("(?s)<li class=\"b_algo\".*?</li>")
                for (m in rx.findAll(html)) {
                    if (out.size >= limit) break
                    val block = m.value
                    val title = Regex("(?s)<h2[^>]*>.*?<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>").find(block) ?: continue
                    val url = title.groupValues[1].replace("&amp;", "&")
                    val t = stripHtml(title.groupValues[2]).trim()
                    val snip = Regex("(?s)<p[^>]*>(.*?)</p>").findAll(block).map { stripHtml(it.groupValues[1]).trim() }
                        .firstOrNull { it.length > 10 } ?: ""
                    if (t.isNotBlank()) out.add(Triple(t, url, snip))
                }
                out
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun ddgSearch(query: String, limit: Int): List<Triple<String, String, String>> {
        return try {
            val req = Request.Builder()
                .url("https://lite.duckduckgo.com/lite/?q=" + java.net.URLEncoder.encode(query, "UTF-8"))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                .get().build()
            http.newCall(req).execute().use { resp ->
                if (resp.code != 200) return emptyList()
                val html = resp.body?.string() ?: return emptyList()
                val out = ArrayList<Triple<String, String, String>>()
                val rx = Regex("(?s)<a[^>]*class=\"result-link\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
                for (m in rx.findAll(html)) {
                    if (out.size >= limit) break
                    val url = m.groupValues[1].replace("&amp;", "&")
                    val t = stripHtml(m.groupValues[2]).trim()
                    if (t.isNotBlank()) out.add(Triple(t, url, ""))
                }
                out
            }
        } catch (e: Exception) { emptyList() }
    }
}
