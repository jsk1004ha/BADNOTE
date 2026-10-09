package com.inkforge.notesstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Runs only against named synthetic fixtures copied into this debug app's private files. */
object PdfIntegrationChecks {
    fun run(context: Context, instrument: android.app.Instrumentation) {
        val fixtures = File(context.filesDir, "pdf-fixtures")
        val original = File(fixtures, "original-text-rotated-blank.pdf")
        val encrypted = File(fixtures, "password-protected.pdf")
        val malformed = File(fixtures, "malformed.pdf")
        val long = File(fixtures, "original-500-pages.pdf")
        require(listOf(original, encrypted, malformed, long).all(File::isFile)) { "pdfFixturesMissing" }

        PdfTextSession.open(original).use { session ->
            check(session.pageCount == 3)
            val first = session.pageText(0)
            check("Left column alpha" in first && "Right column beta" in first && "한글" in first)
            check("Rotated cropped" in session.pageText(1))
            check(session.pageText(2).isBlank())
        }
        val before = fdCount()
        repeat(30) { PdfTextSession.open(original).use { it.pageText(0) } }
        check(fdCount() <= before + 2) { "pdfiumSessionFdLeak" }
        check(runCatching { PdfTextSession.open(encrypted).use { it.pageCount } }.exceptionOrNull()
            .let { it is PdfTextFailure && it.code == "pdfPasswordRequired" })
        check(runCatching { PdfTextSession.open(malformed).use { it.pageCount } }.exceptionOrNull()
            .let { it is PdfTextFailure && it.code == "pdfMalformed" })

        val database = "pdf-s4-" + UUID.randomUUID() + ".db"
        val assetNames = File(context.filesDir, "native-assets").listFiles()?.map(File::getName)?.toSet().orEmpty()
        val output = File(context.cacheDir, "pdf-s4-export.ifnote")
        val cancelledOutput = File(context.cacheDir, "pdf-s4-cancelled.ifnote")
        try {
            NoteRepository(context, database).use { repository ->
                val unchangedAssets=repository.assetDirectory.list()?.toSet().orEmpty()
                val unchangedDocuments=repository.documents().map { it.id }
                listOf("empty" to ByteArray(0), "malformed" to malformed.readBytes(),
                    "encrypted" to encrypted.readBytes()).forEach { (label, bytes) ->
                    check(runCatching { bytes.inputStream().use { PdfImporter.import(repository, it, label, "root") } }.isFailure) {
                        "$label PDF reached finalization"
                    }
                    check(repository.documents().map { it.id } == unchangedDocuments &&
                        repository.assetDirectory.list()?.toSet().orEmpty() == unchangedAssets) {
                        "$label PDF left a document or asset"
                    }
                }
                val complete = CountDownLatch(1)
                val failure = AtomicReference<String?>()
                val document = original.inputStream().use { source ->
                    PdfImporter.import(repository, source, "PDF original", "root", changed = { _, pagesChanged, error ->
                        if (!pagesChanged) { failure.set(error); complete.countDown() }
                    })
                }
                val firstId = repository.pageIds(document.id).first()
                val firstPage = requireNotNull(repository.page(firstId))
                check(firstPage.meta.optInt("pdfPointWidth") > 0 && firstPage.meta.optInt("pdfPointHeight") > 0)
                check(firstPage.meta.optString("pdfSource").startsWith("asset:"))
                val sourceId=firstPage.meta.getString("pdfSource").removePrefix("asset:")
                check(repository.asset(sourceId).readBytes().contentEquals(original.readBytes())) { "PDF source bytes changed" }
                check(!repository.asset("$sourceId.pending").exists()) { "Validated PDF left a pending file" }
                check(complete.await(60, TimeUnit.SECONDS)) { "pdfIndexTimeout" }
                check(failure.get() == null) { "pdfIndexFailed:${failure.get()}" }
                val imported = repository.pageIds(document.id)
                check(imported.size == 3)
                check("한글" in requireNotNull(repository.pageMeta(imported[0])).optString("pdfText"))
                check("Rotated cropped" in requireNotNull(repository.pageMeta(imported[1])).optString("pdfText"))
                check(requireNotNull(repository.pageMeta(imported[2])).optString("pdfText").isBlank())

                val latest = requireNotNull(repository.pageMeta(firstId)).put("unknownPdfField", "retained")
                repository.putPageMeta(firstId, latest)
                check(!repository.mergePdfText(firstId, "asset:wrong-source", 1, "stale", "pdfium-8086"))
                check(requireNotNull(repository.pageMeta(firstId)).optString("unknownPdfField") == "retained")

                val tileReady = CountDownLatch(1)
                lateinit var loader: BackgroundLoader
                instrument.runOnMainSync {
                    loader = BackgroundLoader(context, repository, { tileReady.countDown() })
                    val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                    try { loader.beginFrame(); loader.draw(Canvas(bitmap), firstPage, RectF(0f, 0f, 512f, 512f), 1f); loader.endFrame() }
                    finally { bitmap.recycle() }
                }
                check(tileReady.await(20, TimeUnit.SECONDS)) { "pdfVisibleTileTimeout" }
                instrument.runOnMainSync { loader.close() }

                val image = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(400 * 400)
                java.util.Random(7).let { random -> pixels.indices.forEach { pixels[it] = random.nextInt() or -0x1000000 } }
                image.setPixels(pixels, 0, 400, 0, 0, 400, 400)
                val imageBytes = java.io.ByteArrayOutputStream().use { buffer ->
                    try { check(image.compress(Bitmap.CompressFormat.PNG, 100, buffer));buffer.toByteArray() }
                    finally { image.recycle() }
                }
                val pngId = imageBytes.inputStream().use { repository.storeAsset(it, "png") }
                val extra = NotePage.blank("blank")
                extra.meta.put("unknownPageField", "kept")
                extra.objects += json("id" to "stroke-kept", "type" to "stroke", "points" to JSONArray())
                extra.objects += json("id" to "image-kept", "type" to "image", "src" to "asset:$pngId", "unknownObjectField" to 17)
                repository.addPage(document.id, extra, 3)
                val region = json("id" to "line:stroke-kept", "strokeIds" to JSONArray(listOf("stroke-kept")),
                    "inputDigest" to "binding-kept", "status" to "complete", "rawText" to "raw", "selectedText" to "raw")
                val result = json("policy" to "ko-primary", "status" to "complete", "regions" to JSONArray(listOf(region)))
                val digest = OcrInput.pageDigest(requireNotNull(repository.page(extra.id)), "ko-primary")
                check(repository.putOcrResult(document.id, extra.id, repository.generation(extra.id), digest, "ko-primary", result))
                repository.correctOcrRegion(extra.id, "line:stroke-kept", "corrected")
                repository.exportLegacy(document.id, output)
                check(output.isFile && output.length() > imageBytes.size)
                val payload = output.readText(Charsets.UTF_8)
                check(payload.contains("data:image/jpeg;base64,") && payload.contains("data:image/png;base64,"))
                check(!payload.contains("asset:")) { "legacyExportLeakedLocalAssetReference" }
                val copy = output.inputStream().use { repository.importNote(it, "root") }
                val copiedIds = repository.pageIds(copy.id)
                check(copiedIds.size == 4)
                val copied = requireNotNull(repository.page(copiedIds.last()))
                check(copied.meta.optString("unknownPageField") == "kept")
                check(copied.objects.map { it.optString("id") } == listOf("stroke-kept", "image-kept"))
                check(copied.objects.last().optInt("unknownObjectField") == 17)
                check(repository.ocrText(copied.id) == "corrected")
                check(repository.asset(copied.objects.last().getString("src").removePrefix("asset:")).length() == imageBytes.size.toLong())
                check(requireNotNull(repository.page(copiedIds.first())).meta.optString("backgroundImage").startsWith("asset:"))
                check(!requireNotNull(repository.page(copiedIds.first())).meta.has("pdfSource"))

                var checks = 0
                val cancellation = runCatching { repository.exportLegacy(document.id, cancelledOutput) { ++checks > 15 } }
                check(cancellation.isFailure && !cancelledOutput.exists())
                repository.deleteDocument(copy.id)
                repository.deleteDocument(document.id)

                val longFinished = CountDownLatch(1)
                val longError = AtomicReference<String?>()
                val longDocument = long.inputStream().use { source ->
                    PdfImporter.import(repository, source, "500 pages", "root", changed = { _, pagesChanged, error ->
                        if (!pagesChanged) { longError.set(error); longFinished.countDown() }
                    })
                }
                val longFirst = repository.pageIds(longDocument.id).first()
                check(requireNotNull(repository.pageMeta(longFirst)).optInt("pdfPointWidth") > 0)
                check(longFinished.await(180, TimeUnit.SECONDS)) { "pdf500IndexTimeout" }
                check(longError.get() == null) { "pdf500IndexFailed:${longError.get()}" }
                val longIds = repository.pageIds(longDocument.id)
                check(longIds.size == 500) { "pdf500PageCount:${longIds.size}" }
                check("BADNOTE large PDF original page 1" in requireNotNull(repository.pageMeta(longIds.first())).optString("pdfText"))
                check("BADNOTE large PDF original page 500" in requireNotNull(repository.pageMeta(longIds.last())).optString("pdfText"))

                val source = requireNotNull(repository.pageMeta(longFirst)).optString("pdfSource")
                val stoppedId = "stopped-import-${UUID.randomUUID()}"
                val stoppedDocId = "stopped-doc-${UUID.randomUUID()}"
                val stopped = DocumentInfo(stoppedDocId, json("id" to stoppedDocId, "title" to "pending", "folderId" to "root"))
                val stopFirst = NotePage.blank("blank").apply { meta.put("pdfSource", source)
                    .put("pdfPageNumber", 1).put("pdfImportId", stoppedId).put("pdfImportPending", true)
                    .put("pdfTotalPages", 3) }
                val stopSecond = NotePage.blank("blank").apply { meta.put("pdfSource", source)
                    .put("pdfPageNumber", 2).put("pdfImportId", stoppedId) }
                repository.transaction {
                    repository.putDocument(stopped)
                    repository.putPage(stoppedDocId, stopFirst, 0)
                    repository.putPage(stoppedDocId, stopSecond, 1)
                }
                repository.removePage(stoppedDocId, stopSecond.id)
                check(!requireNotNull(repository.pageMeta(stopFirst.id)).optBoolean("pdfImportPending"))
                val latePage = NotePage.blank("blank")
                check(!repository.appendPdfPage(stoppedDocId, stopFirst.id, stopFirst.id, stoppedId, source, latePage))
                PdfImporter.resumePending(repository)
                SystemClock.sleep(500)
                check(repository.pageIds(stoppedDocId) == listOf(stopFirst.id)) { "deletedPdfPageResurrected" }
                repository.deleteDocument(stoppedDocId)
                repository.deleteDocument(longDocument.id)

                val cancelledDocument = long.inputStream().use { PdfImporter.import(repository, it, "cancelled", "root") }
                repository.deleteDocument(cancelledDocument.id)
                SystemClock.sleep(500)
                check(repository.document(cancelledDocument.id) == null && repository.pageIds(cancelledDocument.id).isEmpty())
            }
        } finally {
            context.deleteDatabase(database)
            output.delete(); cancelledOutput.delete()
            File(context.filesDir, "native-assets").listFiles()?.filter { it.name !in assetNames }?.forEach(File::delete)
        }
    }

    private fun fdCount() = File("/proc/self/fd").list()?.size ?: 0
}
