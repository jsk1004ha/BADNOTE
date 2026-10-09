package com.inkforge.notesstudio

import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.os.BuildCompat
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Commits a dimensionally correct first page, then fills pages and original text off the save executor. */
object PdfImporter {
    private data class Job(val repository: NoteRepository, val documentId: String, val firstPageId: String,
                           val importId: String, val assetId: String, val title: String,
                           val totalPages: Int, val changed: (String, Boolean, String?) -> Unit,
                           val cancelled: AtomicBoolean = AtomicBoolean())
    private val executor = Executors.newSingleThreadExecutor()
    private val jobs = HashMap<String, Job>()

    fun import(repository: NoteRepository, input: InputStream, title: String, folder: String,
               appendTo: String? = null, insertAt: Int = 0,
               changed: (String, Boolean, String?) -> Unit = { _, _, _ -> }): DocumentInfo {
        var count = 0
        val (document, assetId) = input.use { source ->
            val destination = appendTo?.let { requireNotNull(repository.document(it)) { "추가할 노트가 없습니다." } }
                ?: uid("doc").let { id -> DocumentInfo(id, json("id" to id, "title" to title,
                    "folderId" to folder, "version" to 5, "schema" to "com.inkforge.ifnote",
                    "pdfSourceName" to title)) }
            val stored = repository.storeAsset(source, "pdf") { pending ->
                count = PdfTextSession.open(pending).use { it.pageCount }
                require(count in 1..100_000) { "PDF 페이지 수가 지원 범위를 벗어났습니다." }
                openRenderer(pending).use { renderer ->
                    require(renderer.pageCount == count) { "PDF 페이지 수가 렌더러와 다릅니다." }
                    renderer.openPage(0).use { page -> require(page.width > 0 && page.height > 0) { "PDF 페이지 크기가 잘못되었습니다." } }
                }
            }
            destination to stored
        }
        val file = repository.asset(assetId)
        var attached = false
        try {
            val importId = uid("pdf_import")
            val source = "asset:$assetId"
            val first = openRenderer(file).use { renderer ->
                require(renderer.pageCount == count) { "PDF 페이지 수가 렌더러와 다릅니다." }
                renderer.openPage(0).use { page -> makePage(page, source, title, 1, importId, count) }
            }
            document.data.put("id", document.id)
            repository.transaction {
                if (appendTo == null) repository.putDocument(document)
                if (appendTo == null) repository.putPage(document.id, first, 0)
                else repository.addPage(document.id, first, insertAt)
            }
            attached = true
            schedule(Job(repository, document.id, first.id, importId, assetId, title, count, changed))
            return document
        } catch (error: Exception) {
            if (!attached) file.delete()
            throw error
        }
    }

    /** A stopped process can resume unfinished imports from the retained source PDF and page marker. */
    fun resumePending(repository: NoteRepository) {
        repository.documents().forEach { document ->
            repository.pageIds(document.id).forEach { pageId ->
                val meta = repository.pageMeta(pageId) ?: return@forEach
                if (!meta.optBoolean("pdfImportPending")) return@forEach
                val source = meta.optString("pdfSource")
                val importId = meta.optString("pdfImportId")
                if (!source.startsWith("asset:") || importId.isBlank() || meta.optInt("pdfPageNumber") != 1)
                    return@forEach
                val count = meta.optInt("pdfTotalPages")
                if (count !in 1..100_000) return@forEach
                schedule(Job(repository, document.id, pageId, importId, source.removePrefix("asset:"),
                    meta.optString("pdfSourceName"), count, { _, _, _ -> }))
            }
        }
    }

    fun cancelDocument(documentId: String) = synchronized(jobs) {
        jobs.values.filter { it.documentId == documentId }.forEach { it.cancelled.set(true) }
    }

    fun cancelImport(importId: String) = synchronized(jobs) {
        jobs[importId]?.cancelled?.set(true)
    }

    private fun schedule(job: Job) {
        synchronized(jobs) {
            if (jobs.containsKey(job.importId)) return
            jobs[job.importId] = job
        }
        executor.execute {
            try { continueImport(job) }
            finally { synchronized(jobs) { jobs.remove(job.importId) } }
        }
    }

