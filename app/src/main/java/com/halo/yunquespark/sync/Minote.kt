package com.halo.yunquespark.sync

import android.content.Context
import com.halo.yunquespark.App
import com.halo.yunquespark.data.Db
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Cookie 失效（服务端返回 401 信封）。上层应引导用户在设置里重新登录。 */
class MinoteAuthException(message: String) : Exception(message)

/**
 * 小米笔记云 v2 接口客户端。
 * 协议细节全部来自《小米笔记同步技术完全指南》（2026-09 实测）：
 *  - /note/v2/hasData 校验 Cookie（旧链路 token 会被 v2 拒绝，必须用这个强校验）
 *  - 双路径枚举（全量游标 + 逐文件夹）取并集，对抗 lastPage 谎报与游标跳条
 *  - extraInfo 是 JSON 字符串，标题首选 extraInfo.title
 *  - 附件引用是 com.miui.notes 假 URL，按 digest→fileId 改写为本地引用
 */
class MinoteClient(private val cookieProvider: () -> String) {

    companion object {
        const val BASE = "https://i.mi.com"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/73.0.3683.103 Safari/537.36"
        val NEEDED_COOKIES = listOf(
            "serviceToken", "userId", "i.mi.com_ph", "i.mi.com_slh",
            "i.mi.com_isvalid_servicetoken", "i.mi.com_istrudev",
        )

        /** 从任意 Cookie 串提取所需的 6 项并清洗（值去除空白/控制字符——OkHttp 拒绝带 \n 的 Header）。 */
        fun cleanCookie(raw: String): String? = NEEDED_COOKIES.mapNotNull { name ->
            Regex("$name=([^;\\s]+)").find(raw)?.groupValues?.get(1)?.trim()?.let { "$name=$it" }
        }.takeIf { list -> list.size >= 3 }?.joinToString("; ")
            ?.takeIf { it.contains("serviceToken") }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun get(url: String): Pair<Int, String> {
        val req = Request.Builder()
            .url(url)
            .header("Cookie", cookieProvider())
            .header("User-Agent", UA)
            .header("Accept-Encoding", "identity")
            .get().build()
        http.newCall(req).execute().use { resp ->
            return resp.code to (resp.body?.string() ?: "")
        }
    }

    private fun api(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val sb = StringBuilder(BASE).append(path).append("?ts=").append(System.currentTimeMillis())
        for ((k, v) in params) sb.append('&').append(k).append('=').append(URLEncoder.encode(v, "UTF-8"))
        val (code, body) = get(sb.toString())
        if (code != 200) {
            if (body.contains("\"R\":401") || code == 401) throw MinoteAuthException("Cookie 已失效（HTTP $code）")
            throw RuntimeException("$path -> HTTP $code: ${body.take(200)}")
        }
        val json = JSONObject(body)
        if (json.optString("result") != "ok" || json.isNull("data")) {
            if (json.optString("R") == "401") throw MinoteAuthException("Cookie 已失效")
            throw RuntimeException("$path -> ${body.take(200)}")
        }
        return json.getJSONObject("data")
    }

    /** 强校验：v2 接口可达才认为 Cookie 有效。 */
    fun hasData(): Boolean = try {
        api("/note/v2/hasData"); true
    } catch (e: MinoteAuthException) {
        throw e
    } catch (e: Exception) {
        false
    }

    data class Entry(
        val id: String, val type: String, val modifyDate: Long, val createDate: Long,
        val folderId: String, val title: String, val snippet: String,
    )

    /** 双路径枚举（路径A全量游标 + 路径B逐文件夹），按 id 取并集。 */
    fun enumerate(): Pair<List<Entry>, Map<String, String>> {
        val seen = HashSet<String>()
        val notes = ArrayList<Entry>()
        val folders = LinkedHashMap<String, String>()

        // 路径 A：全量游标
        var tag = ""
        var guard = 0
        while (guard++ < 200) {
            val d = api("/note/v2/full/page", mapOf("syncTag" to tag, "limit" to "200"))
            val folderArr = d.optJSONArray("folders")
            if (folderArr != null) for (i in 0 until folderArr.length()) {
                val f = folderArr.getJSONObject(i)
                runCatching { folders.putIfAbsent(f.getString("id"), f.optString("subject")) }
            }
            val entries = d.optJSONArray("entries") ?: org.json.JSONArray()
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                collectEntry(e, seen, notes)
            }
            if (d.optBoolean("lastPage")) {
                // 不信任 lastPage —— 用游标再确认一次
                val nxt = d.optString("syncTag") ?: ""
                if (nxt.isEmpty()) break
                val confirm = api("/note/v2/full/page", mapOf("syncTag" to nxt, "limit" to "200"))
                val ce = confirm.optJSONArray("entries") ?: org.json.JSONArray()
                if (ce.length() == 0) break
                for (i in 0 until ce.length()) collectEntry(ce.getJSONObject(i), seen, notes)
                tag = confirm.optString("syncTag") ?: ""
                if (tag.isEmpty()) break
            } else {
                tag = d.optString("syncTag") ?: ""
                if (tag.isEmpty()) break
            }
        }

        // 路径 B：逐文件夹兜底（含未分类 folderId=0）
        for (fid in folders.keys + "0") {
            var cursor = ""
            var g2 = 0
            while (g2++ < 60) {
                val d = try {
                    api("/note/v2/full/folder", mapOf("folderId" to fid, "noteId" to cursor, "limit" to "200"))
                } catch (e: Exception) { break }
                val entries = d.optJSONArray("entries") ?: org.json.JSONArray()
                for (i in 0 until entries.length()) collectEntry(entries.getJSONObject(i), seen, notes)
                if (d.optBoolean("lastPage") || d.optString("lastNoteId").isEmpty()) break
                val nxt = d.optString("lastNoteId")
                if (nxt == cursor) break
                cursor = nxt
            }
        }
        return notes to folders
    }

