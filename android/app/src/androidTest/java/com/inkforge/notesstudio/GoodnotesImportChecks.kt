package com.inkforge.notesstudio

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max

/** Public-corpus coverage for editable Goodnotes documents and manual-backup archives. */
internal object GoodnotesImportChecks {
    private data class Fixture(
        val name: String,
        val sha256: String,
        val inkPerPage: List<Int>,
        val textPerPage: List<Int>,
        val imagePerPage: List<Int>,
        val omittedTape: Int = 0,
    )

    private data class PageSnapshot(val meta: JSONObject, val objects: List<JSONObject>)
    private data class DocumentSnapshot(val pages: List<PageSnapshot>, val assetDigests: List<String>)

    private val fixtures = listOf(
        Fixture("test2.goodnotes", "e9a702d8f8246e917b56356ad1b656f4dfb0743a2b45c25042cffb53a2b35fda",
            listOf(1), listOf(0), listOf(0)),
        Fixture("Test5.goodnotes", "14a9a910537d4deeee38d36ecd568484d0cee9d6f5d1bf7b62a16f3ce7606779",
            listOf(0, 17, 47), listOf(0, 0, 2), listOf(0, 0, 1), omittedTape = 1),
        Fixture("Test6.goodnotes", "77e3a6485cc76fcc0b5372f4733fb4d0b911ac83541a65a7576f1e27b41b0484",
            listOf(13, 1, 3, 0, 1), listOf(1, 1, 1, 1, 1), listOf(0, 0, 0, 0, 0)),
        Fixture("Test9.goodnotes", "7d6b1ef60e2cd2c0f20b3dde6871535987e3ab6958f8dd493c33a15197a6fb47",
            listOf(0, 120, 0, 11, 0, 22, 6), listOf(0, 0, 4, 0, 0, 0, 0),
            listOf(0, 0, 1, 1, 0, 0, 0), omittedTape = 3),
        Fixture("Test_13.goodnotes", "dae95bcafe311b18289ef606a498d3b2197b4a738c19da0192b28205de147b0e",
            listOf(0, 0), listOf(0, 1), listOf(0, 0)),
    )

