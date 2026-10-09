package com.inkforge.notesstudio

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Consumes one named original-PDF input at a time, then validates the product importer and tile loader. */
internal object PdfStressChecks {
    data class Receipt(val pages: Int, val firstReadyMs: Long, val fullIndexMs: Long, val fdDelta: Int)

    fun run(context: Context, instrument: Instrumentation, mib: Int, cancelAfterFirst: Boolean): Receipt {
        require(mib == 100 || mib == 300) { "pdfStressMiB must be 100 or 300" }
        val input = File(context.filesDir, "pdf-stress/original-${mib}MiB.pdf")
        require(input.isFile) { "Missing named PDF stress fixture: ${input.name}" }
        val manifest = org.json.JSONObject(File(context.filesDir, "pdf-stress/manifest.json").readText(Charsets.UTF_8))
        val expected = manifest.getJSONObject("files").getJSONObject(input.name)
        check(input.length() == expected.getLong("size"))
        val digest = MessageDigest.getInstance("SHA-256")
        input.inputStream().use { stream ->
            val bytes = ByteArray(64 * 1024)
            while (true) { val count = stream.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == expected.getString("sha256"))
        require(StatFs(context.filesDir.path).availableBytes > input.length() + 160L * 1024 * 1024) {
            "pdfStressInsufficientDeviceStorage"
        }
        val count = if (mib == 100) 67 else 200
        val started = SystemClock.elapsedRealtime()
        val beforeFds = fdCount()
        val name = "pdf-stress-${mib}-${UUID.randomUUID()}.db"
        var firstReadyMs = 0L
        var fullIndexMs = 0L
        try {
            NoteRepository(context, name).use { repository ->
                val assetsBefore = repository.assetDirectory.listFiles()?.map(File::getName)?.toSet().orEmpty()
                try {
                    val finished = CountDownLatch(1)
                    val failure = AtomicReference<String?>()
                    val doc = input.inputStream().use { PdfImporter.import(repository, it,
                        "Synthetic original ${mib}MiB", "root", changed = { _, changedPages, error ->
                            if (!changedPages) { failure.set(error); finished.countDown() }
                        }) }
                    val firstId = repository.pageIds(doc.id).first()
                    val first = requireNotNull(repository.page(firstId))
                    check(first.meta.optInt("pdfPageNumber") == 1 && first.meta.optString("pdfSource").startsWith("asset:"))
                    firstReadyMs = SystemClock.elapsedRealtime() - started
                    val originalAsset = repository.asset(first.meta.getString("pdfSource").removePrefix("asset:"))
                    // The import has already finalized its source copy, so this staging file is disposable.
                    check(input.delete()) { "PDF staging source could not be released" }
                    if (cancelAfterFirst) {
                        repository.deleteDocument(doc.id)
                        // A cancelled importer may exit after noticing the removed document,
                        // without delivering its optional observer callback.
                        finished.await(5, TimeUnit.SECONDS)
                        PdfImporter.resumePending(repository)
                        SystemClock.sleep(500)
                        check(repository.document(doc.id) == null && repository.pageIds(doc.id).isEmpty()) {
                            "Deleted PDF was resurrected"
                        }
                        originalAsset.delete()
                        fullIndexMs = SystemClock.elapsedRealtime() - started
                    } else {
                        check(finished.await(if (mib == 300) 900 else 420, TimeUnit.SECONDS)) { "pdfStressIndexTimeout" }
                        check(failure.get() == null) { "pdfStressIndexFailed:${failure.get()}" }
                        fullIndexMs = SystemClock.elapsedRealtime() - started
                        val ids = repository.pageIds(doc.id)
                        check(ids.size == count) { "PDF page count ${ids.size} != $count" }
                        check("BADNOTE $mib MiB streaming original page 1" in
                            requireNotNull(repository.pageMeta(ids.first())).optString("pdfText"))
                        check("BADNOTE $mib MiB streaming original page $count" in
                            requireNotNull(repository.pageMeta(ids.last())).optString("pdfText"))
                        check(!requireNotNull(repository.pageMeta(firstId)).optBoolean("pdfImportPending"))
                        visibleTile(context, instrument, repository, requireNotNull(repository.page(ids.first())))
                        visibleTile(context, instrument, repository, requireNotNull(repository.page(ids.last())))
                        repository.deleteDocument(doc.id)
                        check(repository.pageIds(doc.id).isEmpty())
                        originalAsset.delete()
                    }
                } finally {
                    repository.assetDirectory.listFiles()?.filter { it.name !in assetsBefore }?.forEach(File::delete)
                }
            }
        } finally { context.deleteDatabase(name) }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline && fdCount() > beforeFds + 4) SystemClock.sleep(100)
        check(fdCount() <= beforeFds + 4) { "PDF stress descriptor leak" }
        return Receipt(if (cancelAfterFirst) 1 else count, firstReadyMs, fullIndexMs, fdCount() - beforeFds)
    }

    private fun visibleTile(context: Context, instrument: Instrumentation, repository: NoteRepository, page: NotePage) {
        val ready = CountDownLatch(1)
        lateinit var loader: BackgroundLoader
        instrument.runOnMainSync { loader = BackgroundLoader(context, repository, { ready.countDown() }) }
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            fun draw() = instrument.runOnMainSync {
                bitmap.eraseColor(Color.WHITE)
                val canvas = Canvas(bitmap)
                canvas.scale(512f / page.width, 512f / page.width)
                loader.beginFrame()
                loader.draw(canvas, page, RectF(0f, 0f, page.width, page.height.coerceAtMost(page.width)), .5f)
                loader.endFrame()
            }
            draw()
            check(ready.await(90, TimeUnit.SECONDS)) { "PDF visible tile was not ready" }
            val deadline = SystemClock.elapsedRealtime() + 30_000
            var visible = false
            while (!visible && SystemClock.elapsedRealtime() < deadline) {
                draw()
                val pixels = IntArray(512 * 512)
                bitmap.getPixels(pixels, 0, 512, 0, 0, 512, 512)
                visible = pixels.any { Color.red(it) < 220 || Color.green(it) < 220 || Color.blue(it) < 220 }
                if (!visible) SystemClock.sleep(100)
            }
            check(visible) { "PDF tile did not produce visible source pixels" }
        } finally {
            instrument.runOnMainSync { loader.close() }
            bitmap.recycle()
        }
    }

    private fun fdCount() = File("/proc/self/fd").list()?.size ?: 0
}
