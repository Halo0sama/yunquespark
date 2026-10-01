package com.halo.yunquespark.ai

import com.halo.yunquespark.App
import com.halo.yunquespark.data.Db
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 任务生命周期管理：跑在 Application 级作用域，UI 离开页面不会杀死 AI 任务。
 *
 * 自动整理的触发看「待办」而非「增量」：
 *  - 云同步拉到新笔记 → 立即启动流水线（并发去重兜底）；
 *  - 库里还有未分类存量（含种子导入的 1694 篇）或整理任务从未跑过 → 自动补跑（10 分钟冷却防抖）；
 *  - classify 环节连续多轮（≤3）直到未分类清零或无进展。
 */
object JobRunner {

    private val active = ConcurrentHashMap<String, Job>()

    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running: StateFlow<Set<String>> = _running

    private val _logs = MutableStateFlow<Map<String, String>>(emptyMap())
    val logs: StateFlow<Map<String, String>> = _logs

    private val PIPELINE = listOf("classify", "consolidate", "kb", "expand")
    private const val COOLDOWN_MS = 10 * 60_000L

    fun start(db: Db, jobId: String): Boolean {
        if (jobId in _running.value) return false
        launchTracked(db, listOf(jobId))
        return true
    }

    /** 顺序流水线：依次运行 ids，任一环被取消则整条停止。返回 false 表示全部已在跑。 */
    fun startPipeline(db: Db, jobIds: List<String>): Boolean {
        val fresh = jobIds.filter { it !in _running.value }
        if (fresh.isEmpty()) return false
        launchTracked(db, fresh)
        return true
    }

    /** 同步后自动整理：新笔记立即跑；存量积压（未分类>0 或整理任务从未运行）也跑（带冷却）。 */
    fun autoOrganizeAfterSync(db: Db, report: com.halo.yunquespark.sync.SyncReport?) {
        if (db.getSetting("auto_organize", "1") != "1") return
        if (report != null && report.downloaded > 0) {
            startPipeline(db, PIPELINE)
            return
        }
        autoOrganizeBacklog(db)
    }

    /** 打开 app / 推送闹钟等场景调用：存在存量待办就补跑流水线。 */
    fun autoOrganizeBacklog(db: Db) {
        if (db.getSetting("auto_organize", "1") != "1") return
        if (_running.value.isNotEmpty()) return
        val (done, total, _) = db.classifiedProgress()
        val unclassifiedLeft = total - done
        val organizeEverRan = db.taskAll().any { it.id != "card" && it.id != "classify" }
        if (unclassifiedLeft <= 0 && organizeEverRan) return
        val last = db.getSetting("pipeline_last_start").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last < COOLDOWN_MS) return
        db.setSetting("pipeline_last_start", System.currentTimeMillis().toString())
        startPipeline(db, PIPELINE)
    }

    fun cancel(jobId: String) {
        active[jobId]?.cancel()
    }

    private fun launchTracked(db: Db, ids: List<String>) {
        db.setSetting("pipeline_last_start", System.currentTimeMillis().toString())
        _running.value = _running.value + ids.toSet()
        val job = App.instance.scope.launch {
            try {
                for (id in ids) {
                    val spec = Jobs.spec(id)
                    if (id == "classify") {
                        // 存量消化：连续多轮（≤3）直到未分类清零或一轮无进展
                        var round = 0
                        var prevDone = -1
                        while (round < 3) {
                            val done = db.classifiedProgress().first
                            if (done >= db.noteCount() || done == prevDone) break
                            prevDone = done
                            round++
                            appendLog(id, "── ${spec.name} 第 $round 轮（未分类余 ${db.noteCount() - done}）──")
                            try {
                                Jobs.run(db, id, onEvent = logger(id))
                                appendLog(id, "── ${spec.name} 第 $round 轮结束 ──")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                appendLog(id, "✗ ${e.message}")
                                break
                            }
                        }
                    } else {
                        appendLog(id, "── ${spec.name} 启动 ──")
                        try {
                            Jobs.run(db, id, onEvent = logger(id))
                            appendLog(id, "── ${spec.name} 结束 ──")
                        } catch (e: CancellationException) {
                            db.taskUpdate(id, spec.name, "stopped", detail = "用户停止")
                            throw e
                        } catch (e: Exception) {
                            appendLog(id, "✗ ${e.message}")
                        }
                    }
                }
            } catch (e: CancellationException) {
                appendLog("classify", "── 流水线已停止 ──")
            } finally {
                _running.value = _running.value - ids.toSet()
                ids.forEach { active.remove(it) }
            }
        }
        ids.forEach { active[it] = job }
    }

    private fun logger(id: String): (AgentEvent) -> Unit = { ev ->
        when (ev) {
            is AgentEvent.ToolStart -> appendLog(id, "⚙ ${ev.name}(${ev.args.toString().take(90)})")
            is AgentEvent.ToolEnd -> appendLog(id, "  → ${if (ev.ok) "✓" else "✗"} ${ev.summary.take(110)}")
            is AgentEvent.Notice -> appendLog(id, "⚠ ${ev.message}")
            else -> {}
        }
    }

    private fun appendLog(jobId: String, line: String) {
        _logs.value = _logs.value + (jobId to ((_logs.value[jobId] ?: "") + line + "\n"))
    }
}
