package com.inkforge.notesstudio

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.concurrent.Executors

/** SQLite WAL commits edits atomically; immutable binary assets live outside JSON. */
class NoteRepository(context: Context) : SQLiteOpenHelper(context, "badnote-native.db", null, 1) {
    val assetDirectory = File(context.filesDir, "native-assets").apply { mkdirs() }
    val workDirectory = File(context.cacheDir, "note-work").apply { mkdirs() }
    val executor = Executors.newSingleThreadExecutor()
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE documents(id TEXT PRIMARY KEY,body TEXT NOT NULL,updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE pages(id TEXT PRIMARY KEY,document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,position INTEGER NOT NULL,body TEXT NOT NULL)")
        db.execSQL("CREATE INDEX page_order ON pages(document_id,position)")
        db.execSQL("CREATE TABLE objects(id TEXT NOT NULL,page_id TEXT NOT NULL REFERENCES pages(id) ON DELETE CASCADE,position INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(page_id,id))")
        db.execSQL("CREATE INDEX object_order ON objects(page_id,position)")
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,body TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit

    fun transaction(block: () -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    fun setting(key: String, default: String = ""): String = readableDatabase.rawQuery("SELECT body FROM settings WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else default
    }
    fun setting(key: String, value: String, write: Boolean) {
        if (write) writableDatabase.insertWithOnConflict("settings", null, ContentValues().apply {
            put("key", key); put("body", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun documents(): List<DocumentInfo> = readableDatabase.rawQuery("SELECT id,body FROM documents ORDER BY updated DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(DocumentInfo(c.getString(0), JSONObject(c.getString(1)))) }
    }
    fun document(id: String): DocumentInfo? = readableDatabase.rawQuery("SELECT body FROM documents WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) DocumentInfo(id, JSONObject(it.getString(0))) else null
    }
    fun putDocument(doc: DocumentInfo) {
        require(!doc.data.has("pages")) { "문서 메타데이터에 페이지 본문을 저장할 수 없습니다." }
        val values = ContentValues().apply {
            put("id", doc.id); put("body", doc.data.toString()); put("updated", System.currentTimeMillis())
        }
        // REPLACE would cascade-delete child pages. Update existing rows in place.
        if (writableDatabase.update("documents", values, "id=?", arrayOf(doc.id)) == 0)
            writableDatabase.insertOrThrow("documents", null, values)
    }
    fun create(title: String, folder: String, template: String): DocumentInfo {
        val id = uid("doc")
        val doc = DocumentInfo(id, json("id" to id, "title" to title, "folderId" to folder,
            "schema" to "com.inkforge.ifnote", "version" to 5, "favorite" to false,
            "createdAt" to System.currentTimeMillis(), "audio" to JSONArray()))
        transaction { putDocument(doc); putPage(id, NotePage.blank(template), 0) }
        return doc
    }
    fun pageIds(documentId: String): List<String> = readableDatabase.rawQuery("SELECT id FROM pages WHERE document_id=? ORDER BY position,id", arrayOf(documentId)).use {
        buildList { while (it.moveToNext()) add(it.getString(0)) }
    }
    fun pageMeta(id: String): JSONObject? = readableDatabase.rawQuery("SELECT body FROM pages WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else null
    }
    fun page(id: String): NotePage? {
        val meta = pageMeta(id) ?: return null
        val objects = readableDatabase.rawQuery("SELECT body FROM objects WHERE page_id=? ORDER BY position,rowid", arrayOf(id)).use {
            buildList { while (it.moveToNext()) add(JSONObject(it.getString(0))) }.toMutableList()
        }
        return NotePage(id, meta, objects)
    }
    fun putPage(documentId: String, page: NotePage, position: Int) {
        val values = ContentValues().apply {
            put("id", page.id); put("document_id", documentId); put("position", position); put("body", page.meta.toString())
        }
        if (writableDatabase.update("pages", values, "id=?", arrayOf(page.id)) == 0)
            writableDatabase.insertOrThrow("pages", null, values)
        writableDatabase.delete("objects", "page_id=?", arrayOf(page.id))
        page.objects.forEachIndexed { i, obj -> putObject(page.id, obj, i) }
    }
    fun putPageMeta(pageId: String, meta: JSONObject) {
        writableDatabase.update("pages", ContentValues().apply { put("body", meta.toString()) }, "id=?", arrayOf(pageId))
    }
    private fun putObject(pageId: String, obj: JSONObject, position: Int) {
        writableDatabase.insertWithOnConflict("objects", null, ContentValues().apply {
            put("id", obj.getString("id")); put("page_id", pageId); put("position", position); put("body", obj.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun commit(documentId: String, pageId: String, change: ObjectChange, positions: Map<String, Int>) {
        transaction {
            change.before.forEach { writableDatabase.delete("objects", "page_id=? AND id=?", arrayOf(pageId, it.getString("id"))) }
            change.after.forEach { putObject(pageId, it, positions[it.getString("id")] ?: Int.MAX_VALUE) }
            positions.forEach { (id, position) -> writableDatabase.execSQL("UPDATE objects SET position=? WHERE page_id=? AND id=?", arrayOf<Any>(position, pageId, id)) }
            writableDatabase.execSQL("UPDATE documents SET updated=? WHERE id=?", arrayOf<Any>(System.currentTimeMillis(), documentId))
        }
    }
    fun addPage(doc: String, page: NotePage, position: Int) = transaction {
        writableDatabase.execSQL("UPDATE pages SET position=position+1 WHERE document_id=? AND position>=?", arrayOf<Any>(doc, position))
        putPage(doc, page, position)
    }
    fun reorder(doc: String, ids: List<String>) = transaction {
        ids.forEachIndexed { i, id -> writableDatabase.execSQL("UPDATE pages SET position=? WHERE document_id=? AND id=?", arrayOf<Any>(i, doc, id)) }
    }
    fun removePage(doc: String, id: String) = transaction {
        require(pageIds(doc).size > 1) { "마지막 페이지는 삭제할 수 없습니다." }
        writableDatabase.delete("pages", "document_id=? AND id=?", arrayOf(doc, id))
    }
    fun deleteDocument(id: String) { writableDatabase.delete("documents", "id=?", arrayOf(id)) }

    fun asset(id: String): File {
        require(Regex("[A-Za-z0-9_.-]{1,160}").matches(id)) { "자산 경로가 잘못되었습니다." }
        return File(assetDirectory, id)
    }
    fun storeAsset(input: InputStream, extension: String = "bin"): String {
        val id = uid("asset") + "." + extension.replace(Regex("[^A-Za-z0-9]"), "").take(12).ifBlank { "bin" }
        val output = asset(id)
        try { FileOutputStream(output).use { stream -> ArchiveCodec.copy(input, stream); stream.fd.sync() } }
        catch (e: Exception) { output.delete(); throw e }
        return id
    }
    fun export(id: String, target: File, cancelled: () -> Boolean = { false }, progress: (Int, Long) -> Unit = { _, _ -> }) {
        // Caller uses the repository executor: edits cannot race with this consistent snapshot.
        val doc = requireNotNull(document(id))
        val ids = pageIds(id)
        val assets = linkedSetOf<String>()
        val manifest = doc.data.copyJson().put("format", "badnote-native").put("version", 5).put("pageIds", JSONArray(ids))
        collectAssets(manifest, assets)
        val entries = sequence {
            yield(ArchiveCodec.Entry("manifest.json") { ByteArrayInputStream(manifest.toString().toByteArray(Charsets.UTF_8)) })
            for (pageId in ids) {
                val page = requireNotNull(page(pageId))
                val data = page.json()
                collectAssets(data, assets)
                yield(ArchiveCodec.Entry("pages/$pageId.json") { ByteArrayInputStream(data.toString().toByteArray(Charsets.UTF_8)) })
            }
            for (assetId in assets) {
                val file = asset(assetId)
                require(file.isFile) { "노트 자산이 없습니다: $assetId" }
                yield(ArchiveCodec.Entry("assets/$assetId") { FileInputStream(file) })
            }
        }
        try { FileOutputStream(target).use { ArchiveCodec.write(it, entries, cancelled, progress) } }
        catch (e: Exception) { target.delete(); throw e }
    }
    fun importArchive(input: InputStream, folder: String = "root", keepId: Boolean = false): DocumentInfo {
        val staging = File(workDirectory, uid("import"))
        val copied = mutableListOf<File>()
        try {
            val entries = ArchiveCodec.extract(input, staging)
            val metadata = JSONObject(File(staging, "manifest.json").readText())
            require(metadata.optString("format") == "badnote-native" && metadata.optInt("version") == 5) { "지원하지 않는 노트 버전입니다." }
            val oldPageIds = metadata.array("pageIds").let { a -> (0 until a.length()).map { a.getString(it) } }
            require(oldPageIds.isNotEmpty() && oldPageIds.size == oldPageIds.toSet().size) { "페이지 목록이 잘못되었습니다." }
            val pageMap = oldPageIds.associateWith { uid("page") }
            val map = mutableMapOf<String, String>()
            for (entry in entries.filter { it.startsWith("assets/") }) {
                val old = entry.substringAfter('/')
                val new = File(staging, entry).inputStream().use { storeAsset(it, old.substringAfterLast('.', "bin")) }
                map[old] = new; copied += asset(new)
            }
            val id = if (keepId) metadata.optString("id").ifBlank { uid("doc") } else uid("doc")
            require(document(id) == null) { "이미 이전된 문서입니다." }
            metadata.remove("pageIds"); metadata.remove("format")
            metadata.put("id", id).put("folderId", folder).put("trashed", false)
            rewriteAssets(metadata, map)
            rewritePageReferences(metadata, pageMap)
            val doc = DocumentInfo(id, metadata)
            transaction {
                putDocument(doc)
                oldPageIds.forEachIndexed { i, old ->
                    require("pages/$old.json" in entries) { "페이지가 누락되었습니다." }
                    val data = JSONObject(File(staging, "pages/$old.json").readText())
                    rewriteAssets(data, map)
                    rewritePageReferences(data, pageMap)
                    data.put("id", pageMap.getValue(old))
                    putPage(id, NotePage.from(data), i)
                }
            }
            return doc
        } catch (e: Exception) { copied.forEach { it.delete() }; throw e }
        finally { staging.deleteRecursively() }
    }
    fun importLegacy(input: InputStream, folder: String = "root"): DocumentInfo {
        val stage = File(workDirectory, uid("legacy")).apply { mkdirs() }
        val assets = mutableListOf<File>()
        val pageMap = mutableMapOf<String, String>()
        var pages = 0
        try {
            val meta = LegacyJsonReader(input.reader(Charsets.UTF_8), { mime ->
                val id = uid("asset") + "." + extension(mime)
                val file = asset(id); assets += file
                "asset:$id" to FileOutputStream(file)
            }, { page ->
                val old = page.optString("id")
                val replacement = uid("page")
                if (old.isNotBlank()) {
                    require(old !in pageMap) { "중복된 페이지 ID입니다." }
                    pageMap[old] = replacement
                }
                page.put("id", replacement)
                File(stage, "${pages++}.json").writeText(page.toString())
            }).read()
            require(pages > 0) { "노트에 페이지가 없습니다." }
            val doc = DocumentInfo(uid("doc"), meta.apply {
                put("folderId", folder); put("trashed", false); put("version", 5)
            })
            doc.data.put("id", doc.id)
            rewritePageReferences(doc.data, pageMap)
            transaction {
                putDocument(doc)
                repeat(pages) { i ->
                    val data = JSONObject(File(stage, "$i.json").readText())
                    rewritePageReferences(data, pageMap)
                    require(!data.has("backgroundAssetId") || data.has("backgroundImage")) {
                        "이전 노트의 PDF 자산이 누락되었습니다. 기존 앱의 파일 내보내기로 다시 저장해 주세요."
                    }
                    data.remove("backgroundAssetId")
                    putPage(doc.id, NotePage.from(data), i)
                }
            }
            return doc
        } catch (e: Exception) { assets.forEach { it.delete() }; throw e }
        finally { stage.deleteRecursively() }
    }
    fun importNote(input: InputStream, folder: String): DocumentInfo {
        val buffered = input.buffered(ArchiveCodec.BUFFER_SIZE)
        buffered.mark(4); val first = buffered.read(); val second = buffered.read(); buffered.reset()
        return if (first == 80 && second == 75) importArchive(buffered, folder) else importLegacy(buffered, folder)
    }
    companion object {
        fun extension(mime: String) = when (mime.substringBefore(';')) {
            "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"
            "audio/webm" -> "webm"; "audio/mp4" -> "m4a"; "audio/ogg" -> "ogg"
            "application/pdf" -> "pdf"; else -> "bin"
        }
        fun collectAssets(value: Any?, into: MutableSet<String>) {
            when (value) {
                is JSONObject -> value.keys().forEach { collectAssets(value.opt(it), into) }
                is JSONArray -> (0 until value.length()).forEach { collectAssets(value.opt(it), into) }
                is String -> if (value.startsWith("asset:")) into += value.removePrefix("asset:")
            }
        }
        fun rewriteAssets(value: Any?, map: Map<String, String>) {
            when (value) {
                is JSONObject -> value.keys().asSequence().toList().forEach { key ->
                    val v = value.opt(key)
                    if (v is String && v.startsWith("asset:")) value.put(key, "asset:" + requireNotNull(map[v.removePrefix("asset:")]) { "자산 참조가 누락되었습니다." })
                    else rewriteAssets(v, map)
                }
                is JSONArray -> (0 until value.length()).forEach { i ->
                    val v = value.opt(i)
                    if (v is String && v.startsWith("asset:")) value.put(i, "asset:" + requireNotNull(map[v.removePrefix("asset:")]))
                    else rewriteAssets(v, map)
                }
            }
        }
    }
}
