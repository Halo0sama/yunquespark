package com.halo.yunquespark.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

/** 原始笔记（不可变层）。aiCategory/aiTags/aiReason 是 app 内 AI 写入的分类结论，不动原文。 */
data class Note(
    val id: String, val title: String, val folder: String, val content: String,
    val createTs: Long, val modifyTs: Long,
    val aiCategory: String?, val aiTags: List<String>, val aiReason: String?, val classified: Boolean,
) {
    val plainLength: Int get() = content.length
    fun snippet(max: Int = 120): String {
        val t = content.replace(Regex("![^\\n]*"), " ").replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.take(max) + "…"
    }
}

data class KbPage(
    val slug: String, val title: String, val category: String, val body: String,
    val sources: List<String>, val updatedTs: Long,
)

data class Card(
    val day: String, val title: String, val body: String,
    val sourceNoteId: String, val comment: String, val createdTs: Long,
    val favorite: Boolean = false,
)

data class Draft(
    val id: Long, val kind: String, val title: String, val body: String,
    val sourceIds: List<String>, val createdTs: Long,
)

data class TaskRow(
    val id: String, val name: String, val state: String,
    val progress: Int, val total: Int, val detail: String, val updatedTs: Long,
)

class Db(context: Context) : SQLiteOpenHelper(context, "yunque.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE notes(
            id TEXT PRIMARY KEY, title TEXT NOT NULL, folder TEXT NOT NULL, content TEXT NOT NULL,
            createTs INTEGER, modifyTs INTEGER, aiCategory TEXT, aiTags TEXT,
            aiReason TEXT, classified INTEGER DEFAULT 0, source TEXT DEFAULT 'seed')""")
        db.execSQL("""CREATE VIRTUAL TABLE notes_fts USING fts4(noteId UNINDEXED, title, content, tokenize=unicode61)""")
        db.execSQL("""CREATE TABLE kb_pages(
            slug TEXT PRIMARY KEY, title TEXT, category TEXT, body TEXT,
            sources TEXT, updatedTs INTEGER)""")
        db.execSQL("""CREATE TABLE cards(
            day TEXT PRIMARY KEY, title TEXT, body TEXT, sourceNoteId TEXT, comment TEXT,
            createdTs INTEGER, favorite INTEGER DEFAULT 0)""")
        db.execSQL("""CREATE TABLE drafts(
            id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, title TEXT, body TEXT,
            sourceIds TEXT, createdTs INTEGER)""")
        db.execSQL("""CREATE TABLE chat(
            id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, name TEXT, content TEXT, ts INTEGER)""")
        db.execSQL("""CREATE TABLE tasks(
            id TEXT PRIMARY KEY, name TEXT, state TEXT, progress INTEGER DEFAULT 0,
            total INTEGER DEFAULT 0, detail TEXT, updatedTs INTEGER)""")
        db.execSQL("""CREATE TABLE ledger(
            id TEXT PRIMARY KEY, relativePath TEXT, syncTime INTEGER)""")
        db.execSQL("""CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT)""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE cards ADD COLUMN favorite INTEGER DEFAULT 0")
        }
    }

    // ---------- notes ----------

    /**
     * 插入/更新笔记。内容字段可被云端同步覆盖，但 AI 分类元数据（aiCategory/aiTags/aiReason/classified）
     * 一旦存在绝不被重置。FTS 行同步重建。
     */
    fun upsertNote(
        id: String, title: String, folder: String, content: String,
        createTs: Long, modifyTs: Long, source: String,
    ) {
        val exists = readableDatabase.rawQuery("SELECT 1 FROM notes WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
        if (exists) {
            writableDatabase.execSQL(
                "UPDATE notes SET title=?, folder=?, content=?, createTs=?, modifyTs=?, source=? WHERE id=?",
                arrayOf<Any?>(title, folder, content, createTs, modifyTs, source, id),
            )
        } else {
            val cv = ContentValues().apply {
                put("id", id); put("title", title); put("folder", folder); put("content", content)
                put("createTs", createTs); put("modifyTs", modifyTs); put("source", source)
            }
            writableDatabase.insertWithOnConflict("notes", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
        rebuildFts(id, title, content)
    }

    private fun rebuildFts(id: String, title: String, content: String) {
        writableDatabase.execSQL("DELETE FROM notes_fts WHERE noteId = ?", arrayOf(id))
        writableDatabase.execSQL(
            "INSERT INTO notes_fts(noteId, title, content) VALUES(?, ?, ?)",
            arrayOf(id, title, content),
        )
    }

    fun noteCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM notes", null).use { it.moveToFirst(); it.getInt(0) }

    fun folderCounts(): Map<String, Int> {
        val m = LinkedHashMap<String, Int>()
        readableDatabase.rawQuery(
            "SELECT folder, COUNT(*) c FROM notes GROUP BY folder ORDER BY c DESC", null
        ).use { r ->
            while (r.moveToNext()) m[r.getString(0)] = r.getInt(1)
        }
        return m
    }

    fun dateRange(): Pair<Long, Long>? =
        readableDatabase.rawQuery("SELECT MIN(createTs), MAX(createTs) FROM notes", null).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) to it.getLong(1) else null
        }

    fun getNote(id: String): Note? =
        readableDatabase.rawQuery("SELECT * FROM notes WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) noteFrom(it) else null
        }

    fun listNotes(folder: String?, offset: Int, limit: Int, order: String, onlyUnclassified: Boolean = false): List<Note> {
        val orderBy = when (order) {
            "create_asc" -> "createTs ASC"
            "modify_desc" -> "modifyTs DESC"
            "len_asc" -> "LENGTH(content) ASC"
            else -> "createTs DESC"
        }
        val where = StringBuilder("1=1")
        val args = mutableListOf<String>()
        if (!folder.isNullOrBlank()) { where.append(" AND folder = ?"); args.add(folder) }
        if (onlyUnclassified) { where.append(" AND classified = 0") }
        args.add("$limit"); args.add("$offset")
        return readableDatabase.rawQuery(
            "SELECT * FROM notes WHERE $where ORDER BY $orderBy LIMIT ? OFFSET ?", args.toTypedArray()
        ).use { r -> buildList { while (r.moveToNext()) add(noteFrom(r)) } }
    }

    fun searchNotes(query: String, folder: String?, limit: Int): List<Note> {
        val like = if (query.length >= 2) query else "%$query%"
        val args = mutableListOf<String>().apply {
            add("%$query%"); add("%$query%")
            if (!folder.isNullOrBlank()) add(folder)
            add("$limit")
        }
        val sql = StringBuilder(
            "SELECT * FROM notes WHERE (title LIKE ? OR content LIKE ?) "
        )
        if (!folder.isNullOrBlank()) sql.append("AND folder = ? ")
        sql.append("ORDER BY modifyTs DESC LIMIT ?")
        val likeHits = readableDatabase.rawQuery(sql.toString(), args.toTypedArray()).use { r ->
            buildList { while (r.moveToNext()) add(noteFrom(r)) }
        }
        if (likeHits.size >= limit) return likeHits
        // FTS 兜底补位（对带空格的英文/词组查询更准）
        val seen = likeHits.map { it.id }.toMutableSet()
        val fts = try {
            readableDatabase.rawQuery(
                "SELECT n.* FROM notes_fts f JOIN notes n ON n.id = f.noteId WHERE notes_fts MATCH ? LIMIT ?",
                arrayOf(query, "$limit")
            ).use { r -> buildList { while (r.moveToNext()) add(noteFrom(r)) } }
        } catch (e: Exception) { emptyList() }
        return likeHits + fts.filter { seen.add(it.id) }
    }

    fun randomNotes(n: Int): List<Note> =
        readableDatabase.rawQuery("SELECT * FROM notes ORDER BY RANDOM() LIMIT ?", arrayOf("$n")).use { r ->
            buildList { while (r.moveToNext()) add(noteFrom(r)) }
        }

    private fun noteFrom(c: android.database.Cursor) = Note(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        folder = c.getString(c.getColumnIndexOrThrow("folder")),
        content = c.getString(c.getColumnIndexOrThrow("content")),
        createTs = c.getLong(c.getColumnIndexOrThrow("createTs")),
        modifyTs = c.getLong(c.getColumnIndexOrThrow("modifyTs")),
        aiCategory = c.getString(c.getColumnIndexOrThrow("aiCategory")),
        aiTags = c.getString(c.getColumnIndexOrThrow("aiTags"))?.let {
            runCatching { JSONArray(it).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        } ?: emptyList(),
        aiReason = c.getString(c.getColumnIndexOrThrow("aiReason")),
        classified = c.getInt(c.getColumnIndexOrThrow("classified")) == 1,
    )

    // ---------- 分类结果 ----------

    fun tagNote(id: String, category: String, tags: List<String>, reason: String): Boolean =
        getNote(id)?.let {
            writableDatabase.execSQL(
                "UPDATE notes SET aiCategory=?, aiTags=?, aiReason=?, classified=1 WHERE id=?",
                arrayOf(category, JSONArray(tags).toString(), reason, id),
            )
            true
        } ?: false

    fun classifiedProgress(): Triple<Int, Int, Map<String, Int>> {
        val total = noteCount()
        var done = 0
        readableDatabase.rawQuery("SELECT COUNT(*) FROM notes WHERE classified=1", null).use {
            it.moveToFirst(); done = it.getInt(0)
        }
        val byCat = LinkedHashMap<String, Int>()
        readableDatabase.rawQuery(
            "SELECT aiCategory, COUNT(*) c FROM notes WHERE classified=1 GROUP BY aiCategory ORDER BY c DESC", null
        ).use { r -> while (r.moveToNext()) byCat[r.getString(0) ?: "?"] = r.getInt(1) }
        return Triple(done, total, byCat)
    }

    // ---------- settings ----------

    fun setSetting(key: String, value: String) {
        val cv = ContentValues().apply { put("key", key); put("value", value) }
        writableDatabase.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getSetting(key: String, def: String = ""): String =
        readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else def
        }

    // ---------- taxonomy ----------

    fun taxonomyJson(): String = getSetting("taxonomy", "")
    fun setTaxonomyJson(json: String) = setSetting("taxonomy", json)

    // ---------- kb ----------

    fun kbUpsert(slug: String, title: String, category: String, body: String, sources: List<String>) {
        val cv = ContentValues().apply {
            put("slug", slug); put("title", title); put("category", category)
            put("body", body); put("sources", JSONArray(sources).toString())
            put("updatedTs", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("kb_pages", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun kbGet(slug: String): KbPage? =
        readableDatabase.rawQuery("SELECT * FROM kb_pages WHERE slug=?", arrayOf(slug)).use {
            if (it.moveToFirst()) kbFrom(it) else null
        }

    fun kbList(category: String? = null): List<KbPage> {
        val sql = if (category == null) "SELECT * FROM kb_pages ORDER BY category, updatedTs DESC"
        else "SELECT * FROM kb_pages WHERE category=? ORDER BY updatedTs DESC"
        val args = if (category == null) emptyArray() else arrayOf(category)
        return readableDatabase.rawQuery(sql, args).use { r ->
            buildList { while (r.moveToNext()) add(kbFrom(r)) }
        }
    }

    fun kbSearch(query: String, limit: Int = 10): List<KbPage> =
        readableDatabase.rawQuery(
            "SELECT * FROM kb_pages WHERE title LIKE ? OR body LIKE ? ORDER BY updatedTs DESC LIMIT ?",
            arrayOf("%$query%", "%$query%", "$limit")
        ).use { r -> buildList { while (r.moveToNext()) add(kbFrom(r)) } }

    fun kbCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM kb_pages", null).use { it.moveToFirst(); it.getInt(0) }

    private fun kbFrom(c: android.database.Cursor) = KbPage(
        slug = c.getString(c.getColumnIndexOrThrow("slug")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        category = c.getString(c.getColumnIndexOrThrow("category")),
        body = c.getString(c.getColumnIndexOrThrow("body")),
        sources = c.getString(c.getColumnIndexOrThrow("sources"))?.let {
            runCatching { JSONArray(it).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        } ?: emptyList(),
        updatedTs = c.getLong(c.getColumnIndexOrThrow("updatedTs")),
    )

    // ---------- cards ----------

    fun cardToday(day: String): Card? =
        readableDatabase.rawQuery("SELECT * FROM cards WHERE day=?", arrayOf(day)).use {
            if (it.moveToFirst()) cardFrom(it) else null
        }

    fun cardSave(card: Card) {
        val cv = ContentValues().apply {
            put("day", card.day); put("title", card.title); put("body", card.body)
            put("sourceNoteId", card.sourceNoteId); put("comment", card.comment)
            put("createdTs", card.createdTs); put("favorite", if (card.favorite) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("cards", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun cardRecent(n: Int = 7): List<Card> =
        readableDatabase.rawQuery("SELECT * FROM cards ORDER BY day DESC LIMIT ?", arrayOf("$n")).use { r ->
            buildList { while (r.moveToNext()) add(cardFrom(r)) }
        }

    fun cardAll(): List<Card> =
        readableDatabase.rawQuery("SELECT * FROM cards ORDER BY day DESC", null).use { r ->
            buildList { while (r.moveToNext()) add(cardFrom(r)) }
        }

    fun cardDelete(day: String) {
        writableDatabase.delete("cards", "day=?", arrayOf(day))
    }

    fun cardSetFavorite(day: String, favorite: Boolean) {
        writableDatabase.execSQL("UPDATE cards SET favorite=? WHERE day=?",
            arrayOf<Any?>(if (favorite) 1 else 0, day))
    }

    fun cardUpdate(day: String, title: String, body: String, comment: String) {
        writableDatabase.execSQL("UPDATE cards SET title=?, body=?, comment=? WHERE day=?",
            arrayOf(title, body, comment, day))
    }

    fun cardCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM cards", null).use { it.moveToFirst(); it.getInt(0) }

    private fun cardFrom(c: android.database.Cursor) = Card(
        day = c.getString(c.getColumnIndexOrThrow("day")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        body = c.getString(c.getColumnIndexOrThrow("body")),
        sourceNoteId = c.getString(c.getColumnIndexOrThrow("sourceNoteId")) ?: "",
        comment = c.getString(c.getColumnIndexOrThrow("comment")) ?: "",
        createdTs = c.getLong(c.getColumnIndexOrThrow("createdTs")),
        favorite = c.getInt(c.getColumnIndexOrThrow("favorite")) == 1,
    )

    // ---------- drafts ----------

    fun draftAdd(kind: String, title: String, body: String, sourceIds: List<String>): Long {
        val cv = ContentValues().apply {
            put("kind", kind); put("title", title); put("body", body)
            put("sourceIds", JSONArray(sourceIds).toString()); put("createdTs", System.currentTimeMillis())
        }
        return writableDatabase.insert("drafts", null, cv)
    }

    fun draftList(limit: Int = 100): List<Draft> =
        readableDatabase.rawQuery("SELECT * FROM drafts ORDER BY id DESC LIMIT ?", arrayOf("$limit")).use { r ->
            buildList { while (r.moveToNext()) add(draftFrom(r)) }
        }

    fun draftCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM drafts", null).use { it.moveToFirst(); it.getInt(0) }

    private fun draftFrom(c: android.database.Cursor) = Draft(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        kind = c.getString(c.getColumnIndexOrThrow("kind")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        body = c.getString(c.getColumnIndexOrThrow("body")),
        sourceIds = c.getString(c.getColumnIndexOrThrow("sourceIds"))?.let {
            runCatching { JSONArray(it).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        } ?: emptyList(),
        createdTs = c.getLong(c.getColumnIndexOrThrow("createdTs")),
    )

    // ---------- chat ----------

    fun chatAppend(role: String, name: String?, content: String) {
        val cv = ContentValues().apply {
            put("role", role); put("name", name); put("content", content); put("ts", System.currentTimeMillis())
        }
        writableDatabase.insert("chat", null, cv)
    }

    fun chatRecent(limit: Int = 200): List<Pair<String, String>> =
        readableDatabase.rawQuery("SELECT role, content FROM chat WHERE role IN ('user','assistant') ORDER BY id DESC LIMIT ?",
            arrayOf("$limit")).use { r ->
            buildList { while (r.moveToNext()) add(r.getString(0) to r.getString(1)) }.reversed()
        }

    // ---------- tasks ----------

    fun taskUpdate(id: String, name: String, state: String, progress: Int? = null, total: Int? = null, detail: String? = null) {
        val cv = ContentValues().apply {
            put("id", id); put("name", name); put("state", state)
            put("updatedTs", System.currentTimeMillis())
            progress?.let { put("progress", it) }
            total?.let { put("total", it) }
            detail?.let { put("detail", it.take(400)) }
        }
        writableDatabase.insertWithOnConflict("tasks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun taskAll(): List<TaskRow> =
        readableDatabase.rawQuery("SELECT * FROM tasks ORDER BY updatedTs DESC", null).use { r ->
            buildList {
                while (r.moveToNext()) add(TaskRow(
                    id = r.getString(r.getColumnIndexOrThrow("id")),
                    name = r.getString(r.getColumnIndexOrThrow("name")),
                    state = r.getString(r.getColumnIndexOrThrow("state")),
                    progress = r.getInt(r.getColumnIndexOrThrow("progress")),
                    total = r.getInt(r.getColumnIndexOrThrow("total")),
                    detail = r.getString(r.getColumnIndexOrThrow("detail")) ?: "",
                    updatedTs = r.getLong(r.getColumnIndexOrThrow("updatedTs")),
                ))
            }
        }

    // ---------- ledger ----------

    fun ledgerGet(id: String): Long? =
        readableDatabase.rawQuery("SELECT syncTime FROM ledger WHERE id=?", arrayOf(id)).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }

    fun ledgerPut(id: String, syncTime: Long) {
        val cv = ContentValues().apply { put("id", id); put("syncTime", syncTime) }
        writableDatabase.insertWithOnConflict("ledger", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun ledgerCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM ledger", null).use { it.moveToFirst(); it.getInt(0) }
}
