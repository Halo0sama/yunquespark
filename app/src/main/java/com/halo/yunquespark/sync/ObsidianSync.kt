package com.halo.yunquespark.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.halo.yunquespark.App
import com.halo.yunquespark.data.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 产出同步到手机 Obsidian：SAF 目录授权后，把知识库页面、草稿、每日卡片
 * （以及它们引用的原始笔记）以 Markdown 写入 vault 的 YunqueSpark/ 目录。
 * 策略与云同步一致：只增改不删；按时间戳增量，卡片始终重写（支持编辑后更新）。
 *
 * 目录结构：
 *   YunqueSpark/
 *     index.md              总目录（wiki 双链）
 *     KnowledgeBase/<slug>.md
 *     Drafts/<标题>-<id>.md
 *     Cards/<yyyy-MM-dd>.md
 *     Notes/<标题>-<id尾6>.md   被引用的原始笔记（链接可跳转）
 */
object ObsidianSync {

    data class Report(val exported: Int, val skipped: Int, val error: String? = null)

    fun vaultUri(ctx: Context): String = App.instance.db.getSetting("obsidian_tree_uri")
    fun configured(ctx: Context): Boolean = vaultUri(ctx).isNotBlank()
    fun autoEnabled(ctx: Context): Boolean = App.instance.db.getSetting("obsidian_auto", "1") == "1"

    /** AI 任务/选卡完成后调用：已配置且开了自动同步就后台静默推一次。 */
    fun maybeAuto() {
        val ctx = App.instance
        if (!autoEnabled(ctx) || !configured(ctx)) return
        ctx.scope.launch { runCatching { syncAll(ctx) } }
    }

    suspend fun syncAll(ctx: Context, onLog: (String) -> Unit = {}): Report = withContext(Dispatchers.IO) {
        val db = App.instance.db
        val uriStr = vaultUri(ctx)
        if (uriStr.isBlank()) return@withContext Report(0, 0, "尚未关联 vault")
        val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(uriStr))
        if (tree == null || !tree.canWrite()) return@withContext Report(0, 0, "vault 访问已失效，请重新选择文件夹")

        val root = ensureDir(tree, "YunqueSpark") ?: return@withContext Report(0, 0, "无法创建 YunqueSpark 目录")
        val kbDir = ensureDir(root, "KnowledgeBase") ?: return@withContext Report(0, 0, "无法创建 KnowledgeBase")
        val draftDir = ensureDir(root, "Drafts") ?: return@withContext Report(0, 0, "无法创建 Drafts")
        val cardDir = ensureDir(root, "Cards") ?: return@withContext Report(0, 0, "无法创建 Cards")
        val noteDir = ensureDir(root, "Notes") ?: return@withContext Report(0, 0, "无法创建 Notes")

        onLog("收集被引用的原始笔记…")
        val noteNames = HashMap<String, String>()
        val refs = linkedMapOf<String, Note>()
        val refRx = Regex("""yunque://note/([A-Za-z0-9_]+)""")
        val bodies = buildList {
            for (p in db.kbList()) {
                add(p.body)
                p.sources.forEach { add("yunque://note/$it") }
            }
            for (d in db.draftList(500)) {
                add(d.body)
                d.sourceIds.forEach { add("yunque://note/$it") }
            }
            for (c in db.cardRecent(1000)) add("yunque://note/${c.sourceNoteId}")
        }
        for (b in bodies) for (m in refRx.findAll(b)) {
            val id = m.groupValues[1]
            if (!refs.containsKey(id)) db.getNote(id)?.let { refs[id] = it }
        }

        val state = JSONObject(db.getSetting("obsidian_state", "{}"))
        var exported = 0
        var skipped = 0
        fun needExport(key: String, ts: Long): Boolean =
            if (state.optLong(key, -1L) >= ts) false else { state.put(key, ts); true }

        // 1) 引用的原始笔记（时间戳增量）
        for ((id, n) in refs) {
            val name = sanitize(n.title) + "-" + id.takeLast(6) + ".md"
            noteNames[id] = name.removeSuffix(".md")
            if (!needExport("note:$id", n.modifyTs)) { skipped++; continue }
            val fm = frontmatter(n.title, linkedMapOf(
                "folder" to n.folder, "created" to fmt(n.createTs),
                "aicategory" to (n.aiCategory ?: "").ifBlank { "未分类" }, "id" to id,
                "tags" to "yunque,note"))
            if (writeFile(ctx, noteDir, name, fm + rewrite(n.content, noteNames) + "\n")) exported++ else skipped++
        }

