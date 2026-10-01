package com.halo.yunquespark.ai

import com.halo.yunquespark.data.Card
import com.halo.yunquespark.data.Db
import com.halo.yunquespark.data.Note
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 五个自治任务。骨架只装配工具与规范；生产内容全部由 app 内 AI 通过工具调用自主完成。 */
object Jobs {

    data class Spec(val id: String, val name: String, val desc: String)

    val ALL = listOf(
        Spec("classify", "笔记分类", "建立分类体系并逐篇归类 1700 篇笔记"),
        Spec("consolidate", "碎片统合", "把散落各处的同主题碎片串成完整想法"),
        Spec("kb", "知识库构建", "按 LLM Wiki 模式把笔记沉淀成可复利的知识库"),
        Spec("expand", "灵感扩充", "把一句话火花扩成半成品，附原文链接"),
        Spec("card", "每日卡片", "挑一张今天值得看一眼的灵感卡片"),
    )

    fun spec(id: String): Spec = ALL.first { it.id == id }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

    fun aiClient(db: Db): AnthropicClient {
        val key = db.getSetting("api_key").ifBlank { BuildConfigHelper.key() }
        val base = db.getSetting("api_base").ifBlank { BuildConfigHelper.baseUrl() }
        return AnthropicClient(base, key)
    }

    fun model(db: Db): String =
        db.getSetting("ai_model").ifBlank { BuildConfigHelper.modelFast() }

    /**
     * 运行一个任务。返回 AI 的最终陈述文本。
     * onEvent 供 UI 展示流式过程；isCancelled 支持中途停止。
     */
    suspend fun run(
        db: Db,
        jobId: String,
        onEvent: (AgentEvent) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): String {
        val spec = spec(jobId)
        db.taskUpdate(jobId, spec.name, "running", detail = "启动中…")
        try {
            val registry = ToolRegistry()
            when (jobId) {
                "classify" -> { Tools.registerNoteTools(registry, db); Tools.registerWebTools(registry) }
                "consolidate" -> { Tools.registerNoteTools(registry, db); Tools.registerDraftTools(registry, db); Tools.registerWebTools(registry) }
                "kb" -> { Tools.registerNoteTools(registry, db); Tools.registerKbTools(registry, db); Tools.registerWebTools(registry) }
                "expand" -> { Tools.registerNoteTools(registry, db); Tools.registerDraftTools(registry, db); Tools.registerWebTools(registry) }
                "card" -> { Tools.registerNoteTools(registry, db); Tools.registerKbTools(registry, db); Tools.registerCardTools(registry, db, today()) }
            }
            Tools.registerJobTool(registry, db, jobId, spec.name)

            val system = when (jobId) {
                "classify" -> Prompts.CLASSIFY
                "consolidate" -> Prompts.CONSOLIDATE
                "kb" -> Prompts.KB_BUILD
                "expand" -> Prompts.EXPAND
                "card" -> Prompts.cardSystem(cardExcludeRomance(db))
                else -> Prompts.chatSystem(db)
            }

            val loop = AgentLoop(aiClient(db), registry, system, model(db),
                maxTurns = if (jobId == "card") 16 else 30)

            val kickoff = when (jobId) {
                "card" -> "现在请挑选今天的灵感卡片。先 note_stats 和 card_recent 了解情况，然后按规范选材并 card_submit 提交。"
                else -> "现在开始运行「${spec.name}」任务。先用 note_stats 了解全貌，再按规范推进本轮工作。记住：每完成一个大步骤用 job_update 汇报；本轮做完后用 kb_log 记录。"
            }
            val final = loop.run(kickoff, onEvent, isCancelled)

            db.taskUpdate(jobId, spec.name, "done", detail = summarize(final))
            // AI 产出落库后自动同步到 Obsidian（已配置且开启时）
            com.halo.yunquespark.sync.ObsidianSync.maybeAuto()
            return final
        } catch (e: Exception) {
            val msg = if (e is AiException) e.message ?: "AI 调用失败" else "${e.javaClass.simpleName}: ${e.message}"
            db.taskUpdate(jobId, spec.name, if (e is AiException && e.retryable) "failed" else "error", detail = msg.take(300))
            throw e
        }
    }

