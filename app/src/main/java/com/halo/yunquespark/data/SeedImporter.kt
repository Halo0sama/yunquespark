package com.halo.yunquespark.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 首次启动把打包在 assets 里的 1694 篇历史笔记导入本地库（之后由同步引擎接管增量）。 */
object SeedImporter {

    suspend fun importIfNeeded(context: Context, db: Db, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) {
            // 种子资产不存在（开源构建/自定义数据）时优雅跳过，笔记完全由云同步提供
            if (!runCatching { context.assets.open("seed/notes.jsonl").close() }.isSuccess) {
                db.setSetting("seed_imported", "v2")
                return@withContext -1
            }
            // v2 = 已导入且已补账本。老安装（"1"）只补 ledger 基线，不重写笔记（保护 AI 分类元数据）。
            val state = db.getSetting("seed_imported")
            if (state == "v2") return@withContext -1
            val onlyLedger = state == "1"
            var count = 0
            val lines = mutableListOf<String>()
            context.assets.open("seed/notes.jsonl").bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isNotBlank()) lines.add(line)
                    if (lines.size >= 200) {
                        db.writableDatabase.beginTransaction()
                        try {
                            if (onlyLedger) { for (l in lines) ledgerOne(db, l) } else { for (l in lines) importOne(db, l) }
                            count += lines.size
                            db.writableDatabase.setTransactionSuccessful()
                        } finally { db.writableDatabase.endTransaction() }
                        lines.clear()
                        onProgress(count, 1694)
                    }
                }
            }
            if (lines.isNotEmpty()) {
                db.writableDatabase.beginTransaction()
                try {
                    if (onlyLedger) { for (l in lines) ledgerOne(db, l) } else { for (l in lines) importOne(db, l) }
                    count += lines.size
                    db.writableDatabase.setTransactionSuccessful()
                } finally { db.writableDatabase.endTransaction() }
            }
            db.setSetting("seed_imported", "v2")
            onProgress(count, count)
            count
        }

    private fun importOne(db: Db, line: String) {
        runCatching {
            val o = JSONObject(line)
            val id = o.getString("id")
            val modifyTs = o.optLong("modifyTs", o.optLong("createTs", 0L))
            db.upsertNote(
                id = id,
                title = o.getString("title"),
                folder = o.getString("folder"),
                content = o.getString("content"),
                createTs = o.optLong("createTs", modifyTs),
                modifyTs = modifyTs,
                source = "seed",
            )
            // 种子即"已同步"基线：云同步只拉此后云端的新变更
            if (modifyTs > 0 && db.ledgerGet(id) == null) db.ledgerPut(id, modifyTs)
        }
    }

    /** 老安装补账本：只写 ledger，不触碰 notes（不丢 AI 分类）。 */
    private fun ledgerOne(db: Db, line: String) {
        runCatching {
            val o = JSONObject(line)
            val modifyTs = o.optLong("modifyTs", o.optLong("createTs", 0L))
            val id = o.getString("id")
            if (modifyTs > 0 && db.ledgerGet(id) == null) db.ledgerPut(id, modifyTs)
        }
    }
}