        // 2) 知识库页面（slug 作文件名，[[slug]] 双链可用）
        for (p in db.kbList()) {
            val base = if (p.slug.startsWith("_")) p.slug.drop(1) else p.slug
            val name = base + ".md"
            if (!needExport("kb:" + p.slug, p.updatedTs)) { skipped++; continue }
            val fm = frontmatter(p.title, linkedMapOf(
                "category" to p.category, "updated" to fmt(p.updatedTs), "tags" to "yunque,kb"))
            val srcSection = if (p.sources.isEmpty()) "" else buildString {
                appendLine(); appendLine("---"); appendLine("来源笔记：")
                for (sid in p.sources) {
                    val file = noteNames[sid]
                    if (file != null) appendLine("- [[$file|笔记 ${sid.takeLast(6)}]]")
                    else appendLine("- 笔记 ${sid.takeLast(6)}（未导出）")
                }
            }
            if (writeFile(ctx, kbDir, name, fm + rewrite(p.body, noteNames) + srcSection)) exported++ else skipped++
        }

        // 3) 草稿
        for (d in db.draftList(500)) {
            val name = sanitize(d.title) + "-" + d.id + ".md"
            if (!needExport("draft:" + d.id, d.createdTs)) { skipped++; continue }
            val fm = frontmatter(d.title, linkedMapOf(
                "kind" to d.kind, "created" to fmt(d.createdTs), "tags" to "yunque,draft"))
            if (writeFile(ctx, draftDir, name, fm + rewrite(d.body, noteNames))) exported++ else skipped++
        }

        // 4) 每日卡片（始终重写：支持 app 内编辑后更新）
        for (c in db.cardRecent(1000)) {
            state.put("card:" + c.day, c.createdTs)
            val link = noteNames[c.sourceNoteId]?.let { "[查看原文](Notes/$it.md)" }
                ?: "笔记 ${c.sourceNoteId.takeLast(6)}"
            val md = frontmatter(c.title, linkedMapOf(
                "date" to c.day, "favorite" to c.favorite.toString(), "tags" to "yunque,card")) +
                    c.body + "\n\n> " + c.comment + "\n\n" + link + "\n"
            if (writeFile(ctx, cardDir, c.day + ".md", md)) exported++ else skipped++
        }

        // 5) 总目录
        val idx = buildString {
            appendLine("# 云雀灵感闪现 · 索引")
            appendLine()
            appendLine("> 由「云雀灵感闪现」app 自动同步，请勿在此目录手动改名。最新更新：${fmt(System.currentTimeMillis())}")
            appendLine()
            appendLine("## 知识库")
            for (p in db.kbList()) {
                if (p.slug == "_log") continue
                val base = if (p.slug.startsWith("_")) p.slug.drop(1) else p.slug
                appendLine("- [[$base|${p.title}]]（${p.category}）")
            }
            appendLine()
            appendLine("## 草稿")
            for (d in db.draftList(200)) appendLine("- ${d.title}（${d.kind} · ${fmt(d.createdTs)}）")
            appendLine()
            appendLine("## 近期卡片")
            for (c in db.cardRecent(14)) appendLine("- [[Cards/${c.day}|${c.day} · ${c.title}]]${if (c.favorite) " ❤" else ""}")
        }
        writeFile(ctx, root, "index.md", idx)
        db.setSetting("obsidian_state", state.toString())
        onLog("完成：新写入 $exported，无变化 $skipped")
        Report(exported, skipped)
    }

    // ---------- 工具 ----------

    private fun ensureDir(parent: DocumentFile, name: String): DocumentFile? {
        parent.findFile(name)?.takeIf { it.isDirectory }?.let { return it }
        return runCatching { parent.createDirectory(name) }.getOrNull()
    }

    private fun writeFile(ctx: Context, dir: DocumentFile, name: String, content: String): Boolean {
        return try {
            val file = dir.findFile(name) ?: dir.createFile("text/markdown", name) ?: return false
            ctx.contentResolver.openOutputStream(file.uri, "wt")?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
                os.flush()
            } ?: return false
            true
        } catch (e: Exception) { false }
    }

    /** yunque://note 链接改写为 vault 内相对链接；未导出的退化为纯文本标注。 */
    private fun rewrite(body: String, noteNames: Map<String, String>): String =
        Regex("""\[([^\]]+)]\(yunque://note/([^)]+)\)""").replace(body) { m ->
            val text = m.groupValues[1]
            val id = m.groupValues[2]
            val file = noteNames[id]
            if (file != null) "[$text](Notes/$file.md)" else "$text（笔记 ${id.takeLast(6)}）"
        }

    private fun frontmatter(title: String, fields: LinkedHashMap<String, String>): String = buildString {
        appendLine("---")
        appendLine("title: \"${title.replace("\"", "'")}\"")
        for ((k, v) in fields) if (v.isNotBlank()) appendLine("$k: \"${
            v.replace("\"", "'").replace("\n", " ")}\"")
        appendLine("---")
        appendLine()
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\n\\r#^\\[\\]]"), "").trim().take(60).ifBlank { "未命名" }

    private fun fmt(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ts))
}