    private fun continueImport(job: Job) {
        val repository = job.repository
        val source = "asset:${job.assetId}"
        val file = repository.asset(job.assetId)
        var imported = 1
        try {
            check(!job.cancelled.get()) { "pdfImportCancelled" }
            val pageIds = HashMap<Int, String>()
            repository.pageIds(job.documentId).forEach { id ->
                val meta = repository.pageMeta(id) ?: return@forEach
                if (meta.optString("pdfImportId") == job.importId && meta.optString("pdfSource") == source)
                    pageIds[meta.optInt("pdfPageNumber")] = id
            }
            require(pageIds[1] == job.firstPageId) { "pdfImportSourceChanged" }
            openRenderer(file).use { renderer ->
                require(renderer.pageCount == job.totalPages) { "pdfImportSourceChanged" }
                var previous = job.firstPageId
                for (index in 1 until job.totalPages) {
                    check(!job.cancelled.get()) { "pdfImportCancelled" }
                    if (repository.document(job.documentId) == null) return
                    val existing = pageIds[index + 1]
                    if (existing != null) { previous = existing; imported++; continue }
                    val page = renderer.openPage(index).use { sourcePage ->
                        makePage(sourcePage, source, job.title, index + 1, job.importId, job.totalPages)
                    }
                    if (!repository.appendPdfPage(job.documentId, job.firstPageId, previous, job.importId, source, page))
                        throw IllegalStateException("pdfImportStopped")
                    pageIds[index + 1] = page.id
                    previous = page.id
                    imported++
                }
                if (job.cancelled.get()) throw IllegalStateException("pdfImportCancelled")
                notify(job, true, null)
                val native = if (Build.VERSION.SDK_INT >= 35 && BuildCompat.S_EXTENSION_INT >= 13)
                    null else PdfTextSession.open(file)
                try {
                    for (index in 0 until job.totalPages) {
                        check(!job.cancelled.get()) { "pdfImportCancelled" }
                        if (repository.document(job.documentId) == null) return
                        val text = if (Build.VERSION.SDK_INT >= 35 && BuildCompat.S_EXTENSION_INT >= 13)
                            renderer.openPage(index).use { page -> page.textContents.joinToString(" ") { it.text } }
                        else requireNotNull(native).pageText(index)
                        val engine = if (native != null) "pdfium-8086" else "android-pdf-35"
                        repository.mergePdfText(requireNotNull(pageIds[index + 1]), source, index + 1, text, engine)
                    }
                } finally { native?.close() }
            }
            repository.pdfImportState(job.firstPageId, job.importId, source, false, imported)
            notify(job, false, null)
        } catch (error: Exception) {
            val code = (error as? PdfTextFailure)?.code ?: error.message.orEmpty().take(80).ifBlank { "pdfImportFailed" }
            repository.pdfImportState(job.firstPageId, job.importId, source, false, imported, code)
            notify(job, false, code)
        }
    }

    private fun notify(job: Job, pagesChanged: Boolean, error: String?) {
        try { job.changed(job.documentId, pagesChanged, error) }
        catch (_: Exception) { /* UI observers cannot roll back an imported PDF. */ }
    }

    private fun makePage(sourcePage: PdfRenderer.Page, source: String, title: String, number: Int,
                         importId: String, total: Int): NotePage {
        require(sourcePage.width > 0 && sourcePage.height > 0) { "PDF 페이지 크기가 잘못되었습니다." }
        val page = NotePage.blank("blank", 1000f, 1000f * sourcePage.height / sourcePage.width)
        page.meta.put("pdfSource", source).put("pdfPageNumber", number).put("pdfSourceName", title)
            .put("pdfPointWidth", sourcePage.width).put("pdfPointHeight", sourcePage.height)
            .put("importedFromPdf", true).put("pdfImportId", importId).put("pdfTotalPages", total)
        if (number == 1) page.meta.put("pdfImportPending", true).put("pdfImportedPages", 1)
        return page
    }

    private fun openRenderer(file: File): PdfRenderer {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try { PdfRenderer(descriptor) } catch (error: Exception) { descriptor.close(); throw error }
    }
}
