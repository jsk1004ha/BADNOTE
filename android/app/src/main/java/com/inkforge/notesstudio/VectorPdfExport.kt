package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Build
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** One native vector page is finished, spooled and closed before the next page is loaded. */
internal object VectorPdfExport {
    private const val IMAGE_BUDGET_BYTES = 24L * 1024 * 1024
    private const val RASTER_WIDTH = 1800

    fun write(repository: NoteRepository, documentId: String, file: File, cancelled: () -> Boolean) {
        val ids = repository.pageIds(documentId)
        PdfPageAssembler.write(file, repository.workDirectory, ids.size, { index, spool ->
            PageExportRenderer.checkCancelled(cancelled)
            val page = requireNotNull(repository.page(ids[index])) { "내보낼 페이지가 없습니다." }
            val width = page.meta.f("pdfPointWidth", 595f).takeIf { it > 0 } ?: 595f
            val height = page.meta.f("pdfPointHeight", width * page.height / page.width)
                .takeIf { it > 0 } ?: (width * page.height / page.width)
            val pointWidth = width.roundToInt().coerceIn(1, 30000)
            val pointHeight = height.roundToInt().coerceIn(1, 30000)
            val rasterHeight = (RASTER_WIDTH * page.height / page.width).roundToInt().coerceIn(1, 5000)
            val pdf = PdfDocument()
            val retained = mutableListOf<Bitmap>()
            try {
                val nativePage = pdf.startPage(PdfDocument.PageInfo.Builder(pointWidth, pointHeight, 1).create())
                try {
                    val canvas = nativePage.canvas
                    // A recording PDF canvas can retain all image pixels until writeTo. Large
                    // image collections use the existing bounded one-bitmap raster path instead.
                    if (ExportRasterPolicy.legacyBlend(page.objects, Build.VERSION.SDK_INT) ||
                        imageBytes(repository, page, rasterHeight, cancelled) > IMAGE_BUDGET_BYTES) {
                        val bitmap = StreamingPdf.render(repository, page, RASTER_WIDTH, cancelled)
                        retained += bitmap
                        canvas.drawBitmap(bitmap, null, RectF(0f, 0f, pointWidth.toFloat(), pointHeight.toFloat()), null)
                    } else {
                        canvas.scale(pointWidth / page.width, pointHeight / page.height)
                        PageExportRenderer.draw(repository, page, canvas, RASTER_WIDTH, rasterHeight, cancelled, retained)
                    }
                } finally { pdf.finishPage(nativePage) }
                PageExportRenderer.checkCancelled(cancelled)
                spool.outputStream().buffered(ArchiveCodec.BUFFER_SIZE).use { output ->
                    pdf.writeTo(CancellableOutput(output, cancelled))
                }
            } finally {
                try { pdf.close() } finally { retained.forEach(Bitmap::recycle) }
            }
        }, { cancelled() })
    }

    private fun imageBytes(repository: NoteRepository, page: NotePage, rasterHeight: Int,
                           cancelled: () -> Boolean): Long {
        fun decodedBytes(reference: String, maxSide: Int): Long {
            if (!reference.startsWith("asset:")) return 0
            PageExportRenderer.checkCancelled(cancelled)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(repository.asset(reference.removePrefix("asset:")).path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return 0
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            // Round up to avoid undercounting sampled decoder dimensions.
            return ((bounds.outWidth.toLong() + sample - 1) / sample) *
                ((bounds.outHeight.toLong() + sample - 1) / sample) * 4
        }
        var bytes = if (page.meta.optString("pdfSource").startsWith("asset:"))
            RASTER_WIDTH.toLong() * rasterHeight * 4 else decodedBytes(page.meta.optString("backgroundImage"), 2400)
        for (obj in page.objects) {
            if (bytes > IMAGE_BUDGET_BYTES) break
            if (obj.optString("type") == "image" && !obj.optBoolean("hidden"))
                bytes += decodedBytes(obj.optString("src"), 2000)
        }
        return bytes
    }

    private class CancellableOutput(output: OutputStream, private val cancelled: () -> Boolean) : FilterOutputStream(output) {
        override fun write(value: Int) { PageExportRenderer.checkCancelled(cancelled); out.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            PageExportRenderer.checkCancelled(cancelled); out.write(bytes, offset, length)
        }
    }
}
