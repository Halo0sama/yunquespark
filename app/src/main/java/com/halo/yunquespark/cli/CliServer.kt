package com.halo.yunquespark.cli

import android.content.Context
import com.halo.yunquespark.App
import com.halo.yunquespark.ai.JobRunner
import com.halo.yunquespark.ai.Jobs
import com.halo.yunquespark.sync.CloudSync
import com.halo.yunquespark.sync.MinoteClient
import com.halo.yunquespark.sync.MinoteSync
import com.halo.yunquespark.sync.ObsidianSync
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本机控制面（CLI 桥）：NanoHTTPD 只绑 127.0.0.1，外部经 `adb forward tcp:PORT tcp:PORT` 访问。
 *
 *  GET  /health                 存活与基础状态
 *  GET  /stats                  笔记/分类/知识库/草稿/卡片统计
 *  GET  /tasks                  任务状态表
 *  POST /tasks/{id}/run         启动任务（classify/consolidate/kb/expand/card）
 *  POST /tasks/{id}/cancel      停止任务
 *  GET  /task/{id}/wait?ms=N    等待任务离开 running（默认 120s），返回最终状态
 *  POST /sync/cloud             静默云同步（含自动续期与自动整理钩子）
 *  POST /sync/obsidian          立即同步 Obsidian vault
 *  POST /login/cookie           body: {"cookie": "..."}   注入 Cookie（清洗保存）
 *  POST /login/account          body: {"account":"...","password":"..."} 无头登录（阻塞，最长 100s）
 *  GET  /cards?n=7              最近卡片
 *  GET  /note?id=...            读取笔记全文
 *  GET  /kb?slug=...            读取知识库页（缺省列目录）
 */
class CliServer(private val context: Context, private val port: Int) : NanoHTTPD("127.0.0.1", port) {

    companion object {
        const val DEFAULT_PORT = 8721
        @Volatile var instance: CliServer? = null
            private set

        fun start(context: Context, port: Int = DEFAULT_PORT): CliServer =
            CliServer(context, port).apply { start(SOCKET_READ_TIMEOUT, false); instance = this }
    }