    private fun collectEntry(e: JSONObject, seen: MutableSet<String>, out: MutableList<Entry>) {
        runCatching {
            val id = e.optString("id")
            val type = e.optString("type")
            if (id.isEmpty() || (type != "mdNote" && type != "note")) return
            if (!seen.add(id)) return
            val extra = runCatching { JSONObject(e.optString("extraInfo", "{}")) }.getOrDefault(JSONObject())
            out.add(Entry(
                id = id, type = type,
                modifyDate = e.optLong("modifyDate", 0L),
                createDate = e.optLong("createDate", 0L),
                folderId = e.optString("folderId", "0"),
                title = extra.optString("title", "").ifBlank { e.optString("snippet").lineSequence().firstOrNull() ?: "" },
                snippet = e.optString("snippet", ""),
            ))
        }
    }

    /** 单篇详情：Markdown 正文 + 附件表 setting.data[] */
    fun detail(id: String): Pair<String, List<Attachment>> {
        val d = api("/note/v2/note/$id/")
        val entry = d.getJSONObject("entry")
        val content = entry.optString("content", "")
        val atts = ArrayList<Attachment>()
        val arr = entry.optJSONObject("setting")?.optJSONArray("data")
        if (arr != null) for (i in 0 until arr.length()) {
            runCatching {
                val a = arr.getJSONObject(i)
                atts.add(Attachment(
                    fileId = a.getString("fileId"),
                    mime = a.optString("mimeType", "application/octet-stream"),
                    digest = a.optString("digest", ""),
                ))
            }
        }
        return content to atts
    }

    data class Attachment(val fileId: String, val mime: String, val digest: String) {
        val ext: String get() = mime.substringAfter('/', "bin")
    }

    fun downloadFile(fileId: String): ByteArray {
        val (code, body) = get("$BASE/file/full?type=note_img&fileid=$fileId")
        if (code != 200) throw RuntimeException("file/full HTTP $code")
        return body.toByteArray(Charsets.ISO_8859_1)
    }
}

data class SyncReport(
    val enumerated: Int, val downloaded: Int, val skipped: Int, val failed: Int, val folders: Map<String, String>,
)

/** 静默云同步入口：供打开 app / 推送闹钟 / 手动按钮共用。
 *  Cookie 失效（401）且本机存有账号凭据时，自动重新登录续期并重试一次。 */
