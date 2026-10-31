package com.inkforge.notesstudio

import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Real attached editor workloads. Timings are observations, never physical-pen/display latency claims. */
internal object EditorWorkloadChecks {
    private const val FILLED_STROKES = 2048
    private const val FILLED_POINTS = 64
    private const val LONG_MOVE_POINTS = 4096
    private const val MANY_PAGES = 320
    private const val PAGE_STROKES = 96
    private const val PAGE_POINTS = 32

    private class Samples {
        private val wall = ArrayList<Long>()
        private val cpu = ArrayList<Long>()
        fun measure(action: () -> Unit) {
            val begin = System.nanoTime(); val cpuBegin = Debug.threadCpuTimeNanos()
            action()
            wall += System.nanoTime() - begin; cpu += Debug.threadCpuTimeNanos() - cpuBegin
        }
        fun json() = json("samples" to wall.size, "wall" to distribution(wall), "cpu" to distribution(cpu))
    }
    private fun distribution(values: List<Long>): JSONObject {
        if (values.isEmpty()) return json("samples" to 0)
        val sorted = values.sorted()
        return json("samples" to sorted.size, "p50Ms" to sorted[(sorted.size - 1) / 2] / 1e6,
            "p95Ms" to sorted[(ceil(sorted.size * .95).toInt() - 1).coerceAtLeast(0)] / 1e6,
            "maxMs" to sorted.last() / 1e6)
    }

