package com.inkforge.notesstudio

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.Cursor
import android.graphics.BitmapFactory
import android.system.Os
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Room owns the single database connection and its schema; opaque JSON stays intact. */
class NoteRepository(context: Context, databaseName: String = NativeDatabase.NAME) : Closeable {
    private val room = NativeDatabase.open(context, databaseName)
    private val lowMemory = (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass <= 128
    private val memoryCacheBudget = if (lowMemory) 1024 * 1024 else 2 * 1024 * 1024
    private val memoryCache = LinkedHashMap<String, String>(16, .75f, true)
    private var memoryCacheBytes = 0
    private val readableDatabase get() = Sql(room.openHelper.readableDatabase)
    private val writableDatabase get() = Sql(room.openHelper.writableDatabase)
    val assetDirectory = File(context.filesDir, "native-assets").apply { mkdirs() }
    val workDirectory = File(context.cacheDir, "note-work").apply { mkdirs() }
    val executor = Executors.newSingleThreadExecutor()
    private class Sql(private val db: SupportSQLiteDatabase) {
        fun rawQuery(sql: String, args: Array<String>?): Cursor = db.query(sql, args?.map { it as Any }.orEmpty().toTypedArray())
        fun insertWithOnConflict(table: String, ignored: String?, values: ContentValues, algorithm: Int): Long = db.insert(table, algorithm, values)
        fun insertOrThrow(table: String, ignored: String?, values: ContentValues): Long = db.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
        fun update(table: String, values: ContentValues, where: String, args: Array<String>): Int = db.update(table, SQLiteDatabase.CONFLICT_ABORT, values, where, args)
        fun delete(table: String, where: String, args: Array<String>): Int = db.delete(table, where, args)
        fun execSQL(sql: String, args: Array<Any>) = db.execSQL(sql, args)
        fun beginTransaction() = db.beginTransaction()
        fun setTransactionSuccessful() = db.setTransactionSuccessful()
        fun endTransaction() = db.endTransaction()
    }

    private fun bumpGeneration(pageId: String, captureFloor: Long = 1) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO page_generation(page_id,generation,next_capture_seq) VALUES(?,0,?)", arrayOf<Any>(pageId, captureFloor))
        writableDatabase.execSQL("UPDATE page_generation SET generation=generation+1,next_capture_seq=MAX(next_capture_seq,?) WHERE page_id=?",
            arrayOf<Any>(captureFloor, pageId))
    }
    fun generation(pageId: String): Long = readableDatabase.rawQuery(
        "SELECT generation FROM page_generation WHERE page_id=?", arrayOf(pageId)).use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    }
    fun reserveCaptureSequence(pageId: String): Long {
        var result = 0L
        transaction {
            val existing = readableDatabase.rawQuery("SELECT next_capture_seq FROM page_generation WHERE page_id=?", arrayOf(pageId)).use {
                if (it.moveToFirst()) it.getLong(0) else null
            }
            result = existing ?: page(pageId)?.objects?.maxOfOrNull { it.optLong("captureSeq", 0) + 1 }?.coerceAtLeast(1) ?: 1
            writableDatabase.execSQL("INSERT OR IGNORE INTO page_generation(page_id,generation,next_capture_seq) VALUES(?,0,?)", arrayOf<Any>(pageId, result))
            writableDatabase.execSQL("UPDATE page_generation SET next_capture_seq=? WHERE page_id=?", arrayOf<Any>(result + 1, pageId))
        }
        return result
    }

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
        bumpGeneration(page.id, (page.objects.maxOfOrNull { it.optLong("captureSeq", 0) } ?: 0) + 1)
    }
    fun modelGeneration(languageTag: String): Long = setting("ocr-model-generation:$languageTag", "0")
        .toLongOrNull()?.coerceAtLeast(0) ?: 0
    fun invalidateModelGeneration(languageTag: String): Long {
        var next = 0L
        transaction {
            next = modelGeneration(languageTag) + 1
            setting("ocr-model-generation:$languageTag", next.toString(), true)
        }
        return next
    }
    fun putPageMeta(pageId: String, meta: JSONObject) = transaction {
        writableDatabase.update("pages", ContentValues().apply { put("body", meta.toString()) }, "id=?", arrayOf(pageId))
        bumpGeneration(pageId)
    }
    /** Apply a bookmark to the latest row so delayed UI saves cannot erase PDF indexing state. */
    fun setPageBookmark(pageId: String, bookmarked: Boolean): JSONObject? {
        var committed: JSONObject? = null
        transaction {
            val meta = pageMeta(pageId) ?: return@transaction
            meta.put("bookmarked", bookmarked)
            writableDatabase.update("pages", ContentValues().apply { put("body", meta.toString()) }, "id=?", arrayOf(pageId))
            bumpGeneration(pageId)
            committed = meta
        }
        return committed
    }
    /** Merge only PDF-owned fields into the latest metadata; never replace a page edited since indexing began. */
    fun mergePdfText(pageId: String, source: String, number: Int, text: String, engine: String): Boolean {
        var merged = false
        transaction {
            val meta = pageMeta(pageId) ?: return@transaction
            if (meta.optString("pdfSource") != source || meta.optInt("pdfPageNumber") != number) return@transaction
            if (meta.has("pdfText") && meta.optString("pdfTextSource").isBlank() && meta.optString("pdfText").isNotBlank())
                return@transaction
            if (meta.optString("pdfText") == text && meta.optString("pdfTextSource") == engine) {
                merged = true; return@transaction
            }
            meta.put("pdfText", text).put("pdfTextSource", engine)
            writableDatabase.update("pages", ContentValues().apply { put("body", meta.toString()) }, "id=?", arrayOf(pageId))
            bumpGeneration(pageId)
            merged = true
        }
        return merged
    }
    fun pdfImportState(pageId: String, importId: String, source: String, pending: Boolean,
                       importedPages: Int, error: String? = null): Boolean {
        var updated = false
        transaction {
            val meta = pageMeta(pageId) ?: return@transaction
            if (meta.optString("pdfImportId") != importId || meta.optString("pdfSource") != source) return@transaction
            meta.put("pdfImportPending", pending).put("pdfImportedPages", importedPages)
            if (error == null) meta.remove("pdfImportError") else meta.put("pdfImportError", error)
            writableDatabase.update("pages", ContentValues().apply { put("body", meta.toString()) }, "id=?", arrayOf(pageId))
            bumpGeneration(pageId)
            updated = true
        }
        return updated
    }
    fun appendPdfPage(documentId: String, firstPageId: String, previousId: String, importId: String,
                      source: String, page: NotePage): Boolean {
        var inserted = false
        transaction {
            if (document(documentId) == null) return@transaction
            val first = pageMeta(firstPageId) ?: return@transaction
            if (first.optString("pdfImportId") != importId || first.optString("pdfSource") != source ||
                !first.optBoolean("pdfImportPending")) return@transaction
            val previous = pageMeta(previousId) ?: return@transaction
            if (previous.optString("pdfImportId") != importId || previous.optString("pdfSource") != source)
                return@transaction
            val position = pageIds(documentId).indexOf(previousId)
            if (position < 0) return@transaction
            addPage(documentId, page, position + 1)
            inserted = true
        }
        return inserted
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
            bumpGeneration(pageId, (change.after.maxOfOrNull { it.optLong("captureSeq", 0) } ?: 0) + 1)
            writableDatabase.execSQL("UPDATE documents SET updated=? WHERE id=?", arrayOf<Any>(System.currentTimeMillis(), documentId))
        }
    }
    fun addPage(doc: String, page: NotePage, position: Int) = transaction {
        val shifted = pageIds(doc).drop(position)
        writableDatabase.execSQL("UPDATE pages SET position=position+1 WHERE document_id=? AND position>=?", arrayOf<Any>(doc, position))
        shifted.forEach(::bumpGeneration)
        putPage(doc, page, position)
    }
    fun duplicatePage(documentId: String, sourceId: String, position: Int): NotePage {
        var duplicated: NotePage? = null
        transaction {
        require(sourceId in pageIds(documentId)) { "복제할 페이지가 없습니다." }
        val source = requireNotNull(page(sourceId))
        val raw = readableDatabase.rawQuery("SELECT body FROM ocr_page_state WHERE page_id=?", arrayOf(sourceId)).use {
            if (it.moveToFirst()) JSONObject(it.getString(0)) else null
        }?.takeIf { ocrResult(sourceId) != null }
        val newId = uid("page")
        val objectIds = source.objects.associate { it.getString("id") to uid("obj") }
        val pageMap = mapOf(sourceId to newId)
        val meta = source.meta.copyJson().put("id", newId)
        rewritePageReferences(meta, pageMap)
        val objects = source.objects.map { original ->
            original.copyJson().apply {
                put("id", objectIds.getValue(original.getString("id")))
                original.optString("sourceStrokeId").takeIf(String::isNotBlank)?.let { old ->
                    objectIds[old]?.let { put("sourceStrokeId", it) }
                }
                rewritePageReferences(this, pageMap)
            }
        }.toMutableList()
        val duplicate = NotePage(newId, meta, objects)
        val shifted = pageIds(documentId).drop(position)
        writableDatabase.execSQL("UPDATE pages SET position=position+1 WHERE document_id=? AND position>=?", arrayOf<Any>(documentId, position))
        shifted.forEach(::bumpGeneration)
        putPage(documentId, duplicate, position)

        fun mappedRegionId(id: String): String = when {
            id.startsWith("line:") -> "line:" + (objectIds[id.removePrefix("line:")] ?: id.removePrefix("line:"))
            id.startsWith("unresolved:") -> "unresolved:" + (objectIds[id.removePrefix("unresolved:")] ?: id.removePrefix("unresolved:"))
            else -> id
        }
        val newKeys = HashMap<String, String>()
        if (raw != null) {
            val newLayout = PageOcrLayout.analyze(duplicate.objects, duplicate.width, duplicate.height)
            val policy = raw.optString("policy")
            val imageKey = if (raw.array("regions").objects().any { it.optString("role") == "image" }) {
                val bitmap = StreamingPdf.render(this, duplicate, 1400)
                try { OcrInput.imageKey(duplicate, policy, bitmap) } finally { bitmap.recycle() }
            } else null
            raw.array("regions").objects().forEach { region ->
                val oldRegionId = region.getString("id")
                val mappedIds = region.array("strokeIds").let { ids ->
                    JSONArray((0 until ids.length()).map { objectIds[ids.getString(it)] ?: ids.getString(it) })
                }
                region.put("id", mappedRegionId(oldRegionId)).put("strokeIds", mappedIds)
                val match = newLayout.regions.firstOrNull { candidate ->
                    candidate.strokes.map { it.id } == (0 until mappedIds.length()).map { mappedIds.getString(it) }
                }
                if (match != null) {
                    val key = OcrInput.regionInputDigest(match, region.optString("languageTag", when(policy){"en-primary"->"en-US";else->"ko"}),
                        "", match.bounds.width.coerceAtLeast(1f), match.bounds.height.coerceAtLeast(1f), match.timeMode)
                    region.put("inputDigest", key); newKeys[oldRegionId] = key
                } else if (region.optString("role") == "image" && imageKey != null) {
                    region.put("inputDigest", imageKey); newKeys[oldRegionId] = imageKey
                }
                region.remove("correctedText")
            }
            raw.array("readingOrder").let { values -> raw.put("readingOrder", JSONArray((0 until values.length()).map { mappedRegionId(values.getString(it)) })) }
            raw.array("nonTextPreserved").let { values -> raw.put("nonTextPreserved", JSONArray((0 until values.length()).map { objectIds[values.getString(it)] ?: values.getString(it) })) }
            raw.array("invalidInput").objects().forEach { invalid ->
                invalid.optString("id").takeIf(String::isNotBlank)?.let { old -> objectIds[old]?.let { invalid.put("id", it) } }
            }
            raw.put("documentId", documentId).put("pageId", newId)
                .put("pageDigest", OcrInput.pageDigest(duplicate, policy)).put("generation", generation(newId))
            require(putOcrResult(documentId, newId, generation(newId), raw.getString("pageDigest"), policy, raw)) {
                "복제한 페이지의 OCR 결과를 저장하지 못했습니다."
            }
        }
        readableDatabase.rawQuery("SELECT region_id,body,updated_at FROM ocr_corrections WHERE page_id=?", arrayOf(sourceId)).use { cursor ->
            while (cursor.moveToNext()) {
                val oldRegionId = cursor.getString(0)
                val correction = JSONObject(cursor.getString(1))
                val ids = correction.array("strokeIds")
                correction.put("strokeIds", JSONArray((0 until ids.length()).map { objectIds[ids.getString(it)] ?: ids.getString(it) }))
                newKeys[oldRegionId]?.let { correction.put("inputDigest", it) }
                writableDatabase.insertWithOnConflict("ocr_corrections", null, ContentValues().apply {
                    put("page_id", newId); put("region_id", mappedRegionId(oldRegionId))
                    put("body", correction.toString()); put("updated_at", cursor.getLong(2))
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
        duplicated = duplicate
        }
        return requireNotNull(duplicated)
    }
    fun reorder(doc: String, ids: List<String>) = transaction {
        ids.forEachIndexed { i, id -> writableDatabase.execSQL("UPDATE pages SET position=? WHERE document_id=? AND id=?", arrayOf<Any>(i, doc, id)); bumpGeneration(id) }
    }
    fun removePage(doc: String, id: String) {
        var stoppedImport: String? = null
        transaction {
            val ids = pageIds(doc)
            require(ids.size > 1) { "마지막 페이지는 삭제할 수 없습니다." }
            val importId = if (id in ids) pageMeta(id)?.optString("pdfImportId").orEmpty() else ""
            if (importId.isNotBlank()) {
                val firstId = ids.firstOrNull { candidate ->
                    val meta = pageMeta(candidate)
                    meta?.optString("pdfImportId") == importId && meta.optInt("pdfPageNumber") == 1 &&
                        meta.optBoolean("pdfImportPending")
                }
                if (firstId != null) {
                    if (firstId != id) {
                        val first = requireNotNull(pageMeta(firstId))
                        first.put("pdfImportPending", false).put("pdfImportError", "pdfImportCancelled")
                        writableDatabase.update("pages", ContentValues().apply { put("body", first.toString()) },
                            "id=?", arrayOf(firstId))
                        bumpGeneration(firstId)
                    }
                    stoppedImport = importId
                }
            }
            writableDatabase.delete("pages", "document_id=? AND id=?", arrayOf(doc, id))
        }
        stoppedImport?.let(PdfImporter::cancelImport)
    }
    fun deleteDocument(id: String) {
        PdfImporter.cancelDocument(id)
        writableDatabase.delete("documents", "id=?", arrayOf(id))
    }

    /** The result transaction rejects both an edited page and an Undo that restored old bytes. */
    fun putOcrResult(documentId: String, pageId: String, expectedGeneration: Long,
                     expectedDigest: String, policy: String, result: JSONObject,
                     digestChecked: Boolean = false): Boolean {
        var applied = false
        transaction {
            val belongsToDocument = readableDatabase.rawQuery(
                "SELECT 1 FROM pages WHERE id=? AND document_id=?", arrayOf(pageId, documentId)
            ).use { it.moveToFirst() }
            val digestMatches = digestChecked || page(pageId)?.let { OcrInput.pageDigest(it, policy) == expectedDigest } == true
            if (belongsToDocument && generation(pageId) == expectedGeneration && digestMatches) {
                val body = result.copyJson().put("policy", policy).toString()
                writableDatabase.insertWithOnConflict("ocr_page_state", null, ContentValues().apply {
                    put("page_id", pageId); put("document_id", documentId); put("generation", expectedGeneration)
                    put("digest", expectedDigest); put("status", result.optString("status")); put("body", body)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                writableDatabase.delete("ocr_regions", "page_id=?", arrayOf(pageId))
                result.array("regions").objects().forEach { region ->
                    writableDatabase.insertWithOnConflict("ocr_regions", null, ContentValues().apply {
                        put("page_id", pageId); put("region_id", region.getString("id")); put("body", region.toString())
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
                applied = true
            }
        }
        return applied
    }

    /** Returns only a result tied to the current stored page and model policy. */
    fun ocrResult(pageId: String): JSONObject? {
        val row = readableDatabase.rawQuery("SELECT generation,digest,body FROM ocr_page_state WHERE page_id=?", arrayOf(pageId)).use {
            if (it.moveToFirst()) Triple(it.getLong(0), it.getString(1), it.getString(2)) else null
        } ?: return null
        val result = JSONObject(row.third)
        val current = page(pageId) ?: return null
        if (row.first != generation(pageId) || row.second != OcrInput.pageDigest(current, result.optString("policy"))) return null
        val regions = result.array("regions")
        for (i in 0 until regions.length()) {
            val region = regions.optJSONObject(i) ?: continue
            val correction = readableDatabase.rawQuery("SELECT body FROM ocr_corrections WHERE page_id=? AND region_id=?",
                arrayOf(pageId, region.optString("id"))).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
            if (correction != null && correction.array("strokeIds").toString() == region.array("strokeIds").toString() &&
                correction.optString("inputDigest") == region.optString("inputDigest"))
                region.put("correctedText", correction.optString("text"))
        }
        return result
    }

    fun correctOcrRegion(pageId: String, regionId: String, text: String,
                         expectedInputDigest: String? = null, expectedPageDigest: String? = null): Boolean {
        var applied = false
        transaction {
            val result = ocrResult(pageId) ?: throw IllegalArgumentException("인식 결과가 없습니다.")
            val region = result.array("regions").objects().firstOrNull { it.optString("id") == regionId }
                ?: throw IllegalArgumentException("인식 영역이 없습니다.")
            if ((expectedPageDigest != null && expectedPageDigest != result.optString("pageDigest")) ||
                (expectedInputDigest != null && expectedInputDigest != region.optString("inputDigest")))
                return@transaction
            val correction = json("text" to text, "strokeIds" to region.array("strokeIds"),
                "inputDigest" to region.optString("inputDigest"), "provenance" to "user")
            writableDatabase.insertWithOnConflict("ocr_corrections", null, ContentValues().apply {
                put("page_id", pageId); put("region_id", regionId); put("body", correction.toString())
                put("updated_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            applied = true
        }
        return applied
    }

    fun ocrText(pageId: String): String {
        val result = ocrResult(pageId) ?: return readableDatabase.rawQuery("SELECT 1 FROM ocr_page_state WHERE page_id=?",
            arrayOf(pageId)).use { if (it.moveToFirst()) "" else pageMeta(pageId)?.optString("ocrText").orEmpty() }
        return result.array("regions").objects().mapNotNull { region ->
            if (region.optString("status") in setOf("complete", "recognized", "ok", "cached", "legacy"))
                region.optString("correctedText", region.optString("selectedText")) else null
        }.joinToString("\n")
    }

    /** Previously indexed text is retained with unknown provenance, never treated as model truth. */
    fun migrateLegacyOcr() {
        val rows = readableDatabase.rawQuery("SELECT p.id,p.document_id,p.body FROM pages p LEFT JOIN ocr_page_state o ON o.page_id=p.id WHERE o.page_id IS NULL", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))) }
        }
        rows.forEach { (pageId, documentId, body) ->
            val text = JSONObject(body).optString("ocrText")
            if (text.isBlank()) return@forEach
            val current = page(pageId) ?: return@forEach
            val region = json("id" to "legacy", "strokeIds" to JSONArray(), "order" to 0,
                "status" to "legacy", "rawText" to text, "selectedText" to text,
                "reviewReasons" to JSONArray(listOf("legacyUnverified")), "candidates" to JSONArray())
            val result = json("status" to "partial", "regions" to JSONArray(listOf(region)),
                "reviewReasons" to JSONArray(listOf("legacyUnverified")))
            putOcrResult(documentId, pageId, generation(pageId), OcrInput.pageDigest(current, "legacy"), "legacy", result)
            writableDatabase.insertWithOnConflict("ocr_corrections", null, ContentValues().apply {
                put("page_id", pageId); put("region_id", "legacy")
                put("body", json("text" to text, "strokeIds" to JSONArray(), "provenance" to "legacyUnknown").toString())
                put("updated_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    fun cachedOcr(contentKey: String): JSONObject? {
        val hot = synchronized(memoryCache) { memoryCache[contentKey] }
        val body = hot ?: readableDatabase.rawQuery("SELECT body FROM ocr_cache WHERE content_key=?", arrayOf(contentKey)).use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return null
        if (hot == null) rememberCache(contentKey, body)
        writableDatabase.execSQL("UPDATE ocr_cache SET last_used=? WHERE content_key=?", arrayOf<Any>(System.currentTimeMillis(), contentKey))
        return JSONObject(body)
    }
    private fun rememberCache(key: String, body: String) = synchronized(memoryCache) {
        val bytes = body.toByteArray(Charsets.UTF_8).size
        if (bytes > memoryCacheBudget) return@synchronized
        memoryCache.remove(key)?.let { memoryCacheBytes -= it.toByteArray(Charsets.UTF_8).size }
        memoryCache[key] = body; memoryCacheBytes += bytes
        while (memoryCacheBytes > memoryCacheBudget) {
            val oldest = memoryCache.entries.iterator().next()
            memoryCacheBytes -= oldest.value.toByteArray(Charsets.UTF_8).size
            memoryCache.remove(oldest.key)
        }
    }
    fun clearOcrMemoryCache() = synchronized(memoryCache) { memoryCache.clear(); memoryCacheBytes = 0 }
    fun cacheOcr(contentKey: String, value: JSONObject,
                 budgetBytes: Long = if (lowMemory) 16L * 1024 * 1024 else 32L * 1024 * 1024) {
        val body = value.toString(); val size = body.toByteArray(Charsets.UTF_8).size
        if (size > 2 * 1024 * 1024) return
        transaction {
            writableDatabase.insertWithOnConflict("ocr_cache", null, ContentValues().apply {
                put("content_key", contentKey); put("body", body); put("byte_count", size)
                put("last_used", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            var total = readableDatabase.rawQuery("SELECT COALESCE(SUM(byte_count),0) FROM ocr_cache", null).use { it.moveToFirst(); it.getLong(0) }
            if (total > budgetBytes) {
                val oldest = readableDatabase.rawQuery("SELECT content_key,byte_count FROM ocr_cache ORDER BY last_used,content_key", null).use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) }
                }
                for ((key, bytes) in oldest) {
                    if (total <= budgetBytes) break
                    writableDatabase.delete("ocr_cache", "content_key=?", arrayOf(key)); total -= bytes
                }
            }
        }
        rememberCache(contentKey, body)
    }

    private fun ocrArchive(pageId: String): JSONObject? {
        val result = readableDatabase.rawQuery("SELECT body FROM ocr_page_state WHERE page_id=?", arrayOf(pageId)).use {
            if (it.moveToFirst()) JSONObject(it.getString(0)) else null
        }?.takeIf { ocrResult(pageId) != null }
        val corrections = readableDatabase.rawQuery("SELECT region_id,body,updated_at FROM ocr_corrections WHERE page_id=? ORDER BY region_id", arrayOf(pageId)).use { c ->
            JSONArray().apply { while (c.moveToNext()) put(json("regionId" to c.getString(0),
                "body" to JSONObject(c.getString(1)), "updatedAt" to c.getLong(2))) }
        }
        return if (result == null && corrections.length() == 0) null
            else json("result" to (result ?: JSONObject.NULL), "corrections" to corrections)
    }
    internal fun exportOcrSidecar(pageId: String): JSONObject? = ocrArchive(pageId)
    fun exportLegacy(id: String, target: File, cancelled: () -> Boolean = { false }) =
        LegacyJsonWriter(this, cancelled).write(id, target)

    private fun restoreOcrSidecar(documentId: String, pageId: String, sidecar: JSONObject) {
        val result = sidecar.optJSONObject("result")
        if (result != null) {
            val current = requireNotNull(page(pageId))
            val policy = result.optString("policy")
            result.put("documentId", documentId).put("pageId", pageId)
                .put("pageDigest", OcrInput.pageDigest(current, policy))
                .put("generation", generation(pageId))
            require(putOcrResult(documentId, pageId, generation(pageId), result.getString("pageDigest"), policy, result)) {
                "인식 결과 이전에 실패했습니다."
            }
        }
        sidecar.array("corrections").objects().forEach { correction ->
            writableDatabase.insertWithOnConflict("ocr_corrections", null, ContentValues().apply {
                put("page_id", pageId); put("region_id", correction.getString("regionId"))
                put("body", correction.getJSONObject("body").toString())
                put("updated_at", correction.optLong("updatedAt", System.currentTimeMillis()))
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun asset(id: String): File {
        require(Regex("[A-Za-z0-9_.-]{1,160}").matches(id)) { "자산 경로가 잘못되었습니다." }
        return File(assetDirectory, id)
    }
    fun storeAsset(input: InputStream, extension: String = "bin", validate: (File) -> Unit = {}): String {
        val id = uid("asset") + "." + extension.replace(Regex("[^A-Za-z0-9]"), "").take(12).ifBlank { "bin" }
        storeAssetAtId(input, id, validate)
        return id
    }

    /** The shared writer also permits a fixed ID in collision regression tests. */
    internal fun storeAssetAtId(input: InputStream, id: String, validate: (File) -> Unit = {}) {
        val output = asset(id)
        val pending = asset("$id.pending")
        require(!output.exists()) { "자산 ID 충돌" }
        require(pending.createNewFile()) { "자산 ID 충돌" }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val copied = FileOutputStream(pending).use { stream ->
                val bytes = ArchiveCodec.copy(input, DigestOutputStream(stream, digest))
                stream.fd.sync()
                bytes
            }
            finalizePendingAsset(id, copied, digest.digest(), validate)
        } catch (error: Throwable) { pending.delete(); throw error }
    }

    /** Commit a file already written by the app (including MediaRecorder) without replacing another asset. */
    internal fun finalizePendingAsset(id: String, expectedBytes: Long? = null,
                                      expectedSha256: ByteArray? = null, validate: (File) -> Unit = {}) {
        val output = asset(id)
        val pending = asset("$id.pending")
        try {
            require(pending.isFile) { "임시 자산이 없습니다." }
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            FileInputStream(pending).use { stream ->
                val buffer = ByteArray(ArchiveCodec.BUFFER_SIZE)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    digest.update(buffer, 0, count)
                    bytes += count
                }
            }
            require(bytes == pending.length() && (expectedBytes == null || bytes == expectedBytes) &&
                (expectedSha256 == null || MessageDigest.isEqual(digest.digest(), expectedSha256))) {
                "자산 바이트 검증에 실패했습니다."
            }
            validate(pending)
            // Only our empty reservation may be replaced by rename. The lock covers all
            // repository instances in this app process; createNewFile rejects late collisions.
            synchronized(assetPublicationLock) {
                var reserved = false
                var published = false
                try {
                    require(!output.exists() && output.createNewFile()) { "자산 ID 충돌" }
                    reserved = true
                    Os.rename(pending.path, output.path)
                    published = true
                }
                catch (error: Throwable) {
                    if (reserved && !published) output.delete()
                    if (error is android.system.ErrnoException) throw IOException("자산 확정 실패", error)
                    throw error
                }
            }
        } catch (error: Throwable) {
            pending.delete()
            throw error
        }
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
                ocrArchive(pageId)?.let { sidecar ->
                    yield(ArchiveCodec.Entry("ocr/$pageId.json") { ByteArrayInputStream(sidecar.toString().toByteArray(Charsets.UTF_8)) })
                }
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
        var importedId: String? = null
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
            importedId = id
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
            oldPageIds.forEach { old ->
                if ("ocr/$old.json" !in entries) return@forEach
                val sidecar = JSONObject(File(staging, "ocr/$old.json").readText())
                restoreOcrSidecar(id, pageMap.getValue(old), sidecar)
            }
            migrateLegacyOcr()
            return doc
        } catch (e: Exception) { importedId?.let { deleteDocument(it) }; copied.forEach { it.delete() }; throw e }
        finally { staging.deleteRecursively() }
    }
    fun importLegacy(input: InputStream, folder: String = "root"): DocumentInfo {
        val stage = File(workDirectory, uid("legacy")).apply { mkdirs() }
        val publishedAssets = mutableListOf<File>()
        val pendingAssets = mutableListOf<Triple<String, String, MessageDigest>>()
        val pageMap = mutableMapOf<String, String>()
        var importedId: String? = null
        var pages = 0
        try {
            val meta = LegacyJsonReader(input.reader(Charsets.UTF_8), { mime ->
                val id = uid("asset") + "." + extension(mime)
                val file = asset(id); val pending = asset("$id.pending")
                val digest = MessageDigest.getInstance("SHA-256")
                require(!file.exists() && pending.createNewFile()) { "자산 ID 충돌" }
                pendingAssets += Triple(id, mime, digest)
                "asset:$id" to DigestOutputStream(FileOutputStream(pending), digest)
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
            pendingAssets.forEach { (id, mime, digest) ->
                val pending = asset("$id.pending")
                FileOutputStream(pending, true).use { it.fd.sync() }
                finalizePendingAsset(id, pending.length(), digest.digest()) { source ->
                    if (mime in setOf("image/png", "image/jpeg", "image/webp")) checkedImageSize(source)
                }
                publishedAssets += asset(id)
            }
            val doc = DocumentInfo(uid("doc"), meta.apply {
                put("folderId", folder); put("trashed", false); put("version", 5)
            })
            importedId = doc.id
            doc.data.put("id", doc.id)
            rewritePageReferences(doc.data, pageMap)
            transaction {
                putDocument(doc)
                repeat(pages) { i ->
                    val data = JSONObject(File(stage, "$i.json").readText())
                    val sidecar = data.optJSONObject("_nativeOcrSidecar")?.takeIf { it.has("result") || it.has("corrections") }
                    if (sidecar != null) data.remove("_nativeOcrSidecar")
                    rewritePageReferences(data, pageMap)
                    require(!data.has("backgroundAssetId") || data.has("backgroundImage")) {
                        "이전 노트의 PDF 자산이 누락되었습니다. 기존 앱의 파일 내보내기로 다시 저장해 주세요."
                    }
                    data.remove("backgroundAssetId")
                    val page = NotePage.from(data)
                    putPage(doc.id, page, i)
                    if (sidecar != null) restoreOcrSidecar(doc.id, page.id, sidecar)
                }
            }
            migrateLegacyOcr()
            return doc
        } catch (e: Exception) {
            importedId?.let { id -> runCatching { deleteDocument(id) }.onFailure { e.addSuppressed(it) } }
            pendingAssets.forEach { asset("${it.first}.pending").delete() }
            publishedAssets.forEach { it.delete() }
            throw e
        }
        finally { stage.deleteRecursively() }
    }
    fun importNote(input: InputStream, folder: String): DocumentInfo {
        val buffered = input.buffered(ArchiveCodec.BUFFER_SIZE)
        buffered.mark(4); val first = buffered.read(); val second = buffered.read(); buffered.reset()
        return if (first == 80 && second == 75) importArchive(buffered, folder) else importLegacy(buffered, folder)
    }
    companion object {
        private val assetPublicationLock = Any()
        /** Decode a bounded sample, not only the header; corrupt imports never reach the final name. */
        internal fun checkedImageSize(file: File): Pair<Int, Int> {
            RandomAccessFile(file, "r").use { source ->
                val length = source.length()
                require(length >= 12) { "이미지가 비어 있거나 잘렸습니다." }
                val header = ByteArray(12)
                source.readFully(header)
                when {
                    header.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10)) -> {
                        source.seek(length - 12)
                        val end = ByteArray(12)
                        source.readFully(end)
                        require(end.contentEquals(byteArrayOf(0,0,0,0,73,69,78,68,174.toByte(),66,96,130.toByte()))) {
                            "PNG 이미지가 잘렸습니다."
                        }
                    }
                    header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() -> {
                        source.seek(length - 2)
                        require(source.read() == 0xff && source.read() == 0xd9) { "JPEG 이미지가 잘렸습니다." }
                    }
                    header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)) &&
                        header.copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.US_ASCII)) -> {
                        val declared = (header[4].toLong() and 255) or ((header[5].toLong() and 255) shl 8) or
                            ((header[6].toLong() and 255) shl 16) or ((header[7].toLong() and 255) shl 24)
                        require(declared + 8 == length) { "WebP 이미지가 잘렸습니다." }
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            require(width > 0 && height > 0) { "이미지를 읽지 못했습니다." }
            var sample = 1
            while (((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample) > 4_194_304 ||
                width.toLong() / sample > 4096 || height.toLong() / sample > 4096) {
                require(sample < 1 shl 29) { "이미지 크기를 읽지 못했습니다." }
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = requireNotNull(BitmapFactory.decodeFile(file.path, options)) { "이미지를 읽지 못했습니다." }
            try { require(decoded.width > 0 && decoded.height > 0) { "이미지를 읽지 못했습니다." } }
            finally { decoded.recycle() }
            return width to height
        }

        fun extension(mime: String) = when (mime.substringBefore(';')) {
            "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"
            "audio/webm" -> "webm"; "audio/mp4" -> "m4a"; "audio/ogg" -> "ogg"
            "application/pdf" -> "pdf"; else -> "bin"
        }
        fun collectAssets(value: Any?, into: MutableSet<String>) {
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    val child = value.opt(key)
                    if (isAssetField(key) && child is String && child.startsWith("asset:")) into += child.removePrefix("asset:")
                    else if (child !is String) collectAssets(child, into)
                }
                is JSONArray -> (0 until value.length()).forEach { collectAssets(value.opt(it), into) }
            }
        }
        fun rewriteAssets(value: Any?, map: Map<String, String>) {
            when (value) {
                is JSONObject -> value.keys().asSequence().toList().forEach { key ->
                    val v = value.opt(key)
                    if (isAssetField(key) && v is String && v.startsWith("asset:")) value.put(key, "asset:" + requireNotNull(map[v.removePrefix("asset:")]) { "자산 참조가 누락되었습니다." })
                    else rewriteAssets(v, map)
                }
                is JSONArray -> (0 until value.length()).forEach { i ->
                    val v = value.opt(i)
                    rewriteAssets(v, map)
                }
            }
        }
    }
    override fun close() { clearOcrMemoryCache(); executor.shutdown(); room.close() }
}
