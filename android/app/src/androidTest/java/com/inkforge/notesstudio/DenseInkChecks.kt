package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Debug
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.sin

/** Production View input and software raster; not physical pen, GPU, or display-latency evidence. */
object DenseInkChecks {
    private const val STROKES=2048
    private const val POINTS=20
    private const val CONTACTS=8
    private fun fixture():Sequence<JSONObject> = sequence {
        repeat(STROKES){stroke->
            if(stroke==1024)yield(json("id" to "image-boundary","type" to "image","src" to "",
                "x" to 900,"y" to 1200,"w" to 20,"h" to 20).copyJson())
            val x=40f+(stroke%64)*14;val y=80f+(stroke/64)*28
            yield(json("id" to "dense-$stroke","type" to "stroke","brush" to if(stroke==2)"highlighter" else "ballpoint",
                "color" to if(stroke==2)"#ffe066" else "#172033","width" to 2,"opacity" to if(stroke%11==0).32 else 1,
                "locked" to (stroke==0),"hidden" to (stroke==1),"revision" to 1,"captureSeq" to stroke+1,
                "captureSessionId" to "dense-fixture","timeBasis" to "session-monotonic",
                "vendorObject" to json("order" to JSONArray(listOf(3,1,2))),
                "points" to JSONArray((0 until POINTS).map{sample->json("x" to x+sample*.45f,
                    "y" to y+sin(sample*.3f)*.7f,"p" to .2f+sample*.02f,"t" to 1000L+stroke*100+sample,
                    "pointerId" to 7,"tilt" to .2,"orientation" to .4,"azimuth" to 1.1,"tx" to 0,
                    "ty" to JSONObject.NULL,"vendor" to json("sample" to sample))})).copyJson())
        }
        yield(json("id" to "sparse-precise","type" to "stroke","brush" to "ballpoint","width" to 2,
            "captureSeq" to STROKES+1,"captureSessionId" to "dense-fixture","revision" to 1,
            "points" to JSONArray(listOf(json("x" to 400,"y" to 1000,"p" to .3,"t" to 100,
                "ty" to JSONObject.NULL,"vendor" to json("raw" to 1)),json("x" to 600,"y" to 1000,
                "p" to .8,"t" to 300,"vendor" to json("raw" to 2))))).copyJson())
        // Wide, overlapping and rotated brush paths cross screen-tile boundaries at fractional scale.
        for((index,brush) in listOf("fountain","ballpoint","gel","brush","pencil","fineliner","highlighter").withIndex())
            yield(json("id" to "raster-$brush","type" to "stroke","brush" to brush,"color" to "#276aba",
                "width" to 12,"opacity" to .35,"rotation" to (index-3)*.035,
                "points" to JSONArray((0..16).map{sample->json("x" to 100+sample*31,
                    "y" to 1180+index*12+sin(sample*.7)*8,"p" to .2+sample*.04,"tx" to 35,"ty" to -20)})))
    }

