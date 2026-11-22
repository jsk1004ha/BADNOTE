package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Debug
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

/** Uses only beta2 public View/model/repository APIs, so this test APK also measures the original APK. */
object LassoDragChecks {
    private const val STROKES = 512
    private const val POINTS = 32
    private const val MAX_EVENTS = 48
    private const val SAMPLES_PER_EVENT = 4
    private const val DX = 96f
    private const val DY = 64f
    private const val COORDINATE_TOLERANCE = .001 // Page units, not pixels; only translated x/y use this bound.

    private fun fixture(): MutableList<JSONObject> = buildList {
        repeat(STROKES) { stroke ->
            if (stroke == 170 || stroke == 340) add(json("id" to "locked-$stroke", "type" to "tape",
                "x" to 150, "y" to 390 + (stroke / 170) * 35, "w" to 700, "h" to 12,
                "color" to "#d02020", "locked" to true))
            val x = 180f + (stroke % 32) * 18f; val y = 280f + (stroke / 32) * 24f
            add(json("id" to "stroke-$stroke", "type" to "stroke", "brush" to "ballpoint",
                "width" to 3, "color" to "#172033", "captureSeq" to stroke + 1,
                "captureSessionId" to "lasso-regression", "timeBasis" to "session-monotonic", "revision" to 1,
                "vendorObject" to json("sequence" to JSONArray(listOf(3, 1, 2))),
                "points" to JSONArray((0 until POINTS).map { index ->
                    json("x" to x + index * .35f, "y" to y + sin(index * .3f) * 3f,
                        "p" to .2f + index * .02f, "t" to 1000L + stroke * 100 + index,
                        "tilt" to .2, "orientation" to .4, "pointerId" to 7,
                        "azimuth" to 1.1, "tx" to 0, "ty" to JSONObject.NULL,
                        "vendor" to json("sample" to index))
                })))
        }
        add(json("id" to "hidden", "type" to "stroke", "hidden" to true,
            "width" to 3, "points" to JSONArray(listOf(InkPoint(200f, 300f).json()))))
    }.toMutableList()

    private fun assertJsonEqual(actual:Any?,expected:Any?,path:String,ignoreKeys:Set<String> = emptySet()){
        when{
            actual is JSONObject&&expected is JSONObject->{
                val keys=actual.keys().asSequence().toSet()-ignoreKeys
                check(keys==expected.keys().asSequence().toSet()-ignoreKeys){"$path: JSON fields changed"}
                for(key in keys)assertJsonEqual(actual.get(key),expected.get(key),"$path.$key")
            }
            actual is JSONArray&&expected is JSONArray->{
                check(actual.length()==expected.length()){"$path: array length changed"}
                for(index in 0 until actual.length())assertJsonEqual(actual.get(index),expected.get(index),"$path[$index]")
            }
            actual is Number&&expected is Number->check(actual.toDouble()==expected.toDouble()){
                "$path: number changed ($actual != $expected)"}
            else->check(actual==expected){"$path: value changed ($actual != $expected)"}
        }
    }
    private fun assertPageEqual(actual:List<JSONObject>,expected:List<JSONObject>,label:String,restored:Boolean=false){
        check(actual.map{it.getString("id")}==expected.map{it.getString("id")}){"$label: object order changed"}
        actual.zip(expected).forEach{(value,source)->
            val ignore=if(restored&&source.optString("type")=="stroke"&&!source.optBoolean("hidden")&&!source.optBoolean("locked"))
                setOf("revision") else emptySet()
            assertJsonEqual(value,source,"$label.${source.getString("id")}",ignore)
        }
    }
    private fun coordinateError(point:JSONObject,prior:JSONObject,axis:String,delta:Float,path:String):Double{
        val expected=prior.getDouble(axis)+delta;val coordinate=point.getDouble(axis)
        val error=abs(coordinate-expected)
        check(coordinate.isFinite()&&error<=COORDINATE_TOLERANCE){
            "$path.$axis: translated $coordinate, expected $expected ±$COORDINATE_TOLERANCE"}
        return error
    }
    private fun assertTranslatedPage(actual:List<JSONObject>,original:List<JSONObject>,label:String,receipt:JSONObject){
        var maxXError=0.0;var maxYError=0.0
        check(actual.map{it.getString("id")}==original.map{it.getString("id")}){"$label: object order changed"}
        actual.zip(original).forEach{(value,source)->
            val path="$label.${source.getString("id")}"
            if(source.optString("type")!="stroke"||source.optBoolean("locked")||source.optBoolean("hidden")){
                assertJsonEqual(value,source,path)
            }else{
                assertJsonEqual(value,source,path,setOf("points","revision"))
                check(value.getLong("revision")>source.getLong("revision")){"$path: revision did not advance"}
                val points=value.getJSONArray("points");val before=source.getJSONArray("points")
                check(points.length()==before.length()){"$path: point count changed"}
                for(index in 0 until points.length()){
                    val point=points.getJSONObject(index);val prior=before.getJSONObject(index)
                    assertJsonEqual(point,prior,"$path.points[$index]",setOf("x","y"))
                    maxXError=maxOf(maxXError,coordinateError(point,prior,"x",DX,"$path.points[$index]"))
                    maxYError=maxOf(maxYError,coordinateError(point,prior,"y",DY,"$path.points[$index]"))
                }
            }
        }
        receipt.put("maxCoordinateErrorXPageUnits",maxXError).put("maxCoordinateErrorYPageUnits",maxYError)
    }
    private fun percentile(values: List<Long>, fraction: Double) =
        values.sorted()[(ceil(values.size * fraction).toInt() - 1).coerceAtLeast(0)] / 1e6
    private fun settle(repository: NoteRepository) {
        val done = CountDownLatch(1); repository.executor.execute { done.countDown() }
        check(done.await(60, TimeUnit.SECONDS)) { "Lasso object delta did not settle" }
    }

