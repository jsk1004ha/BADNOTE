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

    fun pageText(index: Int): String = synchronized(PdfiumBridge.lock) {
        require(handle != 0L) { "pdfSessionClosed" }
        PdfiumBridge.call { nativePageText(handle, index) }
    }

    override fun close() = synchronized(PdfiumBridge.lock) {
        if (handle != 0L) {
            PdfiumBridge.nativeClose(handle)
            handle = 0L
        }
    }

    companion object {
        fun open(file: File): PdfTextSession = synchronized(PdfiumBridge.lock) {
            require(file.isFile) { "pdfFileMissing" }
            PdfTextSession(PdfiumBridge.call { nativeOpen(file.canonicalPath) })
        }
    }
}

internal object PdfiumBridge {
    val lock = Any()
    init { System.loadLibrary("badnote_pdfium") }

    external fun nativeOpen(path: String): Long
    external fun nativePageCount(handle: Long): Int
    external fun nativePageText(handle: Long, index: Int): String
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
