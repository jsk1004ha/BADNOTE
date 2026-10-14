package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.InterruptedIOException

/** One scene implementation for PNG, raster fallback and vector PDF canvases. */
internal object PageExportRenderer {
    fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted)
            throw InterruptedIOException("내보내기를 취소했습니다.")
    }

    fun draw(repository: NoteRepository, page: NotePage, canvas: Canvas,
             backgroundWidth: Int, backgroundHeight: Int, cancelled: () -> Boolean = { false }) {
        checkCancelled(cancelled)
        val images = mutableMapOf<String, Bitmap>()
        val renderer = NoteRenderer { reference ->
            checkCancelled(cancelled)
            if (!reference.startsWith("asset:")) null else images.getOrPut(reference) {
                BackgroundLoader.decodeImage(repository.asset(reference.removePrefix("asset:")), 2000)
                    ?: error("이미지 자산을 읽지 못했습니다.")
            }
        }
        val saved = canvas.save()
        try {
            canvas.clipRect(0f, 0f, page.width, page.height)
            renderer.template(canvas, page)
            val reference = page.meta.optString("pdfSource")
            val bounds = RectF(0f, 0f, page.width, page.height)
            if (reference.startsWith("asset:")) {
                val descriptor = ParcelFileDescriptor.open(repository.asset(reference.removePrefix("asset:")),
                    ParcelFileDescriptor.MODE_READ_ONLY)
                val pdf = try { PdfRenderer(descriptor) } catch (error: Throwable) { descriptor.close(); throw error }
                pdf.use {
                    it.openPage(page.meta.optInt("pdfPageNumber", 1) - 1).use { source ->
                        checkCancelled(cancelled)
                        val background = Bitmap.createBitmap(backgroundWidth, backgroundHeight, Bitmap.Config.ARGB_8888)
                        try {
                            background.eraseColor(Color.WHITE)
                            source.render(background, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            checkCancelled(cancelled)
                            canvas.drawBitmap(background, null, bounds, null)
                        } finally { background.recycle() }
                    }
                }
            } else if (page.meta.optString("backgroundImage").startsWith("asset:")) {
                val background = BackgroundLoader.decodeImage(repository.asset(
                    page.meta.getString("backgroundImage").removePrefix("asset:")), 2400)
                    ?: error("PDF 배경 자산을 읽지 못했습니다.")
                try { checkCancelled(cancelled); canvas.drawBitmap(background, null, bounds, null) }
                finally { background.recycle() }
            }
            for (obj in page.objects) {
                checkCancelled(cancelled)
                renderer.draw(canvas, obj, backgroundWidth.toDouble() / page.width)
                // PdfDocument owns its native drawing resources until this one page is written.
                images.values.forEach(Bitmap::recycle); images.clear()
            }
        } finally {
            images.values.forEach(Bitmap::recycle)
            canvas.restoreToCount(saved)
        }
    }
}