    fun run(activity: MainActivity, ui: (() -> Unit) -> Unit, result: Bundle, baseline: Boolean = false,
            eventCount: Int = MAX_EVENTS) {
        require(eventCount in 1..MAX_EVENTS) { "lassoEvents must be between 1 and $MAX_EVENTS" }
        val workload=json("selectedStrokes" to STROKES,"pointsPerStroke" to POINTS,
            "moveEvents" to eventCount,"defaultMoveEvents" to MAX_EVENTS,
            "samplesPerEvent" to SAMPLES_PER_EVENT,"moveSamples" to eventCount*SAMPLES_PER_EVENT,
            "workloadFraction" to eventCount.toDouble()/MAX_EVENTS,"warmupTrials" to 1,"measuredTrials" to 3,
            "coordinateTolerancePageUnits" to COORDINATE_TOLERANCE,"metadataComparison" to "structural_exact")
        result.putString("lassoDragWorkload",workload.toString())
        result.putString("lassoDragScope","production_View_MotionEvent_and_software_Canvas_512_strokes_32_points_${eventCount}_events_4_samples_no_GPU_or_physical_pen_latency")
        result.putString("lassoDragMode",if(baseline)"baseline-comparable" else "candidate-functional-and-performance")
        val repository = activity.repository
        val document = repository.create("lasso-drag-performance", "root", "blank")
        val page = requireNotNull(repository.page(repository.pageIds(document.id).first()))
        val original = fixture().map{it.copyJson()} // Compare the persisted JSON value types, not fixture-only Float wrappers.
        val trials = JSONArray()
        val allTrials=JSONArray()
        fun saveReceipt(phase:String){
            result.putString("lassoDragTrials",trials.toString())
            result.putString("lassoDragTrialDetails",allTrials.toString())
            val directory=java.io.File(activity.filesDir,"lasso-drag").apply{check(isDirectory||mkdirs())}
            java.io.File(directory,"trials.json").writeText(json("phase" to phase,"workload" to workload,
                "trialDetails" to allTrials).toString())
            result.putString("lassoDragReceipt","files/lasso-drag/trials.json")
        }
        // A non-inline trial scope releases all View/snapshot locals before the next trial begins.
        fun runTrial(trial:Int){
            page.objects.clear(); page.objects.addAll(original.map { it.copyJson() })
            repository.putPage(document.id, page, 0)
            val generation = repository.generation(page.id)
            lateinit var view: InkCanvasView
            lateinit var input: StylusContactChecks.Input
            var edits = 0
            ui {
                view = InkCanvasView(activity, repository)
                view.layout(0, 0, 1080, 1600); view.drawHold = false; view.scribbleErase = false
                view.visibility=android.view.View.INVISIBLE
                (activity.window.decorView as android.view.ViewGroup).addView(view,android.widget.FrameLayout.LayoutParams(1080,1600))
                view.open(listOf(page.id), listOf(page.meta))
            }
            try {
            // An attached View delivers open's posted page instead of retaining it in a detached action queue.
            settle(repository)
            ui {
                (view.parent as android.view.ViewGroup).removeView(view);view.visibility=android.view.View.VISIBLE
                view.replacePage(page)
                view.tool = InkCanvasView.Tool.LASSO; view.selectAll()
                view.onEdit = { id, change, positions ->
                    edits++; repository.executor.execute { repository.commit(document.id, id, change, positions) }
                }
                input = StylusContactChecks.Input(view)
                val bitmap = Bitmap.createBitmap(1080, 1600, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    view.draw(canvas) // Warm canonical layers before the timed contact.
                    val heapBeforeDown=Runtime.getRuntime().let{it.totalMemory()-it.freeMemory()}
                    val start = System.nanoTime(); val cpuStart = Debug.threadCpuTimeNanos()
                    input.touch(MotionEvent.ACTION_DOWN, 180f, 280f)
                    val downWall = System.nanoTime() - start; val downCpu = Debug.threadCpuTimeNanos() - cpuStart
                    val wall = mutableListOf<Long>(); val cpu = mutableListOf<Long>()
                    val drawWall = mutableListOf<Long>(); val drawCpu = mutableListOf<Long>()
                    val eventAndDraw = mutableListOf<Long>()
                    repeat(eventCount) { event ->
                        fun position(sample: Int) = (180f + DX * sample / (eventCount * SAMPLES_PER_EVENT)) to
                            (280f + DY * sample / (eventCount * SAMPLES_PER_EVENT))
                        val first = event * SAMPLES_PER_EVENT + 1
                        val samples = (first until first + SAMPLES_PER_EVENT).map(::position)
                        val motion = input.event(MotionEvent.ACTION_MOVE, samples.last().first, samples.last().second,
                            history = samples.dropLast(1))
                        val wallStart = System.nanoTime(); val moveCpuStart = Debug.threadCpuTimeNanos()
                        try { view.onTouchEvent(motion) } finally { motion.recycle() }
                        cpu += Debug.threadCpuTimeNanos() - moveCpuStart; wall += System.nanoTime() - wallStart
                        val drawStart = System.nanoTime(); val drawCpuStart = Debug.threadCpuTimeNanos()
                        view.draw(canvas)
                        drawCpu += Debug.threadCpuTimeNanos() - drawCpuStart; drawWall += System.nanoTime() - drawStart
                        eventAndDraw += System.nanoTime() - wallStart
                    }
                    check(edits == 0 && repository.generation(page.id) == generation) { "Drag persisted before lift" }
                    val trialReceipt=json("trial" to trial,"warmup" to (trial==0),"checksPassed" to false,
                        "moveWallP95Ms" to percentile(wall,.95),
                        "moveCpuP95Ms" to percentile(cpu, .95), "moveWallMedianMs" to percentile(wall, .5),
                        "moveWallMaxMs" to wall.max() / 1e6, "drawWallP95Ms" to percentile(drawWall, .95),
                        "drawCpuP95Ms" to percentile(drawCpu, .95), "eventAndDrawP95Ms" to percentile(eventAndDraw, .95),
                        "downWallMs" to downWall / 1e6, "downCpuMs" to downCpu / 1e6,
                        "moveEvents" to eventCount, "historicalSamples" to eventCount * (SAMPLES_PER_EVENT - 1),
                        "defaultMoveEvents" to MAX_EVENTS, "workloadFraction" to eventCount.toDouble() / MAX_EVENTS,
                        "selectedStrokes" to STROKES,"pointsPerStroke" to POINTS,
                        "heapBeforeDownBytes" to heapBeforeDown,
                        "heapBeforeUpBytes" to Runtime.getRuntime().let{it.totalMemory()-it.freeMemory()},
                        "maxHeapBytes" to Runtime.getRuntime().maxMemory())
                    allTrials.put(trialReceipt)
                    if(trial>0)trials.put(trialReceipt)
                    saveReceipt("trial-$trial-before-up")
                    val upStart=System.nanoTime();val upCpuStart=Debug.threadCpuTimeNanos()
                    input.touch(MotionEvent.ACTION_UP,180f+DX,280f+DY)
                    val upWall=System.nanoTime()-upStart;val upCpu=Debug.threadCpuTimeNanos()-upCpuStart
                    trialReceipt.put("upWallMs",upWall/1e6).put("upCpuMs",upCpu/1e6).put("editCount",edits)
                        .put("heapAfterUpBytes",Runtime.getRuntime().let{it.totalMemory()-it.freeMemory()})
                    saveReceipt("trial-$trial-after-up-before-checks")
                    check(edits==1){"Lasso committed $edits edits instead of one"}
                    assertTranslatedPage(page.objects,original,"contact",trialReceipt)
                    saveReceipt("trial-$trial-geometry-and-metadata-verified")
                } finally { bitmap.recycle() }
            }
            settle(repository)
            assertPageEqual(requireNotNull(repository.page(page.id)).objects,page.objects,"saved")
            ui { view.undo() }
            settle(repository)
            assertPageEqual(page.objects,original,"undo",restored=true)
            assertPageEqual(requireNotNull(repository.page(page.id)).objects,page.objects,"saved undo")
            val beforeCancel = page.objects.map{it.copyJson()}
            val cancelGeneration = repository.generation(page.id)
            ui {
                view.selectAll(); val beforeEdits = edits
                input.touch(MotionEvent.ACTION_DOWN, 180f, 280f)
                input.touch(MotionEvent.ACTION_MOVE, 230f, 320f, history = listOf(200f to 290f, 210f to 310f))
                input.touch(MotionEvent.ACTION_CANCEL, 240f, 330f)
                check(edits==beforeEdits){"Cancelled lasso committed an edit"}
                assertPageEqual(page.objects,beforeCancel,"cancel")
            }
            settle(repository)
            check(repository.generation(page.id)==cancelGeneration){"Cancelled lasso changed storage generation"}
            assertPageEqual(requireNotNull(repository.page(page.id)).objects,beforeCancel,"saved cancel")
            allTrials.getJSONObject(trial).put("checksPassed",true)
            saveReceipt("trial-$trial-persistence-undo-cancel-verified")
            }finally{ui{
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.close()
            }}
        }
        for(trial in 0 until 4)runTrial(trial)
        if(!baseline){
            visualAndSelectionChecks(activity,ui,result)
            LassoOwnershipChecks.run(activity,ui,result)
        }
        result.putString("lassoDragPersistence", "PASS: one edit, saved raw fields/order, one-step undo, cancel without generation change")
    }

