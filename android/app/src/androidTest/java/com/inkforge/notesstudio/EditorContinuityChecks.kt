package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Picture
import android.os.Bundle
import android.view.MotionEvent
import android.widget.LinearLayout
import org.json.JSONArray

/** Fast pixel regression for scene ownership; no full OCR/export/performance workload. */
internal object EditorContinuityChecks {
    fun run(activity: MainActivity, ui: (() -> Unit) -> Unit, result: Bundle) {
        val repository = activity.repository
        val document = repository.create("editor-continuity-check", "root", "blank")
        val page = requireNotNull(repository.page(repository.pageIds(document.id).single()))
        fun stroke(id: String, y: Float) = json("id" to id, "type" to "stroke",
            "brush" to "ballpoint", "color" to "#172033", "width" to 12,
            "points" to JSONArray(listOf(InkPoint(180f, y).json(), InkPoint(820f, y).json())))
        page.objects.addAll(listOf(stroke("target", 300f), stroke("untouched", 650f)))
        try {
            ui {
                val field = MainActivity::class.java.getDeclaredField("editorNavbar").apply { isAccessible = true }
                val navbar = field.get(activity) as LinearLayout
                check(navbar.orientation == LinearLayout.HORIZONTAL) { "Toolbar wrapped into two rows" }
                check(navbar.height <= 64 * activity.resources.displayMetrics.density) { "Toolbar retained double-row height" }
                val editor = requireNotNull(activity.inkView)
                val previousTool = editor.tool
                editor.tool = InkCanvasView.Tool.PEN
                val dockField = MainActivity::class.java.getDeclaredField("activeDock").apply { isAccessible = true }
                val dock = dockField.get(activity) as LinearLayout
                check(dock.layoutParams.height <= 64 * activity.resources.displayMetrics.density &&
                    dock.layoutParams.height > 0) { "Pen controls wrapped into multiple rows" }
                editor.tool = previousTool
                val view = InkCanvasView(activity, repository)
                val output = Bitmap.createBitmap(1080, 1500, Bitmap.Config.ARGB_8888)
                // A completed Surface transaction can be ready while the View owns the
                // current frame. The View must not turn into an empty overlay in that state.
                val front = object : InkFrontSurface {
                    override var presented = true
                    override fun hideForLegacy() { presented = false }
                    override fun renderScene(picture: Picture, width: Int, height: Int, commitFront: Boolean) { presented = true }
                    override fun renderFront(frame: InkFrontFrame) = false
                    override fun cancelFront() = Unit
                }
                try {
                    view.layout(0, 0, output.width, output.height)
                    view.drawHold = false; view.scribbleErase = false
                    view.open(listOf(page.id), listOf(page.meta)); view.replacePage(page)
                    view.attachFrontBuffer(front)
                    val input = StylusContactChecks.Input(view)
                    var frames = 0
                    fun frame(label: String) {
                        output.eraseColor(Color.MAGENTA)
                        view.draw(Canvas(output))
                        val point = view.screenPoint(500f, 650f)
                        val color = output.getPixel(point.x.toInt(), point.y.toInt())
                        check(Color.red(color) < 100 && Color.green(color) < 100 && Color.blue(color) < 100) {
                            "Unrelated ink disappeared: $label / ${Integer.toHexString(color)}"
                        }
                        frames++
                    }
                    frame("ready Surface, idle View")
                    view.tool = InkCanvasView.Tool.PEN
                    input.touch(MotionEvent.ACTION_DOWN, 200f, 450f); frame("pen down")
                    input.touch(MotionEvent.ACTION_MOVE, 500f, 450f); frame("pen move")
                    input.touch(MotionEvent.ACTION_UP, 600f, 450f); frame("pen commit")
                    frame("pen Surface completion")
                    view.tool = InkCanvasView.Tool.ERASER; view.preciseEraser = true
                    input.touch(MotionEvent.ACTION_DOWN, 400f, 300f); frame("eraser down")
                    input.touch(MotionEvent.ACTION_MOVE, 450f, 300f); frame("eraser move")
                    input.touch(MotionEvent.ACTION_UP, 460f, 300f); frame("eraser commit")
                    view.undo(); frame("undo eraser")
                    view.redo(); frame("redo eraser")
                    view.zoomBy(1.2f); frame("zoom in")
                    view.zoomBy(1 / 1.2f); frame("zoom out")
                    view.tool = InkCanvasView.Tool.PEN
                    input.touch(MotionEvent.ACTION_DOWN, 200f, 500f); frame("next pen")
                    input.touch(MotionEvent.ACTION_CANCEL, 200f, 500f); frame("cancel pen")
                    frame("cancel Surface completion")
                    check(page.objects.any { it.optString("id") == "untouched" })
                    result.putString("editorContinuity", "single_row_toolbar_and_${frames}_frames_of_unchanged_ink_verified")
                } finally { view.close(); output.recycle() }
            }
        } finally { repository.deleteDocument(document.id) }
    }
}
