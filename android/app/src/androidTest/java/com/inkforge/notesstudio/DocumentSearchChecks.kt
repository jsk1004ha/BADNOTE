package com.inkforge.notesstudio

import android.content.Context
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Consumer-level checks using a real tiny PDF, stored OCR sidecars and ordinary text objects. */
internal object DocumentSearchChecks {
    fun run(context: Context, fixtureContext: Context) {
        val database = "document-search-${UUID.randomUUID()}.db"
        val ownedAssets = ArrayList<String>()
        try {
            NoteRepository(context, database).use { repository ->
                val asset = tinyPdf().inputStream().use { repository.storeAsset(it, "pdf") }
                ownedAssets += asset
                pdfChecks(repository, asset, ownedAssets)
                positionedGlyphChecks(repository, fixtureContext, ownedAssets)
                ocrAndObjectChecks(repository)
            }
        } finally {
            ownedAssets.forEach { java.io.File(java.io.File(context.filesDir, "native-assets"), it).delete() }
            context.deleteDatabase(database)
        }
    }

    private fun pdfChecks(repository: NoteRepository, asset: String, ownedAssets: MutableList<String>) {
        val document = repository.create("Search PDF synthetic", "root", "blank")
        val firstId = repository.pageIds(document.id).single()
        fun pdfPage(id: String, number: Int, height: Float) = NotePage(id,
            json("width" to 1000f, "height" to height, "pdfSource" to "asset:$asset", "pdfPageNumber" to number), mutableListOf())
        val first = pdfPage(firstId, 1, 1000f * 792 / 612)
        // Search is already usable before asynchronous original-text indexing finishes.
        first.meta.put("pdfImportPending", true).put("pdfImportedPages", 3)
        repository.putPage(document.id, first, 0)
        val rotated = pdfPage(uid("page"), 2, 1000f * 468 / 648)
        repository.addPage(document.id, rotated, 1)
        val blank = pdfPage(uid("page"), 3, 1000f)
        repository.addPage(document.id, blank, 2)
        check(!requireNotNull(repository.pageMeta(firstId)).has("pdfText"))
        val alpha = repository.searchDocumentPages(document.id, "ALPHA").single()
        check(alpha.index == 0 && alpha.pageId == firstId && alpha.snippet.contains("alpha"))
        check(alpha.bounds.isNotEmpty() && alpha.bounds.all {
            it.left > 200f && it.right < 350f && it.top > 125f && it.bottom < 175f
        }) { "pdfAlphaCharacterArea:${alpha.bounds}" }
        val beta = repository.searchDocumentPages(document.id, "beta").single()
        check(beta.bounds.isNotEmpty() && beta.bounds.all { it.left > 650f && it.top > 125f && it.bottom < 175f }) {
            "pdfColumnCharacterArea:${beta.bounds}"
        }
        val columns = repository.searchDocumentPages(document.id, "column").single()
        check(columns.bounds.any { it.left < 350f } && columns.bounds.any { it.left > 550f }) {
            "pdfAllOccurrencesMissing:${columns.bounds}"
        }
        val turned = repository.searchDocumentPages(document.id, "Rotated").single()
        check(turned.index == 1 && turned.pageId == rotated.id && turned.bounds.isNotEmpty())
        check(turned.bounds.all { it.left > 900f && it.right < 980f && it.top > 15f && it.bottom < 220f }) {
            "pdfCropRotationArea:${turned.bounds}"
        }
        check(repository.searchDocumentPages(document.id, "Leftcolumn").isEmpty()) {
            "Explicit PDF word spacing was incorrectly removed"
        }
        check(repository.searchDocumentPages(document.id, "not present").isEmpty())
        check(!requireNotNull(repository.pageMeta(firstId)).has("pdfText")) { "Search unexpectedly mutated import metadata" }
        PdfTextSession.open(repository.asset(asset)).use { session ->
            check(session.searchPage(2, "alpha", 1000f, 1000f).isEmpty())
            check(session.searchPage(0, "", 1000f, 1000f).isEmpty())
            check(runCatching { session.searchPage(99, "alpha", 1000f, 1000f) }.exceptionOrNull()
                .let { it is PdfTextFailure && it.code == "pdfPageMissing" })
        }
        val closed = PdfTextSession.open(repository.asset(asset))
        closed.close(); closed.close()
        check(runCatching { closed.searchPage(0, "alpha", 1000f, 1000f) }.isFailure)
        // Already imported pages with stored text also resolve actual areas from the retained PDF.
        check(repository.mergePdfText(firstId, "asset:$asset", 1, "Left column alpha Right column beta", "android-pdf-35"))
        check(repository.searchDocumentPages(document.id, "alpha").single().bounds == alpha.bounds)
        // Legacy platform extraction may have lost every word boundary, or stored an empty
        // string. Actual source characters must still match without rewriting user metadata.
        listOf("a\nl\np\nh\na\nlegacy cached phrase\nphantom\nneedle", "").forEach { oldText ->
            val oldMeta = requireNotNull(repository.pageMeta(firstId))
                .put("pdfText", oldText).put("pdfTextSource", "android-pdf-35")
            repository.putPageMeta(firstId, oldMeta)
            val generation = repository.generation(firstId)
            val recovered = repository.searchDocumentPages(document.id, "alpha").single()
            check(recovered.pageId == firstId && recovered.bounds == alpha.bounds &&
                recovered.snippet.contains("alpha")) { "legacyPdfSourceMatchMissing:$oldText" }
            check(repository.searchDocumentPages(document.id, "phantomneedle").isEmpty()) {
                "Legacy PDF text was falsely joined into a word"
            }
            if (oldText.contains("legacy cached phrase")) {
                val cached = repository.searchDocumentPages(document.id, "legacy cached phrase").single()
                check(cached.pageId == firstId && cached.bounds.isEmpty())
            }
            check(requireNotNull(repository.pageMeta(firstId)).toString() == oldMeta.toString() &&
                repository.generation(firstId) == generation) { "Legacy PDF search mutated stored metadata" }
        }
        val unavailable = pdfPage(uid("page"), 999, 1000f)
        unavailable.meta.put("pdfText", "retained unavailable text").put("pdfImportError", "pdfTextUnavailable")
        repository.addPage(document.id, unavailable, 3)
        val retained = repository.searchDocumentPages(document.id, "retained unavailable").single()
        check(retained.pageId == unavailable.id && retained.bounds.isEmpty())
        val missing = pdfPage(uid("page"), 1, 1000f)
        missing.meta.put("pdfSource", "asset:missing-search-source.pdf").put("pdfText", "missing source fallback")
        repository.addPage(document.id, missing, 4)
        check(repository.searchDocumentPages(document.id, "missing source fallback").single().bounds.isEmpty())
        val failedText = pdfPage(uid("page"), 999, 1000f)
        failedText.meta.put("pdfImportError", "pdfTextUnavailable").put("pdfImportPending", false)
        failedText.objects += json("id" to uid("text"), "type" to "text", "text" to "annotation after extraction failure",
            "x" to 40, "y" to 50, "w" to 300, "h" to 60)
        repository.addPage(document.id, failedText, 5)
        check(repository.searchDocumentPages(document.id, "annotation after extraction failure").single().bounds ==
            listOf(InkBounds(40f, 50f, 340f, 110f)))
        repository.deleteDocument(document.id)

        val complete = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        val imported = repository.asset(asset).inputStream().use {
            PdfImporter.import(repository, it, "Completed tiny PDF", "root", changed = { _, pagesChanged, error ->
                if (!pagesChanged) { failure.set(error); complete.countDown() }
            })
        }
        val importedFirst = repository.pageIds(imported.id).first()
        ownedAssets += requireNotNull(repository.pageMeta(importedFirst)).getString("pdfSource").removePrefix("asset:")
        check(complete.await(30, TimeUnit.SECONDS)) { "tinyPdfImportTimeout" }
        check(failure.get() == null) { "tinyPdfImportFailure:${failure.get()}" }
        check(!requireNotNull(repository.pageMeta(importedFirst)).optBoolean("pdfImportPending"))
        check(repository.searchDocumentPages(imported.id, "alpha").single().bounds == alpha.bounds)
        check(repository.searchDocumentPages(imported.id, "Rotated").single().bounds == turned.bounds)
        repository.deleteDocument(imported.id)
    }