object CloudSync {
    suspend fun quiet(context: Context, db: com.halo.yunquespark.data.Db, onProgress: (String) -> Unit = {}): SyncReport? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val acct = db.getSetting("mi_account")
            val pwd = db.getSetting("mi_password")
            if (db.getSetting("minote_cookie").isBlank() && acct.isBlank()) return@withContext null
            try {
                if (db.getSetting("minote_cookie").isBlank()) throw MinoteAuthException("no cookie")
                MinoteSync(context, db, MinoteClient { db.getSetting("minote_cookie") }).sync(onProgress)
            } catch (e: MinoteAuthException) {
                // 401：用本机凭据自动续期后重试
                if (acct.isBlank() || pwd.isBlank()) {
                    db.setSetting("mi_login_err", "Cookie 已失效，请在设置里账号登录一次")
                    db.setSetting("last_sync_err", "401 且无本机凭据")
                    return@withContext null
                }
                val fresh = runCatching { MiLogin.loginSuspend(context, acct, pwd) }.getOrElse {
                    db.setSetting("mi_login_err", "自动续期失败：${it.message}")
                    db.setSetting("last_sync_err", "续期失败: ${it.message}")
                    return@withContext null
                }
                db.setSetting("minote_cookie", fresh)
                db.setSetting("mi_login_err", "")
                runCatching {
                    MinoteSync(context, db, MinoteClient { db.getSetting("minote_cookie") }).sync(onProgress)
                }.onFailure { db.setSetting("last_sync_err", "重试失败: ${it.message}") }
                    .getOrNull()
            } catch (e: Exception) {
                db.setSetting("last_sync_err", "${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
}

/**
 * 同步引擎：枚举 → 对照账本增量下载详情与附件 → 改写附件引用 → 落库。
 * 策略：只增改不删（云端删除不触发本地删除，本地即备份）。
 */
class MinoteSync(private val context: Context, private val db: Db, private val client: MinoteClient) {

    suspend fun sync(onProgress: (String) -> Unit): SyncReport {
        onProgress("校验 Cookie…")
        client.hasData() // 失效直接抛 MinoteAuthException

        onProgress("枚举云端笔记（双路径）…")
        val (entries, folders) = client.enumerate()
        var downloaded = 0; var skipped = 0; var failed = 0

        for ((idx, e) in entries.withIndex()) {
            if (idx % 20 == 0) onProgress("同步中 ${idx + 1}/${entries.size} …")
            val prev = db.ledgerGet(e.id)
            if (prev != null && e.modifyDate <= prev && db.getNote(e.id) != null) { skipped++; continue }

            try {
                val (content, atts) = client.detail(e.id)
                // 附件：digest → fileId.ext，下载到 attachments/
                val digestMap = HashMap<String, String>()
                for (a in atts) {
                    val name = "${a.fileId}.${a.ext}"
                    digestMap[a.digest] = name
                    val target = File(File(context.filesDir, "attachments"), name)
                    if (!target.exists()) {
                        target.parentFile?.mkdirs()
                        runCatching { target.writeBytes(client.downloadFile(a.fileId)) }
                    }
                }
                // 改写假 URL 引用
                val rewritten = Regex("!?" + "\\" + "[^\\]" + "*" + "\\]" + "\\(https?://com\\.miui\\.notes/(?:image|audio)/([^)?]+)[^)]*\\)")
                    .replace(content) { m ->
                        val digest = m.groupValues[1]
                        val local = digestMap[digest]
                        if (local != null) "![](attachments/$local)" else m.value
                    }
                val folder = folders[e.folderId] ?: if (e.folderId == "0") "未分类" else "未分类"
                val title = e.title.ifBlank { e.snippet.lineSequence().firstOrNull().orEmpty() }
                    .ifBlank { "未命名笔记_${e.id}" }
                db.upsertNote(e.id, sanitize(title), folder, rewritten, e.createDate, e.modifyDate, "cloud")
                db.ledgerPut(e.id, e.modifyDate)
                downloaded++
            } catch (ex: Exception) {
                failed++
            }
        }
        return SyncReport(entries.size, downloaded, skipped, failed, folders)
    }

    private fun sanitize(t: String) =
        t.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "").trim().take(120).ifBlank { "未命名笔记" }
}