    private fun same(actual:Any?,expected:Any?,path:String,ignoreRevision:Boolean=false){
        when{
            actual is JSONObject&&expected is JSONObject->{
                val ignored=if(ignoreRevision)setOf("revision") else emptySet()
                val keys=actual.keys().asSequence().toSet()-ignored
                check(keys==expected.keys().asSequence().toSet()-ignored){"$path fields changed"}
                for(key in keys)same(actual.get(key),expected.get(key),"$path.$key")
            }
            actual is JSONArray&&expected is JSONArray->{
                check(actual.length()==expected.length()){"$path array length changed"}
                for(i in 0 until actual.length())same(actual.get(i),expected.get(i),"$path[$i]")
            }
            actual is Number&&expected is Number->check(actual.toDouble()==expected.toDouble()){"$path number changed"}
            else->check(actual==expected){"$path changed"}
        }
    }
    /** Independent snapshots without retaining another full 40,960-point JSONObject tree per phase. */
    private class PageSnapshot(source:List<JSONObject>,previous:PageSnapshot?=null){
        val ids=source.map{it.getString("id")}
        val encoded:List<String> = source.map{obj->
            val text=obj.toString();val prior=previous?.encodedById?.get(obj.getString("id"))
            if(prior!=null&&prior==text)prior else text
        }
        private val encodedById:Map<String,String> = ids.indices.associate{ids[it] to encoded[it]}
        val size get()=ids.size
        fun objectWithId(id:String)=JSONObject(checkNotNull(encodedById[id]))
    }
    private fun samePage(actual:List<JSONObject>,expected:PageSnapshot,label:String,ignoreRevision:Boolean=false){
        check(actual.size==expected.size){"$label object count changed"}
        for(index in actual.indices){
            check(actual[index].getString("id")==expected.ids[index]){"$label object order changed at $index"}
            same(actual[index],JSONObject(expected.encoded[index]),"$label.${expected.ids[index]}",ignoreRevision)
        }
    }
    private fun settle(repository:NoteRepository){
        val done=CountDownLatch(1);repository.executor.execute{done.countDown()}
        check(done.await(60,TimeUnit.SECONDS)){"Dense ink save queue did not settle"}
    }
    private fun percentile(values:List<Long>,fraction:Double)=
        values.sorted()[(ceil(values.size*fraction).toInt()-1).coerceAtLeast(0)]/1e6
    private class Samples {
        val wall=ArrayList<Long>();val cpu=ArrayList<Long>()
        fun measure(action:()->Unit){
            val start=System.nanoTime();val cpuStart=Debug.threadCpuTimeNanos()
            action();cpu+=Debug.threadCpuTimeNanos()-cpuStart;wall+=System.nanoTime()-start
        }
        fun json()=json("count" to wall.size,"wallP95Ms" to percentile(wall,.95),
            "cpuP95Ms" to percentile(cpu,.95),"wallMaxMs" to wall.max()/1e6)
    }

