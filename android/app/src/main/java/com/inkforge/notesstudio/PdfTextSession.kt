package com.inkforge.notesstudio

import java.io.Closeable
import java.io.File
import java.io.IOException

class PdfTextFailure(val code: String, message: String) : IOException(message)

/** File-backed PDFium access. A session owns its native file descriptor and document. */
class PdfTextSession private constructor(private var handle: Long) : Closeable {
    val pageCount: Int get() = synchronized(PdfiumBridge.lock) {
        require(handle != 0L) { "pdfSessionClosed" }
        PdfiumBridge.nativePageCount(handle)
    }

    fun pageText(index: Int, cancelled: () -> Boolean = { false }): String {
        checkSearchCancelled(cancelled)
        return synchronized(PdfiumBridge.lock) {
            checkSearchCancelled(cancelled)
            require(handle != 0L) { "pdfSessionClosed" }
            PdfiumBridge.call { nativePageText(handle, index) }.also { checkSearchCancelled(cancelled) }
        }
    }

    /** PDFium maps matched character boxes through the crop/rotation-aware display transform. */
    fun searchPage(index: Int, query: String, pageWidth: Float, pageHeight: Float,
                   cancelled: () -> Boolean = { false }): List<InkBounds> {
        checkSearchCancelled(cancelled)
        return synchronized(PdfiumBridge.lock) {
            // A cancelled worker may have been waiting behind an import using PDFium.
            checkSearchCancelled(cancelled)
            require(handle != 0L) { "pdfSessionClosed" }
            require(pageWidth.isFinite() && pageHeight.isFinite() && pageWidth > 0f && pageHeight > 0f)
            if (query.isEmpty() || '\u0000' in query) return@synchronized emptyList()
            val boxes = PdfiumBridge.call { nativeSearchPage(handle, index, query) }
            checkSearchCancelled(cancelled)
            check(boxes.size % 4 == 0) { "pdfInvalidSearchBounds" }
            buildList(boxes.size / 4) {
                var offset = 0
                while (offset < boxes.size) {
                    if (offset % 256 == 0) checkSearchCancelled(cancelled)
                    val left = boxes[offset++] * pageWidth
                    val top = boxes[offset++] * pageHeight
                    val right = boxes[offset++] * pageWidth
                    val bottom = boxes[offset++] * pageHeight
                    if (left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
                        right > left && bottom > top) add(InkBounds(left, top, right, bottom))
                }
            }
        }
    }

    override fun close() = synchronized(PdfiumBridge.lock) {
        if (handle != 0L) {
            PdfiumBridge.nativeClose(handle)
            handle = 0L
        }
    }

    companion object {
        fun open(file: File, cancelled: () -> Boolean = { false }): PdfTextSession {
            checkSearchCancelled(cancelled)
            return synchronized(PdfiumBridge.lock) {
                checkSearchCancelled(cancelled)
                require(file.isFile) { "pdfFileMissing" }
                val session = PdfTextSession(PdfiumBridge.call { nativeOpen(file.canonicalPath) })
                try { checkSearchCancelled(cancelled); session }
                catch (error: Exception) { session.close(); throw error }
            }
        }
    }
}

internal object PdfiumBridge {
    val lock = Any()
    init { System.loadLibrary("badnote_pdfium") }

    external fun nativeOpen(path: String): Long
    external fun nativePageCount(handle: Long): Int
    external fun nativePageText(handle: Long, index: Int): String
    external fun nativeSearchPage(handle: Long, index: Int, query: String): FloatArray
    external fun nativeClose(handle: Long)

    fun <T> call(block: PdfiumBridge.() -> T): T = try { block() } catch (error: IllegalStateException) {
        val code = error.message.orEmpty()
        throw PdfTextFailure(code, when (code) {
            "pdfPasswordRequired" -> "암호가 필요한 PDF입니다."
            "pdfSecurityUnsupported" -> "지원하지 않는 PDF 보안 방식입니다."
            "pdfMalformed" -> "손상되었거나 지원하지 않는 PDF입니다."
            "pdfTextTooLarge" -> "PDF 페이지의 원문 텍스트가 너무 큽니다."
            "pdfAllocationFailed" -> "PDF 원문을 처리할 메모리가 부족합니다."
            "pdfPageMissing" -> "PDF 페이지를 읽을 수 없습니다."
            else -> "PDF 원문을 읽지 못했습니다: $code"
        })
    }
}