    /**
     * 每日卡片：生成失败时从笔记里随机兜底一张，保证打开 app 永远有卡。互斥防并发重跑。
     * 兜底选卡走代码级过滤：感情回忆类内容（主人明确要求不推送）一票否决。
     */
    private val cardMutex = kotlinx.coroutines.sync.Mutex()

    /** 感情/恋情相关内容过滤——主人要求灵感卡片不推送此类回忆（设置里可关）。 */
    private val relationshipRx = Regex(
        "喜欢|表白|告白|分手|恋爱|暗恋|在一起|想她|想他|梦到你|梦见你|梦到她|你离开|离开我|她离开|他离开|异地恋|脱单|前任|心动|女朋友|男朋友|相识的|高考还剩"
    )
    private val safeFolders = setOf("点子", "装梦想的箱子", "造梦", "if", "finish", "备忘录", "观点")

    fun cardExcludeRomance(db: Db): Boolean = db.getSetting("card_exclude_romance", "1") == "1"

    /** 今天这张卡不满意？删掉重选。 */
    suspend fun rerollDailyCard(db: Db, onEvent: (AgentEvent) -> Unit = { _ -> }): Card {
        db.cardDelete(today())
        return ensureDailyCard(db, onEvent)
    }

    suspend fun ensureDailyCard(db: Db, onEvent: (AgentEvent) -> Unit = { _ -> }): Card {
        val day = today()
        db.cardToday(day)?.let { return it }
        val result = cardMutex.withLock {
            db.cardToday(day) ?: run {
                try {
                    run(db, "card", onEvent)
                    db.cardToday(day)
                } catch (e: Exception) {
                    onEvent(AgentEvent.Notice("AI 选卡失败，使用随机兜底卡片: ${e.message}"))
                    null
                } ?: run {
                    // 兜底：优先从灵感/点子类文件夹挑；排除开关打开时过滤感情类内容
                    val exclude = cardExcludeRomance(db)
                    val pool = db.randomNotes(80).asSequence()
                        .filter { it.content.length in 8..400 }
                        .filter { !exclude || !relationshipRx.containsMatchIn(it.title + it.content) }
                        .toList()
                    val n: Note = pool.firstOrNull { it.folder in safeFolders }
                        ?: pool.firstOrNull()
                        ?: db.randomNotes(80).first {
                            it.content.length in 8..400 &&
                                    (!exclude || (it.folder !in setOf("朝朝暮暮", "日记") &&
                                            !relationshipRx.containsMatchIn(it.title + it.content)))
                        }
                    Card(day, n.title.take(30), n.content.replace(Regex("\\s+"), " ").take(140),
                        n.id, "AI 暂时不在，先看一眼你自己写下的这句话。", System.currentTimeMillis())
                }.also { db.cardSave(it) }
            }
        }
        com.halo.yunquespark.sync.ObsidianSync.maybeAuto()
        return result
    }

    private fun summarize(final: String): String =
        final.replace(Regex("\\s+"), " ").take(200).ifBlank { "（无输出）" }
}

/** BuildConfig 的间接层，方便测试与用户覆盖。 */
object BuildConfigHelper {
    fun key(): String = com.halo.yunquespark.BuildConfig.DEFAULT_AI_KEY
    fun baseUrl(): String = com.halo.yunquespark.BuildConfig.AI_BASE_URL
    fun modelFast(): String = com.halo.yunquespark.BuildConfig.AI_MODEL_FAST
    fun modelPro(): String = com.halo.yunquespark.BuildConfig.AI_MODEL_PRO
}
