package com.inkforge.notesstudio

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real Room, coordinator, and Activity checks; no recognition output is fabricated. */
object S3ClosureChecks {
    fun run(context: Context, instrument: Instrumentation) {
        staleDigestAndBookmarkMerge(context)
        heldSaveManualRequest(context, instrument)
    }

    private fun stroke(id: String) = json("id" to id, "type" to "stroke", "points" to JSONArray(listOf(
        json("x" to 30, "y" to 40, "p" to .5, "t" to 1),
        json("x" to 90, "y" to 51, "p" to .5, "t" to 2))))

    private fun staleDigestAndBookmarkMerge(context: Context) {
        val database = "s3-closure-${UUID.randomUUID()}.db"
        try {
            NoteRepository(context, database).use { repository ->
                val docId = "s3-${UUID.randomUUID()}"
                val doc = DocumentInfo(docId, json("id" to docId, "title" to "S3 check", "version" to 5))
                val page = NotePage.blank("blank").apply {
                    meta.put("pdfSource", "asset:reference").put("pdfPageNumber", 1)
                        .put("pdfImportId", "pending").put("pdfImportPending", true)
                    objects += stroke("original")
                }
                repository.transaction { repository.putDocument(doc); repository.putPage(docId, page, 0) }
                val edited = NotePage.from(page.json()).apply { objects += stroke("unsaved") }
                val expected = OcrInput.pageDigest(edited, "ko-primary")
                val recognition = RecognitionService()
                val coordinator = PageOcrCoordinator(repository, recognition)
                try {
                    val done = CountDownLatch(1)
                    val receipt = AtomicReference<PageOcrCoordinator.Receipt>()
                    coordinator.request(docId, page.id, "ko-primary", false, expectedDigest = expected,
                        complete = { receipt.set(it); done.countDown() })
                    check(done.await(5, TimeUnit.SECONDS)) { "staleDigestReceiptTimeout" }
                    check(receipt.get()?.errorCode == "stalePage" && repository.ocrResult(page.id) == null)
                } finally { coordinator.close(); recognition.close() }

                val staleUiMeta = requireNotNull(repository.pageMeta(page.id))
                check(staleUiMeta.optBoolean("pdfImportPending"))
                check(repository.mergePdfText(page.id, "asset:reference", 1, "indexed original", "pdfium-8086"))
                check(repository.pdfImportState(page.id, "pending", "asset:reference", false, 1))
                repository.setPageBookmark(page.id, true)
                val committed = requireNotNull(repository.pageMeta(page.id))
                check(committed.optBoolean("bookmarked") && !committed.optBoolean("pdfImportPending"))
                check(committed.optString("pdfText") == "indexed original")
            }
        } finally { context.deleteDatabase(database) }
    }

    private fun heldSaveManualRequest(context: Context, instrument: Instrumentation) {
        val priorMigration: String
        val docId: String
        val pageId: String
        NoteRepository(context).use { repository ->
            priorMigration = repository.setting("legacy-migration-complete")
            repository.setting("legacy-migration-complete", "true", true)
            val doc = repository.create("S3 save race ${UUID.randomUUID()}", "root", "blank")
            docId = doc.id
            pageId = repository.pageIds(doc.id).single()
        }
        val release = CountDownLatch(1)
        var activity: MainActivity? = null
        try {
            activity = instrument.startActivitySync(Intent(Intent.ACTION_MAIN)
                .setClassName(context.packageName, MainActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val opened = requireNotNull(activity)
            ui(instrument) { opened.openDocument(docId) }
            await("s3EditorReady") { ui(instrument) { opened.inkView?.currentPage?.id == pageId } }
            ui(instrument) {
                val settings = MainActivity::class.java.getDeclaredField("settings").apply { isAccessible = true }
                    .get(opened) as JSONObject
                settings.put("autoOcr", false).put("autoMath", false)
            }
            val entered = CountDownLatch(1)
            opened.repository.executor.execute { entered.countDown(); release.await(20, TimeUnit.SECONDS) }
            check(entered.await(10, TimeUnit.SECONDS)) { "saveExecutorNotHeld" }
            val newId = "held-save-${UUID.randomUUID()}"
            ui(instrument) {
                opened.inkView!!.add(stroke(newId))
                MainActivity::class.java.getDeclaredMethod("recognize", Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(opened, false, false)
            }
            SystemClock.sleep(300)
            check(opened.repository.page(pageId)?.objects?.none { it.optString("id") == newId } == true)
            check(opened.repository.ocrResult(pageId) == null) { "unsavedPageOcrPersisted" }
            val coordinator = MainActivity::class.java.getDeclaredField("pageOcr").apply { isAccessible = true }
                .get(opened) as PageOcrCoordinator
            val queue = PageOcrCoordinator::class.java.getDeclaredField("queue").apply { isAccessible = true }
                .get(coordinator)
            val pending = OcrRequestQueue::class.java.getDeclaredField("pending").apply { isAccessible = true }
                .get(queue) as Map<*, *>
            val active = OcrRequestQueue::class.java.getDeclaredField("activeRequest").apply { isAccessible = true }
                .get(queue)
            check(pending.isEmpty() && active == null) { "ocrDispatchedBeforeHeldSave" }
            check(ui(instrument) { val row = MainActivity::class.java.getDeclaredField("ocrStatusRow")
                .apply { isAccessible = true }.get(opened) as View
                row.visibility == View.VISIBLE }) { "phoneOcrStatusHidden" }
            release.countDown()
            await("heldEditCommitted") { opened.repository.page(pageId)?.objects?.any { it.optString("id") == newId } == true }
            ui(instrument) {
                val label = MainActivity::class.java.getDeclaredField("ocrStatusText")
                    .apply { isAccessible = true }.get(opened) as android.widget.TextView
                check(label.text.contains("필기") || label.text.contains("손글씨")) { "ocrStatusOverwrittenBySave" }
                val button = MainActivity::class.java.getDeclaredField("ocrCancelButton")
                .apply { isAccessible = true }.get(opened) as View
                check(button.visibility == View.VISIBLE) { "ocrCancelHidden" }
                button.performClick()
            }
            SystemClock.sleep(250)
            check(opened.repository.ocrResult(pageId) == null) { "cancelledOcrPersisted" }
        } finally {
            release.countDown()
            activity?.let { ui(instrument) { it.finish() } }
            val current = activity?.repository
            if (current != null) {
                current.deleteDocument(docId)
                current.setting("legacy-migration-complete", priorMigration, true)
            } else NoteRepository(context).use { repository ->
                repository.deleteDocument(docId)
                repository.setting("legacy-migration-complete", priorMigration, true)
            }
        }
    }

    private fun <T> ui(instrument: Instrumentation, block: () -> T): T {
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        instrument.runOnMainSync { try { result.set(block()) } catch (error: Throwable) { failure.set(error) } }
        failure.get()?.let { throw it }
        return result.get()
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        error("Timeout:$label")
    }
}
