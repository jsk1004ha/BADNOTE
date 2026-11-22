package com.inkforge.notesstudio

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real PdfRenderer checks using only generated assets and a uniquely named, disposable database. */
internal object PdfZoomChecks {
    fun run(context: Context, instrument: Instrumentation): String {
        val database = "pdf-zoom-${UUID.randomUUID()}.db"
        val documents = ArrayList<String>()
        val assets = ArrayList<File>()
        val beforeFds = fdCount()
        var callbacks = 0
        try {
            NoteRepository(context, database).use { repository ->
                try {
                    fun importFixture(color: Int): NotePage {
                        val bytes = ByteArrayOutputStream().use { output ->
                            val pdf = PdfDocument()
                            try {
                                repeat(2) { index ->
                                    val sheet = pdf.startPage(PdfDocument.PageInfo.Builder(1000, 1000, index + 1).create())
                                    sheet.canvas.drawColor(if (index == 0) color else Color.rgb(180, 215, 245))
                                    val paint = Paint().apply { this.color = Color.BLACK; strokeWidth = .65f }
                                    for (x in 40..900 step 3) sheet.canvas.drawLine(x + .25f, 40f, x + 71.75f, 900f, paint)
                                    pdf.finishPage(sheet)
                                }
                                pdf.writeTo(output)
                            } finally { pdf.close() }
                            output.toByteArray()
                        }
                        val done = CountDownLatch(1)
                        val error = AtomicReference<String?>()
                        val document = bytes.inputStream().use { input ->
                            PdfImporter.import(repository, input, "Generated PDF zoom regression", "root", changed = { _, changed, failure ->
                                if (!changed) { error.set(failure); done.countDown() }
                            })
                        }
                        documents += document.id
                        val first = requireNotNull(repository.page(repository.pageIds(document.id).first()))
                        assets += repository.asset(first.meta.getString("pdfSource").removePrefix("asset:"))
                        check(done.await(30, TimeUnit.SECONDS)) { "pdfZoomImportTimeout" }
                        check(error.get() == null) { "pdfZoomImportFailed:${error.get()}" }
                        return first
                    }
                    val page = importFixture(Color.rgb(234, 229, 210))
                    val otherSource = importFixture(Color.rgb(210, 245, 200)).copy(id = page.id)
                    val viewport = RectF(0f, 0f, 500f, 500f)
                    val error = AtomicReference<String?>()
                    val visibleReady = CountDownLatch(1)
                    lateinit var loader: BackgroundLoader
                    instrument.runOnMainSync {
                        loader = BackgroundLoader(context, repository, { callbacks++; visibleReady.countDown() }, { error.set(it) })
                    }
                    val probe = Probe(loader, instrument)
                    val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                    try {
                        fun draw(scale: Float, visible: RectF = viewport, target: NotePage = page): IntArray {
                            instrument.runOnMainSync {
                                bitmap.eraseColor(Color.MAGENTA)
                                val canvas = Canvas(bitmap)
                                canvas.scale(bitmap.width / visible.width(), bitmap.height / visible.height())
                                canvas.translate(-visible.left, -visible.top)
                                loader.beginFrame(); loader.draw(canvas, target, visible, scale); loader.endFrame()
                            }
                            return IntArray(bitmap.width * bitmap.height).also {
                                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            }
                        }
                        fun covered(pixels: IntArray, name: String) {
                            check(pixels.none { it == Color.MAGENTA }) { "pdfZoomBlank:$name" }
                        }
                        draw(.99f)
                        probe.drain()
                        check(visibleReady.await(5, TimeUnit.SECONDS)) { "pdfZoomVisibleInvalidationMissing" }
                        val warm = draw(.99f)
                        covered(warm, "initial")
                        instrument.runOnMainSync {
                            val overviews = probe.cache().snapshot().filterKeys { ":overview:" in it }
                            check(overviews.size == 1) { "pdfZoomOverviewMissing" }
                            val value = overviews.values.single()
                            val field = value.javaClass.getDeclaredField("bitmap").apply { isAccessible = true }
                            val overview = field.get(value) as Bitmap
                            check(overview.width <= 512 && overview.height <= 512 &&
                                overview.allocationByteCount <= 512 * 512 * 4) { "pdfZoomOverviewExceededTileBudget" }
                        }
                        probe.gate().use {
                            val zoomFallback = draw(1.01f)
                            covered(zoomFallback, "bucketTransition")
                            check(zoomFallback.contentEquals(warm)) { "pdfZoomDidNotReusePreviousBucket" }
                            val requested = probe.pending()
                            check(requested.size == 4) { "pdfZoomExpectedFourHighResolutionTiles:${requested.size}" }
                            covered(draw(1.01f, RectF(10f, 10f, 510f, 510f)), "smallPan")
                            val moved = probe.pending()
                            check(moved.keys == requested.keys && moved.all { (key, task) -> requested[key] === task }) {
                                "pdfZoomReplacedOverlappingRequests"
                            }
                            covered(draw(1.01f, RectF(400f, 0f, 900f, 500f)), "tileBoundaryPan")
                            val crossed = probe.pending()
                            val shared = crossed.keys.intersect(requested.keys)
                            check(shared.size == 2 && shared.all { crossed[it] === requested[it] }) {
                                "pdfZoomLostSharedBoundaryRequests"
                            }
                            check(crossed.size == 4 && probe.worker.queue.size == 4) { "pdfZoomObsoleteQueuedTiles" }
                            covered(draw(1.01f), "returnPan")
                        }
                        probe.drain()
                        val sharp = draw(1.01f)
                        covered(sharp, "highResolution")
                        check(!sharp.contentEquals(warm)) { "pdfZoomNeverReplacedFallbackWithHighResolution" }
                        check(probe.pending().isEmpty()) { "pdfZoomPendingDidNotConverge" }
                        probe.gate().use { covered(draw(.49f), "zoomOutFallback") }
                        probe.drain()
                        covered(draw(.49f), "zoomOutReady")
                        check(draw(1.01f).contentEquals(sharp) && probe.pending().isEmpty()) { "pdfZoomWarmBucketWasNotReused" }

                        // Neither a different original PDF nor a different source page may inherit old-page tiles.
                        probe.gate().use {
                            check(draw(1.01f, target = otherSource).all { it == Color.MAGENTA }) { "pdfZoomLeakedAnotherDocument" }
                        }
                        probe.drain()
                        covered(draw(1.01f, target = otherSource), "otherDocument")
                        val secondPage = page.copy(meta = page.meta.copyJson().put("pdfPageNumber", 2))
                        probe.gate().use {
                            check(draw(1.01f, target = secondPage).all { it == Color.MAGENTA }) { "pdfZoomLeakedAnotherPage" }
                        }
                        probe.drain()
                        covered(draw(1.01f, target = secondPage), "otherPage")
                        check(error.get() == null) { "pdfZoomRendererError:${error.get()}" }

                        // Cache eviction must remove the fallback index, not recycle pixels owned by a Picture.
                        val picture = Picture()
                        val published = ArrayList<Bitmap>()
                        instrument.runOnMainSync {
                            val canvas = picture.beginRecording(512, 512)
                            canvas.scale(512f / viewport.width(), 512f / viewport.height())
                            loader.beginFrame(); loader.draw(canvas, page, viewport, 1.01f); loader.endFrame()
                            picture.endRecording()
                            val cache = probe.cache()
                            check(cache.size() <= cache.maxSize()) { "pdfZoomCacheExceededBudget" }
                            cache.snapshot().values.forEach { value ->
                                val field = value.javaClass.getDeclaredField("bitmap").apply { isAccessible = true }
                                published += field.get(value) as Bitmap
                            }
                            cache.evictAll()
                            check(probe.pdfIndexEmpty()) { "pdfZoomEvictionRetainedTileIndex" }
                            check(published.none { it.isRecycled }) { "pdfZoomRecycledPublishedBitmap" }
                            bitmap.eraseColor(Color.MAGENTA)
                            Canvas(bitmap).drawPicture(picture)
                        }
                        val replay = IntArray(512 * 512)
                        bitmap.getPixels(replay, 0, 512, 0, 0, 512, 512)
                        covered(replay, "pictureAfterEviction")
                        check(replay.contentEquals(sharp)) { "pdfZoomPublishedPictureChangedAfterEviction" }

                        // A render that finished but has not been published must also be safe to close.
                        var callbacksAtClose = 0
                        instrument.runOnMainSync {
                            loader.beginFrame(); loader.draw(Canvas(bitmap), page, viewport, .99f); loader.endFrame()
                            val finished = CountDownLatch(1)
                            probe.worker.execute { finished.countDown() }
                            check(finished.await(10, TimeUnit.SECONDS)) { "pdfZoomClosePublicationRenderTimeout" }
                            callbacksAtClose = callbacks
                            loader.close()
                        }
                        check(probe.worker.awaitTermination(20, TimeUnit.SECONDS)) { "pdfZoomInFlightCloseTimeout" }
                        instrument.runOnMainSync {
                            check(callbacks == callbacksAtClose && probe.pendingOnMain().isEmpty() &&
                                probe.cache().size() == 0 && probe.pdfIndexEmpty()) { "pdfZoomPublishedAfterClose" }
                        }
                    } finally {
                        instrument.runOnMainSync { loader.close() }
                        check(probe.worker.awaitTermination(20, TimeUnit.SECONDS)) { "pdfZoomCloseTimeout" }
                        instrument.runOnMainSync { }
                        bitmap.recycle()
                    }

                    // Complete an actual render while main publication is held, then change the viewport.
                    // This deterministically exercises the old generation-mismatch discard race.
                    lateinit var raceLoader: BackgroundLoader
                    instrument.runOnMainSync { raceLoader = BackgroundLoader(context, repository, { callbacks++ }) }
                    val race = Probe(raceLoader, instrument)
                    val raceBitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                    try {
                        instrument.runOnMainSync {
                            val canvas = Canvas(raceBitmap)
                            raceLoader.beginFrame(); raceLoader.draw(canvas, page, viewport, .99f); raceLoader.endFrame()
                            val finished = CountDownLatch(1)
                            race.worker.execute { finished.countDown() }
                            check(finished.await(10, TimeUnit.SECONDS)) { "pdfZoomHeldPublicationRenderTimeout" }
                            val original = race.pendingOnMain().toMap()
                            raceLoader.beginFrame()
                            raceLoader.draw(canvas, page, RectF(10f, 10f, 510f, 510f), .99f)
                            raceLoader.endFrame()
                            check(race.pendingOnMain().all { (key, task) -> original[key] === task }) {
                                "pdfZoomReplacedRunningTile"
                            }
                        }
                        race.drain()
                        instrument.runOnMainSync {
                            raceBitmap.eraseColor(Color.MAGENTA)
                            raceLoader.beginFrame(); raceLoader.draw(Canvas(raceBitmap), page, viewport, .99f); raceLoader.endFrame()
                            check(race.pendingOnMain().isEmpty()) { "pdfZoomDiscardedValidRunningTile" }
                        }
                        check(raceBitmap.getPixel(20, 20) != Color.MAGENTA) { "pdfZoomRunningTileWasNotPublished" }

                        val gate = race.gate()
                        try {
                            instrument.runOnMainSync {
                                raceLoader.beginFrame(); raceLoader.draw(Canvas(raceBitmap), page, viewport, 2.01f); raceLoader.endFrame()
                                check(race.pendingOnMain().isNotEmpty())
                                raceLoader.close()
                            }
                        } finally { gate.close() }
                        check(race.worker.awaitTermination(20, TimeUnit.SECONDS)) { "pdfZoomQueuedCloseTimeout" }
                        instrument.runOnMainSync {
                            check(race.pendingOnMain().isEmpty() && race.cache().size() == 0 && race.pdfIndexEmpty()) {
                                "pdfZoomCloseRetainedWork"
                            }
                            val pdfs = BackgroundLoader::class.java.getDeclaredField("pdfs").apply { isAccessible = true }
                            check((pdfs.get(raceLoader) as Map<*, *>).isEmpty()) { "pdfZoomCloseRetainedRenderer" }
                        }
                    } finally {
                        instrument.runOnMainSync { raceLoader.close() }
                        check(race.worker.awaitTermination(20, TimeUnit.SECONDS))
                        raceBitmap.recycle()
                    }
                } finally {
                    documents.forEach(repository::deleteDocument)
                    assets.forEach { check(!it.exists() || it.delete()) { "pdfZoomGeneratedAssetCleanupFailed" } }
                }
            }
        } finally { context.deleteDatabase(database) }
        check(fdCount() <= beforeFds + 4) { "pdfZoomDescriptorLeak" }
        return "actualPdfRenderer=true,bucketFallback=true,viewportReuse=true,highResolution=true,sourceIsolation=true,pictureLifetime=true,close=true,callbacks=$callbacks"
    }