    private val db get() = App.instance.db

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        val method = session.method
        return try {
            val body = if (method == Method.POST) readBody(session) else JSONObject()
            val json = route(uri, method, body, session.parameters)
            newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
        } catch (e: HttpError) {
            newFixedLengthResponse(e.status, "application/json", JSONObject().put("error", e.message).toString())
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().put("error", "${e.javaClass.simpleName}: ${e.message}").toString()
            )
        }
    }

    private class HttpError(val status: Response.Status, message: String) : Exception(message)

    private fun readBody(session: IHTTPSession): JSONObject {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val raw = map["postData"] ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun route(uri: String, method: Method, body: JSONObject, params: Map<String, List<String>>): JSONObject = runBlocking {
        val j = JSONObject()
        when {
            uri == "/health" && method == Method.GET -> {
                j.put("ok", true)
                j.put("app", "云雀灵感闪现")
                j.put("notes", db.noteCount())
                j.put("cookieConfigured", db.getSetting("minote_cookie").isNotBlank())
                j.put("obsidianConfigured", ObsidianSync.configured(context))
                j.put("running", JSONArray(JobRunner.running.value))
            }

            uri == "/stats" && method == Method.GET -> {
                val (done, total, byCat) = db.classifiedProgress()
                j.put("notes", db.noteCount())
                j.put("classified", done)
                j.put("total", total)
                j.put("byCategory", JSONObject(byCat))
                j.put("folders", JSONObject(db.folderCounts()))
                j.put("kbPages", db.kbCount())
                j.put("drafts", db.draftCount())
                j.put("cards", db.cardCount())
                j.put("dateRange", db.dateRange()?.let { listOf(it.first, it.second) } ?: JSONObject.NULL)
            }

            uri == "/tasks" && method == Method.GET -> {
                val arr = JSONArray()
                for (t in db.taskAll()) arr.put(JSONObject()
                    .put("id", t.id).put("state", t.state)
                    .put("progress", t.progress).put("total", t.total).put("detail", t.detail))
                for (s in Jobs.ALL) if (arr.length() == 0 || db.taskAll().none { it.id == s.id })
                    arr.put(JSONObject().put("id", s.id).put("state", "未运行"))
                j.put("tasks", arr)
                j.put("running", JSONArray(JobRunner.running.value))
            }

            uri == "/tasks/pipeline/run" && method == Method.POST -> {
                val ids = body.optString("ids", "classify,consolidate,kb,expand")
                    .split(',').mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
                    .filter { id -> Jobs.ALL.any { it.id == id } }
                if (ids.isEmpty()) throw HttpError(Response.Status.BAD_REQUEST, "无有效任务 id")
                val started = JobRunner.startPipeline(db, ids)
                j.put("started", started).put("pipeline", JSONArray(ids))
                    .put("alreadyRunning", !started)
            }

            uri.startsWith("/tasks/") && uri.endsWith("/run") && method == Method.POST -> {
                val id = uri.removePrefix("/tasks/").removeSuffix("/run")
                Jobs.ALL.firstOrNull { it.id == id } ?: throw HttpError(Response.Status.NOT_FOUND, "未知任务: $id")
                val started = JobRunner.start(db, id)
                j.put("started", started)
                j.put("alreadyRunning", !started)
            }

            uri.startsWith("/tasks/") && uri.endsWith("/cancel") && method == Method.POST -> {
                val id = uri.removePrefix("/tasks/").removeSuffix("/cancel")
                JobRunner.cancel(id)
                j.put("cancelled", true)
            }

            uri.startsWith("/task/") && uri.endsWith("/wait") && method == Method.GET -> {
                val id = uri.removePrefix("/task/").removeSuffix("/wait")
                val ms = params["ms"]?.firstOrNull()?.toLongOrNull() ?: 120_000L
                val deadline = System.currentTimeMillis() + ms.coerceIn(1_000, 600_000)
                while (id in JobRunner.running.value && System.currentTimeMillis() < deadline) {
                    withTimeoutOrNull(500) { kotlinx.coroutines.delay(500) }
                }
                val t = db.taskAll().firstOrNull { it.id == id }
                j.put("id", id)
                j.put("running", id in JobRunner.running.value)
                if (t != null) j.put("state", t.state).put("progress", t.progress)
                    .put("total", t.total).put("detail", t.detail)
            }

            uri == "/sync/cloud" && method == Method.POST -> {
                val r = withTimeoutOrNull(600_000) { CloudSync.quiet(context, db) }
                if (r == null) {
                    j.put("ok", false).put("reason", "未配置 Cookie / 同步失败 / 超时(600s)")
                        .put("lastError", db.getSetting("last_sync_err").ifBlank { "未知" })
                } else {
                    db.setSetting("last_sync_err", "")
                    j.put("ok", true).put("enumerated", r.enumerated).put("downloaded", r.downloaded)
                        .put("skipped", r.skipped).put("failed", r.failed)
                }
            }

            uri == "/sync/obsidian" && method == Method.POST -> {
                val r = withTimeoutOrNull(180_000) { ObsidianSync.syncAll(context) }
                if (r == null) j.put("ok", false).put("reason", "超时")
                else j.put("ok", r.error == null).put("exported", r.exported)
                    .put("skipped", r.skipped).put("error", r.error ?: "")
            }

            uri == "/login/cookie" && method == Method.POST -> {
                val cleaned = MinoteClient.cleanCookie(body.optString("cookie"))
                if (cleaned != null) {
                    db.setSetting("minote_cookie", cleaned)
                    db.setSetting("mi_login_err", "")
                    j.put("ok", true).put("length", cleaned.length)
                } else j.put("ok", false).put("error", "未找到 serviceToken")
            }

            uri == "/login/account" && method == Method.POST -> {
                val acct = body.optString("account")
                val pwd = body.optString("password")
                if (acct.isBlank() || pwd.isBlank()) throw HttpError(Response.Status.BAD_REQUEST, "account/password 必填")
                // 无头登录在主线程 Handler 上跑 WebView，这里挂起等待结果（最长 100s）
                val cookie = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        com.halo.yunquespark.sync.MiLogin.login(context, acct, pwd) { cookie, err ->
                            if (cont.isActive) {
                                if (cookie != null) cont.resumeWith(Result.success(cookie))
                                else cont.resumeWith(Result.failure(RuntimeException(err ?: "登录失败")))
                            }
                        }
                    }
                }
                db.setSetting("mi_account", acct)
                db.setSetting("mi_password", pwd)
                db.setSetting("minote_cookie", cookie)
                db.setSetting("mi_login_err", "")
                j.put("ok", true).put("cookieLength", cookie.length)
            }

            uri == "/login/otp" && method == Method.POST -> {
                val code = body.optString("code")
                val ok = com.halo.yunquespark.sync.MiLogin.submitOtp(code.trim())
                j.put("ok", ok).put("waiting", com.halo.yunquespark.sync.MiLogin.waitingForOtp())
                    .put("state", db.getSetting("mi_login_state"))
            }

            uri == "/cards" && method == Method.GET -> {
                val n = (params["n"]?.firstOrNull()?.toIntOrNull() ?: 7).coerceIn(1, 100)
                val arr = JSONArray()
                for (c in db.cardRecent(n)) arr.put(JSONObject()
                    .put("day", c.day).put("title", c.title).put("body", c.body)
                    .put("comment", c.comment).put("favorite", c.favorite)
                    .put("sourceNoteId", c.sourceNoteId))
                j.put("cards", arr)
            }

            uri == "/note" && method == Method.GET -> {
                val id = params["id"]?.firstOrNull()
                    ?: throw HttpError(Response.Status.BAD_REQUEST, "缺少 id")
                val n = db.getNote(id) ?: throw HttpError(Response.Status.NOT_FOUND, "笔记不存在")
                j.put("id", n.id).put("title", n.title).put("folder", n.folder)
                    .put("created", n.createTs).put("content", n.content)
                    .put("aiCategory", n.aiCategory ?: JSONObject.NULL)
            }

            uri == "/kb" && method == Method.GET -> {
                val slug = params["slug"]?.firstOrNull()
                if (slug == null) {
                    val arr = JSONArray()
                    for (p in db.kbList()) arr.put(JSONObject().put("slug", p.slug).put("title", p.title)
                        .put("category", p.category))
                    j.put("pages", arr)
                } else {
                    val p = db.kbGet(slug) ?: throw HttpError(Response.Status.NOT_FOUND, "页面不存在")
                    j.put("slug", p.slug).put("title", p.title).put("category", p.category)
                        .put("body", p.body).put("sources", JSONArray(p.sources))
                }
            }

            else -> throw HttpError(Response.Status.NOT_FOUND, "未知端点: $method $uri")
        }
        j
    }
}
