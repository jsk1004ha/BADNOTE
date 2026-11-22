package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import org.json.JSONArray
import java.io.File

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
        ui {
            view = requireNotNull(activity.inkView)
            view.drawHold = true
            view.scribbleErase = true
            view.stylusOnly = true
            view.tool = InkCanvasView.Tool.PEN
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
        await("gesture edits committed") {
            repository.page(pageId)?.objects?.let { objects ->
                objects.size == 3 && objects.count { it.optString("type") == "shape" } == 2 &&
                    objects.any { it.optString("id") == "locked" }
            } == true
        }
        graphiteRaster()
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
