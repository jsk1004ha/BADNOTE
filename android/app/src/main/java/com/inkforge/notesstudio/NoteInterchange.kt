package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.util.Base64
import android.util.Base64InputStream
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlin.math.roundToInt

internal object NoteInterchange {
    enum class Format { BADNOTE, PDF, IMAGE, XOURNAL, GOODNOTES, GOODNOTES_BACKUP }

    data class ImportResult(val documents: List<DocumentInfo>, val folders: JSONArray? = null, val skipped: Int = 0) {
        constructor(document: DocumentInfo) : this(listOf(document))
    }

    fun format(name: String, contentType: String?): Format {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = contentType?.substringBefore(';')?.lowercase(Locale.ROOT)
        return when {
            name.endsWith(".goodnotes.zip", true) -> Format.GOODNOTES_BACKUP
            extension == "goodnotes" || mime in setOf("application/x-goodnotes", "application/vnd.goodnotes") -> Format.GOODNOTES
            extension == "pdf" || mime == "application/pdf" -> Format.PDF
            extension in setOf("xopp", "xoj") || mime in setOf(XournalInk.MIME, "application/x-xoj") -> Format.XOURNAL
            extension in setOf("jpg", "jpeg", "png", "webp") || mime?.startsWith("image/") == true -> Format.IMAGE
            extension in setOf("sdocx", "sdoc", "note", "one", "onepkg") ->
                error("원본 앱에서 PDF로 내보낸 뒤 BADNOTE에서 가져와 주세요.")
            else -> Format.BADNOTE
        }
    }

    fun title(name: String) = name.substringBeforeLast('.', name).ifBlank { "가져온 노트" }

    fun importImage(repository: NoteRepository, input: InputStream, title: String, folder: String,
                    contentType: String): DocumentInfo {
        var width = 0
        var height = 0
        val id = repository.storeAsset(input, NoteRepository.extension(contentType)) {
            val size = NoteRepository.checkedImageSize(it)
            width = size.first
            height = size.second
        }
        try {
            val pageHeight = height * 1000.0 / width
            require(pageHeight in 32.0..30000.0) { "지원하지 않는 이미지 비율입니다." }
            return repository.createFromPages(title, folder) { append ->
                val page = NotePage.blank("blank", 1000f, pageHeight.toFloat())
                page.meta.put("backgroundImage", "asset:" + id)
                append(page)
            }
        } catch (error: Exception) {
            repository.asset(id).delete()
            throw error
        }
    }

    fun importXournal(repository: NoteRepository, input: InputStream, title: String, folder: String): DocumentInfo {
        val assets = mutableListOf<String>()
        try {
            return repository.createFromPages(title, folder) { append ->
                XournalInk.read(input, { image ->
                    Base64InputStream(image.byteInputStream(Charsets.US_ASCII), Base64.DEFAULT).use { decoded ->
                        val id = repository.storeAsset(decoded, "png") { NoteRepository.checkedImageSize(it) }
                        assets += id
                        "asset:" + id
                    }
                }, append)
            }
        } catch (error: Exception) {
            assets.forEach { repository.asset(it).delete() }
            throw error
        }
    }

    fun exportXournal(repository: NoteRepository, doc: DocumentInfo, file: File, cancelled: () -> Boolean) {
        val pages = repository.pageIds(doc.id).asSequence().map { requireNotNull(repository.page(it)) }
        file.outputStream().buffered().use { target ->
            XournalInk.write(target, doc.title, pages, { page ->
                val width = (1800 * page.width / page.height).roundToInt().coerceIn(32, 1600)
                val bitmap = StreamingPdf.render(repository, page, width, cancelled)
                try {
                    val png = ByteArrayOutputStream()
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)) { "Xournal 페이지 이미지를 만들 수 없습니다." }
                    Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP)
                } finally {
                    bitmap.recycle()
                }
            }) { PageExportRenderer.checkCancelled(cancelled) }
        }
    }
}