    fun run(context: Context, result: Bundle) {
        check(NoteInterchange.format("editable.goodnotes", null) == NoteInterchange.Format.GOODNOTES)
        check(NoteInterchange.format("manual.goodnotes.zip", null) == NoteInterchange.Format.GOODNOTES_BACKUP)

        val fixtureDirectory = File(context.filesDir, "goodnotes-fixtures")
        val sources = fixtures.associateWith { fixture ->
            File(fixtureDirectory, fixture.name).also { file ->
                check(file.isFile && sha256(file) == fixture.sha256) { "Goodnotes fixture changed: ${fixture.name}" }
            }
        }
        val isolated = IsolatedContext(context)
        val roundTrip = File(isolated.cacheDir, "test5.ifnote")
        val importedIds = linkedMapOf<String, String>()
        val editMarker = " BADNOTE 편집"
        try {
            NoteRepository(isolated, isolated.databaseName).use { repository ->
                fixtures.forEach { fixture ->
                    val result = sources.getValue(fixture).inputStream().use { input ->
                        GoodnotesImporter.read(repository, input, fixture.name.substringBeforeLast('.'), "root")
                    }
                    val imported = result.documents.single()
                    if (fixture.omittedTape > 0) check(result.skipped == fixture.omittedTape) {
                        "${fixture.name}: expected only ${fixture.omittedTape} unsupported tape objects, got ${result.skipped}"
                    }
                    importedIds[fixture.name] = imported.id
                    assertFixture(repository, imported, fixture)
                }

                val oneStroke = requireObject(repository, importedIds.getValue("test2.goodnotes"), "stroke")
                val changedStroke = oneStroke.third.copyJson().put("color", "#123456")
                changedStroke.getJSONArray("points").getJSONObject(0).let { point ->
                    point.put("x", point.getDouble("x") + .25)
                }
                repository.commit(oneStroke.first, oneStroke.second, ObjectChange(listOf(oneStroke.third), listOf(changedStroke)),
                    mapOf(changedStroke.getString("id") to oneStroke.fourth))

                val richText = requireObject(repository, importedIds.getValue("Test9.goodnotes"), "text")
                val changedText = richText.third.copyJson().put("text", richText.third.optString("text") + editMarker)
                repository.commit(richText.first, richText.second, ObjectChange(listOf(richText.third), listOf(changedText)),
                    mapOf(changedText.getString("id") to richText.fourth))

                val test5 = requireNotNull(repository.document(importedIds.getValue("Test5.goodnotes")))
                val expected = snapshot(repository, test5.id)
                repository.export(test5.id, roundTrip)
                result.putString("goodnotesRoundTripExpected", expected.pages.joinToString(",") { it.objects.size.toString() })
            }

            NoteRepository(isolated, isolated.databaseName).use { repository ->
                val reopenedStroke = requireObject(repository, importedIds.getValue("test2.goodnotes"), "stroke").third
                check(reopenedStroke.optString("color") == "#123456") { "Edited Goodnotes stroke did not survive reopen" }
                val reopenedText = requireObject(repository, importedIds.getValue("Test9.goodnotes"), "text").third
                check(reopenedText.optString("text").endsWith(editMarker)) { "Edited Goodnotes text did not survive reopen" }

                val original = snapshot(repository, importedIds.getValue("Test5.goodnotes"))
                val copy = roundTrip.inputStream().use { repository.importArchive(it) }
                assertRoundTrip(original, snapshot(repository, copy.id))

                backupChecks(repository, sources)
                rollbackChecks(repository, sources.getValue(fixtures.first()))
            }
            result.putInt("goodnotesPublicFixtures", fixtures.size)
            result.putInt("goodnotesPublicPages", fixtures.sumOf { it.inkPerPage.size })
            result.putString("goodnotesEditable", "stroke_text_reopen_ifnote_geometry_assets_verified")
            result.putString("goodnotesBackup", "zip_order_nested_folders_atomic_rollback_verified")
        } finally {
            roundTrip.delete()
            context.deleteDatabase(isolated.databaseName)
            isolated.filesDir.deleteRecursively()
            isolated.cacheDir.deleteRecursively()
        }
    }

    private fun assertFixture(repository: NoteRepository, document: DocumentInfo, fixture: Fixture) {
        val pages = repository.pageIds(document.id).map { requireNotNull(repository.page(it)) }
        check(pages.size == fixture.inkPerPage.size) { "${fixture.name}: ${pages.size} live pages" }
        fun count(page: NotePage, types: Set<String>) = page.objects.count { it.optString("type") in types }
        check(pages.map { count(it, setOf("stroke", "shape")) } == fixture.inkPerPage) { "${fixture.name}: ink corpus changed" }
        check(pages.map { count(it, setOf("text")) } == fixture.textPerPage) { "${fixture.name}: text corpus changed" }
        check(pages.map { count(it, setOf("image")) } == fixture.imagePerPage) { "${fixture.name}: image corpus changed" }
        pages.forEach { page ->
            check(page.meta.optString("goodnotesPageId").isNotBlank()) { "${fixture.name}: source page identity lost" }
            check(page.width > 32f && page.height > 32f) { "${fixture.name}: invalid page geometry" }
            val assets = linkedSetOf<String>()
            NoteRepository.collectAssets(page.json(), assets)
            assets.forEach { id -> check(repository.asset(id).isFile && repository.asset(id).length() > 0) { "Missing imported asset: $id" } }
        }
        if (fixture.name == "Test5.goodnotes") {
            check(pages.any { it.meta.optString("pdfSource").startsWith("asset:") }) { "Test5 PDF paper was flattened or lost" }
            val text = pages.flatMap { it.objects }.filter { it.optString("type") == "text" }
            check(text.size == 2 && text.all { it.optString("text").isNotBlank() && it.optInt("textLayoutVersion") == 1 }) {
                "Test5 editable RTF text was flattened or lost"
            }
        }
        if (fixture.name == "Test9.goodnotes") {
            val text = pages.flatMap { it.objects }.filter { it.optString("type") == "text" }
            check(text.size == 4 && text.all { it.has("textRuns") && it.optInt("textLayoutVersion") == 1 }) {
                "Test9 modern rich text runs were flattened or lost"
            }
        }
        if (fixture.name == "Test6.goodnotes") {
            val filled = pages.flatMap { it.objects }.filter { it.has("fillColor") }
            check(filled.size == 3 && filled.any { it.optString("goodnotesShape") == "polygon" }) {
                "Goodnotes ellipse or polygon fill did not survive import"
            }
        }
        if (fixture.name == "Test_13.goodnotes") {
            val text = pages.last().objects.single()
            check(text.optBoolean("fullPageText") && text.getString("text").count { it == '\n' } == 21 &&
                text.getJSONArray("textRuns").length() > 20) { "Goodnotes flow text lost paragraphs or styles" }
        }
    }

