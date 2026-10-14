package com.inkforge.notesstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import java.io.File
import java.io.InterruptedIOException
import kotlin.math.abs

/** Actual PdfDocument output, PdfRenderer readback and repository scene checks. */
internal object VectorPdfChecks {
    fun run(context: Context) {
        check(context.packageName.endsWith(".debug"))
        NoteRepository(context).use { repository ->
            val doc = repository.create("벡터 PDF 회귀", "root", "blank")
            val page = requireNotNull(repository.page(repository.pageIds(doc.id).first()))
            val file = File(context.cacheDir, "vector-pdf-check.pdf")
            val sourceFile = File(context.cacheDir, "vector-pdf-source.pdf")
            val assets = mutableListOf<File>()
            try {
                addInkFixture(page)
                save(repository, doc, page)
                StreamingPdf.write(repository, doc.id, file) { false }
                val syntax = file.readText(Charsets.ISO_8859_1)
                check(!syntax.contains("/Subtype /Image")) { "Ink-only page was rasterized" }
                check(syntax.contains("/Font")) { "Text font resources were lost" }
                compareScene(repository, page, file)
                file.copyTo(sourceFile, overwrite = true)
                checkPageOrder(repository, doc, file)
                checkBackground(repository, doc, page, sourceFile, file, assets)
                checkImageBudget(repository, doc, page, file, assets)
                checkCancellation(repository, doc, page, file)
            } finally {
                file.delete(); sourceFile.delete(); repository.deleteDocument(doc.id); assets.forEach(File::delete)
            }
        }
    }

    // Keep minimum-API instrumentation fixture construction out of the large test body.
    private fun addInkFixture(page: NotePage) {
        val brushes = "fountain ballpoint fineliner gel brush pencil calligraphy highlighter".split(' ')
        brushes.forEachIndexed { index, brush ->
            val y = 120f + index * 90
            page.objects += json("id" to uid("stroke"), "type" to "stroke", "brush" to brush,
                "width" to 10, "color" to if (brush == "highlighter") "#efcc21" else "#184d91",
                "settings" to json("grain" to .25, "smoothing" to .2),
                "points" to JSONArray((0..20).map { i ->
                    InkPoint(100f + i * 35, y + if (i % 2 == 0) 0 else 12,
                        .2f + i * .035f, i * 10L, .4f, .7f).json()
                }))
        }
        page.objects += json("id" to uid("shape"), "type" to "shape", "shape" to "curve",
            "x1" to 160, "y1" to 1000, "x2" to 800, "y2" to 1000, "cx" to 480, "cy" to 880,
            "color" to "#d02525", "width" to 6, "rotation" to .08)
        page.objects += json("id" to uid("text"), "type" to "text", "text" to "벡터 PDF · 한글 보존",
            "x" to 120, "y" to 1160, "w" to 780, "h" to 80, "fontSize" to 34)
        page.objects += json("id" to uid("hidden"), "type" to "image", "src" to "asset:missing.png", "hidden" to true)
        page.objects += json("id" to uid("ocr"), "type" to "ocrIndex", "text" to "must not draw")
    }

    private fun save(repository: NoteRepository, doc: DocumentInfo, page: NotePage) =
        repository.transaction { repository.putPage(doc.id, page, 0) }

    private fun checkPageOrder(repository: NoteRepository, doc: DocumentInfo, file: File) {
        val landscape = NotePage.blank("lined", 1414f, 1000f)
        landscape.objects += json("id" to uid("shape"), "type" to "shape", "shape" to "rectangle",
            "x1" to 200, "y1" to 200, "x2" to 900, "y2" to 700,
            "rotation" to .3, "color" to "#cf2121", "width" to 8)
        landscape.meta.put("pdfPointWidth", 842).put("pdfPointHeight", 595)
        repository.transaction { repository.putPage(doc.id, landscape, 1) }
        StreamingPdf.write(repository, doc.id, file) { false }
        readPdf(file) { pdf ->
            check(pdf.pageCount == 2)
            pdf.openPage(0).use { check(it.width == 595 && it.height > 800) }
            pdf.openPage(1).use { check(it.width == 842 && it.height == 595) }
        }
    }

    private fun checkBackground(repository: NoteRepository, doc: DocumentInfo, page: NotePage,
                                sourceFile: File, file: File, assets: MutableList<File>) {
        val source = repository.asset(uid("pdf") + ".pdf").also { assets += it }
        sourceFile.copyTo(source, overwrite = true)
        page.meta.put("pdfSource", "asset:${source.name}").put("pdfPageNumber", 1)
        save(repository, doc, page)
        StreamingPdf.write(repository, doc.id, file) { false }
        check(file.readText(Charsets.ISO_8859_1).contains("/Subtype /Image"))
        compareScene(repository, page, file)
    }

    private fun checkImageBudget(repository: NoteRepository, doc: DocumentInfo, page: NotePage,
                                 file: File, assets: MutableList<File>) {
        page.meta.remove("pdfSource")
        val picture = repository.asset(uid("image") + ".png").also { assets += it }
        val bitmap = Bitmap.createBitmap(2000, 2000, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            picture.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        repeat(2) { index -> page.objects += json("id" to uid("image"), "type" to "image",
            "src" to "asset:${picture.name}", "x" to 150 + index * 250, "y" to 1020, "w" to 180, "h" to 100) }
        save(repository, doc, page)
        StreamingPdf.write(repository, doc.id, file) { false }
        check(!file.readText(Charsets.ISO_8859_1).contains("/Font")) { "Image budget did not select the raster fallback" }
        compareScene(repository, page, file)
    }

    private fun checkCancellation(repository: NoteRepository, doc: DocumentInfo, page: NotePage, file: File) {
        var probes = 0; var interrupted = false
        try { StreamingPdf.write(repository, doc.id, file) { ++probes >= 12 } }
        catch (_: InterruptedIOException) { interrupted = true }
        check(interrupted && !file.exists()) { "Cancelled vector PDF survived" }
        check(repository.workDirectory.listFiles().orEmpty().none { it.name.startsWith("pdf-vector-page-") })
        check(repository.page(page.id)?.objects?.size == page.objects.size) { "Export changed the note" }
    }

    private fun readPdf(file: File, action: (PdfRenderer) -> Unit) {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val pdf = try { PdfRenderer(descriptor) } catch (error: Throwable) { descriptor.close(); throw error }
        pdf.use(action)
    }

    private fun compareScene(repository: NoteRepository, page: NotePage, file: File) {
        val expected = StreamingPdf.render(repository, page, 1800)
        val actual = Bitmap.createBitmap(expected.width, expected.height, Bitmap.Config.ARGB_8888)
        try {
            readPdf(file) { pdf -> pdf.openPage(0).use {
                actual.eraseColor(Color.WHITE)
                it.render(actual, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            } }
            var error = 0L; var samples = 0
            for (y in 0 until expected.height step 3) for (x in 0 until expected.width step 3) {
                val a = expected.getPixel(x, y); val b = actual.getPixel(x, y)
                if (minOf(Color.red(a), Color.green(a), Color.blue(a)) > 225 &&
                    minOf(Color.red(b), Color.green(b), Color.blue(b)) > 225) continue
                error += abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
                samples += 3
            }
            check(samples > 500) { "Empty PDF scene" }
            check(error.toDouble() / samples < 24) { "Vector PDF differs from native scene: ${error.toDouble() / samples}" }
        } finally { expected.recycle(); actual.recycle() }
    }
}