    /** Observed synthetic Skia Type3 PDF: glyph Td operators induce false PDFium CR/LF. */
    private fun positionedGlyphChecks(repository: NoteRepository, fixtureContext: Context,
                                      ownedAssets: MutableList<String>) {
        val asset = fixtureContext.assets.open("pdf-search-positioned-glyphs.pdf").use {
            repository.storeAsset(it, "pdf")
        }
        ownedAssets += asset
        val document = repository.create("Positioned glyph search regression", "root", "blank")
        val page = requireNotNull(repository.page(repository.pageIds(document.id).single()))
        val originalText = PdfTextSession.open(repository.asset(asset)).use { it.pageText(0) }
        check(originalText.startsWith("p\r\nd\r\nf") && !originalText.contains("pdfneedle")) {
            "Positioned glyph fixture no longer reproduces generated line breaks"
        }
        page.meta.put("width", 1000).put("height", 1000f * 800 / 600)
            .put("pdfSource", "asset:$asset").put("pdfPageNumber", 1)
            .put("pdfText", originalText).put("pdfTextSource", "android-pdf-35")
        repository.putPage(document.id, page, 0)
        val before = requireNotNull(repository.pageMeta(page.id)).toString()
        val generation = repository.generation(page.id)
        val hit = repository.searchDocumentPages(document.id, "PDFNEEDLE").single()
        check(hit.pageId == page.id && hit.snippet.contains("pdfneedle", ignoreCase = true))
        val first = hit.bounds.filter { it.right < 400f }
        val second = hit.bounds.filter { it.left > 650f }
        check(first.size == 9 && second.size == 9 && hit.bounds.size == 18) {
            "Positioned glyph occurrences missing or duplicated:${hit.bounds}"
        }
        check(first.all { it.left > 125f && it.right < 280f && it.top > 195f && it.bottom < 245f } &&
            second.all { it.left > 690f && it.right < 850f && it.top > 325f && it.bottom < 380f }) {
            "Positioned glyph bounds escaped the two observed words:${hit.bounds}"
        }
        check(repository.searchDocumentPages(document.id, "pdfneedlepdfneedle").isEmpty()) {
            "Generated separators between distinct baselines were incorrectly removed"
        }
        check(requireNotNull(repository.pageMeta(page.id)).toString() == before &&
            repository.generation(page.id) == generation) { "Positioned glyph search changed original metadata" }
        repository.deleteDocument(document.id)
    }

