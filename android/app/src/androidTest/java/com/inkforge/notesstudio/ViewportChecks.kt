package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/** Measures software Canvas draw CPU, not display frame latency or physical touch latency. */
object ViewportChecks {
    fun run(activity: MainActivity, ui: (() -> Unit) -> Unit, baseline: Boolean, result: Bundle) {
        val coordinator = MainActivity::class.java.getDeclaredField("pageOcr").apply { isAccessible = true }
            .get(activity) as PageOcrCoordinator
        val service = PageOcrCoordinator::class.java.getDeclaredField("recognition").apply { isAccessible = true }
            .get(coordinator) as RecognitionService
        val queue = PageOcrCoordinator::class.java.getDeclaredField("queue").apply { isAccessible = true }
            .get(coordinator) as OcrRequestQueue
        var gestureAdmitted = 0
        for (pinch in listOf(false, true)) {
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val completed = CountDownLatch(1)
            service.executor.execute { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            check(entered.await(5, TimeUnit.SECONDS))
            ui {
                val view = requireNotNull(activity.inkView)
                view.tool = InkCanvasView.Tool.HAND
                event(view, MotionEvent.ACTION_DOWN, listOf(100f to 150f))
                if (pinch) event(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), listOf(100f to 150f, 300f to 350f))
                event(view, MotionEvent.ACTION_MOVE, if (pinch) listOf(200f to 250f, 450f to 500f) else listOf(200f to 250f))
            }
            try {
                coordinator.request("viewport-probe", "gesture-$pinch", "ko-primary", true) { completed.countDown() }
                val lock = requireNotNull(OcrRequestQueue::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(queue))
                synchronized(lock) {
                    @Suppress("UNCHECKED_CAST")
                    val pending = OcrRequestQueue::class.java.getDeclaredField("pending").apply { isAccessible = true }
                        .get(queue) as Map<String, OcrRequestQueue.Request>
                    gestureAdmitted += pending.values.count { it.automatic }
                }
            } finally {
                release.countDown()
                ui { event(requireNotNull(activity.inkView), MotionEvent.ACTION_UP, listOf(200f to 250f)) }
            }
            check(completed.await(5, TimeUnit.SECONDS))
        }
        result.putInt("viewportGestureAutoAdmitted", gestureAdmitted)
        check(gestureAdmitted == if (baseline) 2 else 0) { "Automatic OCR competed with pan/pinch" }

        ui {
            val view = InkCanvasView(activity, activity.repository)
            val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val times = mutableListOf<Long>()
            val hashes = mutableListOf<Long>()
            val pixels = ByteBuffer.allocate(bitmap.byteCount)
            var candidates = 0
            var heightReads = 0
            val pan = InkCanvasView::class.java.getDeclaredField("panY").apply { isAccessible = true }
            try {
                val count = 10_000
                view.layout(0, 0, 480, 640)
                // Count the metadata reads performed by the real draw loop, including culled pages.
                // This catches accidentally leaving a full-document loop around a bounded helper.
                val pageHeightReads = IntArray(count)
                val metadata = List(count) { index ->
                    object : JSONObject() {
                        override fun optDouble(name: String?, fallback: Double): Double {
                            if (name == "height") { heightReads++; pageHeightReads[index]++ }
                            return super.optDouble(name, fallback)
                        }
                    }.put("height", 1414)
                }
                view.open(List(count) { "viewport-$it" }, metadata)
                // Warm fonts/Canvas; loaded-page placeholders isolate viewport traversal from storage/PDF IO.
                repeat(3) { view.draw(canvas) }
                repeat(60) { frame ->
                    view.zoomBy(if (frame % 2 == 0) 1.08f else 1f / 1.08f)
                    pan.setFloat(view, -(frame * (count - 1) / 59) * 1442f * view.scale)
                    bitmap.eraseColor(0)
                    heightReads = 0
                    pageHeightReads.fill(0)
                    val start = Debug.threadCpuTimeNanos()
                    view.draw(canvas)
                    times += Debug.threadCpuTimeNanos() - start
                    candidates += heightReads
                    if (!baseline) {
                        val repeatedPage = pageHeightReads.indexOfFirst { it > 1 }
                        check(repeatedPage < 0) {
                            "Viewport frame $frame reread page $repeatedPage height ${pageHeightReads[repeatedPage]} times"
                        }
                    }
                    if (frame in listOf(0, 29, 59)) {
                        pixels.clear(); bitmap.copyPixelsToBuffer(pixels)
                        hashes += CRC32().apply { update(pixels.array()) }.value
                    }
                }
                times.sort()
                result.putLong("viewportDrawP50Us", times[times.size / 2] / 1_000)
                result.putLong("viewportDrawP95Us", times[(times.size - 1) * 95 / 100] / 1_000)
                result.putInt("viewportCandidates", candidates)
                result.putString("viewportPixels", hashes.joinToString(","))
                result.putString("viewportWorkload", "10000_page_metadata_60_scroll_zoom_480x640_software_draws_warm")
                if (!baseline) check(candidates < 600) { "Viewport still traverses offscreen notebook pages" }
            } finally { view.close(); bitmap.recycle() }
        }
    }

    private fun event(view: InkCanvasView, action: Int, points: List<Pair<Float, Float>>) {
        val properties = points.indices.map { index -> MotionEvent.PointerProperties().apply {
            id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coordinates = points.map { (x, y) -> MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = 1f
        } }.toTypedArray()
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, points.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }
}