    private fun visualAndSelectionChecks(activity: MainActivity, ui: (() -> Unit) -> Unit, result: Bundle) {
        val document = activity.repository.create("lasso-visual-order", "root", "blank")
        val page = requireNotNull(activity.repository.page(activity.repository.pageIds(document.id).first()))
        ui {
            val view = InkCanvasView(activity, activity.repository)
            view.layout(0, 0, 1080, 1600); view.drawHold = false; view.scribbleErase = false
            fun tape(id: String, x: Int, y: Int, color: String, locked: Boolean = false) =
                json("id" to id, "type" to "tape", "x" to x, "y" to y, "w" to 240, "h" to 100,
                    "color" to color, "locked" to locked)
            page.objects.addAll(listOf(tape("under", 200, 300, "#0000ff"),
                tape("middle", 250, 320, "#ff0000", true), tape("over", 300, 340, "#00ff00")))
            view.open(listOf(page.id), listOf(page.meta)); view.replacePage(page)
            view.tool = InkCanvasView.Tool.LASSO; view.selectAll()
            val input = StylusContactChecks.Input(view)
            val bitmap = Bitmap.createBitmap(1080, 1600, Bitmap.Config.ARGB_8888)
            val after = Bitmap.createBitmap(1080, 1600, Bitmap.Config.ARGB_8888)
            try {
                input.touch(MotionEvent.ACTION_DOWN, 210f, 310f)
                input.touch(MotionEvent.ACTION_MOVE, 250f, 350f)
                view.draw(Canvas(bitmap))
                input.touch(MotionEvent.ACTION_UP, 250f, 350f)
                view.draw(Canvas(after))
                val directory=java.io.File(activity.filesDir,"lasso-drag").apply{check(isDirectory||mkdirs())}
                java.io.File(directory,"preview.png").outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
                java.io.File(directory,"committed.png").outputStream().use{check(after.compress(Bitmap.CompressFormat.PNG,100,it))}
                result.putString("lassoDragPreview","files/lasso-drag/preview.png")
                result.putString("lassoDragCommitted","files/lasso-drag/committed.png")
                check(bitmap.sameAs(after)) { "Drag preview changed object stacking or moved locked selection" }
                check(page.objects.map { it.getString("id") } == listOf("under", "middle", "over"))
                check(abs(page.objects[0].f("x") - 240f) < .01f && page.objects[1].f("x") == 250f)
                view.undo()
                input.touch(MotionEvent.ACTION_DOWN, 180f, 280f)
                input.touch(MotionEvent.ACTION_MOVE, 550f, 280f)
                input.touch(MotionEvent.ACTION_MOVE, 550f, 460f)
                input.touch(MotionEvent.ACTION_MOVE, 180f, 460f)
                input.touch(MotionEvent.ACTION_UP, 180f, 280f)
                check(view.selected().map { it.getString("id") } == listOf("under", "middle", "over")) { "Indexed lasso changed exact selection/order" }
            } finally { bitmap.recycle(); after.recycle(); view.close() }
        }
    }
}