    /** Reflection is confined to instrumentation; production has no test executor or diagnostics API. */
    private class Probe(private val loader: BackgroundLoader, private val instrument: Instrumentation) {
        val worker = BackgroundLoader::class.java.getDeclaredField("worker").let {
            it.isAccessible = true; it.get(loader) as ThreadPoolExecutor
        }
        @Suppress("UNCHECKED_CAST")
        fun pendingOnMain(): Map<String, Any> = BackgroundLoader::class.java.getDeclaredField("pending").let {
            it.isAccessible = true; it.get(loader) as Map<String, Any>
        }
        fun pending(): Map<String, Any> {
            var result = emptyMap<String, Any>()
            instrument.runOnMainSync { result = pendingOnMain().toMap() }
            return result
        }
        @Suppress("UNCHECKED_CAST")
        fun cache(): LruCache<String, Any> = BackgroundLoader::class.java.getDeclaredField("cache").let {
            it.isAccessible = true; it.get(loader) as LruCache<String, Any>
        }
        fun pdfIndexEmpty(): Boolean = BackgroundLoader::class.java.getDeclaredField("pdfTiles").let {
            it.isAccessible = true; (it.get(loader) as Map<*, *>).isEmpty()
        }
        fun drain() {
            val done = CountDownLatch(1)
            worker.execute { done.countDown() }
            check(done.await(30, TimeUnit.SECONDS)) { "pdfZoomRenderTimeout" }
            instrument.runOnMainSync { }
        }
        fun gate(): Gate = Gate(worker)
    }

    private class Gate(worker: ThreadPoolExecutor) : AutoCloseable {
        private val release = CountDownLatch(1)
        init {
            val entered = CountDownLatch(1)
            worker.execute { entered.countDown(); check(release.await(30, TimeUnit.SECONDS)) { "pdfZoomWorkerGateTimeout" } }
            check(entered.await(20, TimeUnit.SECONDS)) { "pdfZoomWorkerGateNotEntered" }
        }
        override fun close() { release.countDown() }
    }

    private fun fdCount() = File("/proc/self/fd").list()?.size ?: 0
}
