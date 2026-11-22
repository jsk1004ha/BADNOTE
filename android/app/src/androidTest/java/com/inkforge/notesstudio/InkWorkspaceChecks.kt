package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import org.json.JSONArray
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal object InkWorkspaceChecks {
    fun run(activity: MainActivity, ui: (() -> Unit) -> Unit, await: (String, () -> Boolean) -> Unit) {
        val repository = activity.repository
        val document = repository.create("ink-workspace-check", "root", "blank")
        val pageId = repository.pageIds(document.id).single()
        ui { activity.openDocument(document.id) }
        await("ink workspace opened") {
            var ready = false
            ui { ready = activity.inkView?.currentPage?.id == pageId && activity.inkView!!.width > 0 }
            ready
        }
        lateinit var view: InkCanvasView
        fun control(label: String): View {
            fun find(view: View): View? {
                if (view.contentDescription == label) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) {
                    find(view.getChildAt(i))?.let { return it }
                }
                return null
            }
            return checkNotNull(find(activity.window.decorView)) { "Missing editor control $label" }
        }
        lateinit var undo: View
        ui {
            view = requireNotNull(activity.inkView)
            view.drawHold = true
            view.scribbleErase = true
            view.stylusOnly = true
            view.tool = InkCanvasView.Tool.PEN
            undo = control("실행 취소")
            check(!undo.isEnabled)
        }
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        fun sample(action: Int, x: Float, y: Float, start: Long, tick: Long) {
            val coordinate = view.screenPoint(x, y)
            val points = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = coordinate.x
                this.y = coordinate.y
                pressure = .65f
            })
            val event = MotionEvent.obtain(start, tick, action, 1, properties, points, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_STYLUS, 0)
            try { check(view.onTouchEvent(event)) } finally { event.recycle() }
        }
        val down = SystemClock.uptimeMillis()
        ui {
            sample(MotionEvent.ACTION_DOWN, 330f, 320f, down, down)
            sample(MotionEvent.ACTION_MOVE, 440f, 330f, down, down + 16)
        }
        SystemClock.sleep(550)
        ui {
            sample(MotionEvent.ACTION_UP, 440f, 330f, down, SystemClock.uptimeMillis())
            val held = view.currentPage!!.objects.single()
            check(held.optString("shape") == "line") { "A sparse held line wasn't converted" }
            check(control("실행 취소") === undo && undo.isEnabled) { "Saving a stroke recreated the undo control" }
            val expectedWidth = LegacyBrush.toolStrokeWidthToPage(
                (view.scale * 1000 / view.resources.displayMetrics.density).toDouble(), view.inkWidth.toDouble())
            check(kotlin.math.abs(held.getDouble("width") - expectedWidth) < .01)
            view.undo()
            check(view.currentPage!!.objects.isEmpty())
            view.redo()
            check(view.currentPage!!.objects.single().optString("shape") == "line")
        }

        ui {
            view.drawHold = false
            view.tool = InkCanvasView.Tool.SHAPE
            check(view.shape == "freehand")
            val now = SystemClock.uptimeMillis()
            val corners = listOf(530f to 300f, 613f to 303f, 615f to 377f, 529f to 378f, 532f to 304f)
            corners.forEachIndexed { index, (x, y) ->
                val action = if (index == 0) MotionEvent.ACTION_DOWN
                    else if (index == corners.lastIndex) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                sample(action, x, y, now, now + 16L * index)
            }
            check(view.currentPage!!.objects.last().optString("shape") == "rectangle") { "Freehand shape failed on UP" }
        }

        ui {
            val target = json("id" to "scratch-me", "type" to "stroke", "brush" to "ballpoint", "width" to 4,
                "points" to JSONArray(listOf(InkPoint(500f, 380f).json(), InkPoint(500f, 470f).json())))
            view.add(target)
            view.add(target.copyJson().put("id", "locked").put("locked", true))
            view.tool = InkCanvasView.Tool.PEN
            val now = SystemClock.uptimeMillis()
            sample(MotionEvent.ACTION_DOWN, 450f, 395f, now, now)
            sample(MotionEvent.ACTION_MOVE, 550f, 413f, now, now + 20)
            sample(MotionEvent.ACTION_MOVE, 451f, 425f, now, now + 40)
            sample(MotionEvent.ACTION_UP, 547f, 444f, now, now + 60)
            fun ids() = view.currentPage!!.objects.map { it.getString("id") }
            check(ids().size == 3 && "scratch-me" !in ids() && "locked" in ids()) { "Sparse scratch didn't erase just the intended ink" }
            view.undo()
            check("scratch-me" in ids())
            view.redo()
            check("scratch-me" !in ids())
        }
        fun trace(path: List<InkPoint>): Long {
            val down = SystemClock.uptimeMillis() - path.lastIndex * 10L
            path.forEachIndexed { index, point ->
                sample(if (index == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE,
                    point.x, point.y, down, down + index * 10L)
            }
            return down
        }
        val circle = (0..28).map { i ->
            val angle = .48 + i * (2 * PI - .96) / 28
            InkPoint(380 + 100 * cos(angle).toFloat(), 620 + 97 * sin(angle).toFloat())
        }
        // Probe the missing right edge of the raw path. Only the snapped preview covers it.
        fun circlePreviewIsVisible(): Boolean {
            val left = view.screenPoint(462f, 620f)
            val right = view.screenPoint(483f, 620f)
            val probe = Bitmap.createBitmap((right.x - left.x).toInt().coerceAtLeast(1), 9, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(probe)
                canvas.translate(-left.x, 4 - left.y)
                view.draw(canvas)
                return (0 until probe.width).any { x ->
                    val color = probe.getPixel(x, 4)
                    Color.alpha(color) > 180 && Color.red(color) < 120
                }
            } finally { probe.recycle() }
        }
        var circleDown = 0L
        ui {
            view.drawHold = true
            check(view.tool == InkCanvasView.Tool.PEN)
            circleDown = trace(circle)
            check(!circlePreviewIsVisible()) { "The pen should wait before snapping" }
        }
        val end = circle.last()
        repeat(28) { i ->
            SystemClock.sleep(15)
            ui {
                val jitter = view.resources.displayMetrics.density / view.scale
                sample(MotionEvent.ACTION_MOVE, end.x + if (i % 2 == 0) jitter else -jitter,
                    end.y, circleDown, SystemClock.uptimeMillis())
            }
        }
        await("normal pen previews a nearly closed circle while still down") {
            var preview = false
            ui { preview = view.hasActiveContact && view.currentPage!!.objects.size == 3 && circlePreviewIsVisible() }
            preview
        }
        ui {
            sample(MotionEvent.ACTION_UP, end.x, end.y, circleDown, SystemClock.uptimeMillis())
            val circle = view.currentPage!!.objects.last()
            check(circle.optString("shape") == "circle") { "The visible circle wasn't committed" }
            check(kotlin.math.abs((circle.f("x2") - circle.f("x1")) -
                (circle.f("y2") - circle.f("y1"))) < .01f)
        }

        val arc = (0..22).map { i ->
            val angle = i * PI / 22
            InkPoint(660 - 94 * cos(angle).toFloat(), 650 - 60 * sin(angle).toFloat())
        }
        var curveDown = 0L
        ui { curveDown = trace(arc) }
        SystemClock.sleep(550)
        ui {
            sample(MotionEvent.ACTION_UP, arc.last().x, arc.last().y, curveDown, SystemClock.uptimeMillis())
            val curve = view.currentPage!!.objects.last()
            check(curve.optString("shape") == "curve" && curve.f("cy") < 575f) { "The pen didn't keep the open bend" }
            val quick = trace(circle)
            sample(MotionEvent.ACTION_UP, end.x, end.y, quick, quick + circle.size * 10L)
            check(view.currentPage!!.objects.last().optString("type") == "stroke") { "A loop without a hold should stay ink" }
            view.undo()
        }
        await("gesture edits committed") {
            repository.page(pageId)?.objects?.let { objects ->
                objects.size == 5 && objects.any { it.optString("id") == "locked" } &&
                    objects.filter { it.optString("type") == "shape" }.map { it.optString("shape") }
                        .toSet() == setOf("line", "rectangle", "circle", "curve")
            } == true
        }
        graphiteRaster()
        graphitePreview()
        ui {
            val pen = view.brush
            val penColor = view.inkColor
            val penWidth = view.inkWidth
            check(control("연필").performClick())
            check(view.brush == "pencil" && control("연필").isSelected)
            check(control("굵기 2").performClick())
            check(view.inkWidth == 2f)
            check(control("필기 색상 추가").performClick())
            check(control("색상 #7f1d1d").performClick())
            check(control("적용").performClick())
            val pencilColor = view.inkColor
            check(pencilColor == "#7f1d1d" && control(pencilColor).isSelected)
            check(control("펜").performClick())
            check(view.brush == pen && view.inkColor == penColor && view.inkWidth == penWidth)
            check(control("연필").performClick())
            check(view.inkColor == pencilColor && view.inkWidth == 2f && control(pencilColor).isSelected)
        }
        val output = File.createTempFile("ink-workspace-", ".xopp", repository.workDirectory)
        try {
            // Shapes take the image fallback; exercise the Android parser and embedded PNG storage.
            NoteInterchange.exportXournal(repository, document, output) { false }
            val reopened = output.inputStream().use { NoteInterchange.importXournal(repository, it, "round-trip", "root") }
            try {
                val image = repository.page(repository.pageIds(reopened.id).single())!!.objects.single()
                check(image.optString("type") == "image")
                check(repository.asset(image.getString("src").removePrefix("asset:")).length() > 0)
            } finally { repository.deleteDocument(reopened.id) }
        } finally {
            output.delete()
            ui { activity.showLibrary() }
        }
    }

    private fun graphitePreview() {
        val renderer = NoteRenderer()
        val publication = InkPointPublication()
        val points = (0..80).map { i ->
            InkPoint(20f + i * 3, 66f + 22f * sin(i / 9.0).toFloat(), .4f + i / 200f, ty = 43f)
        }
        val stroke = json("id" to "graphite-preview", "type" to "stroke", "brush" to "pencil",
            "pencilTexture" to "graphite", "width" to 6, "color" to "#262a30",
            "graphiteVersion" to GraphiteBrush.CURRENT_VERSION)
        val bitmap = Bitmap.createBitmap(280, 130, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val live = IntArray(bitmap.width * bitmap.height)
            val committed = IntArray(live.size)
            for ((i, point) in points.withIndex()) {
                publication.append(point)
                if (i !in setOf(0, 1, 2, 17, 39, points.lastIndex)) continue
                bitmap.eraseColor(Color.WHITE)
                renderer.stroke(canvas, stroke, publication.snapshot(), 1.0)
                bitmap.getPixels(live, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                bitmap.eraseColor(Color.WHITE)
                val saved = stroke.copyJson().put("points", JSONArray(points.take(i + 1).map(InkPoint::json)))
                renderer.draw(canvas, saved, 1.0)
                bitmap.getPixels(committed, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                check(live.contentEquals(committed)) { "Saved graphite changed at sample $i" }
            }
        } finally { bitmap.recycle() }
    }

    private fun graphiteRaster() {
        val renderer = NoteRenderer()
        fun raster(pressure: Float, tilt: Float): IntArray {
            val bitmap = Bitmap.createBitmap(280, 130, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                val points = (20..260 step 8).map { InkPoint(it.toFloat(), 65f, pressure, ty = tilt).json() }
                val stroke = json("id" to "graphite-raster", "type" to "stroke", "brush" to "pencil",
                    "pencilTexture" to "graphite", "width" to 10, "color" to "#303030", "points" to JSONArray(points))
                renderer.draw(Canvas(bitmap), stroke)
                return IntArray(bitmap.width * bitmap.height).also {
                    bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                }
            } finally { bitmap.recycle() }
        }
        val light = raster(.16f, 0f)
        val heavy = raster(.9f, 0f)
        val angled = raster(.9f, 65f)
        check(light.contentEquals(raster(.16f, 0f))) { "Graphite changes between identical replays" }
        check(heavy.count { Color.red(it) < 185 } > light.count { Color.red(it) < 185 }) { "Pressure didn't darken graphite" }
        check(angled.indices.count { Color.red(angled[it]) < 245 && kotlin.math.abs(it / 280 - 65) >= 6 } >
            heavy.indices.count { Color.red(heavy[it]) < 245 && kotlin.math.abs(it / 280 - 65) >= 6 }) {
            "Tilt didn't widen the graphite shading"
        }
    }
}