    /** Window metrics are actual attached HWUI frames, not the time spent calling View.draw(). */
    private class Frames(private val activity: MainActivity, private val view: InkCanvasView) {
        private val thread = HandlerThread("workload-frame-receipts").apply { start() }
        private val metrics = LinkedHashMap<String, MutableList<LongArray>>()
        private val nextDraw = LinkedHashMap<String, MutableList<Long>>()
        private val pending = ArrayList<Pair<String, Long>>()
        private val phases = ArrayList<Pair<Long, String>>().apply { add(0L to "opening") }
        @Volatile var phase = "opening"
            set(value) {
                synchronized(metrics) { phases.add(System.nanoTime() to value); field = value }
            }
        private val drawn = ViewTreeObserver.OnDrawListener {
            val now = System.nanoTime()
            for ((name, begin) in pending) nextDraw.getOrPut(name) { ArrayList() }.add(now - begin)
            pending.clear()
        }
        private val listener = Window.OnFrameMetricsAvailableListener { _, frame, dropped ->
            val value = longArrayOf(frame.getMetric(FrameMetrics.TOTAL_DURATION),
                frame.getMetric(FrameMetrics.INPUT_HANDLING_DURATION), frame.getMetric(FrameMetrics.DRAW_DURATION),
                dropped.toLong(), frame.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION),
                frame.getMetric(FrameMetrics.SYNC_DURATION), frame.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
                frame.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
                if (Build.VERSION.SDK_INT >= 31) frame.getMetric(FrameMetrics.GPU_DURATION) else -1L)
            synchronized(metrics) {
                // Callbacks can arrive after a slow diagnostic has changed the phase. Attribute
                // each frame by its original vsync, not the time its callback was delivered.
                val start = frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
                val name = phases.lastOrNull { it.first <= start }?.second ?: phase
                metrics.getOrPut(name) { ArrayList() }.add(value)
            }
        }
        init {
            view.viewTreeObserver.addOnDrawListener(drawn)
            activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        }
        fun mark() { pending += phase to System.nanoTime() }
        fun receipt(): JSONObject {
            val result = JSONObject()
            synchronized(metrics) {
                for ((name, rows) in metrics) result.put(name, json("windowTotal" to distribution(rows.map { it[0] }),
                    "windowInput" to distribution(rows.map { it[1] }), "windowDraw" to distribution(rows.map { it[2] }),
                    "metricCallbacksDropped" to rows.sumOf { it[3] },
                    "windowUiDelay" to distribution(rows.map { it[4] }),
                    "windowSync" to distribution(rows.map { it[5] }),
                    "windowCommandIssue" to distribution(rows.map { it[6] }),
                    "windowSwap" to distribution(rows.map { it[7] }),
                    "windowGpu" to distribution(rows.map { it[8] }.filter { it >= 0L })))
            }
            for ((name, values) in nextDraw) {
                val value = result.optJSONObject(name) ?: JSONObject().also { result.put(name, it) }
                value.put("inputHandlerToNextViewDraw", distribution(values))
            }
            return result
        }
        fun close() {
            activity.window.removeOnFrameMetricsAvailableListener(listener)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(drawn)
            thread.quitSafely()
        }
    }

    private fun filledStroke(index: Int): JSONObject {
        val points = JSONArray()
        repeat(FILLED_POINTS) { sample ->
            val point = json("x" to 40f + 920f * sample / (FILLED_POINTS - 1),
                "y" to 707f + sin(sample * .16 + index * .027).toFloat() * 590f,
                "p" to .25f + (sample % 11) * .055f, "t" to 1000L + index * 100L + sample)
            if (sample % 16 == 0) point.put("vendor", json("sample" to sample, "nullable" to JSONObject.NULL))
            points.put(point)
        }
        return json("id" to "filled-$index", "type" to "stroke", "brush" to
            if (index == 2 || index % 17 == 0) "highlighter" else "ballpoint",
            "color" to if (index == 2 || index % 17 == 0) "#f0c53d" else "#172033",
            "width" to 2.5, "opacity" to if (index % 7 == 0) .35 else .65,
            "locked" to (index == 0), "hidden" to (index == 1), "revision" to 1,
            "captureSeq" to index + 1, "captureSessionId" to "filled-deterministic",
            "timeBasis" to "session-monotonic", "vendorObject" to json("order" to JSONArray(listOf(3, 1, 2))),
            "points" to points)
    }
    private fun longPoint(index: Int): Pair<Float, Float> {
        // An open spiral cannot become a held ellipse or a scratch gesture if a slow capture pauses input.
        val angle = index * 6 * 2 * PI / (LONG_MOVE_POINTS + 1)
        val fraction = index.toFloat() / (LONG_MOVE_POINTS + 1)
        return 500f + cos(angle).toFloat() * (125f + 200f * fraction) to
            690f + sin(angle).toFloat() * (175f + 225f * fraction)
    }
    private fun markerColor(index: Int) = Color.rgb(35 + index * 37 % 150, 35 + index * 61 % 150, 35 + index * 97 % 150)
    private fun pageInk(pageIndex: Int, stroke: Int): JSONObject {
        val points = JSONArray()
        repeat(PAGE_POINTS) { sample -> points.put(json("x" to 70f + sample * 860f / (PAGE_POINTS - 1),
            "y" to 340f + stroke * 10f + sin(sample * .3 + pageIndex * .07).toFloat() * 7f,
            "p" to .3f + sample % 5 * .1f, "t" to pageIndex * 100_000L + stroke * 100L + sample)) }
        return json("id" to "many-$pageIndex-$stroke", "type" to "stroke", "brush" to "ballpoint",
            "color" to "#172033", "width" to 3, "vendorObject" to json("page" to pageIndex, "stroke" to stroke),
            "points" to points)
    }
    private fun pageMarker(index: Int) = json("id" to "many-marker-$index", "type" to "stroke", "brush" to "ballpoint",
        "color" to "#%06x".format(markerColor(index) and 0xffffff), "width" to 36,
        "points" to JSONArray(listOf(json("x" to 150, "y" to 160, "p" to 1, "t" to 1),
            json("x" to 850, "y" to 160, "p" to 1, "t" to 2))))

    /** Only digests/IDs survive a phase; no second page-sized raw JSONObject tree or encoded JSON copy. */
    private fun fingerprint(objects: List<JSONObject>, ignoreRevision: Boolean = true): List<Pair<String, String>> =
        objects.map { it.getString("id") to digest(it, ignoreRevision) }
    private fun digest(value: Any?, ignoreRevision: Boolean = true): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fun token(text: String) { hash.update(text.toByteArray(Charsets.UTF_8)); hash.update(0.toByte()) }
        fun visit(item: Any?) {
            when (item) {
                is JSONObject -> {
                    token("object")
                    for (key in item.keys().asSequence().filter { !ignoreRevision || it != "revision" }.sorted()) { token(key); visit(item.get(key)) }
                    token("end")
                }
                is JSONArray -> { token("array:${item.length()}"); repeat(item.length()) { visit(item.get(it)) } }
                is Number -> token("number:${JSONObject.numberToString(item)}")
                else -> token("${item?.javaClass?.name}:${item}")
            }
        }
        visit(value)
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun same(objects: List<JSONObject>, expected: List<Pair<String, String>>, label: String) {
        check(objects.size == expected.size) { "$label object count ${objects.size} != ${expected.size}" }
        objects.forEachIndexed { index, obj -> check(obj.getString("id") == expected[index].first && digest(obj) == expected[index].second) {
            "$label raw/order mismatch at $index:${expected[index].first}"
        } }
    }
    private fun samePersisted(repository: NoteRepository, pageId: String, expected: List<Pair<String, String>>): JSONObject {
        val room = NoteRepository::class.java.getDeclaredField("room").apply { isAccessible = true }.get(repository) as NativeDatabase
        var index = 0
        // Borrow the live connection; the cursor owns at most a window and this loop parses one object, never another page.
        room.openHelper.readableDatabase.query(
            "SELECT id,body FROM objects WHERE page_id=? ORDER BY position,rowid", arrayOf(pageId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val row = checkNotNull(expected.getOrNull(index)) { "Saved filled ink has extra object at $index" }
                val objectId = cursor.getString(0)
                check(objectId == row.first) { "Saved filled ink order changed at $index:${row.first}" }
                val obj = JSONObject(cursor.getString(1))
                check(obj.getString("id") == objectId && digest(obj, ignoreRevision = false) == row.second) {
                    "Saved filled ink raw fields/revision changed at $index:$objectId"
                }
                index++
            }
        }
        check(index == expected.size) { "Saved filled ink missing objects:$index/${expected.size}" }
        return json("objects" to index, "rawFieldsRevisionsAndOrderExact" to true, "readOwnership" to "single_cursor_one_parsed_object_at_a_time")
    }

    fun filled(activity: MainActivity, instrument: Instrumentation, result: Bundle, profileErase: Boolean = false,
               continuousTiming: Boolean = false) {
        require(Build.VERSION.SDK_INT >= 31) { "filledInkOnly requires actual front-buffer presentation on API31+" }
        val repository = activity.repository
        val settings = Settings(activity, instrument)
        val document = repository.create("Filled overlapping ink workload", "root", "blank")
        var retained = false
        var frames: Frames? = null
        val receipt = json("scope" to "synthetic_stylus_attached_HWUI_front_surface_screenshot_observation_not_physical_display_latency",
            "fixtureStrokes" to FILLED_STROKES, "pointsPerStroke" to FILLED_POINTS,
            "fixtureRawSamples" to FILLED_STROKES * FILLED_POINTS, "timingsAreAssertions" to false,
            "buildType" to BuildConfig.BUILD_TYPE,
            "debuggable" to (activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0),
            "eraseMethodTraceEnabled" to profileErase,
            "eraseTraceMode" to if (profileErase) "instrumented" else "none",
            "continuousInputWithoutInterleavedPixelOracle" to continuousTiming)
        fun publish() {
            result.putString("filledInk", receipt.toString())
            File(activity.filesDir, "filled-ink-progress.json").writeText(receipt.toString())
        }
        fun phase(name: String) {
            result.putString("filledInkPhase", name); frames?.phase = name; receipt.put("phase", name); publish()
            android.util.Log.i("FilledInkCheck", name)
        }
        try {
            phase("fixture")
            val pageId = run {
                val page = requireNotNull(repository.page(repository.pageIds(document.id).single()))
                repeat(FILLED_STROKES) { page.objects.add(filledStroke(it)) }
                repository.putPage(document.id, page, 0)
                page.id
            }
            val openBegin = System.nanoTime()
            ui(instrument) { activity.openDocument(document.id) }
            await("filled page loaded") { ui(instrument) { selectedReady(activity, pageId) } }
            receipt.put("selectedPageLoadedMs", (System.nanoTime() - openBegin) / 1e6)
            val view = ui(instrument) { requireNotNull(activity.inkView).also {
                check(it.isAttachedToWindow && it.isShown && it.isHardwareAccelerated)
                it.zoomBy((it.height * .92f) / (it.currentPage!!.height * it.scale)); it.goTo(0)
            } }
            receipt.put("actualSettingsAtEditorOpen", ui(instrument) { observedSettings(activity, view) })
            awaitScene(activity, instrument)
            val input = StylusContactChecks.Input(view)
            ui(instrument) {
                frames = Frames(activity, view)
                check(view.currentPage!!.objects.sumOf { it.getJSONArray("points").length() } == FILLED_STROKES * FILLED_POINTS)
                view.currentPage!!.objects.forEachIndexed { index, obj ->
                    check(obj.getInt("revision") == 1 && digest(obj) == digest(filledStroke(index))) { "filled fixture raw fields:$index" }
                }
            }
            phase("filled_initial_raster")
            receipt.put("initialRaster", raster(view, instrument))
            phase("filled_initial_hardware_raster")
            receipt.put("initialHardwareRaster", hardwareRaster(view, instrument, "initial"))
            val screens = File(activity.filesDir, "filled-ink").apply { mkdirs() }
            val initial = screenshot(instrument, File(screens, "${document.id}-filled.png"))
            try { receipt.put("visiblePaperCoverage", coverage(initial, view, instrument)) } finally { initial.recycle() }
            val original = ui(instrument) { fingerprint(view.currentPage!!.objects) }
            realFrames(view, instrument)
            val down = Samples(); val move = Samples(); val up = Samples(); val nextDown = Samples()
            val rapidDown = Samples(); val rapidMove = Samples(); val rapidUp = Samples()
            val longDown = longPoint(0)
            phase("long_pen_down")
            ui(instrument) {
                view.tool = InkCanvasView.Tool.PEN; view.brush = "ballpoint"; view.inkWidth = 12f; view.inkColor = "#e000c0"
                view.penSettings = JSONObject()
                down.measure { input.touch(MotionEvent.ACTION_DOWN, longDown.first, longDown.second) }; frames!!.mark()
            }
            phase("long_pen_move")
            repeat(LONG_MOVE_POINTS / 64) { batch ->
                ui(instrument) {
                    val samples = (1..64).map { longPoint(batch * 64 + it) }
                    move.measure { input.touch(MotionEvent.ACTION_MOVE, samples.last().first, samples.last().second, history = samples.dropLast(1)) }
                    frames!!.mark()
                }
                if (batch % 8 == 7) realFrames(view, instrument)
            }
            // A real compositor capture observes active ink, not merely a worker callback/counter.
            phase("long_pen_screenshot_check")
            val liveBegin = System.nanoTime()
            val newestTail = longPoint(LONG_MOVE_POINTS - 256)
            observe(instrument, "long active latest tail displayed") { image ->
                val visible = colorPixels(image, view, instrument, Color.rgb(224, 0, 192)) > 100 &&
                    colorAt(image, view, instrument, newestTail.first, newestTail.second, Color.rgb(224, 0, 192))
                if (visible) File(screens, "${document.id}-long-live.png").outputStream().use {
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                visible
            }
            receipt.put("activeScreenshotObservationMs", (System.nanoTime() - liveBegin) / 1e6)
            phase("long_pen_up_and_next_down")
            val upObservationBegin = System.nanoTime()
            ui(instrument) {
                val end = longPoint(LONG_MOVE_POINTS + 1)
                up.measure { input.touch(MotionEvent.ACTION_UP, end.first, end.second) }; frames!!.mark()
                // No frame/save wait between the previous UP and this DOWN.
                nextDown.measure { input.touch(MotionEvent.ACTION_DOWN, 180f, 110f) }; frames!!.mark()
                rapidMove.measure { input.touch(MotionEvent.ACTION_MOVE, 500f, 110f, history = listOf(260f to 110f, 380f to 110f)) }
                rapidUp.measure { input.touch(MotionEvent.ACTION_UP, 780f, 110f) }
            }
            phase("rapid_pen_contacts")
            repeat(5) { contact -> ui(instrument) {
                val y = 140f + contact * 28f
                rapidDown.measure { input.touch(MotionEvent.ACTION_DOWN, 180f, y) }
                rapidMove.measure { input.touch(MotionEvent.ACTION_MOVE, 500f, y, history = listOf(260f to y, 380f to y)) }
                rapidUp.measure { input.touch(MotionEvent.ACTION_UP, 780f, y) }; frames!!.mark()
            } }
            receipt.put("longPenInput", json("down" to down.json(), "move" to move.json(), "up" to up.json(),
                "historicalSamplesPerMove" to 63, "immediateNextDown" to nextDown.json()))
                .put("rapidPenInput", json("contacts" to 6, "followingDown" to rapidDown.json(),
                    "firstDownMeasuredAsImmediateNextDown" to true, "move" to rapidMove.json(), "up" to rapidUp.json()))
            publish()
            phase("rapid_pen_verification")
            awaitScene(activity, instrument)
            observe(instrument, "rapid contacts displayed") { image ->
                colorPixels(image, view, instrument, Color.rgb(224, 0, 192)) > 1000 && (0..5).all { contact ->
                    colorAt(image, view, instrument, 400f, if (contact == 0) 110f else 140f + (contact - 1) * 28f, Color.rgb(224, 0, 192))
                }
            }
            receipt.put("longUpThroughRapidScreenshotObservedMsIncludingSubsequentInputAndCapture", (System.nanoTime() - upObservationBegin) / 1e6)
            publish()
            ui(instrument) {
                val objects = view.currentPage!!.objects
                same(objects.take(FILLED_STROKES), original, "pen untouched originals")
                checkLongSamples(objects[FILLED_STROKES])
                check(objects.take(FILLED_STROKES).all { it.getInt("revision") == 1 }) { "Pen changed untouched revisions" }
                check(objects.size == FILLED_STROKES + 7) { "Rapid contacts missing or merged" }
                objects.takeLast(6).forEach { check(it.getJSONArray("points").length() == 5) { "Rapid historical samples dropped" } }
                val captures = objects.takeLast(7).map { it.getLong("captureSeq") }
                check(captures.first() > FILLED_STROKES) { "Durable capture sequence collided with populated fixture ink" }
                check(captures.zipWithNext().all { it.first < it.second }) { "Capture sequence/order changed" }
            }
            val beforeErase = ui(instrument) { fingerprint(view.currentPage!!.objects) }
            val eraseResults = JSONObject()
            receipt.put("eraseInput", eraseResults)
            for (precise in listOf(false, true)) {
                val name = if (precise) "area_erase" else "whole_stroke_erase"
                val eraseDown = Samples(); val eraseMove = Samples(); val eraseUp = Samples()
                val beforePixels = screenshot(instrument)
                realFrames(view, instrument)
                phase("${name}_input")
                ui(instrument) {
                    view.tool = InkCanvasView.Tool.ERASER; view.preciseEraser = precise; view.eraserRadius = if (precise) 6f else 10f
                    val profiling = profileErase && precise
                    if (profiling) Debug.startMethodTracing(File(screens, "area-erase-down-local3.trace").absolutePath, 32*1024*1024, 0)
                    try { eraseDown.measure { input.touch(MotionEvent.ACTION_DOWN, 490f, 710f) } }
                    finally { if (profiling) Debug.stopMethodTracing() }
                    frames!!.mark()
                }
                repeat(4) { step ->
                    val sendMove = {
                        val x = 508f + step * 18
                        val profiling=profileErase&&precise&&step==1
                        if(profiling)Debug.startMethodTracing(File(screens,"area-erase-local3.trace").absolutePath,32*1024*1024,0)
                        try{
                            eraseMove.measure { input.touch(MotionEvent.ACTION_MOVE, x, 710f, history = listOf(x - 12 to 710f, x - 6 to 710f)) }
                        }finally{if(profiling)Debug.stopMethodTracing()}
                        frames!!.mark()
                    }
                    if (step == 0 && !continuousTiming) {
                        phase("${name}_hardware_oracle")
                        eraseResults.put("${name}ActiveHardwareRaster", hardwareRaster(view, instrument, "$name-before-lift", sendMove))
                        phase("${name}_input")
                    } else ui(instrument) { sendMove() }
                    realFrames(view, instrument)
                }
                eraseResults.put(name, json("down" to eraseDown.json(), "move" to eraseMove.json(),
                    "up" to eraseUp.json(), "objectsBefore" to beforeErase.size))
                publish()
                phase("${name}_screenshot_check")
                val observationBegin = System.nanoTime()
                try {
                    observe(instrument, "$name visible before lift") { image ->
                        changedInkPixels(beforePixels, image, view, instrument, 562f, 710f) > 4
                    }
                } finally { beforePixels.recycle() }
                eraseResults.put("${name}MoveScreenshotObservationMs", (System.nanoTime() - observationBegin) / 1e6)
                phase("${name}_up")
                ui(instrument) {
                    val profiling = profileErase && precise
                    if (profiling) Debug.startMethodTracing(File(screens, "area-erase-up-local3.trace").absolutePath, 32*1024*1024, 0)
                    try { eraseUp.measure { input.touch(MotionEvent.ACTION_UP, 568f, 710f) } }
                    finally { if (profiling) Debug.stopMethodTracing() }
                    frames!!.mark()
                }
                eraseResults.getJSONObject(name).put("up", eraseUp.json())
                publish()
                phase("${name}_verification")
                awaitScene(activity, instrument)
                val erased = ui(instrument) {
                    val objects = view.currentPage!!.objects
                    val actual = fingerprint(objects)
                    check(actual != beforeErase) { "$name did not change densely overlapping ink" }
                    for (id in listOf("filled-0", "filled-1", "filled-2")) {
                        check(actual.first { it.first == id } == beforeErase.first { it.first == id }) { "$name changed protected ink:$id" }
                    }
                    if (precise) check(objects.any { it.optString("sourceStrokeId").startsWith("filled-") }) { "Area erase did not create real split fragments" }
                    else check(objects.size < beforeErase.size) { "Whole-stroke erase did not remove strokes" }
                    actual
                }
                phase("${name}_undo_redo_cancel")
                ui(instrument) { view.undo(); same(view.currentPage!!.objects, beforeErase, "$name undo") }
                awaitScene(activity, instrument)
                ui(instrument) { view.redo(); same(view.currentPage!!.objects, erased, "$name redo") }
                awaitScene(activity, instrument)
                ui(instrument) {
                    input.touch(MotionEvent.ACTION_DOWN, 500f, 750f)
                    input.touch(MotionEvent.ACTION_MOVE, 520f, 750f, history = listOf(510f to 750f))
                    input.touch(MotionEvent.ACTION_CANCEL, 520f, 750f)
                    same(view.currentPage!!.objects, erased, "$name cancel")
                }
                eraseResults.put(name, json("down" to eraseDown.json(), "move" to eraseMove.json(), "up" to eraseUp.json(),
                    "objectsBefore" to beforeErase.size, "objectsAfter" to erased.size))
                // Whole erase is restored before measuring area erase on the identical dense content.
                if (!precise) ui(instrument) { view.undo(); same(view.currentPage!!.objects, beforeErase, "restore dense area-erase workload") }
                awaitScene(activity, instrument)
            }
            receipt.put("attachedFrames", ui(instrument) { frames!!.receipt() }).put("memoryBeforeFinalChecks", memory(activity))
            phase("saved_raw_order")
            awaitSaved(activity, instrument)
            val savedExpected = ui(instrument) { fingerprint(view.currentPage!!.objects, ignoreRevision = false) }
            receipt.put("savedPersistence", samePersisted(repository, pageId, savedExpected))
            phase("final_attached_raster")
            receipt.put("finalRaster", raster(view, instrument))
            phase("final_retained_hardware_raster")
            receipt.put("finalHardwareRaster", hardwareRaster(view, instrument, "final"))
            phase("final_committed_screenshot")
            val image = screenshot(instrument, File(screens, "${document.id}-committed.png")); image.recycle()
            receipt.put("attachedFrames", ui(instrument) { frames!!.receipt() }).put("memory", memory(activity))
                .put("savedObjects", savedExpected.size).put("preservation", "raw extensions/order, historical timestamps, protected ink, one-contact undo/redo/cancel, durable deltas")
                .put("documentId", document.id).put("retainedScreenshotFixture", true).put("screens", screens.absolutePath)
            result.putString("filledInkDocumentId", document.id)
            result.putString("filledInk", receipt.toString())
            retained = true
        } finally {
            ui(instrument) { frames?.close(); activity.inkView?.cancelContact(); activity.showLibrary() }
            awaitSaved(activity, instrument)
            settings.restore()
            if (!retained) repository.deleteDocument(document.id)
        }
    }

    private fun checkLongSamples(stroke: JSONObject) {
        val points = stroke.getJSONArray("points")
        check(points.length() == LONG_MOVE_POINTS + 2) { "Long stroke lost raw/history samples:${points.length()}" }
        repeat(points.length()) { index ->
            val point = points.getJSONObject(index); val expected = longPoint(index)
            check(abs(point.getDouble("x") - expected.first) < .002 && abs(point.getDouble("y") - expected.second) < .002) { "Long raw order/coordinates:$index" }
            check(point.getInt("pointerId") == 7 && abs(point.getDouble("p") - .65) < .00001) { "Long raw pressure/pointer:$index" }
            check(point.getLong("t") == 10_000L + index) { "Long raw timestamp/history:$index:${point.opt("t")}" }
        }
    }

    fun manyPages(activity: MainActivity, instrument: Instrumentation, result: Bundle) {
        require(Build.VERSION.SDK_INT >= 31) { "manyPagesOnly requires real attached presentation on API31+" }
        val repository = activity.repository
        val settings = Settings(activity, instrument)
        val document = repository.create("320 populated pages workload", "root", "blank")
        val receipt = json("pages" to MANY_PAGES, "inkStrokesPerPage" to PAGE_STROKES, "rawSamplesPerPage" to PAGE_STROKES * PAGE_POINTS,
            "totalInkRawSamples" to MANY_PAGES * PAGE_STROKES * PAGE_POINTS, "scope" to "real_editor_populated_pages_visible_production_sidebar_previews",
            "timingsAreAssertions" to false)
        var frames: Frames? = null
        fun publish() { result.putString("manyPages", receipt.toString()) }
        fun phase(name: String) { result.putString("manyPagesPhase", name); receipt.put("phase", name); publish() }
        try {
            phase("persist_populated_pages")
            val first = repository.pageIds(document.id).single()
            repeat(MANY_PAGES) { index ->
                val page = if (index == 0) requireNotNull(repository.page(first)) else NotePage.blank("blank")
                page.objects.add(pageMarker(index))
                repeat(PAGE_STROKES) { page.objects.add(pageInk(index, it)) }
                repository.putPage(document.id, page, index)
            }
            val ids = repository.pageIds(document.id)
            check(ids.size == MANY_PAGES)
            val openBegin = System.nanoTime()
            ui(instrument) { activity.openDocument(document.id, 0) }
            await("many populated first page") { ui(instrument) { selectedReady(activity, ids.first()) } }
            ui(instrument) {
                val metadata = field(activity, "metas") as List<*>
                check(metadata.size == MANY_PAGES && metadata.all { it is JSONObject && !it.has("objects") }) {
                    "Many-page metadata retained populated page bodies"
                }
            }
            receipt.put("initialSelectedPageLoadedMs", (System.nanoTime() - openBegin) / 1e6)
            val view = ui(instrument) { requireNotNull(activity.inkView).also { check(it.isAttachedToWindow && it.isHardwareAccelerated) } }
            receipt.put("actualSettingsAtEditorOpen", ui(instrument) { observedSettings(activity, view) })
            ui(instrument) { frames = Frames(activity, view) }
            val navigation = JSONArray()
            receipt.put("navigation", navigation)
            val edits = LinkedHashMap<String, Pair<String, String>>()
            val destinations = listOf(0, MANY_PAGES / 2, MANY_PAGES - 1, 1, MANY_PAGES / 2 - 1, MANY_PAGES - 2, 80, 240, 0, MANY_PAGES - 1)
            for ((visit, index) in destinations.withIndex()) {
                phase("visit_${visit}_page_$index")
                ui(instrument) { frames!!.phase = "page_$index"; openSidebar(activity); scrollPreviews(activity, index) }
                realFrames(view, instrument)
                if (visit == 0) {
                    observe(instrument, "populated page preview actually displayed") { image -> previewDisplayed(image, activity, instrument, markerColor(0)) }
                    receipt.put("productionPreviewScreenshotObserved", true)
                }
                val begin = System.nanoTime()
                ui(instrument) {
                    // Keep real preview work in flight while the latest selected page supersedes older destinations.
                    view.goTo((index - 2).coerceAtLeast(0)); view.goTo((index - 1).coerceAtLeast(0)); view.goTo(index)
                    frames!!.mark()
                }
                await("selected populated page:$index") { ui(instrument) { view.currentPage?.id == ids[index] } }
                val selectedMs = (System.nanoTime() - begin) / 1e6
                ui(instrument) { closeSidebar(activity) }
                awaitScene(activity, instrument); realFrames(view, instrument)
                observe(instrument, "selected page pixels:$index") { image -> colorAt(image, view, instrument, 500f, 160f, markerColor(index)) }
                val observedMs = (System.nanoTime() - begin) / 1e6
                val state = ui(instrument) {
                    val page = requireNotNull(view.currentPage)
                    check(page.objects.size == PAGE_STROKES + 1 + (if (ids[index] in edits) 1 else 0)) { "Selected page content absent:$index" }
                    val loaded = loadedState(view)
                    check(loaded.getInt("pages") in 1..32) { "Populated editor retains an unbounded page working set:$loaded" }
                    loaded
                }
                // Only three owned edits, then navigate away without waiting for their save completion.
                if (index in setOf(0, MANY_PAGES / 2, MANY_PAGES - 1) && ids[index] !in edits) {
                    ui(instrument) { openSidebar(activity); scrollPreviews(activity, (index + 16).coerceAtMost(MANY_PAGES - 1)) }
                    realFrames(view, instrument)
                    // Write only on an unobscured editor, not through the phone's overlaid sidebar.
                    ui(instrument) { closeSidebar(activity) }
                    realFrames(view, instrument)
                    ui(instrument) {
                        view.tool = InkCanvasView.Tool.PEN; view.brush = "ballpoint"; view.inkColor = "#e000c0"; view.inkWidth = 8f
                        view.penSettings = JSONObject()
                        val input = StylusContactChecks.Input(view)
                        input.touch(MotionEvent.ACTION_DOWN, 200f, 260f)
                        input.touch(MotionEvent.ACTION_MOVE, 520f, 260f, history = listOf(300f to 260f, 400f to 260f))
                        input.touch(MotionEvent.ACTION_UP, 700f, 260f)
                        val stroke = view.currentPage!!.objects.last()
                        check(stroke.getJSONArray("points").length() == 5)
                        edits[ids[index]] = stroke.getString("id") to digest(stroke)
                        view.goTo((index + 1) % MANY_PAGES)
                    }
                }
                navigation.put(json("page" to index, "selectedContentReadyMs" to selectedMs,
                    "screenshotObservedMsIncludingCaptureAndPolling" to observedMs, "retainedState" to state, "memory" to memory(activity)))
                publish()
            }
            awaitSaved(activity, instrument)
            receipt.put("attachedFrames", ui(instrument) { frames!!.receipt() })
            phase("persisted_raw_order_and_reopen")
            // Read/verify one populated page at a time; never retain 320 parsed pages in the test.
            for (index in 0 until MANY_PAGES) {
                val saved = requireNotNull(repository.page(ids[index]))
                val edit = edits[ids[index]]
                check(saved.objects.size == PAGE_STROKES + 1 + (if (edit == null) 0 else 1)) { "Persisted populated page count:$index" }
                check(digest(saved.objects.first()) == digest(pageMarker(index))) { "Persisted page marker:$index" }
                repeat(PAGE_STROKES) { stroke -> check(digest(saved.objects[stroke + 1]) == digest(pageInk(index, stroke))) { "Persisted raw/order:$index/$stroke" } }
                if (edit != null) check(saved.objects.last().getString("id") == edit.first && digest(saved.objects.last()) == edit.second) { "Dropped pending edit:$index" }
            }
            ui(instrument) { frames!!.close(); frames = null; closeSidebar(activity); activity.showLibrary(); activity.openDocument(document.id, MANY_PAGES / 2) }
            await("middle populated page reopened") { ui(instrument) { selectedReady(activity, ids[MANY_PAGES / 2]) } }
            val reopened = ui(instrument) { requireNotNull(activity.inkView) }
            awaitScene(activity, instrument)
            observe(instrument, "reopened selected page displayed") { image -> colorAt(image, reopened, instrument, 500f, 160f, markerColor(MANY_PAGES / 2)) }
            ui(instrument) { check(reopened.currentPage!!.objects.last().getString("id") == edits.getValue(ids[MANY_PAGES / 2]).first) }
            receipt.put("navigation", navigation).put("savedEditedPages", edits.size).put("persistedPopulatedPagesVerified", MANY_PAGES)
                .put("fixtureDeletedAfterCheck", true)
            result.putString("manyPages", receipt.toString())
        } finally {
            ui(instrument) { frames?.close(); activity.inkView?.cancelContact(); activity.showLibrary() }
            awaitSaved(activity, instrument)
            repository.deleteDocument(document.id)
            settings.restore()
        }
    }

    private fun loadedState(view: InkCanvasView): JSONObject {
        fun map(name: String) = InkCanvasView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(view) as Map<*, *>
        val pages = map("pages")
        return json("pages" to pages.size, "pageIds" to JSONArray(pages.keys.toList()),
            "loadedObjects" to pages.values.sumOf { (it as NotePage).objects.size },
            "loadedRawSamples" to pages.values.sumOf { (it as NotePage).objects.sumOf { obj -> obj.optJSONArray("points")?.length() ?: 0 } },
            "picturePages" to map("pictures").size, "indexPages" to map("spatialIndexes").size, "historyPages" to map("histories").size)
    }
    private fun observedSettings(activity: MainActivity, view: InkCanvasView): JSONObject {
        val settings = field(activity, "settings") as JSONObject
        val display = activity.resources.displayMetrics
        return json("drawHold" to view.drawHold, "scribbleErase" to view.scribbleErase,
            "stylusOnly" to view.stylusOnly, "continuousPages" to view.continuous,
            "autoOcr" to settings.optBoolean("autoOcr", true), "autoOcrPreferenceStored" to settings.has("autoOcr"),
            "hardwareAccelerated" to view.isHardwareAccelerated, "attached" to view.isAttachedToWindow,
            "viewWidthPixels" to view.width, "viewHeightPixels" to view.height,
            "displayWidthPixels" to display.widthPixels, "displayHeightPixels" to display.heightPixels,
            "densityDpi" to display.densityDpi, "androidApi" to Build.VERSION.SDK_INT,
            "featurePreferencesPreserved" to true)
    }
    private fun memory(activity: MainActivity): JSONObject {
        val runtime = Runtime.getRuntime(); val manager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info)
        return json("usedJavaHeapBytes" to runtime.totalMemory() - runtime.freeMemory(), "maxJavaHeapBytes" to runtime.maxMemory(),
            "nativeAllocatedBytes" to Debug.getNativeHeapAllocatedSize(), "processPssKiB" to info.totalPss, "memoryClassMiB" to manager.memoryClass)
    }
    private fun hardwareRaster(view: InkCanvasView, instrument: Instrumentation, label: String,
                               beforeRecord: (() -> Unit)? = null): JSONObject {
        val (width, height) = ui(instrument) { view.width to view.height }
        // Capture waits for GPU/fence/readback off UI; only mutable scene recording runs on UI.
        PenPresentationChecks.HardwareRasterOracle(width, height).use { hardware ->
            val actual = hardware.capture("filled-retained/$label") { canvas ->
                ui(instrument) {
                    check(view.isAttachedToWindow && view.isHardwareAccelerated)
                    beforeRecord?.invoke()
                    // A mutation and its retained commands share this dispatch. No new-generation
                    // onDraw/worker completion can interleave before the production node is recorded.
                    view.drawRetainedScene(canvas)
                }
            }
            try {
                val expected = hardware.capture("filled-fresh-reference/$label") { canvas ->
                    ui(instrument) {
                        val page = requireNotNull(view.currentPage)
                        canvas.drawColor(0xfff7f7f8.toInt())
                        val origin = view.screenPoint(0f, 0f)
                        val saved = canvas.save()
                        canvas.translate(origin.x, origin.y); canvas.scale(view.scale, view.scale)
                        val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                        canvas.drawRect(0f, 0f, page.width, page.height, paper)
                        canvas.clipRect(0f, 0f, page.width, page.height)
                        val fresh = NoteRenderer(); fresh.template(canvas, page)
                        page.objects.forEach { fresh.draw(canvas, it, view.scale.toDouble()) }
                        canvas.restoreToCount(saved)
                    }
                }
                try {
                    val a = IntArray(width); val b = IntArray(width)
                    for (y in 0 until height) {
                        actual.getPixels(a, 0, width, 0, y, width, 1)
                        expected.getPixels(b, 0, width, 0, y, width, 1)
                        check(a.contentEquals(b)) {
                            val x = a.indices.first { a[it] != b[it] }
                            "Filled retained HardwareBuffer raster $label changed at $x,$y: " +
                                "${Integer.toHexString(a[x])} != ${Integer.toHexString(b[x])}"
                        }
                    }
                    return json("scope" to "production_retained_scene_vs_fresh_NoteRenderer_GPU_fence_complete_sRGB_readbacks",
                        "label" to label, "comparedPixels" to width.toLong() * height, "pixelExact" to true,
                        "mutationAndRetainedRecordingSameUiDispatch" to (beforeRecord != null))
                } finally { expected.recycle() }
            } finally { actual.recycle() }
        }
    }
    private fun raster(view: InkCanvasView, instrument: Instrumentation): JSONObject = ui(instrument) {
        val page = requireNotNull(view.currentPage)
        val actual = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val expected = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            // Production canonical scene, retaining its exact original viewport transform/clip.
            val method = InkCanvasView::class.java.getDeclaredMethod("recordScene").apply { isAccessible = true }
            Canvas(actual).drawPicture(requireNotNull(method.invoke(view) as? Picture))
            expected.eraseColor(0xfff7f7f8.toInt())
            val canvas = Canvas(expected); val origin = view.screenPoint(0f, 0f)
            canvas.save(); canvas.translate(origin.x, origin.y); canvas.scale(view.scale, view.scale)
            canvas.clipRect(0f, 0f, page.width, page.height)
            val renderer = NoteRenderer(); renderer.template(canvas, page)
            page.objects.forEach { renderer.draw(canvas, it, view.scale.toDouble()) }; canvas.restore()
            val bounds = paperBounds(view)
            val a = IntArray(bounds.width()); val b = IntArray(bounds.width())
            for (y in bounds.top until bounds.bottom) {
                actual.getPixels(a, 0, a.size, bounds.left, y, a.size, 1); expected.getPixels(b, 0, b.size, bounds.left, y, b.size, 1)
                check(a.contentEquals(b)) { "Filled canonical raster changed alpha/order/clip at row:$y" }
            }
            json("comparedPixels" to bounds.width().toLong() * bounds.height(), "scale" to view.scale, "pixelExact" to true)
        } finally { actual.recycle(); expected.recycle() }
    }
    private fun paperBounds(view: InkCanvasView): Rect {
        val page = requireNotNull(view.currentPage); val a = view.screenPoint(0f, 0f); val b = view.screenPoint(page.width, page.height)
        return Rect(maxOf(4, ceil(a.x + 4).toInt()), maxOf(4, ceil(a.y + 4).toInt()),
            minOf(view.width - 4, (b.x - 4).toInt()), minOf(view.height - (80 * view.resources.displayMetrics.density).toInt(), (b.y - 4).toInt())).also { check(!it.isEmpty) }
    }
    private fun coverage(image: Bitmap, view: InkCanvasView, instrument: Instrumentation): JSONObject {
        val (bounds, location) = ui(instrument) { paperBounds(view) to IntArray(2).also { view.getLocationOnScreen(it) } }
        var marked = 0L; var total = 0L
        val row = IntArray(bounds.width())
        for (y in bounds.top until bounds.bottom) {
            image.getPixels(row, 0, row.size, location[0] + bounds.left, location[1] + y, row.size, 1)
            for (color in row) { if (Color.red(color) < 235 || Color.green(color) < 235 || Color.blue(color) < 235) marked++; total++ }
        }
        val ratio = marked.toDouble() / total
        check(ratio > .55) { "Fixture is not densely raster-covered:$ratio" }
        return json("nonWhitePixels" to marked, "sampledVisiblePaperPixels" to total, "ratio" to ratio,
            "scope" to "real_compositor_screenshot_visible_paper_interior_excluding_bottom_HUD")
    }
    private fun colorPixels(image: Bitmap, view: InkCanvasView, instrument: Instrumentation, expected: Int): Int {
        val (bounds, location) = ui(instrument) { paperBounds(view) to IntArray(2).also { view.getLocationOnScreen(it) } }
        var count = 0
        val row = IntArray(bounds.width())
        for (y in bounds.top until bounds.bottom step 2) {
            image.getPixels(row, 0, row.size, location[0] + bounds.left, location[1] + y, row.size, 1)
            for (x in row.indices step 2) if (near(row[x], expected)) count++
        }
        return count
    }
    private fun changedInkPixels(before: Bitmap, after: Bitmap, view: InkCanvasView, instrument: Instrumentation,
                                 cursorX: Float, cursorY: Float): Int {
        data class Region(val bounds: Rect, val location: IntArray, val cursor: android.graphics.PointF, val excluded: Float)
        val region = ui(instrument) {
            val location = IntArray(2); view.getLocationOnScreen(location)
            val a = view.screenPoint(450f, 670f); val b = view.screenPoint(610f, 750f)
            Region(Rect(a.x.toInt(), a.y.toInt(), b.x.toInt(), b.y.toInt()), location,
                view.screenPoint(cursorX, cursorY), view.eraserRadius + 8f)
        }
        var changed = 0
        for (y in region.bounds.top until region.bounds.bottom) for (x in region.bounds.left until region.bounds.right) {
            if (abs(x - region.cursor.x) <= region.excluded && abs(y - region.cursor.y) <= region.excluded) continue
            if (before.getPixel(region.location[0] + x, region.location[1] + y) !=
                after.getPixel(region.location[0] + x, region.location[1] + y)) changed++
        }
        return changed
    }
    private fun previewDisplayed(image: Bitmap, activity: MainActivity, instrument: Instrumentation, expected: Int): Boolean {
        val points = ui(instrument) {
            val sidebar = requireNotNull(field(activity, "pageSidebar") as? ViewGroup)
            descendants(sidebar).filterIsInstance<NotePreview>().mapNotNull { preview ->
                val visible = Rect(); val location = IntArray(2); preview.getLocationOnScreen(location)
                val x = location[0] + preview.width / 2; val y = location[1] + (preview.height * 160f / 1414f).toInt()
                if (preview.isShown && preview.getGlobalVisibleRect(visible) && visible.contains(x, y)) x to y else null
            }.toList()
        }
        return points.any { (x, y) -> x in 0 until image.width && y in 0 until image.height && near(image.getPixel(x, y), expected) }
    }
    private fun colorAt(image: Bitmap, view: InkCanvasView, instrument: Instrumentation, x: Float, y: Float, expected: Int): Boolean {
        val coordinate = ui(instrument) { val p = view.screenPoint(x, y); val location = IntArray(2); view.getLocationOnScreen(location)
            location[0] + p.x.toInt() to location[1] + p.y.toInt() }
        if (coordinate.first !in 2 until image.width - 2 || coordinate.second !in 2 until image.height - 2) return false
        return (-2..2).any { dy -> (-2..2).any { dx -> near(image.getPixel(coordinate.first + dx, coordinate.second + dy), expected) } }
    }
    private fun near(actual: Int, expected: Int) = abs(Color.red(actual) - Color.red(expected)) < 16 &&
        abs(Color.green(actual) - Color.green(expected)) < 16 && abs(Color.blue(actual) - Color.blue(expected)) < 16
    private fun screenshot(instrument: Instrumentation, file: File? = null): Bitmap {
        val image = requireNotNull(instrument.uiAutomation.takeScreenshot()) { "Attached compositor screenshot unavailable" }
        if (file != null) file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return image
    }
    private fun observe(instrument: Instrumentation, label: String, predicate: (Bitmap) -> Boolean) = await(label) {
        val image = screenshot(instrument)
        try { predicate(image) } finally { image.recycle() }
    }
    private fun awaitScene(activity: MainActivity, instrument: Instrumentation) = await("attached canonical presentation") {
        ui(instrument) { (field(activity, "inkFrontBuffer") as? InkFrontSurface)?.presented == true }
    }
    private fun realFrames(view: View, instrument: Instrumentation) {
        val done = CountDownLatch(1)
        ui(instrument) { view.postOnAnimation { view.postOnAnimation { done.countDown() } } }
        check(done.await(30, TimeUnit.SECONDS)) { "Attached View did not produce animation frames" }
    }
    private fun busy(activity: MainActivity) = field(activity, "busy") == true
    private fun selectedReady(activity: MainActivity, pageId: String) = !busy(activity) && activity.inkView?.let {
        it.currentPage?.id == pageId && it.width > 0 && it.height > 0 && it.isAttachedToWindow && it.isShown
    } == true
    private fun field(activity: MainActivity, name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
    }
    private fun control(activity: MainActivity, label: String): View {
        val translated = (field(activity, "dictionary") as JSONObject).optString(label, label)
        return descendants(activity.window.decorView).first { it.isClickable && it.contentDescription?.toString() == translated }
    }
    private fun openSidebar(activity: MainActivity) { if (field(activity, "pageSidebar") == null) check(control(activity, "페이지 관리").performClick()) }
    private fun closeSidebar(activity: MainActivity) { if (field(activity, "pageSidebar") != null) check(control(activity, "페이지 관리 닫기").performClick()) }
    private fun scrollPreviews(activity: MainActivity, index: Int) {
        val grid = requireNotNull(field(activity, "pageGrid") as? View) { "Production page grid unavailable" }
        grid.javaClass.getMethod("scrollToPosition", Int::class.javaPrimitiveType).invoke(grid, index)
    }
    private class Settings(private val activity: MainActivity, private val instrument: Instrumentation) {
        private val encoded = ui(instrument) { (field(activity, "settings") as JSONObject).toString() }
        private val persisted = activity.repository.setting("preferences", "{}")
        fun restore() {
            ui(instrument) {
                val target = field(activity, "settings") as JSONObject; val source = JSONObject(encoded)
                target.keys().asSequence().toList().forEach { target.remove(it) }
                source.keys().forEach { key -> target.put(key, source.get(key)) }
            }
            activity.repository.setting("preferences", persisted, true)
        }
    }
    private fun awaitSaved(activity: MainActivity, instrument: Instrumentation) = await("workload pending saves") {
        ui(instrument) { field(activity, "saving") == false && field(activity, "saveFailed") == false && (field(activity, "saves") as Collection<*>).isEmpty() }
    }
    private fun await(label: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 120_000
        while (SystemClock.uptimeMillis() < deadline) { if (ready()) return; SystemClock.sleep(25) }
        error("Timeout:$label")
    }
    private fun <T> ui(instrument: Instrumentation, action: () -> T): T {
        val value = AtomicReference<T>(); val failure = AtomicReference<Throwable>()
        instrument.runOnMainSync { try { value.set(action()) } catch (error: Throwable) { failure.set(error) } }
        failure.get()?.let { throw it }; return value.get()
    }
}
