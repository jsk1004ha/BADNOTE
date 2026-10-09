package com.inkforge.notesstudio

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

/** Real v1 SQLite schema fixture; no production database or user asset is touched. */
object DatabaseMigrationChecks {
    fun run(context: Context) {
        val name = "ocr-migration-" + UUID.randomUUID() + ".db"
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                old.execSQL("CREATE TABLE documents(id TEXT PRIMARY KEY,body TEXT NOT NULL,updated INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE pages(id TEXT PRIMARY KEY,document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,position INTEGER NOT NULL,body TEXT NOT NULL)")
                old.execSQL("CREATE INDEX page_order ON pages(document_id,position)")
                old.execSQL("CREATE TABLE objects(id TEXT NOT NULL,page_id TEXT NOT NULL REFERENCES pages(id) ON DELETE CASCADE,position INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(page_id,id))")
                old.execSQL("CREATE INDEX object_order ON objects(page_id,position)")
                old.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,body TEXT NOT NULL)")
                old.execSQL("INSERT INTO documents VALUES('d','{\"unknown\":{\"keep\":true}}',7)")
                old.execSQL("INSERT INTO documents VALUES(NULL,'{\"orphan\":\"untouched\"}',8)")
                old.execSQL("INSERT INTO pages VALUES('p','d',4,'{\"width\":1000,\"newerField\":9}')")
                old.execSQL("INSERT INTO objects VALUES('z','p',3,'{\"id\":\"z\",\"points\":[{\"x\":1,\"p\":0}]}')")
                old.execSQL("INSERT INTO objects VALUES('a','p',3,'{\"id\":\"a\",\"extra\":\"yes\"}')")
                old.execSQL("INSERT INTO settings VALUES('preferences','{\"custom\":42}')")
                old.execSQL("INSERT INTO settings VALUES(NULL,'{\"orphanSetting\":true}')")
                old.version = 1
            }
            val room = NativeDatabase.open(context, name)
            try {
                val db = room.openHelper.writableDatabase
                check(db.version == 2)
                db.query("SELECT body,updated FROM documents WHERE id='d'").use {
                    check(it.moveToFirst() && it.getString(0) == "{\"unknown\":{\"keep\":true}}" && it.getLong(1) == 7L)
                }
                db.query("SELECT position,body FROM pages WHERE id='p'").use {
                    check(it.moveToFirst() && it.getInt(0) == 4 && it.getString(1) == "{\"width\":1000,\"newerField\":9}")
                }
                db.query("SELECT id,position,body FROM objects WHERE page_id='p' ORDER BY position,rowid").use {
                    check(it.moveToFirst() && it.getString(0) == "z" && it.getInt(1) == 3)
                    check(it.getString(2).contains("\"p\":0"))
                    check(it.moveToNext() && it.getString(0) == "a" && it.getInt(1) == 3)
                }
                db.query("SELECT COUNT(*) FROM legacy_v1_null_rows").use { check(it.moveToFirst() && it.getInt(0) == 2) }
                db.query("SELECT body FROM settings WHERE key='preferences'").use {
                    check(it.moveToFirst() && it.getString(0) == "{\"custom\":42}")
                }
                db.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) }
                db.query("PRAGMA index_list(pages)").use { cursor ->
                    var found = false
                    while (cursor.moveToNext()) if (cursor.getString(1) == "page_order") found = true
                    check(found)
                }
                db.query("SELECT COUNT(*) FROM ocr_page_state").use { check(it.moveToFirst() && it.getInt(0) == 0) }
            } finally { room.close() }
            NoteRepository(context, name).use { repository ->
                val page = requireNotNull(repository.page("p"))
                val digest = OcrInput.pageDigest(page, "ko-primary")
                val start = repository.generation("p")
                val region = json("id" to "line:z", "strokeIds" to org.json.JSONArray(listOf("z")),
                    "inputDigest" to "first-input", "status" to "complete", "selectedText" to "raw", "rawText" to "raw")
                val result = json("status" to "complete", "regions" to org.json.JSONArray(listOf(region)))
                check(repository.putOcrResult("d", "p", start, digest, "ko-primary", result))
                repository.correctOcrRegion("p", "line:z", "corrected")
                val original = page.objects.first { it.getString("id") == "z" }
                val changed = original.copyJson().put("newField", "changed")
                repository.commit("d", "p", ObjectChange(listOf(original), listOf(changed)), mapOf("z" to 0, "a" to 1))
                check(!repository.putOcrResult("d", "p", start, digest, "ko-primary", result))
                repository.commit("d", "p", ObjectChange(listOf(changed), listOf(original)), mapOf("z" to 0, "a" to 1))
                check(OcrInput.pageDigest(requireNotNull(repository.page("p")), "ko-primary") == digest)
                check(repository.generation("p") == start + 2)
                check(repository.ocrResult("p") == null)
                region.put("inputDigest", "different-input")
                check(repository.putOcrResult("d", "p", repository.generation("p"), digest, "ko-primary", result))
                check(repository.ocrText("p") == "raw")
                region.put("inputDigest", "first-input")
                check(repository.putOcrResult("d", "p", repository.generation("p"), digest, "ko-primary", result))
                check(repository.ocrText("p") == "corrected")
                val duplicate = repository.duplicatePage("d", "p", 1)
                check(duplicate.id != "p" && duplicate.objects.map { it.getString("id") }.none { it in setOf("z", "a") })
                check(repository.ocrText(duplicate.id) == "corrected")
                check(repository.ocrResult(duplicate.id)?.optString("pageDigest") ==
                    OcrInput.pageDigest(requireNotNull(repository.page(duplicate.id)), "ko-primary"))
                val archive = java.io.File.createTempFile("ocr-sidecar-", ".ifnote", context.cacheDir)
                try {
                    repository.export("d", archive)
                    val copy = archive.inputStream().use { repository.importArchive(it) }
                    val copiedPages = repository.pageIds(copy.id)
                    check(copiedPages.size == 2 && copiedPages.all { repository.ocrText(it) == "corrected" })
                    val newPage = copiedPages.first()
                    check(copy.data.getJSONObject("unknown").getBoolean("keep"))
                    check(repository.ocrText(newPage) == "corrected")
                    check(requireNotNull(repository.page(newPage)).objects.any { it.optString("extra") == "yes" })
                    repository.deleteDocument(copy.id)
                    check(repository.ocrResult(newPage) == null)
                } finally { archive.delete() }
                val imageDigest = OcrInput.pageDigest(requireNotNull(repository.page("p")), "ko-primary")
                val imageRegion = json("id" to "image", "strokeIds" to org.json.JSONArray(),
                    "inputDigest" to "rendered:first", "status" to "complete", "rawText" to "image raw",
                    "selectedText" to "image raw")
                val imageResult = json("status" to "complete", "regions" to org.json.JSONArray(listOf(imageRegion)))
                check(repository.putOcrResult("d", "p", repository.generation("p"), imageDigest, "ko-primary", imageResult))
                repository.correctOcrRegion("p", "image", "image corrected")
                check(repository.ocrText("p") == "image corrected")
                imageRegion.put("inputDigest", "rendered:changed")
                check(repository.putOcrResult("d", "p", repository.generation("p"), imageDigest, "ko-primary", imageResult))
                check(repository.ocrText("p") == "image raw")
            }
            NativeDatabase.open(context, name).let { again ->
                try { again.openHelper.readableDatabase.query("SELECT body FROM ocr_corrections WHERE page_id='p' AND region_id='line:z'").use {
                    check(it.moveToFirst() && it.getString(0).contains("corrected"))
                } } finally { again.close() }
            }
            val brokenName = "ocr-migration-broken-" + UUID.randomUUID() + ".db"
            val brokenPath = context.getDatabasePath(brokenName)
            try {
                SQLiteDatabase.openOrCreateDatabase(brokenPath, null).use { old ->
                    old.execSQL("CREATE TABLE documents(id TEXT PRIMARY KEY,body TEXT NOT NULL,updated INTEGER NOT NULL)")
                    old.execSQL("CREATE TABLE pages(id TEXT PRIMARY KEY,document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,position INTEGER NOT NULL,body TEXT NOT NULL)")
                    old.execSQL("CREATE INDEX page_order ON pages(document_id,position)")
                    old.execSQL("CREATE TABLE objects(id TEXT NOT NULL,page_id TEXT NOT NULL REFERENCES pages(id) ON DELETE CASCADE,position INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(page_id,id))")
                    old.execSQL("CREATE INDEX object_order ON objects(page_id,position)")
                    old.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,body TEXT NOT NULL)")
                    old.execSQL("INSERT INTO pages VALUES('orphan','missing',0,'{\"keep\":true}')")
                    old.version = 1
                }
                val invalidRoom = NativeDatabase.open(context, brokenName)
                var rejected = false
                try { invalidRoom.openHelper.writableDatabase } catch (_: Exception) { rejected = true }
                finally { invalidRoom.close() }
                check(rejected)
                SQLiteDatabase.openDatabase(brokenPath.path, null, SQLiteDatabase.OPEN_READONLY).use { original ->
                    check(original.version == 1)
                    original.rawQuery("SELECT body FROM pages WHERE id='orphan'", null).use {
                        check(it.moveToFirst() && it.getString(0) == "{\"keep\":true}")
                    }
                }
            } finally { context.deleteDatabase(brokenName) }
        } finally { context.deleteDatabase(name) }
    }
}
