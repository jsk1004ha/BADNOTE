package com.inkforge.notesstudio

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real repository scheduling and attached preview pixels, with deliberately delayed raster completion. */
object LoadingChecks {
    fun run(activity: MainActivity, instrumentation: Instrumentation): String {
        fun <T> ui(block: () -> T): T {
            val value = AtomicReference<T>(); val failure = AtomicReference<Throwable>()
            instrumentation.runOnMainSync { try { value.set(block()) } catch (error: Throwable) { failure.set(error) } }
            failure.get()?.let { throw it }; return value.get()
        }
        fun await(label: String, condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 20000
            while (SystemClock.uptimeMillis() < end) {
                if (condition()) return
                SystemClock.sleep(20)
            }
            error("Timeout: $label")
        }
        fun CountDownLatch.requireReady(label: String) { check(await(20, TimeUnit.SECONDS)) { "Timeout: $label" } }
        val database = "loading-check-${uid("db")}.db"
        val repository = NoteRepository(activity, database)
        val releaseRead = CountDownLatch(1)
        val releaseFirstRender = CountDownLatch(1)
        val releaseDetachedRender = CountDownLatch(1)
        var loader: ThumbnailLoader? = null
        var host: FrameLayout? = null
        try {
            val document = repository.executor.submit<DocumentInfo> { repository.create("Loading checks", "root", "blank") }.get(20, TimeUnit.SECONDS)
            val ids = repository.executor.submit<List<String>> {
                val first = repository.pageIds(document.id).single()
                repository.putPage(document.id, coloredPage(first, "#ff0000"), 0)
                repeat(3) { index -> repository.addPage(document.id, coloredPage(uid("page"), "#008000"), index + 1) }
                repository.pageIds(document.id)
            }.get(20, TimeUnit.SECONDS)

            val readEntered = CountDownLatch(1)
            repository.executor.execute { readEntered.countDown(); releaseRead.requireReady("read release") }
            readEntered.requireReady("held repository")
            val order = Collections.synchronizedList(ArrayList<String>())
            val callbacks = CountDownLatch(4)
            val offscreen = Any(); val selected = Any(); val preview = Any(); val cancelled = Any()
            val cancelledDelivered = AtomicReference(false)
            ids.take(2).forEach { id -> repository.requestPage(offscreen, id, false) { page ->
                check(page?.id == id && page.objects.single().getString("color").isNotBlank())
                order.add(id); callbacks.countDown()
            } }
            repository.requestThumbnail(preview, document.id, null) { snapshot ->
                check(snapshot?.page()?.id == ids.first()); order.add("preview"); callbacks.countDown()
            }
            repository.requestPage(cancelled, ids.last(), false) { cancelledDelivered.set(true) }
            repository.cancelPageRequests(cancelled)
            repository.requestPage(selected, ids[2], true) { page ->
                check(page?.id == ids[2] && page.objects.single().getString("color") == "#008000")
                order.add("selected"); callbacks.countDown()
            }
            releaseRead.countDown(); callbacks.requireReady("prioritized real pages")
            instrumentation.waitForIdleSync()
            check(order.first { it != "preview" } == "selected") { "Selected page waited behind stale offscreen work: $order" }
            check(!cancelledDelivered.get()) { "Cancelled page request published" }

            val renderStarted = CountDownLatch(1); val detachedRenderStarted = CountDownLatch(1)
            val renderNumber = AtomicInteger()
            val firstPrivateBitmap = AtomicReference<Bitmap>()
            val detachedPrivateBitmap = AtomicReference<Bitmap>()
            val seenFirst = AtomicInteger(Color.WHITE); val seenSecond = AtomicInteger(Color.WHITE)
            val seenReplacement = AtomicInteger(Color.WHITE)
            val replacementSawStale = AtomicReference(false)
            lateinit var first: NotePreview
            lateinit var second: NotePreview
            lateinit var replacement: NotePreview
            ui {
                loader = ThumbnailLoader(repository, { it() }) { page ->
                    val number = renderNumber.incrementAndGet()
                    when (number) {
                        1 -> { renderStarted.countDown(); releaseFirstRender.requireReady("first render release") }
                        3 -> { detachedRenderStarted.countDown(); releaseDetachedRender.requireReady("detached render release") }
                    }
                    StreamingPdf.render(repository, page, 220, 220).also { bitmap ->
                        if (number == 1) firstPrivateBitmap.set(bitmap)
                        if (number == 3) detachedPrivateBitmap.set(bitmap)
                    }
                }
                fun preview(seen: AtomicInteger, stale: AtomicReference<Boolean>? = null): NotePreview = NotePreview(activity).also { view ->
                    requireNotNull(loader).bind(view, document.id, ids.first())
                    val read = requireNotNull(view.loadBitmap)
                    view.loadBitmap = { read()?.also { bitmap ->
                        val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                        seen.set(color); if (color == Color.BLUE) stale?.set(true)
                    } }
                }
                first = preview(seenFirst); second = preview(seenSecond)
                replacement = preview(seenReplacement, replacementSawStale)
                host = FrameLayout(activity).apply {
                    addView(first, FrameLayout.LayoutParams(220, 220))
                    addView(second, FrameLayout.LayoutParams(220, 220).apply { leftMargin = 225 })
                }
                (activity.window.decorView as ViewGroup).addView(host, ViewGroup.LayoutParams(450, 220))
            }
            renderStarted.requireReady("delayed red thumbnail")
            val pageDuringRaster = CountDownLatch(1)
            repository.requestPage(Any(), ids.last(), true) { page ->
                check(page?.objects?.single()?.getString("color") == "#008000")
                pageDuringRaster.countDown()
            }
            pageDuringRaster.requireReady("selected page while thumbnail renderer remains blocked")
            repository.executor.submit { repository.putPage(document.id, coloredPage(ids.first(), "#0000ff"), 0) }.get(20, TimeUnit.SECONDS)
            ui { requireNotNull(loader).invalidatePage(document.id, ids.first()) }
            releaseFirstRender.countDown()
            await("both live previews paint the new generation") { seenFirst.get() == Color.BLUE && seenSecond.get() == Color.BLUE }
            await("old unpublished bitmap released") { firstPrivateBitmap.get()?.isRecycled == true }

            ui { requireNotNull(loader).invalidatePage(document.id, ids.first()) }
            detachedRenderStarted.requireReady("delayed attached blue thumbnail")
            ui { requireNotNull(host).removeAllViews() }
            repository.executor.submit { repository.putPage(document.id, coloredPage(ids.first(), "#00ff00"), 0) }.get(20, TimeUnit.SECONDS)
            // No generation invalidation here: detachment alone must revoke the old publication owner.
            ui { requireNotNull(host).addView(replacement, FrameLayout.LayoutParams(220, 220)) }
            releaseDetachedRender.countDown()
            await("replacement preview paints current persisted page") { seenReplacement.get() == Color.GREEN }
            check(!replacementSawStale.get()) { "Detached renderer resurrected its stale blue thumbnail" }
            await("detached unpublished bitmap released") { detachedPrivateBitmap.get()?.isRecycled == true }
            return "selected_page_overtakes_offscreen_preview_cancelled_callbacks_generation_fanout_detach_pixels_verified"
        } finally {
            releaseRead.countDown(); releaseFirstRender.countDown(); releaseDetachedRender.countDown()
            ui { loader?.close(); host?.let { (it.parent as? ViewGroup)?.removeView(it) } }
            repository.close()
            check(repository.executor.awaitTermination(20, TimeUnit.SECONDS)) { "Loading-check repository did not close" }
            activity.deleteDatabase(database)
        }
    }

    private fun coloredPage(id: String, color: String) = NotePage(id,
        json("id" to id, "width" to 1000, "height" to 1000, "template" to "blank"),
        mutableListOf(json("id" to "color-$id", "type" to "shape", "shape" to "line", "color" to color,
            "width" to 400, "x1" to 0, "y1" to 500, "x2" to 1000, "y2" to 500)))
}
