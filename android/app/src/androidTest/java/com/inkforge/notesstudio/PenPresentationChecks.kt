package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.RecordingCanvas
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import org.json.JSONArray
import java.io.File
import org.json.JSONObject
import java.util.concurrent.Executor

/** Delays actual SurfaceControl commit callbacks to deterministically exercise stroke overlap. */
object PenPresentationChecks {
    private class CommitGate(private val main: Executor) : Executor {
        @Volatile var held = false
        private val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            val queued = synchronized(pending) { if (held) { pending.addLast(command); true } else false }
            if (!queued) main.execute(command)
        }
        fun release() {
            val commands = synchronized(pending) { held = false; pending.toList().also { pending.clear() } }
            commands.forEach { main.execute(it) }
        }
    }

    fun run(activity: MainActivity, instrumentation: android.app.Instrumentation,
            ui: (() -> Unit) -> Unit, result: Bundle) {
        check(android.os.Build.VERSION.SDK_INT >= 31)
        val document = activity.repository.create("pen-presentation", "root", "blank")
        val page = requireNotNull(activity.repository.page(activity.repository.pageIds(document.id).first()))
        val gate = CommitGate(activity.mainExecutor)
        lateinit var host: FrameLayout
        lateinit var view: InkCanvasView
        lateinit var front: InkFrontBuffer
        val screens = File(activity.filesDir, "pen-presentation").apply { mkdirs() }
        fun await(label: String, condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 30_000
            while (SystemClock.uptimeMillis() < end) { if (condition()) return; SystemClock.sleep(25) }
            error("Timeout: $label")
        }
        fun presented(): Boolean { var value = false; ui { value = front.presented }; return value }
        fun pixel(x: Float, y: Float): Int {
            var px = 0; var py = 0
            ui { val location = IntArray(2); view.getLocationOnScreen(location)
                val point = view.screenPoint(x, y); px = location[0] + point.x.toInt(); py = location[1] + point.y.toInt() }
            val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            return try { image.getPixel(px, py) } finally { image.recycle() }
        }
        fun dark(x: Float, y: Float): Boolean { val p = pixel(x, y)
            return Color.red(p) < 100 && Color.green(p) < 130 && Color.blue(p) < 170 }
        fun screen(name: String) { val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { File(screens, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { image.recycle() } }
        var down = 0L
        fun send(action: Int, x: Float, y: Float) = ui {
            val now = SystemClock.uptimeMillis(); if (action == MotionEvent.ACTION_DOWN) down = now
            val point = view.screenPoint(x, y)
            val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 9; toolType = MotionEvent.TOOL_TYPE_STYLUS })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = point.x; this.y = point.y; pressure = .6f })
            val event = MotionEvent.obtain(down, now, action, 1, properties, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            try { check(view.onTouchEvent(event)) } finally { event.recycle() }
        }
        run {
            // Exact software-raster comparison with the previous CLEAR + replay result.
            val painter = InkFrontFrameRenderer()
            val expected = Bitmap.createBitmap(384, 256, Bitmap.Config.ARGB_8888)
            val actual = Bitmap.createBitmap(384, 256, Bitmap.Config.ARGB_8888)
            val reference = NoteRenderer()
            var cases = 0
            try {
                for (brush in listOf("fountain", "ballpoint", "gel", "brush", "pencil", "fineliner", "highlighter")) {
                    for (count in listOf(1, 2, 31)) {
                        val samples = List(count) { n -> InkPoint(30f + n * 8,
                            80f + kotlin.math.sin(n.toFloat()) * 15, .2f + n % 7 * .1f) }
                        val stroke = json("id" to "stable-seed", "type" to "stroke", "brush" to brush,
                            "color" to "#172033", "width" to 10, "opacity" to .35,
                            "points" to JSONArray(samples.map { it.json() }))
                        val frame = InkFrontFrame(stroke, 6f, 12f, .9f, 360f, 250f, samples)
                        expected.eraseColor(Color.TRANSPARENT)
                        val canvas = Canvas(expected); val saved = canvas.save()
                        canvas.translate(frame.x, frame.y); canvas.scale(frame.scale, frame.scale)
                        canvas.clipRect(0f, 0f, frame.pageWidth, frame.pageHeight)
                        reference.draw(canvas, stroke, frame.scale.toDouble()); canvas.restoreToCount(saved)
                        for (snapshot in listOf(frame, frame.copy(points = null))) {
                            actual.eraseColor(Color.MAGENTA)
                            repeat(3) {
                                // Check both immutable samples and serialized compatibility against reference pixels.
                                painter.draw(Canvas(actual), 384, 256, snapshot)
                                check(actual.sameAs(expected)) { "Front replacement changed raster/alpha: $brush/$count/$it" }
                            }
                            cases++
                        }
                    }
                }
                // Queue many snapshots before the worker runs. Its oldest notification
                // must still draw the newest snapshot on its first callback.
                var newest: InkFrontFrame? = null
                repeat(128) { n ->
                    val samples = listOf(InkPoint(20f, 180f, .6f), InkPoint(40f + n * 2, 180f, .6f))
                    val stroke = json("id" to "queued", "type" to "stroke", "brush" to "ballpoint",
                        "color" to "#172033", "width" to 10, "opacity" to 1,
                        "points" to JSONArray(samples.map { it.json() }))
                    newest = InkFrontFrame(stroke, 0f, 0f, 1f, 384f, 256f, samples)
                    painter.submit(requireNotNull(newest))
                }
                expected.eraseColor(Color.TRANSPARENT)
                reference.draw(Canvas(expected), requireNotNull(newest).stroke, 1.0)
                actual.eraseColor(Color.MAGENTA)
                check(painter.drawLatest(Canvas(actual), 384, 256)) { "Queued input starved the first callback" }
                check(actual.sameAs(expected)) { "Queued input did not draw the newest complete snapshot" }
                check(!painter.drawLatest(Canvas(actual), 384, 256)) { "Replayed a snapshot twice" }
                painter.submit(requireNotNull(newest))
                check(painter.drawLatest(Canvas(actual), 384, 256)) { "Explicit repeat was dropped" }
                painter.submit(requireNotNull(newest))
                painter.clear()
                check(!painter.drawLatest(Canvas(actual), 384, 256)) { "Cancelled frame was replayed" }
                painter.submit(requireNotNull(newest))
                check(painter.drawLatest(Canvas(actual), 384, 256)) { "Frame was lost after clearing the surface" }
                result.putInt("penCoalescedSnapshots", 128)
            } finally { expected.recycle(); actual.recycle() }
            result.putInt("penReplacementRasterCases", cases)
        }
        longStrokeRasterChecks(result)
        hardwareStrokeRasterChecks(result)
        ui {
            view = InkCanvasView(activity, activity.repository).apply {
                drawHold = false; scribbleErase = false; inkWidth = 14f
                open(listOf(page.id), listOf(page.meta)); replacePage(page)
            }
            host = FrameLayout(activity)
            front = InkFrontBuffer(activity, { view.frontBufferPresented(it) }, { view.frontBufferRecreated() }, gate)
            view.attachFrontBuffer(front)
            host.addView(front, FrameLayout.LayoutParams(-1, -1)); host.addView(view, FrameLayout.LayoutParams(-1, -1))
            activity.findViewById<ViewGroup>(android.R.id.content).addView(host, ViewGroup.LayoutParams(-1, -1))
        }
        try {
            await("initial canonical scene", ::presented)
            send(MotionEvent.ACTION_DOWN, 200f, 300f); send(MotionEvent.ACTION_MOVE, 500f, 300f)
            await("first active stroke") { dark(350f, 300f) }
            gate.held = true
            send(MotionEvent.ACTION_UP, 520f, 300f)
            check(!presented()) { "New front input admitted before prior commit completed" }
            send(MotionEvent.ACTION_DOWN, 200f, 450f); send(MotionEvent.ACTION_MOVE, 500f, 450f)
            await("next stroke visible while commit callback held") { dark(350f, 450f) }
            screen("01-next-stroke-during-commit")
            gate.release()
            repeat(5) { check(dark(350f, 450f)) { "Prior commit erased next active stroke" }; SystemClock.sleep(20) }
            send(MotionEvent.ACTION_UP, 520f, 450f)
            await("both consecutive strokes committed") { presented() && dark(350f, 300f) && dark(350f, 450f) }
            repeat(4) { n ->
                val y = 600f + n * 60
                send(MotionEvent.ACTION_DOWN, 200f, y); send(MotionEvent.ACTION_MOVE, 500f, y); send(MotionEvent.ACTION_UP, 520f, y)
            }
            await("rapid strokes retained") { presented() && (0..3).all { dark(350f, 600f + it * 60) } }
            screen("02-rapid-strokes-committed")
            send(MotionEvent.ACTION_DOWN, 200f, 900f); send(MotionEvent.ACTION_MOVE, 500f, 900f)
            await("cancel preview visible") { dark(350f, 900f) }
            send(MotionEvent.ACTION_CANCEL, 500f, 900f)
            await("cancelled preview gone") { presented() && !dark(350f, 900f) }
            ui {
                // open() loads the canonical page asynchronously; inspect the view's loaded instance.
                val committed = requireNotNull(view.currentPage)
                check(committed.objects.size == 6) { "Cancel changed committed strokes: ${committed.objects.size}" }
                view.undo()
                check(committed.objects.size == 5) { "Undo did not remove one stroke: ${committed.objects.size}" }
                view.redo()
                check(committed.objects.size == 6) { "Redo did not restore one stroke: ${committed.objects.size}" }
            }
            await("undo redo canonical scene", ::presented)
            screen("03-cancel-undo-redo")
            result.putString("penPresentation", "pass: exact repeated-alpha raster, held-commit next stroke, rapid strokes, cancel, undo/redo")
            result.putString("penPresentationScreens", screens.absolutePath)
        } finally { gate.release(); ui { view.cancelContact(); (host.parent as? ViewGroup)?.removeView(host) } }
    }

    /** Cached live output must match a fresh serialized replay, not a frozen fixed tail. */
    private fun longStrokeRasterChecks(result: Bundle) {
        val expected = Bitmap.createBitmap(384, 256, Bitmap.Config.ARGB_8888)
        val actual = Bitmap.createBitmap(384, 256, Bitmap.Config.ARGB_8888)
        val ordinary = Bitmap.createBitmap(384, 256, Bitmap.Config.ARGB_8888)
        val reference = NoteRenderer()
        val viewBrush = NoteRenderer()
        val front = InkFrontFrameRenderer()
        var cases = 0
        fun transformed(bitmap: Bitmap, frame: InkFrontFrame, draw: (Canvas) -> Unit) {
            val canvas = Canvas(bitmap); val saved = canvas.save()
            try {
                canvas.translate(frame.x, frame.y); canvas.scale(frame.scale, frame.scale)
                canvas.clipRect(0f, 0f, frame.pageWidth, frame.pageHeight)
                draw(canvas)
            } finally { canvas.restoreToCount(saved) }
        }
        fun compare(frame: InkFrontFrame, serialized: JSONObject, label: String) {
            expected.eraseColor(Color.TRANSPARENT)
            transformed(expected, frame) { reference.draw(it, serialized, frame.scale.toDouble()) }
            ordinary.eraseColor(Color.TRANSPARENT)
            transformed(ordinary, frame) {
                viewBrush.stroke(it, frame.stroke, requireNotNull(frame.points), frame.scale.toDouble())
            }
            check(ordinary.sameAs(expected)) { "Incremental View smoothing changed raster: $label" }
            repeat(2) {
                front.draw(Canvas(actual), 384, 256, frame)
                check(actual.sameAs(expected)) { "Incremental front taper/scatter/damage changed raster: $label/$it" }
            }
            cases++
        }
        try {
            val counts = setOf(1, 2, 3, 31, 63, 64, 65, 179, 180, 181, 259, 260, 261,
                519, 520, 521, 1025, 2049, 4097)
            for (brush in listOf("fountain", "ballpoint", "gel", "brush", "pencil", "fineliner", "highlighter"))
                for (smoothing in listOf(0.0, .37)) {
                    val publication = InkPointPublication()
                    val raw = JSONArray()
                    val settings = json("smoothing" to smoothing, "taper" to .78, "pressure" to .63,
                        "grain" to .81, "tiltShade" to .82)
                    val stroke = json("id" to "append-seed-$brush", "type" to "stroke", "brush" to brush,
                        "color" to "#72172033", "width" to 3.25, "opacity" to .35, "settings" to settings)
                    front.clear(); actual.eraseColor(Color.MAGENTA)
                    repeat(4097) { index ->
                        val point = InkPoint(30f + index % 90 * 3.2f,
                            35f + (index / 90 % 5) * 32 + kotlin.math.sin(index * .31).toFloat() * 9,
                            .12f + index % 9 * .11f, index.toLong(), azimuth = index * .015f,
                            tx = (index % 61 - 30).toFloat(), ty = (30 - index % 61).toFloat())
                        publication.append(point); raw.put(point.json())
                        if (index + 1 in counts) {
                            val frame = InkFrontFrame(stroke, 6f, 12f, .9f, 360f, 250f, publication.snapshot())
                            compare(frame, stroke.copyJson().put("points", raw), "$brush/$smoothing/${index + 1}")
                        }
                    }
                    // An existing publication can also be redrawn after tool-setting changes.
                    settings.put("smoothing", .92)
                    val zoomed = InkFrontFrame(stroke, -18f, -9f, 3.1f, 360f, 250f, publication.snapshot())
                    compare(zoomed, stroke.copyJson().put("points", raw), "$brush/settings/zoom")
                    // Replace a broad old extent with a disjoint tiny new one, then nothing.
                    publication.clear()
                    publication.append(InkPoint(265f, 190f, .6f))
                    publication.append(InkPoint(282f, 196f, .7f))
                    val small = InkFrontFrame(stroke, 6f, 12f, .9f, 360f, 250f, publication.snapshot())
                    compare(small, stroke.copyJson().put("points", JSONArray(small.points!!.map { it.json() })),
                        "$brush/shrinking-extent")
                    publication.clear()
                    val empty = small.copy(points = publication.snapshot())
                    compare(empty, stroke.copyJson().put("points", JSONArray()), "$brush/empty")
                    front.clear(); actual.eraseColor(Color.MAGENTA)
                    compare(small, stroke.copyJson().put("points", JSONArray(small.points!!.map { it.json() })),
                        "$brush/recreated-surface")
                }
            // Imported point metadata must affect pixels exactly as its serialized form,
            // while unknown extensions remain untouched rather than entering brush geometry.
            for ((brushIndex, brush) in listOf("fountain", "ballpoint", "gel", "brush", "pencil",
                "fineliner", "highlighter").withIndex()) {
                for ((shape, count, smoothing) in listOf(Triple("single", 1, 0.0),
                    Triple("pair", 2, .92), Triple("long", 191, .37), Triple("unsmoothed", 191, 0.0))) {
                    val publication = InkPointPublication()
                    val raw = JSONArray()
                    repeat(count) { index ->
                        val original = when (index % 7) {
                            2 -> json("p" to JSONObject.NULL, "azimuth" to .7, "tx" to -18, "ty" to 31)
                            3 -> json("azimuth" to JSONObject.NULL, "tx" to 0, "ty" to -27)
                            4 -> json("p" to "0", "azimuth" to "0.35", "tx" to "14", "ty" to JSONObject.NULL)
                            5 -> json("p" to .23f, "azimuth" to .27f, "tx" to -9.4f, "ty" to 11.6f)
                            else -> null
                        }?.put("vendor", json("sequence" to JSONArray(listOf(3, 1, 2)),
                            "nested" to json("future" to true)))
                        val pressure = when (index % 5) {
                            0 -> 0f; 1 -> .17f; 2 -> .5f; 3 -> 1f; else -> 1.3f
                        }
                        val point = InkPoint(30f + index % 90 * 3.2f,
                            90f + kotlin.math.sin(index * .31).toFloat() * 19f, pressure, index.toLong(),
                            tilt = (index % 45).toFloat(), orientation = index * .03f,
                            azimuth = if (index % 4 == 0) null else index * .015f,
                            tx = if (index % 3 == 0) null else (index % 61 - 30).toFloat(),
                            ty = if (index % 6 == 0) 0f else (30 - index % 61).toFloat(), original = original)
                        publication.append(point); raw.put(point.json())
                    }
                    val stroke = json("id" to "metadata-$brush-$shape-\uD83D\uDD8B\uFE0F", "type" to "stroke",
                        "brush" to brush, "color" to "#172033", "width" to if (count == 1) 0 else 5.25,
                        "settings" to json("pressure" to if (count == 2) 0 else .63, "smoothing" to smoothing,
                            "taper" to if (brushIndex % 2 == 0) 0 else .58,
                            "grain" to .81, "tiltShade" to 0, "opacity" to .73))
                    if (brushIndex % 2 == 1) stroke.put("opacity", .61)
                    val serialized = stroke.copyJson().put("points", raw)
                    val source = serialized.toString()
                    // Remove only the extension. copyJson() on raw points also converts
                    // Float coordinates to decimal-parsed Doubles, changing the geometry
                    // independently of vendor data (observable at zoomed antialias edges).
                    val plain = stroke.copyJson().put("points", JSONArray().apply {
                        for (index in 0 until raw.length()) {
                            val point = raw.getJSONObject(index)
                            put(JSONObject().apply {
                                val keys = point.keys()
                                while (keys.hasNext()) {
                                    val key = keys.next()
                                    if (key != "vendor") put(key, point.get(key))
                                }
                            })
                        }
                    })
                    for (scale in listOf(.75f, 3.1f)) {
                        front.clear(); actual.eraseColor(Color.MAGENTA)
                        val frame = InkFrontFrame(stroke, 6f, 12f, scale, 360f, 250f, publication.snapshot())
                        compare(frame, serialized, "$brush/$shape/metadata/$scale")
                        ordinary.eraseColor(Color.TRANSPARENT)
                        transformed(ordinary, frame) { reference.draw(it, plain, scale.toDouble()) }
                        check(ordinary.sameAs(expected)) { "Unknown point extensions changed pixels: $brush/$shape/$scale" }
                        check(serialized.toString() == source) { "Rendering mutated imported point metadata: $brush/$shape/$scale" }
                        check(!stroke.has("points")) { "Live rendering changed raw stroke storage: $brush/$shape/$scale" }
                    }
                }
            }
            result.putInt("penLongStrokeRasterCases", cases)
        } finally { expected.recycle(); actual.recycle(); ordinary.recycle() }
    }

    /** Reuses the hardware target, but returns independent, fence-complete pixel readbacks. */
    internal class HardwareRasterOracle(private val width: Int, private val height: Int) : AutoCloseable {
        private val root = RenderNode("pen-brush-hardware-oracle").apply {
            setPosition(0, 0, this@HardwareRasterOracle.width, this@HardwareRasterOracle.height)
        }
        private val reader = android.media.ImageReader.newInstance(width, height,
            android.graphics.PixelFormat.RGBA_8888, 3,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        private val renderer = android.graphics.HardwareRenderer().apply {
            setSurface(reader.surface)
            setContentRoot(root)
            setOpaque(false)
        }
        private val colorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        private val imageThread = android.os.HandlerThread("hardware-oracle-images").apply { start() }
        @Volatile private var imageReady = java.util.concurrent.CountDownLatch(1)
        init {
            reader.setOnImageAvailableListener({ imageReady.countDown() }, android.os.Handler(imageThread.looper))
            // A zero-sized root previously made every pixel comparison transparent == transparent.
            // Validate real output for every caller, including the broader brush workload.
            try {
                val white = capture("oracle-white") { it.drawColor(Color.WHITE, BlendMode.SRC) }
                try {
                    val black = capture("oracle-black") { it.drawColor(Color.BLACK, BlendMode.SRC) }
                    try {
                        check(white.getPixel(width / 2, height / 2) == Color.WHITE &&
                            black.getPixel(width / 2, height / 2) == Color.BLACK && !white.sameAs(black)) {
                            "Hardware oracle did not observe distinct frames"
                        }
                    } finally { black.recycle() }
                } finally { white.recycle() }
            } catch (failure: Throwable) { close(); throw failure }
        }

        fun capture(label: String, draw: (RecordingCanvas) -> Unit): Bitmap {
            imageReady = java.util.concurrent.CountDownLatch(1)
            val canvas = root.beginRecording()
            try {
                check(canvas.isHardwareAccelerated) { "Hardware oracle recorded a software Canvas: $label" }
                draw(canvas)
            } finally { root.endRecording() }
            renderer.setContentRoot(root)
            val status = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            check(status == android.graphics.HardwareRenderer.SYNC_OK) { "Hardware raster failed: $label/$status" }
            check(imageReady.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Hardware image timeout: $label" }
            val frame = checkNotNull(reader.acquireNextImage()) { "Hardware frame missing: $label" }
            try {
                val buffer = checkNotNull(frame.hardwareBuffer)
                try {
                    val wrapped = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, colorSpace))
                    try { return checkNotNull(wrapped.copy(Bitmap.Config.ARGB_8888, false)) }
                    finally { wrapped.recycle() }
                } finally { buffer.close() }
            } finally { frame.close() }
        }

        override fun close() {
            try { renderer.destroy() }
            finally {
                try { reader.close() }
                finally { root.discardDisplayList(); imageThread.quitSafely() }
            }
        }
    }

    /** Hardware-only coverage: software Canvas never executes cached body RenderNodes. */
    private fun hardwareStrokeRasterChecks(result: Bundle) {
        val width = 384
        val height = 256
        val ordinary = NoteRenderer()
        val front = InkFrontFrameRenderer()
        val backdropPaint = Paint()
        var ordinaryCases = 0
        var frontCases = 0
        fun backdrop(canvas: Canvas) {
            canvas.drawColor(Color.rgb(224, 205, 174))
            backdropPaint.color = Color.rgb(85, 146, 168)
            canvas.drawRect(44f, 28f, 278f, 184f, backdropPaint)
            backdropPaint.color = Color.rgb(190, 104, 135)
            canvas.drawRect(170f, 76f, 352f, 242f, backdropPaint)
        }
        fun transformed(canvas: Canvas, frame: InkFrontFrame, draw: (Canvas) -> Unit) {
            val saved = canvas.save()
            try {
                canvas.translate(frame.x, frame.y); canvas.scale(frame.scale, frame.scale)
                canvas.clipRect(0f, 0f, frame.pageWidth, frame.pageHeight)
                draw(canvas)
            } finally { canvas.restoreToCount(saved) }
        }
        HardwareRasterOracle(width, height).use { hardware ->
            fun compare(frame: InkFrontFrame, raw: JSONArray, label: String) {
                // Preserve the actual Float primitives. A JSON text/copyJson round-trip of
                // raw points changes Double smoothing inputs and antialiased edge pixels.
                val canonical = JSONObject().apply {
                    val keys = frame.stroke.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        put(key, frame.stroke.get(key))
                    }
                    put("points", raw)
                }
                val expectedOrdinary = hardware.capture("ordinary/reference/$label") { canvas ->
                    backdrop(canvas)
                    transformed(canvas, frame) { NoteRenderer().draw(it, canonical, frame.scale.toDouble()) }
                }
                try {
                    repeat(2) { draw ->
                        val actual = hardware.capture("ordinary/live/$label/$draw") { canvas ->
                            backdrop(canvas)
                            transformed(canvas, frame) {
                                ordinary.stroke(it, frame.stroke, requireNotNull(frame.points), frame.scale.toDouble())
                            }
                        }
                        try {
                            check(actual.sameAs(expectedOrdinary)) {
                                "Hardware ordinary body/taper/style raster changed: $label/$draw"
                            }
                            ordinaryCases++
                        } finally { actual.recycle() }
                    }
                } finally { expectedOrdinary.recycle() }

                // Front replacement deliberately isolates multiply in its own transparent layer.
                // Its canonical consumer is not ordinary drawing over the colored page above.
                val expectedFront = hardware.capture("front/reference/$label") { canvas ->
                    canvas.drawColor(Color.TRANSPARENT, BlendMode.SRC)
                    InkFrontFrameRenderer().draw(canvas, width, height, frame.copy(stroke = canonical, points = null))
                }
                try {
                    repeat(2) { draw ->
                        val actual = hardware.capture("front/live/$label/$draw") { canvas ->
                            canvas.drawColor(Color.TRANSPARENT, BlendMode.SRC)
                            front.draw(canvas, width, height, frame)
                        }
                        try {
                            check(actual.sameAs(expectedFront)) {
                                "Hardware front body/taper/style raster changed: $label/$draw"
                            }
                            frontCases++
                        } finally { actual.recycle() }
                    }
                } finally { expectedFront.recycle() }
            }
            fun compareTransforms(stroke: JSONObject, points: List<InkPoint>, raw: JSONArray, label: String) {
                compare(InkFrontFrame(stroke, 6f, 12f, .9f, 360f, 250f, points), raw, "$label/normal")
                compare(InkFrontFrame(stroke, -18f, -9f, 3.1f, 112f, 72f, points), raw, "$label/zoomed-clip")
            }

            val counts = setOf(1, 2, 3, 31, 63, 64, 65, 127, 128, 129, 191, 192, 193,
                255, 256, 257, 521, 1025, 4097)
            for (brush in listOf("fountain", "ballpoint", "gel", "brush", "pencil", "fineliner", "highlighter"))
                for (smoothing in listOf(0.0, .37)) {
                    val publication = InkPointPublication()
                    val raw = JSONArray()
                    val settings = json("smoothing" to smoothing, "taper" to .78, "pressure" to .63,
                        "grain" to .81, "tiltShade" to .82)
                    val stroke = json("id" to "hardware-append-$brush", "type" to "stroke", "brush" to brush,
                        "color" to "#72172033", "width" to 3.25, "opacity" to .35, "settings" to settings)
                    repeat(4097) { index ->
                        // Wrap visible rows rather than throwing away or decimating long input.
                        // Revisited rows expose segment ordering and translucent overlap errors.
                        val point = InkPoint(12f + index % 90 * 3.2f,
                            18f + (index / 90 % 5) * 32 + kotlin.math.sin(index * .31).toFloat() * 9,
                            .12f + index % 9 * .11f, index.toLong(),
                            tilt = (index % 45).toFloat(), orientation = index * .03f,
                            azimuth = index * .015f, tx = (index % 61 - 30).toFloat(),
                            ty = (30 - index % 61).toFloat())
                        publication.append(point); raw.put(point.json())
                        if (index + 1 in counts) compareTransforms(stroke, publication.snapshot(), raw,
                            "$brush/$smoothing/${index + 1}")
                    }
                    // Change each cache key independently while retaining the identical snapshot
                    // and publication lineage. Every comparison redraws that prefix twice.
                    val points = publication.snapshot()
                    fun changed(setting: String) =
                        compareTransforms(stroke, points, raw, "$brush/$smoothing/settings-$setting")
                    stroke.put("width", 6.75); changed("width")
                    settings.put("taper", 0.0); changed("no-taper")
                    settings.put("taper", .94); changed("taper")
                    settings.put("pressure", .18); changed("pressure")
                    stroke.put("opacity", .73); changed("opacity")
                    stroke.put("color", "#c9436fa1"); changed("color")
                    settings.put("smoothing", .92); changed("smoothing")

                    // Keep both optimized consumers alive when the same stroke id starts a new,
                    // disjoint, smaller contact. Revisit the smoothing two-to-three transition.
                    publication.clear()
                    val contactRaw = JSONArray()
                    repeat(3) { index ->
                        val point = InkPoint(310f + index * 8f, 205f + index * 4f,
                            .3f + index * .2f, azimuth = index * .27f, tx = -12f, ty = 19f)
                        publication.append(point); contactRaw.put(point.json())
                        if (index > 0) {
                            val contact = publication.snapshot()
                            compare(InkFrontFrame(stroke, 6f, 12f, .9f, 360f, 250f, contact),
                                contactRaw, "$brush/$smoothing/new-contact/${index + 1}/normal")
                            compare(InkFrontFrame(stroke, -900f, -590f, 3.1f, 330f, 215f, contact),
                                contactRaw, "$brush/$smoothing/new-contact/${index + 1}/zoomed-clip")
                        }
                    }
                    publication.clear()
                    compareTransforms(stroke, publication.snapshot(), JSONArray(), "$brush/$smoothing/empty")
                }
        }
        result.putInt("penHardwareOrdinaryRasterCases", ordinaryCases)
        result.putInt("penHardwareFrontRasterCases", frontCases)
        result.putInt("penHardwareStrokeRasterCases", ordinaryCases + frontCases)
    }
}