    /** Returns document id, page id, object, and its persisted position. */
    private fun requireObject(repository: NoteRepository, documentId: String, type: String): Quadruple<String, String, JSONObject, Int> {
        repository.pageIds(documentId).forEach { pageId ->
            val page = requireNotNull(repository.page(pageId))
            val position = page.objects.indexOfFirst { it.optString("type") == type }
            if (position >= 0) return Quadruple(documentId, pageId, page.objects[position], position)
        }
        error("Imported Goodnotes document has no editable $type")
    }

    private fun snapshot(repository: NoteRepository, documentId: String): DocumentSnapshot {
        val assets = linkedSetOf<String>()
        val pages = repository.pageIds(documentId).map { pageId ->
            val page = requireNotNull(repository.page(pageId))
            NoteRepository.collectAssets(page.json(), assets)
            PageSnapshot(page.meta.copyJson(), page.objects.map { it.copyJson() })
        }
        repository.document(documentId)?.data?.let { NoteRepository.collectAssets(it, assets) }
        return DocumentSnapshot(pages, assets.map { id -> sha256(repository.asset(id)) }.sorted())
    }

    private fun assertRoundTrip(expected: DocumentSnapshot, actual: DocumentSnapshot) {
        check(actual.pages.size == expected.pages.size) { "IFNOTE round-trip changed page count" }
        expected.pages.zip(actual.pages).forEachIndexed { pageIndex, (before, after) ->
            assertNumber(before.meta, after.meta, "width", "page[$pageIndex]")
            assertNumber(before.meta, after.meta, "height", "page[$pageIndex]")
            check(before.objects.size == after.objects.size) { "page[$pageIndex]: object count changed" }
            before.objects.zip(after.objects).forEachIndexed { objectIndex, (old, new) ->
                val path = "page[$pageIndex].object[$objectIndex]"
                check(old.optString("type") == new.optString("type")) { "$path: type changed" }
                if (old.has("text")) check(old.optString("text") == new.optString("text")) { "$path: text changed" }
                geometryKeys.forEach { key ->
                    if (old.has(key)) {
                        check(new.has(key)) { "$path.$key missing" }
                        assertGeometry(old.get(key), new.get(key), "$path.$key")
                    }
                }
            }
        }
        check(actual.assetDigests == expected.assetDigests) { "IFNOTE round-trip changed embedded asset bytes" }
    }

