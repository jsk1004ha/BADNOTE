package com.inkforge.notesstudio

import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.view.ViewGroup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Streams the host's original W3 pages one at a time through the real Room/editor/OCR path. */
internal object W3NotebookChecks {
    data class Receipt(val runId: String, val pages: Int, val strokes: Int, val points: Int,
                       val ocrPageStrokes: Int, val ocrStatus: String, val firstLoaded: Int,
                       val lastLoaded: Int, val pssKb: Int, val totalMs: Long)

    fun run(context: Context, instrument: Instrumentation, runId: String): Receipt {
        require(runId.matches(Regex("[0-9a-fA-F]{32}"))) { "w3RunIdMustBeUuidHex" }
        val directory = File(context.filesDir, "w3").apply { mkdirs() }
        val manifest = JSONObject(File(directory, "manifest.json").readText(Charsets.UTF_8))
        require(manifest.getInt("schemaVersion") == 1 && manifest.getString("workload") == "W3")
        val entries = manifest.getJSONArray("pages")
        require(entries.length() == 300) { "w3Requires300OriginalPages" }
        require(StatFs(context.filesDir.path).availableBytes > 2L * 1024 * 1024 * 1024) {
            "w3InsufficientDeviceStorage"
        }
        val incoming = File(directory, "incoming.json")
        val ready = File(directory, "ready.json")
        check(!incoming.exists() || incoming.delete()) { "w3StaleIncomingCannotBeRemoved" }
        ready.delete()
        val started = SystemClock.elapsedRealtime()
        val database = "w3-$runId.db"
        var view: InkCanvasView? = null
        var host: MainActivity? = null
        try {
            NoteRepository(context, database).use { repository ->
                val documentId = "w3-$runId"
                val document = DocumentInfo(documentId, json("id" to documentId,
                    "title" to "Synthetic W3 load", "folderId" to "root", "version" to 5))
                repository.transaction { repository.putDocument(document) }
                var storedStrokes = 0
                var storedPoints = 0
                for (index in 0 until entries.length()) {
                    val expected = entries.getJSONObject(index)
                    require(expected.getString("id") == "synthetic-W3-$index" &&
                        expected.getString("file") == "synthetic-W3-$index.ink.json" &&
                        expected.getInt("strokes") == 2000 && expected.getInt("points") == 40000)
                    atomicReady(ready, runId, index)
                    val deadline = SystemClock.elapsedRealtime() + 180_000
                    while (!incoming.isFile && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
                    check(incoming.isFile) { "w3IncomingTimeout:$index" }
                    check(sha256(incoming) == expected.getString("sha256")) { "w3IncomingDigest:$index" }
                    val source = JSONObject(incoming.readText(Charsets.UTF_8))
                    require(source.getString("id") == expected.getString("id")) { "w3PageIdentity:$index" }
                    val page = NotePage.fromBorrowed(source)
                    var points = 0
                    require(page.objects.size == 2000) { "w3StrokeCount:$index" }
                    page.objects.forEach { stroke ->
                        require(stroke.optString("type") == "stroke" && stroke.optString("id").isNotBlank())
                        points += stroke.getJSONArray("points").length()
                    }
                    require(points == 40000) { "w3PointCount:$index" }
                    repository.transaction { repository.putPage(documentId, page, index) }
                    val persisted = requireNotNull(repository.page(page.id)) { "w3PageNotPersisted:$index" }
                    check(persisted.objects.size == 2000 &&
                        persisted.objects.sumOf { it.getJSONArray("points").length() } == 40000) {
                        "w3PersistedInkMismatch:$index"
                    }
                    storedStrokes += persisted.objects.size
                    storedPoints += 40000
                    check(incoming.delete()) { "w3IncomingCannotBeReleased:$index" }
                }
                ready.delete()
                val ids = repository.pageIds(documentId)
                check(ids.size == 300 && ids.first() == "synthetic-W3-0" &&
                    ids.last() == "synthetic-W3-299") { "w3StoredPageOrder" }
                check(storedStrokes == 600000 && storedPoints == 12000000)
                val metadata = ids.map { requireNotNull(repository.pageMeta(it)) }

                val intent = Intent(Intent.ACTION_MAIN).setClassName(context.packageName,
                    MainActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                host = instrument.startActivitySync(intent) as MainActivity
                instrument.runOnMainSync {
                    val activity = requireNotNull(host)
                    val root = activity.findViewById<ViewGroup>(android.R.id.content)
                    view = InkCanvasView(activity, repository)
                    root.addView(view, ViewGroup.LayoutParams(-1, -1))
                    view!!.open(ids, metadata, 0)
                }
                await("w3FirstViewport") { onMain(instrument) {
                    view?.width?.let { it > 0 } == true && view?.currentPage?.id == ids.first()
                } }
                val firstLoaded = loadedPages(requireNotNull(view))
                check(firstLoaded in 1..7) { "w3FirstViewportUnbounded:$firstLoaded" }
                instrument.runOnMainSync { view!!.goTo(299) }
                await("w3LastViewport") { onMain(instrument) { view?.currentPage?.id == ids.last() } }
                val lastLoaded = loadedPages(requireNotNull(view))
                check(lastLoaded in 1..7) { "w3LastViewportUnbounded:$lastLoaded" }
                check(onMain(instrument) { view!!.currentPage!!.objects.size } == 2000)

                val recognition = RecognitionService()
                val coordinator = PageOcrCoordinator(repository, recognition)
                val complete = CountDownLatch(1)
                val receipt = AtomicReference<PageOcrCoordinator.Receipt>()
                try {
                    coordinator.request(documentId, ids.first(), "ko-primary", false,
                        complete = { receipt.set(it); complete.countDown() })
                    check(complete.await(360, TimeUnit.SECONDS)) { "w3OcrTimeout" }
                    val actual = requireNotNull(receipt.get())
                    check(actual.applied && actual.result != null) {
                        "w3OcrDidNotPersist:${actual.errorCode}"
                    }
                    val status = actual.result.optString("status")
                    check(status in setOf("complete", "partial") && repository.ocrResult(ids.first()) != null)
                    val pss = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                        .getProcessMemoryInfo(intArrayOf(Process.myPid())).first().totalPss
                    return Receipt(runId, ids.size, storedStrokes, storedPoints, 2000, status,
                        firstLoaded, lastLoaded, pss, SystemClock.elapsedRealtime() - started)
                } finally { coordinator.close(); recognition.close() }
            }
        } finally {
            view?.let { editor -> instrument.runOnMainSync {
                (editor.parent as? ViewGroup)?.removeView(editor)
                editor.close()
            } }
            host?.let { activity -> instrument.runOnMainSync { activity.finish() } }
            ready.delete()
            incoming.delete()
            context.deleteDatabase(database)
        }
    }

    private fun atomicReady(file: File, runId: String, index: Int) {
        val temporary = File(file.parentFile, "ready.json.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(json("runId" to runId, "index" to index).toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        check(!file.exists() || file.delete())
        check(temporary.renameTo(file)) { "w3ReadyAtomicRename" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun await(label: String, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ready()) return
            SystemClock.sleep(100)
        }
        error("Timeout:$label")
    }

    private fun <T> onMain(instrument: Instrumentation, block: () -> T): T {
        val value = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        instrument.runOnMainSync { try { value.set(block()) } catch (error: Throwable) { failure.set(error) } }
        failure.get()?.let { throw it }
        return value.get()
    }

    private fun loadedPages(view: InkCanvasView): Int {
        val field = InkCanvasView::class.java.getDeclaredField("pages").apply { isAccessible = true }
        return (field.get(view) as Map<*, *>).size
    }
}