    private fun ocrAndObjectChecks(repository: NoteRepository) {
        val document = repository.create("Search OCR synthetic", "root", "blank")
        val page = requireNotNull(repository.page(repository.pageIds(document.id).single()))
        page.meta.put("ocrText", "legacy stale poison")
        val firstBox = InkBounds(100f, 200f, 170f, 230f)
        val secondBox = InkBounds(190f, 200f, 260f, 230f)
        val lineBox = InkBounds(100f, 200f, 260f, 230f)
        fun box(value: InkBounds) = json("left" to value.left, "top" to value.top,
            "right" to value.right, "bottom" to value.bottom)
        page.objects += json("id" to "ink", "type" to "stroke", "width" to 4,
            "points" to JSONArray(listOf(InkPoint(100f, 200f, .5f, 1).json(), InkPoint(260f, 230f, .5f, 2).json())))
        page.objects += json("id" to "typed", "type" to "text", "text" to "typed needle", "x" to 300,
            "y" to 400, "w" to 200, "h" to 60)
        page.objects += json("id" to "math", "type" to "math", "expression" to "sqrt(2)", "x" to 600,
            "y" to 500, "w" to 180, "h" to 80)
        repository.putPage(document.id, page, 0)
        // Match PageOcrCoordinator: digest the stored JSON snapshot, not pre-save Float/Long
        // wrappers whose numeric representation changes during JSON serialization.
        val snapshot = requireNotNull(repository.readOcrSnapshot(document.id, page.id))
        val digest = OcrInput.pageDigest(snapshot.page, "ko-primary")
        val region = json("id" to "line:ink", "status" to "complete", "order" to 0,
            "strokeIds" to JSONArray(listOf("ink")), "sourceIdentityDigest" to "source-ink",
            "inputDigest" to "input-ink", "selectedText" to "alpha beta", "bounds" to box(lineBox),
            "wordRegions" to JSONArray(listOf(json("selectedText" to "alpha", "bounds" to box(firstBox)),
                json("selectedText" to "beta", "bounds" to box(secondBox)))))
        val result = json("pageDigest" to digest, "status" to "complete", "regions" to JSONArray(listOf(region)))
        check(repository.putOcrResult(document.id, page.id, snapshot.generation, digest, "ko-primary", result)) {
            "searchFixtureStoredSnapshotRejected"
        }
        check(repository.searchDocumentPages(document.id, "beta").single().bounds == listOf(secondBox))
        check(repository.searchDocumentPages(document.id, "alpha beta").single().bounds == listOf(firstBox, secondBox))
        check(repository.searchDocumentPages(document.id, "typed needle").single().bounds == listOf(InkBounds(300f, 400f, 500f, 460f)))
        check(repository.searchDocumentPages(document.id, "sqrt(2)").single().bounds == listOf(InkBounds(600f, 500f, 780f, 580f)))
        check(repository.correctOcrRegion(page.id, "line:ink", "corrected region", "input-ink", digest))
        check(repository.searchDocumentPages(document.id, "corrected region").single().bounds == listOf(lineBox))
        check(repository.searchDocumentPages(document.id, "beta").isEmpty())
        check(!repository.correctOcrText(page.id, "stale dialog", "old-page-digest"))
        check(repository.correctOcrText(page.id, "single edited result", digest))
        check(repository.ocrText(page.id) == "single edited result")
        check(repository.searchDocumentPages(document.id, "single edited").single().bounds.isEmpty())
        check(repository.searchDocumentPages(document.id, "corrected region").isEmpty())
        val copy = repository.duplicatePage(document.id, page.id, 1)
        check(repository.ocrText(copy.id) == "single edited result") { "Whole-result correction lost during duplicate" }
        check(repository.correctOcrText(page.id, "", digest))
        check(repository.ocrText(page.id).isEmpty())
        check(repository.searchDocumentPages(document.id, "single edited").single().pageId == copy.id)
        check(repository.correctOcrText(page.id, "stale searchable result", digest))
        repository.putPage(document.id, page, 0)
        check(repository.searchDocumentPages(document.id, "stale searchable result").isEmpty())
        check(repository.searchDocumentPages(document.id, "legacy stale poison").isEmpty()) {
            "Stale sidecar incorrectly fell back to embedded legacy text"
        }
        check(!repository.correctOcrText(page.id, "late save", digest))
        val legacy = NotePage.blank("blank")
        legacy.meta.put("ocrText", "legacy searchable content")
        repository.addPage(document.id, legacy, 2)
        val legacyHit = repository.searchDocumentPages(document.id, "legacy searchable").single()
        check(legacyHit.pageId == legacy.id && legacyHit.bounds.isEmpty())
        repository.deleteDocument(document.id)
    }

    /** Minimal real PDF: two columns, a cropped 90-degree page, and an image-free blank page. */
    private fun tinyPdf(): ByteArray {
        fun stream(text: String) = "<< /Length ${text.toByteArray(Charsets.US_ASCII).size} >>\nstream\n$text\nendstream"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Count 3 /Kids [3 0 R 5 0 R 7 0 R] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 9 0 R >> >> /Contents 4 0 R >>",
            stream("BT /F1 18 Tf 48 696 Td (Left column alpha) Tj ET\nBT /F1 18 Tf 328 696 Td (Right column beta) Tj ET"),
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /CropBox [72 72 540 720] /Rotate 90 /Resources << /Font << /F1 9 0 R >> >> /Contents 6 0 R >>",
            stream("BT /F1 18 Tf 90 682 Td (Rotated cropped original PDF) Tj ET"),
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << >> /Contents 8 0 R >>",
            stream(""),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        val output = ByteArrayOutputStream()
        fun append(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
        append("%PDF-1.4\n")
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { index, value ->
            offsets += output.size()
            append("${index + 1} 0 obj\n$value\nendobj\n")
        }
        val xref = output.size()
        append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { append(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
        append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return output.toByteArray()
    }
}
