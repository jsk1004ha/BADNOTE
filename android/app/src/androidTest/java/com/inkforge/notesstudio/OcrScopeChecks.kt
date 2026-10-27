package com.inkforge.notesstudio

import android.content.Context
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Model-free persistence boundaries; quality is measured separately by OcrQualityChecks. */
internal object OcrScopeChecks {
    fun run(context: Context) {
        val database = "ocr-scope-${UUID.randomUUID()}.db"
        try {
            NoteRepository(context, database).use { repository ->
                val doc = repository.create("OCR selection boundary", "root", "grid")
                val page = requireNotNull(repository.page(repository.pageIds(doc.id).single()))
                page.objects += json("id" to "invalid-selected", "type" to "stroke", "points" to JSONArray())
                repository.putPage(doc.id, page, 0)
                val digest = OcrInput.pageDigest(page, "ko-primary")
                val indexed = json("documentId" to doc.id, "pageId" to page.id, "pageDigest" to digest,
                    "status" to "complete", "regions" to JSONArray(listOf(json("id" to "test-index",
                        "status" to "complete", "selectedText" to "existing full-page index"))))
                check(repository.putOcrResult(doc.id, page.id, repository.generation(page.id), digest, "ko-primary", indexed))
                val original = requireNotNull(repository.ocrResult(page.id)).toString()
                val service = RecognitionService()
                var modelsTouched = false
                service.onModelState = { _, _ -> modelsTouched = true }
                val coordinator = PageOcrCoordinator(repository, service)
                try {
                    fun request(math: Boolean = false, selected: Set<String>? = setOf("invalid-selected"),
                                progress: (String, Int, Int) -> Unit = { _, _, _ -> }): PageOcrCoordinator.Receipt {
                        val done = CountDownLatch(1)
                        val result = AtomicReference<PageOcrCoordinator.Receipt>()
                        coordinator.request(doc.id, page.id, "ko-primary", false, math = math, selectedIds = selected,
                            progress = progress, complete = { result.set(it); done.countDown() })
                        check(done.await(10, TimeUnit.SECONDS)) { "scopedOcrReceiptTimeout" }
                        return requireNotNull(result.get())
                    }
                    val scoped = request()
                    check(scoped.applied && scoped.result?.optBoolean("ephemeral") == true)
                    check(scoped.result?.optString("scope") == "selection")
                    check(scoped.result?.array("invalidInput")?.length() == 1)
                    check(repository.ocrResult(page.id)?.toString() == original) { "Selection OCR overwrote full-page text index" }
                    val missing = request(selected = setOf("removed"))
                    check(!missing.applied && missing.errorCode == "selectionChanged")
                    check(repository.ocrResult(page.id)?.toString() == original)
                    val math = request(math = true)
                    check(!math.applied && math.errorCode != null)
                    check(repository.ocrResult(page.id)?.toString() == original) { "Math OCR overwrote full-page text index" }
                    val cancelled = request(progress = { stage, _, _ -> if (stage == "review") coordinator.cancel(page.id) })
                    check(!cancelled.applied && cancelled.errorCode == "cancelled")
                    check(repository.ocrResult(page.id)?.toString() == original)
                    val stale = request(progress = { stage, _, _ ->
                        if (stage == "review") {
                            page.meta.put("scopeBoundaryEdit", true)
                            repository.putPage(doc.id, page, 0)
                        }
                    })
                    check(!stale.applied && stale.errorCode == "stalePage")
                    check(!modelsTouched) { "Invalid/removed selection unexpectedly downloaded or invoked a model" }
                } finally { coordinator.close(); service.close() }
                repository.deleteDocument(doc.id)
            }
        } finally { context.deleteDatabase(database) }
    }
}
