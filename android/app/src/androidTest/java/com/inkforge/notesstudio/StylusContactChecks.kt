package com.inkforge.notesstudio

import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import org.json.JSONArray

/** Real View input regressions; synthetic events do not measure physical S Pen latency. */
object StylusContactChecks {
    internal class Input(private val view: InkCanvasView) {
        private var clock = 10_000L
        private var down = clock
        private val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 7; toolType = MotionEvent.TOOL_TYPE_STYLUS
        })
        private val mouseProperties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 7; toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        private fun coords(x: Float, y: Float) = arrayOf(MotionEvent.PointerCoords().apply {
            val point = view.screenPoint(x, y)
            this.x = point.x; this.y = point.y; pressure = .65f
        })
        fun event(action: Int, x: Float, y: Float, buttons: Int = 0,
                  history: List<Pair<Float, Float>> = emptyList()): MotionEvent {
            if (action == MotionEvent.ACTION_DOWN) down = clock
            val samples = history + (x to y)
            return MotionEvent.obtain(down, clock++, action, 1, properties,
                coords(samples.first().first, samples.first().second), 0, buttons,
                1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0).apply {
                for (sample in samples.drop(1)) addBatch(clock++, coords(sample.first, sample.second), 0)
            }
        }
        fun touch(action: Int, x: Float, y: Float, buttons: Int = 0,
                  history: List<Pair<Float, Float>> = emptyList()) {
            event(action, x, y, buttons, history).let { try { view.onTouchEvent(it) } finally { it.recycle() } }
        }
        fun generic(action: Int, x: Float, y: Float, buttons: Int) {
            event(action, x, y, buttons).let { try { view.onGenericMotionEvent(it) } finally { it.recycle() } }
        }
        fun mouseButton(action: Int, x: Float, y: Float) {
            val event=MotionEvent.obtain(down,clock++,action,1,mouseProperties,coords(x,y),
                0,if(action==MotionEvent.ACTION_BUTTON_PRESS)MotionEvent.BUTTON_PRIMARY else 0,
                1f,1f,0,0,InputDevice.SOURCE_MOUSE,0)
            try { view.onGenericMotionEvent(event) } finally { event.recycle() }
        }
        fun hover(action: Int, x: Float, y: Float, buttons: Int) {
            event(action, x, y, buttons).let { try { view.onHoverEvent(it) } finally { it.recycle() } }
        }
    }

    fun run(activity: MainActivity, ui: (() -> Unit) -> Unit, result: Bundle) {
        val document = activity.repository.create("stylus-contact-regression", "root", "blank")
        val page = requireNotNull(activity.repository.page(activity.repository.pageIds(document.id).first()))
        ui {
            val view = InkCanvasView(activity, activity.repository)
            view.layout(0, 0, 1080, 1600)
            view.drawHold = false; view.scribbleErase = false
            val input = Input(view)
            fun stroke(id: String, x: Float) = json("id" to id, "type" to "stroke",
                "brush" to "fountain", "color" to "#172033", "width" to 4,
                "points" to JSONArray(listOf(InkPoint(x, 390f).json(), InkPoint(x, 410f).json())))
            var edits = 0
            view.onEdit = { _, _, _ -> edits++ }
            fun reset() {
                view.cancelContact(); view.tool = InkCanvasView.Tool.PEN
                page.objects.clear(); page.objects.addAll(listOf(stroke("pressed", 300f),
                    stroke("released", 600f), stroke("hover", 900f)))
                view.open(listOf(page.id), listOf(page.meta)); view.replacePage(page); edits = 0
            }
            fun ids() = page.objects.map { it.getString("id") }
            fun press(path: Int) {
                when (path) {
                    0 -> view.barrelKey(true)
                    1 -> input.generic(MotionEvent.ACTION_BUTTON_PRESS, 300f, 400f, MotionEvent.BUTTON_STYLUS_PRIMARY)
                    else -> input.touch(MotionEvent.ACTION_MOVE, 300f, 400f, MotionEvent.BUTTON_STYLUS_PRIMARY,
                        listOf(200f to 400f, 250f to 400f))
                }
            }
            fun release(path: Int) {
                when (path) {
                    0 -> view.barrelKey(false)
                    1 -> input.generic(MotionEvent.ACTION_BUTTON_RELEASE, 400f, 400f, 0)
                    else -> input.touch(MotionEvent.ACTION_MOVE, 400f, 400f)
                }
            }
            repeat(3) { path ->
                reset()
                input.hover(MotionEvent.ACTION_HOVER_MOVE, 900f, 400f, MotionEvent.BUTTON_STYLUS_PRIMARY)
                view.barrelKey(true); input.generic(MotionEvent.ACTION_BUTTON_PRESS, 900f, 400f, MotionEvent.BUTTON_STYLUS_PRIMARY)
                check(ids() == listOf("pressed", "released", "hover") && edits == 0) { "Hover/button-only input erased ink" }
                view.barrelKey(false); input.generic(MotionEvent.ACTION_BUTTON_RELEASE, 900f, 400f, 0)
                input.hover(MotionEvent.ACTION_HOVER_EXIT, 900f, 400f, 0)
                input.touch(MotionEvent.ACTION_DOWN, 100f, 400f)
                input.touch(MotionEvent.ACTION_MOVE, 150f, 400f)
                press(path)
                input.touch(MotionEvent.ACTION_MOVE, 300f, 400f, MotionEvent.BUTTON_STYLUS_PRIMARY)
                release(path)
                input.touch(MotionEvent.ACTION_MOVE, 600f, 400f, history = listOf(450f to 400f, 550f to 400f))
                input.touch(MotionEvent.ACTION_UP, 650f, 400f)
                check("pressed" !in ids() && "released" !in ids() && "hover" in ids()) { "Release stopped erasing on path $path" }
                val inkSegments = if (path == 0) 0 else 1 // A key press erases at the current tip, including its own preceding segment.
                check(page.objects.count { it.optString("type") == "stroke" && it.optString("id") !in setOf("pressed", "released", "hover") } == inkSegments && edits == 1) {
                    "One mixed contact must retain only its pre-erase ink segment and one undo entry"
                }
                input.touch(MotionEvent.ACTION_DOWN, 700f, 700f)
                input.touch(MotionEvent.ACTION_UP, 750f, 700f)
                check(page.objects.size == inkSegments + 2 && edits == 2) { "Eraser leaked past lift on path $path" }
                view.undo(); check(page.objects.size == inkSegments + 1)
                view.undo(); check(ids() == listOf("pressed", "released", "hover")) { "Undo did not restore contact order" }
                view.redo(); check("released" !in ids()) { "Redo did not restore complete erase contact" }

                reset()
                val before = page.objects.map { it.toString() }
                input.touch(MotionEvent.ACTION_DOWN, 100f, 400f); press(path)
                input.touch(MotionEvent.ACTION_MOVE, 300f, 400f, MotionEvent.BUTTON_STYLUS_PRIMARY)
                release(path); input.touch(MotionEvent.ACTION_MOVE, 600f, 400f)
                input.touch(MotionEvent.ACTION_CANCEL, 650f, 400f)
                check(page.objects.map { it.toString() } == before && edits == 0 && !view.canUndo) { "Cancel failed on path $path" }
                input.touch(MotionEvent.ACTION_DOWN, 700f, 700f); input.touch(MotionEvent.ACTION_UP, 750f, 700f)
                check(page.objects.size == 4 && edits == 1) { "Cancelled eraser leaked into next contact" }

                reset()
                when(path){
                    0 -> view.barrelKey(true)
                    1 -> input.generic(MotionEvent.ACTION_BUTTON_PRESS, 300f, 400f, MotionEvent.BUTTON_STYLUS_PRIMARY)
                }
                input.touch(MotionEvent.ACTION_DOWN, 300f, 400f, MotionEvent.BUTTON_STYLUS_PRIMARY)
                release(path)
                input.touch(MotionEvent.ACTION_MOVE, 600f, 400f)
                input.touch(MotionEvent.ACTION_UP, 650f, 400f)
                check(ids() == listOf("hover") && edits == 1) { "Initially held eraser did not remain latched" }
                view.undo(); check(ids() == listOf("pressed", "released", "hover"))
            }
            reset()
            view.barrelKey(true)
            input.touch(MotionEvent.ACTION_DOWN,300f,400f)
            input.touch(MotionEvent.ACTION_UP,300f,400f)
            input.touch(MotionEvent.ACTION_DOWN,600f,400f)
            input.touch(MotionEvent.ACTION_UP,600f,400f)
            check(ids()==listOf("hover")&&edits==2) { "A physically held key stopped erasing after a normal lift" }
            view.barrelKey(false)
            input.touch(MotionEvent.ACTION_DOWN,700f,700f);input.touch(MotionEvent.ACTION_UP,750f,700f)
            check(page.objects.size==2&&edits==3) { "Released key leaked into a later contact" }
            view.undo();view.undo();view.undo()
            check(ids()==listOf("pressed","released","hover")) { "Separate held-key contacts lost their undo boundaries" }

            reset()
            view.barrelKey(true);input.touch(MotionEvent.ACTION_DOWN,300f,400f)
            input.touch(MotionEvent.ACTION_CANCEL,300f,400f)
            input.touch(MotionEvent.ACTION_DOWN,700f,700f);input.touch(MotionEvent.ACTION_UP,750f,700f)
            check(page.objects.size==4&&edits==1) { "Cancellation retained a held key" }

            reset()
            view.tool=InkCanvasView.Tool.ERASER
            input.touch(MotionEvent.ACTION_DOWN,300f,400f)
            input.mouseButton(MotionEvent.ACTION_BUTTON_PRESS,900f,400f)
            input.mouseButton(MotionEvent.ACTION_BUTTON_RELEASE,900f,400f)
            check(ids()==listOf("released","hover")&&edits==0) { "An unrelated mouse button moved the stylus eraser" }
            input.generic(MotionEvent.ACTION_BUTTON_PRESS,900f,400f,MotionEvent.BUTTON_STYLUS_PRIMARY)
            input.generic(MotionEvent.ACTION_BUTTON_RELEASE,900f,400f,0)
            check(ids()==listOf("released","hover")&&edits==0) { "Generic button coordinates were treated as eraser movement" }
            input.touch(MotionEvent.ACTION_UP,300f,400f)
            check(ids()==listOf("released","hover")&&edits==1)
            view.undo();check(ids()==listOf("pressed","released","hover"))
            reset()
            input.touch(MotionEvent.ACTION_DOWN, 100f, 400f); view.barrelKey(true)
            input.touch(MotionEvent.ACTION_MOVE, 300f, 400f, MotionEvent.BUTTON_STYLUS_PRIMARY)
            view.tool = InkCanvasView.Tool.HIGHLIGHTER
            check(ids() == listOf("pressed", "released", "hover") && edits == 0)
            input.touch(MotionEvent.ACTION_DOWN, 700f, 700f); input.touch(MotionEvent.ACTION_UP, 750f, 700f)
            check(page.objects.size == 4 && page.objects.last().optString("brush") == "highlighter") { "Tool change retained eraser state" }
            view.close()
        }
        result.putString("stylusContact", "PASS: key/generic/MOVE release latch, continued historical erase, hover, held-key lift continuity, unrelated mouse isolation, cancel/tool reset, one-contact undo/redo")
    }
}