    fun run(activity:MainActivity,ui:(()->Unit)->Unit,result:Bundle){
        val repository=activity.repository
        val document=repository.create("dense-ink-regression","root","blank")
        result.putString("denseInkDocumentId",document.id)
        fun phase(name:String){
            result.putString("denseInkPhase",name)
            result.putLong("denseInkUsedHeapBytes",Runtime.getRuntime().let{it.totalMemory()-it.freeMemory()})
        }
        phase("fixture")
        val page=requireNotNull(repository.page(repository.pageIds(document.id).first()))
        fixture().forEach{page.objects.add(it)}
        repository.putPage(document.id,page,0)
        val original=PageSnapshot(page.objects)
        lateinit var view:InkCanvasView
        lateinit var input:StylusContactChecks.Input
        var edits=0
        val receipt=json("fixtureStrokes" to STROKES,"pointsPerStroke" to POINTS,"penContacts" to CONTACTS,
            "scope" to "production_View_MotionEvent_and_software_Canvas_no_GPU_or_physical_pen_latency",
            "syntheticDocumentId" to document.id,"syntheticDocumentRetained" to true)
        ui{
            view=InkCanvasView(activity,repository);view.layout(0,0,1080,1600)
            view.drawHold=false;view.scribbleErase=false;view.visibility=View.INVISIBLE
            (activity.window.decorView as ViewGroup).addView(view,FrameLayout.LayoutParams(1080,1600))
            view.open(listOf(page.id),listOf(page.meta))
        }
        try{
            settle(repository)
            phase("view-loading-complete")
            ui{
                (view.parent as ViewGroup).removeView(view);view.visibility=View.VISIBLE;view.replacePage(page)
                input=StylusContactChecks.Input(view)
                view.onEdit={id,change,positions->edits++;repository.executor.execute{repository.commit(document.id,id,change,positions)}}
            }
            val down=Samples();val move=Samples();val draw=Samples();val up=Samples()
            val eraseDown=Samples();val eraseMove=Samples();val eraseDraw=Samples();val eraseUp=Samples()
            val penMoveDraw=Samples();val penCommitDraw=Samples()
            val eraserMoveDraw=Samples();val eraserCommitDraw=Samples()
            var penMoveReplayObjects=0L;var penCommitReplayObjects=0L
            ui{
                val actual=Bitmap.createBitmap(1080,1600,Bitmap.Config.ARGB_8888)
                val expected=Bitmap.createBitmap(1080,1600,Bitmap.Config.ARGB_8888)
                try{
                    val canvas=Canvas(actual)
                    fun drawNow(){actual.eraseColor(Color.TRANSPARENT);view.draw(canvas)}
                    fun raster(label:String,cursor:Pair<Float,Float>?=null){
                        drawNow();expected.eraseColor(0xfff7f7f8.toInt())
                        val reference=Canvas(expected);val origin=view.screenPoint(0f,0f)
                        reference.save();reference.translate(origin.x,origin.y);reference.scale(view.scale,view.scale)
                        reference.clipRect(0f,0f,page.width,page.height)
                        val renderer=NoteRenderer();renderer.template(reference,page)
                        page.objects.forEach{renderer.draw(reference,it,view.scale.toDouble())};reference.restore()
                        // The editor's page-edge selection border is an intentional overlay, not document ink.
                        val left=maxOf(0,ceil(origin.x+4).toInt());val top=maxOf(0,ceil(origin.y+4).toInt())
                        val right=minOf(actual.width,(origin.x+page.width*view.scale-4).toInt())
                        val bottom=minOf(actual.height,(origin.y+page.height*view.scale-4).toInt())
                        check(right>left&&bottom>top)
                        val rowA=IntArray(right-left);val rowB=IntArray(right-left)
                        val cursorPoint=cursor?.let{view.screenPoint(it.first,it.second)}
                        val cursorRadius=view.eraserRadius+3f
                        for(y in top until bottom){
                            actual.getPixels(rowA,0,rowA.size,left,y,rowA.size,1)
                            expected.getPixels(rowB,0,rowB.size,left,y,rowB.size,1)
                            val same=if(cursorPoint==null||kotlin.math.abs(y-cursorPoint.y)>cursorRadius)rowA.contentEquals(rowB)
                                else rowA.indices.all{x->kotlin.math.abs(left+x-cursorPoint.x)<=cursorRadius||rowA[x]==rowB[x]}
                            check(same){
                                val x=rowA.indices.first{offset->rowA[offset]!=rowB[offset]&&
                                    (cursorPoint==null||kotlin.math.abs(y-cursorPoint.y)>cursorRadius||
                                        kotlin.math.abs(left+offset-cursorPoint.x)>cursorRadius)}
                                "$label: cached scene alpha/order raster changed at ${left+x},$y: "+
                                    "${Integer.toHexString(rowA[x])} != ${Integer.toHexString(rowB[x])}"
                            }
                        }
                    }
                    raster("initial")
                    phase("warm-raster")
                    val warmCommands=view.recordedCommandObjects
                    fun cacheSnapshot()=longArrayOf(view.replayedCommandObjects,view.rasterColdScenes,view.rasterCacheHits,view.rasterizedTiles)
                    fun cacheDelta(before:LongArray)=json("replayedObjects" to view.replayedCommandObjects-before[0],
                        "coldScenes" to view.rasterColdScenes-before[1],"tileHits" to view.rasterCacheHits-before[2],
                        "rasterizedTiles" to view.rasterizedTiles-before[3])
                    val penCacheBefore=cacheSnapshot()
                    repeat(CONTACTS){contact->
                        view.tool=if(contact%2==0)InkCanvasView.Tool.PEN else InkCanvasView.Tool.HIGHLIGHTER
                        view.brush="ballpoint";val y=1060f+contact*12
                        down.measure{input.touch(MotionEvent.ACTION_DOWN,180f,y)}
                        repeat(2){event->
                            val start=180f+event*20
                            move.measure{input.touch(MotionEvent.ACTION_MOVE,start+20,y,
                                history=listOf(start+5 to y,start+10 to y,start+15 to y))}
                            val replayBefore=view.replayedCommandObjects
                            draw.measure{penMoveDraw.measure{drawNow()}}
                            penMoveReplayObjects+=view.replayedCommandObjects-replayBefore
                        }
                        up.measure{input.touch(MotionEvent.ACTION_UP,225f,y)}
                        val replayBefore=view.replayedCommandObjects
                        draw.measure{penCommitDraw.measure{drawNow()}}
                        penCommitReplayObjects+=view.replayedCommandObjects-replayBefore
                        val points=page.objects.last().getJSONArray("points")
                        check(points.length()==10){"Dense pen contact dropped historical/current samples"}
                        for(i in 0 until points.length()){
                            val p=points.getJSONObject(i)
                            check(p.getInt("pointerId")==7&&kotlin.math.abs(p.getDouble("p")-.65)<.00001)
                            if(i>0)check(p.getLong("t")>points.getJSONObject(i-1).getLong("t"))
                        }
                    }
                    phase("pen-contacts-complete")
                    check(edits==CONTACTS){"Pen contacts did not publish one edit each"}
                    val penCommands=view.recordedCommandObjects-warmCommands
                    receipt.put("penSceneCache",cacheDelta(penCacheBefore))
                    samePage(page.objects.take(original.size),original,"pen untouched originals")
                    raster("pen alpha and ordering")
                    receipt.put("penRecordedObjects",penCommands).put("warmRecordedObjects",warmCommands)
                    val beforeErase=PageSnapshot(page.objects,original)
                    view.tool=InkCanvasView.Tool.ERASER;view.eraserRadius=9f
                    val eraseCommandsBefore=view.recordedCommandObjects
                    val eraserCacheBefore=cacheSnapshot()
                    eraseDown.measure{input.touch(MotionEvent.ACTION_DOWN,82f,80f)}
                    repeat(8){event->
                        val x=100f+event*25
                        eraseMove.measure{input.touch(MotionEvent.ACTION_MOVE,x,80f,history=listOf(x-12 to 80f,x-6 to 80f))}
                        eraseDraw.measure{eraserMoveDraw.measure{drawNow()}}
                    }
                    raster("whole erase before lift",275f to 80f)
                    eraseUp.measure{input.touch(MotionEvent.ACTION_UP,290f,80f)}
                    eraseDraw.measure{eraserCommitDraw.measure{drawNow()}}
                    check(edits==CONTACTS+1){"One eraser contact published multiple edits"}
                    check(page.objects.size<beforeErase.size){"Dense whole-stroke eraser did not hit ink"}
                    for(id in listOf("dense-0","dense-1","dense-2"))same(page.objects.first{it.optString("id")==id},
                        beforeErase.objectWithId(id),"protected $id")
                    val eraseCommands=view.recordedCommandObjects-eraseCommandsBefore
                    receipt.put("eraserSceneCache",cacheDelta(eraserCacheBefore))
                    receipt.put("inContactEraseRaster","whole-stroke and precise replacement checked before lift")
                    receipt.put("eraserRecordedObjects",eraseCommands)
                    raster("whole erase")
                    phase("whole-erase-complete")
                    view.undo();samePage(page.objects,beforeErase,"whole erase undo",true);raster("whole erase undo")
                    view.redo();raster("whole erase redo")
                    view.undo();samePage(page.objects,beforeErase,"whole erase second undo",true)
                    val beforeCancel=PageSnapshot(page.objects,beforeErase);val cancelEdits=edits
                    input.touch(MotionEvent.ACTION_DOWN,100f,108f)
                    input.touch(MotionEvent.ACTION_MOVE,260f,108f,history=listOf(140f to 108f,200f to 108f))
                    drawNow();input.touch(MotionEvent.ACTION_CANCEL,260f,108f)
                    check(edits==cancelEdits){"Cancelled eraser published an edit"}
                    samePage(page.objects,beforeCancel,"erase cancel");raster("erase cancel")
                    view.preciseEraser=true;view.eraserRadius=3f
                    val beforePrecise=PageSnapshot(page.objects,beforeCancel);val preciseEdits=edits
                    input.touch(MotionEvent.ACTION_DOWN,500f,1000f)
                    raster("precise erase before lift",500f to 1000f)
                    input.touch(MotionEvent.ACTION_UP,500f,1000f)
                    check(edits==preciseEdits+1)
                    val fragments=page.objects.filter{it.optString("sourceStrokeId")=="sparse-precise"}
                    check(fragments.size==2){"Dense precise erase did not split the sparse stroke"}
                    val source=beforePrecise.objectWithId("sparse-precise")
                    same(fragments.first().getJSONArray("points").getJSONObject(0),source.getJSONArray("points").getJSONObject(0),"precise first raw point")
                    same(fragments.last().getJSONArray("points").getJSONObject(fragments.last().getJSONArray("points").length()-1),
                        source.getJSONArray("points").getJSONObject(1),"precise last raw point")
                    raster("precise erase")
                    view.undo();samePage(page.objects,beforePrecise,"precise undo",true);raster("precise undo")
                    val preciseCancel=PageSnapshot(page.objects,beforePrecise);val preciseCancelEdits=edits
                    input.touch(MotionEvent.ACTION_DOWN,500f,1000f);drawNow()
                    input.touch(MotionEvent.ACTION_CANCEL,500f,1000f)
                    check(edits==preciseCancelEdits);samePage(page.objects,preciseCancel,"precise cancel");raster("precise cancel")
                    phase("precise-erase-and-cancel-complete")
                    receipt.put("spatialIndexBuilds",view.spatialIndexBuilds).put("recordedObjectsFinal",view.recordedCommandObjects)
                    view.tool=InkCanvasView.Tool.PEN
                    val cancelPenEdits=edits
                    input.touch(MotionEvent.ACTION_DOWN,700f,1100f)
                    input.touch(MotionEvent.ACTION_MOVE,730f,1100f,history=listOf(710f to 1100f,720f to 1100f))
                    input.touch(MotionEvent.ACTION_CANCEL,730f,1100f)
                    check(edits==cancelPenEdits)
                    samePage(page.objects,preciseCancel,"pen cancel")
                    val hit=InkBounds(680f,1300f,740f,1330f)
                    view.zoomBy(2f);raster("zoomed scene");view.showSearchHighlights(page.id,listOf(hit));drawNow()
                    fun highlightPixel():Int{
                        val p=view.screenPoint(710f,1315f)
                        check(p.x in 1f..1078f&&p.y in 1f..1598f){"Search hit was not brought into the viewport"}
                        return actual.getPixel(p.x.toInt(),p.y.toInt())
                    }
                    val highlighted=highlightPixel()
                    check(Color.red(highlighted)>220&&Color.blue(highlighted)<230){"Search bounds were not drawn as an overlay"}
                    view.zoomBy(.85f);drawNow()
                    check(Color.blue(highlightPixel())<230){"Search overlay lost the page-coordinate zoom transform"}
                    view.clearSearchHighlights();raster("search cleared after zoom")
                    check(highlightPixel()==Color.WHITE){"Search overlay was baked into cached page commands"}
                    view.showSearchHighlights(page.id,listOf(hit))
                    input.touch(MotionEvent.ACTION_DOWN,710f,1315f)
                    input.touch(MotionEvent.ACTION_CANCEL,710f,1315f);drawNow()
                    check(highlightPixel()==Color.WHITE){"Editing left a stale search highlight"}
                    samePage(page.objects,preciseCancel,"search overlay preserves objects")
                    view.resetZoom();raster("zoom reset");receipt.put("searchOverlay","page-coordinate zoom/reveal/clear/edit raster checked")
                }finally{actual.recycle();expected.recycle()}
            }
            settle(repository)
            val cancelGeneration=repository.generation(page.id)
            val cancelSnapshot=PageSnapshot(page.objects,original);val settledEdits=edits
            ui{
                view.tool=InkCanvasView.Tool.PEN
                input.touch(MotionEvent.ACTION_DOWN,700f,1100f);input.touch(MotionEvent.ACTION_CANCEL,700f,1100f)
                view.tool=InkCanvasView.Tool.ERASER
                input.touch(MotionEvent.ACTION_DOWN,500f,1000f);input.touch(MotionEvent.ACTION_CANCEL,500f,1000f)
                check(edits==settledEdits);samePage(page.objects,cancelSnapshot,"settled cancels")
            }
            settle(repository)
            check(repository.generation(page.id)==cancelGeneration){"Cancelled contacts changed persisted generation"}
            val saved=requireNotNull(repository.page(page.id))
            samePage(saved.objects,cancelSnapshot,"saved dense delta")
            receipt.put("penDown",down.json()).put("penMove",move.json()).put("penDraw",draw.json()).put("penUp",up.json())
                .put("eraserDown",eraseDown.json()).put("eraserMove",eraseMove.json()).put("eraserDraw",eraseDraw.json()).put("eraserUp",eraseUp.json())
                .put("publishedEdits",edits).put("savedObjects",saved.objects.size).put("checksPassed",true)
            receipt.put("penMoveDraw",penMoveDraw.json()).put("penPostUpDraw",penCommitDraw.json())
                .put("eraserMoveDraw",eraserMoveDraw.json()).put("eraserPostUpDraw",eraserCommitDraw.json())
                .put("penMoveReplayObjects",penMoveReplayObjects).put("penPostUpReplayObjects",penCommitReplayObjects)
            phase("asset-raster")
            result.putString("denseInkAssets",DenseInkAssetChecks.run(activity,ui))
            phase("complete")
            result.putString("denseInk",receipt.toString())
            result.putString("denseInkPreservation","PASS: raw fields/history/order, alpha raster, protected ink, erase split, one-contact undo/redo/cancel, saved deltas")
        }finally{ui{(view.parent as? ViewGroup)?.removeView(view);view.close()}}
    }
}