    private fun assertGeometry(expected: Any?, actual: Any?, path: String) {
        when (expected) {
            is Number -> {
                check(actual is Number) { "$path: numeric cell changed type" }
                val a = expected.toDouble(); val b = actual.toDouble()
                check(abs(a - b) <= max(.001, abs(a) * 1e-5)) { "$path: $a != $b" }
            }
            is JSONArray -> {
                check(actual is JSONArray && actual.length() == expected.length()) { "$path: cell count changed" }
                for (index in 0 until expected.length()) assertGeometry(expected.opt(index), actual.opt(index), "$path[$index]")
            }
            is JSONObject -> {
                check(actual is JSONObject) { "$path: geometry object changed type" }
                expected.keys().forEach { key ->
                    check(actual.has(key)) { "$path.$key missing" }
                    assertGeometry(expected.opt(key), actual.opt(key), "$path.$key")
                }
            }
            else -> check(expected == actual) { "$path changed" }
        }
    }

    private fun assertNumber(expected: JSONObject, actual: JSONObject, key: String, path: String) {
        assertGeometry(expected.get(key), actual.get(key), "$path.$key")
    }

    private fun backupChecks(repository: NoteRepository, sources: Map<Fixture, File>) {
        val test2 = sources.getValue(fixtures.first()).readBytes()
        val test6 = sources.getValue(fixtures[2]).readBytes()
        val backup = zip(
            "Semester/Week 2/02 Test 6.goodnotes" to test6,
            "Semester/Week 1/01 Test 2.goodnotes" to test2,
        )
        val imported = backup.inputStream().use {
            GoodnotesImporter.read(repository, it, "Manual backup", "root", backup = true)
        }
        check(imported.skipped == 0) { "Goodnotes backup skipped ${imported.skipped} expected elements" }
        check(imported.documents.map { it.title } == listOf("02 Test 6", "01 Test 2")) {
            "Goodnotes backup ZIP order or title changed"
        }
        val folders = requireNotNull(imported.folders) { "Goodnotes backup omitted nested folders" }
        fun folder(title: String) = folders.objects().single { it.optString("title") == title }
        val semester = folder("Semester")
        val week2 = folder("Week 2")
        val week1 = folder("Week 1")
        check(semester.optString("parentId") == "root" && week2.optString("parentId") == semester.getString("id") &&
            week1.optString("parentId") == semester.getString("id")) { "Goodnotes backup folder hierarchy changed" }
        check(imported.documents[0].folder == week2.getString("id") && imported.documents[1].folder == week1.getString("id")) {
            "Goodnotes backup documents were assigned to the wrong folders"
        }
    }

    private fun rollbackChecks(repository: NoteRepository, valid: File) {
        val documentsBefore = repository.documents().map { it.id }.toSet()
        val assetsBefore = repository.assetDirectory.listFiles().orEmpty().associate { it.name to sha256(it) }
        val foldersBefore = repository.setting("folders", "[]")
        val broken = zip(
            "Rollback/01 valid.goodnotes" to valid.readBytes(),
            "Rollback/02 broken.goodnotes" to "not a Goodnotes archive".toByteArray(),
        )
        check(runCatching {
            broken.inputStream().use { GoodnotesImporter.read(repository, it, "Broken backup", "root", backup = true) }
        }.isFailure) { "Malformed second Goodnotes document did not fail the backup" }
        check(repository.documents().map { it.id }.toSet() == documentsBefore) { "Failed backup left an imported document" }
        check(repository.setting("folders", "[]") == foldersBefore) { "Failed backup left imported folders" }
        check(repository.assetDirectory.listFiles().orEmpty().associate { it.name to sha256(it) } == assetsBefore) {
            "Failed backup left or changed an imported asset"
        }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        bytes.toByteArray()
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private val geometryKeys = setOf("x", "y", "w", "h", "x1", "y1", "x2", "y2", "width", "height",
        "rotation", "fontSize", "opacity", "points", "path", "vertices", "transform")

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private class IsolatedContext(context: Context) : ContextWrapper(context) {
        private val prefix = "goodnotes-import-${UUID.randomUUID()}-"
        val databaseName = prefix + "badnote-native.db"
        override fun getFilesDir() = File(baseContext.filesDir, prefix).apply { mkdirs() }
        override fun getCacheDir() = File(baseContext.cacheDir, prefix).apply { mkdirs() }
    }
}
