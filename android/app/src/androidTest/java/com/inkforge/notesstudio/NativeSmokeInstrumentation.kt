package com.inkforge.notesstudio

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.webkit.WebView
import android.view.ViewGroup

/** Runs in com.inkforge.note4.debug only; release app data is never read or changed. */
class NativeSmokeInstrumentation:Instrumentation(){
    private lateinit var activity:MainActivity
    private lateinit var repository:NoteRepository
    private var downTime=0L
    private var uiOnly=false
    private var migrationOnly=false
    private var legacyOnly=false
    private var ocrOnly=false
    private var pdfOnly=false
    private var s3Only=false
    private var s5aOnly=false
    private var s5bOnly=false
    private var s5aBitmapOnly=false
    private var brushRasterOnly=false
    private var brushRasterTransparent=false
    private var s5cOnly=false
    private var mediaOnly=false
    private var pdfStressMiB:Int?=null
    private var pdfStressCancel=false
    private var w3Only=false
    private var w3RunId:String?=null
    private var replayArguments:Bundle?=null
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);uiOnly=arguments?.getString("uiOnly")=="true";migrationOnly=arguments?.getString("migrationOnly")=="true";ocrOnly=arguments?.getString("ocrOnly")=="true";pdfOnly=arguments?.getString("pdfOnly")=="true";s3Only=arguments?.getString("s3Only")=="true";s5aOnly=arguments?.getString("s5aOnly")=="true";s5bOnly=arguments?.getString("s5bOnly")=="true";s5aBitmapOnly=arguments?.getString("s5aBitmapOnly")=="true";brushRasterOnly=arguments?.getString("brushRasterOnly")=="true";brushRasterTransparent=arguments?.getString("brushRasterTransparent")=="true";s5cOnly=arguments?.getString("s5cOnly")=="true";mediaOnly=arguments?.getString("mediaOnly")=="true";pdfStressMiB=arguments?.getString("pdfStressMiB")?.toIntOrNull();pdfStressCancel=arguments?.getString("pdfStressCancel")=="true";w3Only=arguments?.getString("w3Only")=="true";w3RunId=arguments?.getString("w3RunId");replayArguments=arguments?.takeIf{it.containsKey("ocrInput")};legacyOnly=arguments?.getString("legacyOnly")=="true";start()}
    private fun <T> ui(block:()->T):T{val value=AtomicReference<T>();val failure=AtomicReference<Throwable>();runOnMainSync{try{value.set(block())}catch(e:Throwable){failure.set(e)}};failure.get()?.let{throw it};return value.get()}
    private fun await(label:String,condition:()->Boolean){val end=SystemClock.uptimeMillis()+30000;while(SystemClock.uptimeMillis()<end){if(condition())return;SystemClock.sleep(50)};error("Timeout: $label")}
    private fun check(value:Boolean,message:String){if(!value)throw AssertionError(message)}
    override fun onStart(){
        val result=Bundle()
        try{
            check(targetContext.packageName.endsWith(".debug"),"Refusing to test a release app")
            if(w3Only){val receipt=W3NotebookChecks.run(targetContext,this,requireNotNull(w3RunId))
                result.putString("w3","300_persisted_w1_pages_lazy_viewport_ocr_verified")
                result.putString("w3RunId",receipt.runId);result.putInt("w3PersistedPages",receipt.pages)
                result.putInt("w3PersistedStrokes",receipt.strokes);result.putInt("w3PersistedPoints",receipt.points)
                result.putInt("w3OcrPageStrokes",receipt.ocrPageStrokes);result.putString("w3OcrStatus",receipt.ocrStatus)
                result.putInt("w3FirstLoadedPages",receipt.firstLoaded);result.putInt("w3LastLoadedPages",receipt.lastLoaded)
                result.putInt("w3PssKb",receipt.pssKb);result.putString("w3PssScope","emulator_process_after_300_persisted_pages_and_one_ocr")
                result.putLong("w3TotalMs",receipt.totalMs);result.putString("passed","true");finish(0,result);return}
            pdfStressMiB?.let{mib->val receipt=PdfStressChecks.run(targetContext,this,mib,pdfStressCancel)
                result.putString("pdfStress",if(pdfStressCancel)"original_${mib}MiB_cancelled_deleted_no_resurrection" else "original_${mib}MiB_full_index_tiles_delete_verified")
                result.putInt("pdfStressPages",receipt.pages);result.putLong("pdfStressFirstReadyMs",receipt.firstReadyMs)
                result.putLong("pdfStressFullIndexMs",receipt.fullIndexMs);result.putInt("pdfStressFdDelta",receipt.fdDelta)
                result.putString("passed","true");finish(0,result);return}
            if(mediaOnly){MediaCompatibilityChecks.run(targetContext);result.putString("passed","true");result.putString("media","containers_mime_async_lifecycle_verified");finish(0,result);return}
            if(brushRasterOnly){BrushRasterChecks.run(targetContext,brushRasterTransparent);result.putString("passed","true")
                result.putString(if(brushRasterTransparent)"brushRasterAlpha" else "brushRaster",
                    if(brushRasterTransparent)"36_native_transparent_cases_written" else "36_native_bitmap_cases_written")
                finish(0,result);return}
            if(s5aBitmapOnly){bitmapBrushChecks();result.putString("passed","true");result.putString("s5aBitmap","all_brushes_active_committed_pixels_verified");finish(0,result);return}
            replayArguments?.let{result.putString("ocrReplayOutput",OcrReplayChecks.run(targetContext,it));result.putString("ocrReplay","actual_debug_replay_saved");result.putString("passed","true");finish(0,result);return}
            if(pdfOnly){PdfIntegrationChecks.run(targetContext,this);result.putString("passed","true");result.putString("pdf","api23plus_original_text_import_legacy_roundtrip_lifecycle_verified");finish(0,result);return}
            if(ocrOnly){RecognitionServiceTaskChecks.run();result.putString("ocrTasks","real_task_permit_timeout_recovery_failure_verified");result.putString("passed","true");finish(0,result);return}
            if(s3Only){S3ClosureChecks.run(targetContext,this);result.putString("passed","true");result.putString("s3Closure","held_save_digest_bookmark_phone_status_verified");finish(0,result);return}
            DatabaseMigrationChecks.run(targetContext)
            if(migrationOnly){result.putString("migration","v1_to_v2_records_null_keys_generation_correction_verified");result.putString("passed","true");finish(0,result);return}
            NoteRepository(targetContext).use{it.setting("legacy-migration-complete","true",true);it.setting("preferences","{\"autoOcr\":false,\"drawHold\":false,\"scribbleErase\":false,\"stylusOnly\":true}",true)}
            activity=startActivitySync(Intent(Intent.ACTION_MAIN).setClassName(targetContext.packageName,MainActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            repository=activity.repository
            if(legacyOnly){migrateLegacyFixture(result);result.putString("legacyMigration","blob_origins_mime_bytes_failure_cleanup_verified");result.putString("passed","true");finish(0,result);return}
            val doc=repository.create("네이티브 필기 검증","root","grid")
            val page=requireNotNull(repository.page(repository.pageIds(doc.id).first()))
            fun stroke(id:String,y:Int)=json("id" to id,"type" to "stroke","brush" to "fountain","width" to 4,"color" to "#172033","points" to JSONArray(listOf(InkPoint(450f,y.toFloat()).json(),InkPoint(550f,y.toFloat()).json())))
            if(s5aOnly)page.objects.addAll(listOf(stroke("target",300),stroke("distant",900),
                stroke("locked",300).put("locked",true),stroke("highlight",300).put("brush","highlighter"),
                json("id" to "outline","type" to "shape","shape" to "heartshape","x1" to 100,"y1" to 100,
                    "x2" to 300,"y2" to 300,"width" to 2),
                json("id" to "sparse","type" to "stroke","brush" to "fountain","width" to 4,
                    "color" to "#172033","points" to JSONArray(listOf(InkPoint(100f,600f).json(),InkPoint(900f,600f).json())))))
            else page.objects.addAll(listOf(stroke("first",450),stroke("second",600)))
            repository.transaction{repository.putPage(doc.id,page,0)}
            ui{activity.openDocument(doc.id)}
            await("native editor ready"){ui{activity.inkView?.currentPage?.id==page.id&&activity.inkView!!.width>0}}
            if(s5cOnly){rawCaptureChecks(page.id);S5cOcrChecks.run(targetContext);result.putString("passed","true");result.putString("s5c","raw_samples_ocr_cancel_correction_model_generation_verified");finish(0,result);return}
            if(s5bOnly){
                if(android.os.Build.VERSION.SDK_INT>=31){s5bChecks(result);result.putString("s5b","front_multi_pixel_handoff_fallback_lifecycle_verified")}
                else{s5bFallbackChecks(result);result.putString("s5bFallback","api23_30_canvas_only_pixels_cancel_zoom_verified")}
                result.putString("passed","true");finish(0,result);return
            }
            if(s5aOnly){s5aChecks(page.id,result);result.putString("passed","true");result.putString("s5a","single_contact_edit_spatial_erase_bitmap_verified");finish(0,result);return}
            ui{activity.inkView!!.tool=InkCanvasView.Tool.PEN}
            motion(MotionEvent.ACTION_HOVER_MOVE,500f,450f,32)
            check(ui{activity.inkView!!.currentPage!!.objects.size==2},"Hover must not erase")
            motion(MotionEvent.ACTION_DOWN,500f,450f,32)
            check(ui{activity.inkView!!.currentPage!!.objects.none{it.optString("id")=="first"}},"S Pen button down erases")
            motion(MotionEvent.ACTION_MOVE,500f,600f,32)
            check(ui{activity.inkView!!.currentPage!!.objects.isEmpty()},"Held S Pen button erases along path")
            motion(MotionEvent.ACTION_MOVE,600f,700f,0)
            motion(MotionEvent.ACTION_MOVE,700f,740f,0)
            motion(MotionEvent.ACTION_UP,750f,760f,0)
            check(ui{activity.inkView!!.currentPage!!.objects.size==1},"Button release resumes ink on same contact")
            await("ink saved"){repository.page(page.id)?.objects?.size==1}
            motion(MotionEvent.ACTION_DOWN,200f,300f,0);motion(MotionEvent.ACTION_MOVE,250f,320f,0);motion(MotionEvent.ACTION_CANCEL,250f,320f,0)
            check(ui{activity.inkView!!.currentPage!!.objects.size==1},"Cancelled contact must not commit ink")
            ui{activity.inkView!!.undo()}
            check(ui{activity.inkView!!.currentPage!!.objects.size==2},"One undo restores both original strokes and removes resumed ink")
            ui{activity.inkView!!.redo()}
            check(ui{activity.inkView!!.currentPage!!.objects.size==1},"One redo restores mixed-contact erase and ink")
            motion(MotionEvent.ACTION_DOWN,200f,300f,0,MotionEvent.TOOL_TYPE_FINGER)
            motion(MotionEvent.ACTION_MOVE,250f,320f,0,MotionEvent.TOOL_TYPE_FINGER)
            motion(MotionEvent.ACTION_UP,250f,320f,0,MotionEvent.TOOL_TYPE_FINGER)
            check(ui{activity.inkView!!.currentPage!!.objects.size==1},"Finger cannot write in stylus-only mode")
            ui{activity.inkView!!.goTo(0);activity.inkView!!.preciseEraser=true
                activity.inkView!!.add(stroke("sparse",450))}
            motion(MotionEvent.ACTION_DOWN,500f,450f,32);motion(MotionEvent.ACTION_UP,500f,450f,32)
            check(ui{activity.inkView!!.currentPage!!.objects.size==3},"Precise eraser splits sparse stroke")
            result.putString("stylus","PASS: hover, same-contact pen/eraser as one undo, cancel, finger rejection, partial erase")
            await("partial erase persisted"){repository.page(page.id)?.objects?.size==3}
            ui{activity.inkView!!.undo()}
            await("partial erase undo persisted"){repository.page(page.id)?.objects?.size==2}
            scratchAndClassicUi(result)
            CompatibilityChecks.run(this,activity,result)
            ui{activity.openDocument(doc.id)}
            await("original document ready"){ui{activity.inkView?.currentPage?.id==page.id}}
            if(uiOnly){result.putString("ui","compose_library_settings_accessibility_navigation_verified")
                result.putString("passed","true");finish(0,result);return}

            doc.data.put("lastPageId",page.id).put("outline",JSONArray(listOf(json("pageId" to page.id,"text" to "목차"))))
            repository.putDocument(doc)

            val note=File(targetContext.cacheDir,"roundtrip.ifnote");repository.export(doc.id,note)
            val imported=note.inputStream().use{repository.importNote(it,"root")}
            val importedPageId=repository.pageIds(imported.id).first()
            check(repository.page(importedPageId)!!.objects.size==2,"Archive roundtrip preserves editable strokes")
            check(imported.data.getString("lastPageId")==importedPageId&&imported.data.array("outline").getJSONObject(0).getString("pageId")==importedPageId,"Archive page links remapped")
            val legacyFile="""{"title":"구형 파일 링크","lastPageId":"p2","outline":[{"pageId":"p1"}],"audio":[{"pageId":"p2"}],"pages":[{"id":"p1","objects":[]},{"id":"p2","objects":[{"id":"link","type":"text","targetPageId":"p1"}]}]}"""
            val legacyImport=legacyFile.byteInputStream().use{repository.importNote(it,"root")}
            val legacyIds=repository.pageIds(legacyImport.id)
            check(legacyImport.data.getString("lastPageId")==legacyIds[1]&&legacyImport.data.array("audio").getJSONObject(0).getString("pageId")==legacyIds[1],"Legacy file page links remapped")
            check(repository.page(legacyIds[1])!!.objects.first().getString("targetPageId")==legacyIds[0],"Legacy cross-page links remapped")
            val pdf=File(targetContext.cacheDir,"roundtrip.pdf");StreamingPdf.write(repository,doc.id,pdf){false}
            PdfRenderer(ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY)).use{renderer->
                check(renderer.pageCount==1,"PDF page count");renderer.openPage(0).use{p->val b=Bitmap.createBitmap(300,424,Bitmap.Config.ARGB_8888);p.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);check(b.getPixel(0,0)!=Color.TRANSPARENT,"PDF renders");b.recycle()}}
            val pdfDoc=pdf.inputStream().use{PdfImporter.import(repository,it,"원본 PDF 검증","root")}
            val pdfPage=repository.page(repository.pageIds(pdfDoc.id).first())!!
            check(pdfPage.meta.optString("pdfSource").startsWith("asset:"),"PDF original retained")
            check(repository.asset(pdfPage.meta.getString("pdfSource").removePrefix("asset:")).length()==pdf.length(),"PDF retained byte-for-byte")
            result.putString("files","PASS: native archive roundtrip; PDF generation, rendering, original retention")

            // Exercise the production repository export with a 300 MB immutable asset.
            val stress=repository.create("300 MB 내보내기 검증","root","blank")
            val stressPage=repository.page(repository.pageIds(stress.id).first())!!
            val assetId=uid("stress")+".bin";val source=repository.asset(assetId)
            val expected=MessageDigest.getInstance("SHA-256");val block=ByteArray(65536);val random=Random(77)
            source.outputStream().buffered().use{out->repeat(300*16){random.nextBytes(block);out.write(block);expected.update(block)}}
            stressPage.objects+=json("id" to "asset","type" to "image","src" to "asset:$assetId","x" to 0,"y" to 0,"w" to 100,"h" to 100)
            repository.transaction{repository.putPage(stress.id,stressPage,0)}
            val shareDirectory=File(targetContext.cacheDir,"shared").apply{mkdirs()}
            val archive=File(shareDirectory,"stress.ifnote");repository.export(stress.id,archive)
            // File preparation took many seconds; native sharing still grants a readable URI.
            val share=NativeFileExport.shareIntent(activity,archive)
            check(share.action==Intent.ACTION_SEND&&share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0,"Native share read grant")
            val uri=share.clipData!!.getItemAt(0).uri
            targetContext.contentResolver.openInputStream(uri)!!.use{check(it.read()==80&&it.read()==75,"Shared provider URI is a readable ZIP")}
            result.putString("share","PASS: delayed native share intent, content URI, read grant")
            val restore=archive.inputStream().use{repository.importNote(it,"root")}
            val ref=repository.page(repository.pageIds(restore.id).first())!!.objects.first().getString("src")
            val actual=MessageDigest.getInstance("SHA-256");repository.asset(ref.removePrefix("asset:")).inputStream().use{input->while(true){val n=input.read(block);if(n<0)break;actual.update(block,0,n)}}
            check(expected.digest().contentEquals(actual.digest()),"300 MB production export/import SHA-256")
            result.putString("stress","PASS: 300 MiB production repository export/import, SHA-256 match; max heap ${Runtime.getRuntime().maxMemory()/1024/1024} MiB")
            source.delete();archive.delete();repository.asset(ref.removePrefix("asset:")).delete();repository.deleteDocument(stress.id);repository.deleteDocument(restore.id)
            migrateLegacyFixture(result)
            ui{activity.openDocument(doc.id)}
            await("editor restored"){ui{activity.inkView?.currentPage?.id==page.id}}
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await("native back returns to library"){ui{activity.inkView==null&&!activity.isFinishing}}
            result.putString("navigation","PASS: native back callback returns to library")
            ui{activity.openDocument(doc.id)}
            await("editor reopened"){ui{activity.inkView?.currentPage?.id==page.id}}
            result.putString("passed","true");finish(0,result)
        }catch(e:Throwable){result.putString("passed","false");result.putString("error",e.stackTraceToString());finish(Activity.RESULT_CANCELED,result)}
    }
    private fun scratchAndClassicUi(result:Bundle){
        val doc=repository.create("새 노트","root","grid");val pageId=repository.pageIds(doc.id).first()
        ui{activity.openDocument(doc.id)};await("scratch document"){ui{activity.inkView?.currentPage?.id==pageId}}
        fun fixture(angle:Double){ui{
            val v=activity.inkView!!;v.changeObjects(v.currentPage!!.objects.toList(),emptyList())
            val dx=kotlin.math.cos(angle).toFloat();val dy=kotlin.math.sin(angle).toFloat()
            val points=JSONArray(listOf(InkPoint(500f-dy*32,400f+dx*32).json(),InkPoint(500f+dy*32,400f-dx*32).json()))
            val target=json("id" to "scratch-target","type" to "stroke","brush" to "fountain","width" to 4,"points" to points)
            v.add(target);v.add(target.copyJson().put("id","locked").put("locked",true));v.add(target.copyJson().put("id","highlight").put("brush","highlighter"))
            v.add(json("id" to "text","type" to "text","text" to "보존","x" to 480,"y" to 385,"w" to 40,"h" to 40))
            v.preciseEraser=false;v.tool=InkCanvasView.Tool.PEN;v.scribbleErase=true;v.drawHold=true
        }}
        fun scratch(angle:Double){ui{
            val view=activity.inkView!!;val start=SystemClock.uptimeMillis();var tick=0L
            val props=arrayOf(MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS})
            fun coords(x:Float,y:Float):Array<MotionEvent.PointerCoords>{val p=view.screenPoint(x,y);return arrayOf(MotionEvent.PointerCoords().apply{this.x=p.x;this.y=p.y;pressure=.6f})}
            fun event(action:Int,x:Float,y:Float)=MotionEvent.obtain(start,start+tick++,action,1,props,coords(x,y),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
            val dx=kotlin.math.cos(angle).toFloat();val dy=kotlin.math.sin(angle).toFloat()
            val points=(0..5).flatMap{pass->(0..160).map{i->val x=if(pass%2==0)-65+i*130/160f else 65-i*130/160f;InkPoint(500+x*dx,400+x*dy)}}
            event(MotionEvent.ACTION_DOWN,points[0].x,points[0].y).also{view.onTouchEvent(it);it.recycle()}
            points.drop(1).chunked(16).forEach{group->val first=group.first();val move=event(MotionEvent.ACTION_MOVE,first.x,first.y);group.drop(1).forEach{move.addBatch(start+tick++,coords(it.x,it.y),0)};view.onTouchEvent(move);move.recycle()}
            points.last().let{event(MotionEvent.ACTION_UP,it.x,it.y).also{e->view.onTouchEvent(e);e.recycle()}}
        }}
        for(zoom in listOf(.5f,1f,3.8f))for(degrees in listOf(0,45,90)){
            val angle=Math.toRadians(degrees.toDouble());fixture(angle);ui{activity.inkView!!.resetZoom();activity.inkView!!.zoomBy(zoom)};scratch(angle)
            check(ui{activity.inkView!!.currentPage!!.objects.map{it.getString("id")}.toSet()==setOf("locked","highlight","text")},"Dense historical scratch erases only ink: $degrees degrees at $zoom zoom")
            ui{activity.inkView!!.undo()};check(ui{activity.inkView!!.currentPage!!.objects.any{it.optString("id")=="scratch-target"}},"Scratch undo restores handwriting")
            ui{activity.inkView!!.redo()};check(ui{activity.inkView!!.currentPage!!.objects.size==3},"Scratch redo")
        }
        fixture(0.0);ui{activity.inkView!!.resetZoom();activity.inkView!!.scribbleErase=false};scratch(0.0)
        check(ui{activity.inkView!!.currentPage!!.objects.size==5},"Disabled scratch remains ink")
        fixture(0.0);ui{activity.inkView!!.tool=InkCanvasView.Tool.HIGHLIGHTER};scratch(0.0)
        check(ui{activity.inkView!!.currentPage!!.objects.size==5},"Highlighter gesture does not erase")
        result.putString("scratch","PASS: dense historical MotionEvent samples; horizontal, diagonal, vertical; 50%, 100%, 380%; undo/redo; locked/text/highlighter preserved; setting off")
        fun find(label:String):android.view.View?{fun walk(v:android.view.View):android.view.View?{if(v.contentDescription?.toString()==label)return v;if(v is ViewGroup)for(i in 0 until v.childCount)walk(v.getChildAt(i))?.let{return it};return null};return walk(activity.window.decorView)}
        fun accessibleClick(label:String):Boolean {
            repeat(12) {
                val root=uiAutomation.rootInActiveWindow?:return false
                fun walk(node:android.view.accessibility.AccessibilityNodeInfo):android.view.accessibility.AccessibilityNodeInfo? {
                    if(node.contentDescription?.toString()==label || node.text?.toString()==label)return node
                    for(index in 0 until node.childCount)node.getChild(index)?.let{child->walk(child)?.let{return it}}
                    return null
                }
                val target=walk(root)
                if(target!=null){var clickable:android.view.accessibility.AccessibilityNodeInfo?=target
                    while(clickable!=null&&!clickable.isClickable)clickable=clickable.parent
                    if(clickable?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true)return true
                }
                fun scroll(node:android.view.accessibility.AccessibilityNodeInfo):Boolean {
                    if(node.isScrollable&&node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))return true
                    for(index in 0 until node.childCount)node.getChild(index)?.let{if(scroll(it))return true}
                    return false
                }
                if(!scroll(root))return false
                SystemClock.sleep(150)
            }
            return false
        }
        fun click(label:String){val classic=ui{find(label)}
            check(if(classic!=null)ui{classic.performClick()} else accessibleClick(label),"Control is reachable: $label")
            waitForIdleSync()}
        fun visible(label:String):Boolean {
            val root=uiAutomation.rootInActiveWindow?:return false
            fun walk(node:android.view.accessibility.AccessibilityNodeInfo):Boolean {
                if(node.contentDescription?.toString()==label || node.text?.toString()==label)return true
                for(index in 0 until node.childCount)node.getChild(index)?.let{if(walk(it))return true}
                return false
            }
            return walk(root)
        }
        fun visibleSearchInput():Boolean {
            val root=uiAutomation.rootInActiveWindow?:return false
            fun labelled(node:android.view.accessibility.AccessibilityNodeInfo):Boolean {
                if(node.contentDescription?.toString()=="Search notes")return true
                for(index in 0 until node.childCount)node.getChild(index)?.let{if(labelled(it))return true}
                return false
            }
            fun walk(node:android.view.accessibility.AccessibilityNodeInfo):Boolean {
                // Compose exposes the exact label as a descendant of the editable node.
                if(node.isEditable&&labelled(node))return true
                for(index in 0 until node.childCount)node.getChild(index)?.let{if(walk(it))return true}
                return false
            }
            return walk(root)
        }
        val screenDirectory=File(targetContext.filesDir,"compose-ui/run-${System.currentTimeMillis()}").apply{mkdirs()}
        fun screen(name:String):String {
            // Accessibility can update before the new Compose frame reaches the display.
            uiAutomation.waitForIdle(500,5000)
            val frames=CountDownLatch(1)
            ui{val root=activity.window.decorView;root.postOnAnimation(object:Runnable{
                var remaining=3
                override fun run(){if(--remaining==0)frames.countDown() else root.postOnAnimation(this)}
            })}
            check(frames.await(5,TimeUnit.SECONDS),"Compose screenshot frame synchronization")
            val bitmap=requireNotNull(uiAutomation.takeScreenshot()){"composeScreenshotUnavailable"}
            val file=File(screenDirectory,name)
            try{java.io.FileOutputStream(file).use{output->check(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));output.fd.sync()}}
            finally{bitmap.recycle()}
            return file.relativeTo(targetContext.filesDir).invariantSeparatorsPath}
        ui{activity.inkView!!.changeObjects(activity.inkView!!.currentPage!!.objects.toList(),emptyList());activity.inkView!!.tool=InkCanvasView.Tool.PEN;activity.inkView!!.scribbleErase=true;activity.inkView!!.resetZoom()}
        if(activity.resources.configuration.screenWidthDp<=840){click("더 보기");click("페이지 번호로 이동")}
        else click("페이지 사이드바")
        click("목차");click("오디오");click("페이지");click("닫기")
        click("도구 더보기");click("형광펜");check(ui{activity.inkView!!.tool==InkCanvasView.Tool.HIGHLIGHTER},"Highlighter accessible from original overflow")
        click("펜");click("펜 설정");click("완료")
        click("색상 추가");click("닫기")
        check(ui{val v=activity.inkView!!;val density=activity.resources.displayMetrics.density
            val margin=if(activity.resources.configuration.screenWidthDp<=840)9 else 52
            kotlin.math.abs(v.screenPoint(0f,0f).x-kotlin.math.max((v.width-880*density)/2,margin*density))<3},"880dp paper width fits narrow viewports")
        ui{activity.showLibrary()};waitForIdleSync()
        await("Korean Compose library loaded without index overlay") {
            visible("네이티브 필기 검증") && !visible("목차를 읽는 중…")
        }
        fun hasCompose(view:android.view.View):Boolean = view is androidx.compose.ui.platform.ComposeView ||
            (view is ViewGroup&&(0 until view.childCount).any{hasCompose(view.getChildAt(it))})
        check(ui{hasCompose(activity.findViewById(android.R.id.content))},"Canonical library must use ComposeView")
        result.putString("uiLibraryScreenshot",screen("library.png"))
        click("설정");result.putString("uiSettingsScreenshot",screen("settings.png"))
        click("언어");click("English")
        await("English Compose settings and saved preference"){
            visible("Language")&&visible("Settings")&&
                JSONObject(repository.setting("preferences","{}")).optString("language")=="en"
        }
        ui{activity.showLibrary()};waitForIdleSync()
        await("English Compose library") { visible("Documents")&&visible("Search notes") }
        click("Search notes")
        await("English Compose search opened") { visibleSearchInput() }
        click("Search notes")
        await("English Compose search closed") { !visibleSearchInput() }
        result.putString("uiEnglishLibraryScreenshot",screen("library-en.png"))
        await("English library navigation persisted") { repository.setting("last-open-document")=="" }
        ui{activity.finish()};waitForIdleSync()
        activity=startActivitySync(Intent(Intent.ACTION_MAIN).setClassName(targetContext.packageName,
            MainActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        repository=activity.repository
        await("English library after activity restart") {
            ui{activity.hasWindowFocus()}&&visible("Documents")&&visible("Search notes")
        }
        click("Settings")
        await("English Compose help text") { visible("Language")&&visible("Change the app display language. The note contents and file name will not be changed.") }
        result.putString("uiEnglishSettingsScreenshot",screen("settings-en.png"))
        click("Language");click("korean")
        await("Korean Compose settings restored"){
            visible("언어")&&JSONObject(repository.setting("preferences","{}")).optString("language","ko")=="ko"
        }
        result.putString("uiLocale","English_selected_rendered_persisted_reentered_then_Korean_restored")
        click("완료");click("신규");click("닫기")
        result.putString("uiScreenWidthDp",activity.resources.configuration.screenWidthDp.toString())
        result.putString("classicUi","PASS: page/outline/audio sidebar; highlighter overflow; pen settings; color sheet; canonical Compose library/settings/new-note; original paper width")
    }
    private fun migrateLegacyFixture(result:Bundle){
        val id=uid("legacy_fixture")
        val assetId=id+"_bg"
        val local="https://appassets.androidplatform.net"
        for(url in listOf("blob:$local/id","blob:$local:443/id","blob:https%3A%2F%2Fappassets.androidplatform.net/id"))
            check(LegacyMigration.isLocalBlobUrl(url),"Local FileReader Blob URL rejected: $url")
        for(url in listOf("blob:","blob:https://foreign.example/id","blob:https://user@appassets.androidplatform.net/id",
            "blob:$local:444/id","blob:$local:invalid/id","blob:http://appassets.androidplatform.net/id",
            "blob:file:///id","blob:content://appassets.androidplatform.net/id","blob:https%253A%252F%252Fappassets.androidplatform.net/id"))
            check(!LegacyMigration.isLocalBlobUrl(url),"Nonlocal/malformed Blob URL allowed: $url")
        for(mime in listOf("image/png","","application/octet-stream","audio/mp4"))for(invalid in listOf(byteArrayOf(),byteArrayOf(137.toByte(),80,78))){
            val rejectedId=uid("legacy_rejected")
            val migration=LegacyMigration(activity,repository,{}){}
            check(migration.beginDocument(rejectedId),"Invalid asset fixture started")
            val ref=migration.beginRasterAsset(mime).removePrefix("asset:")
            if(invalid.isNotEmpty())migration.appendAsset(android.util.Base64.encodeToString(invalid,android.util.Base64.NO_WRAP))
            var rejected=false
            try{migration.endAsset()}catch(_:IllegalArgumentException){rejected=true}finally{migration.close()}
            check(rejected&&repository.document(rejectedId)==null&&!repository.asset(ref).exists()&&
                !repository.asset(ref+".pending").exists(),"Empty/malformed migration asset was published")
        }
        val fixtureRandom=Random(1337L)
        val bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply{
            setPixels(IntArray(128*128){Color.rgb(fixtureRandom.nextInt(256),fixtureRandom.nextInt(256),fixtureRandom.nextInt(256))},0,128,0,0,128,128)
        }
        val bytes=java.io.ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        check(bytes.size>49152,"Legacy Blob fixture must exercise multiple chunks")
        val encoded=android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
        for(mime in listOf("","application/octet-stream")){
            val uncommitted=uid("legacy_generic")
            val migration=LegacyMigration(activity,repository,{}){}
            check(migration.beginDocument(uncommitted),"Generic image fixture started")
            val ref=migration.beginRasterAsset(mime).removePrefix("asset:")
            encoded.chunked(65536).forEach{migration.appendAsset(it)}
            try{check(migration.endAsset()&&repository.asset(ref).readBytes().contentEquals(bytes),"Generic MIME PNG bytes")}
            finally{migration.close()}
            check(repository.document(uncommitted)==null&&!repository.asset(ref).exists()&&!repository.asset(ref+".pending").exists(),"Uncommitted generic image cleanup")
        }
        val fixture="""<!doctype html><script>
          window.onerror=function(message){window.fixtureError=String(message);return true;};
        </script><script>
          (function(){
            var request=indexedDB.open('inkforge-notes-studio',4);
            request.onupgradeneeded=function(){var names=['documents','assets','settings'];
              for(var i=0;i<names.length;i++)if(!request.result.objectStoreNames.contains(names[i]))
                request.result.createObjectStore(names[i],{keyPath:names[i]==='settings'?'key':'id'});};
            request.onerror=function(){window.fixtureError=String(request.error||'IndexedDB open failed');};
            request.onsuccess=function(){var db=request.result;
              try{var tx=db.transaction(['documents','assets'],'readwrite');
                tx.onerror=function(){window.fixtureError=String(tx.error||'IndexedDB write failed');db.close();};
                tx.onabort=function(){window.fixtureError=String(tx.error||'IndexedDB write aborted');db.close();};
                tx.oncomplete=function(){
                  var q=db.transaction('assets','readonly').objectStore('assets').get('$assetId');
                  q.onerror=function(){window.fixtureError=String(q.error||'Blob source read failed');db.close();};
                  q.onsuccess=function(){var b=q.result.blob,r=new FileReader();
                    window.fixtureSource={constructed:window.fixtureConstructedSize,stored:b.size,type:b.type};
                    r.onerror=function(){window.fixtureError=String(r.error||'Blob FileReader failed');db.close();};
                    r.onload=function(){var v=new Uint8Array(r.result),s='';
                      for(var j=0;j<v.length;j++)s+=String.fromCharCode(v[j]);
                      window.fixtureSource.read=v.length;window.fixtureSource.exact=btoa(s)==='$encoded';
                      if(!window.fixtureSource.exact)window.fixtureError='Blob source bytes changed: '+JSON.stringify(window.fixtureSource);
                      else window.fixtureReady=true;db.close();};
                    r.readAsArrayBuffer(b);
                  };
                };
                tx.objectStore('documents').put({id:'$id',title:'data: migration title',tags:['asset: allocation','data: tag'],opaqueFuture:{order:[3,1,2],literal:'data: untouched'},folderId:'root',version:4,settings:{pageMode:'single'},lastPageId:'legacy_page',outline:[{pageId:'legacy_page'}],pages:[{id:'legacy_page',title:'Chapter',template:'grid',opaquePage:{sequence:['first','second']},backgroundAssetId:'$assetId',objects:[{id:'legacy_ink',type:'stroke',brush:'fountain',width:4,points:[{x:10,y:20,p:.5},{x:50,y:60,p:.6}]},{id:'legacy_image',type:'image',src:'data:image/png;base64,$encoded',x:100,y:100,w:8,h:8},{id:'heading',type:'text',fontSize:30,text:'data: heading',x:20,y:90},{id:'old_tape',type:'tape',x1:420,y1:620,x2:770,y2:674}]}],audio:[{id:'old_audio',pageId:'legacy_page',src:'data:audio/mp4;base64,AQIDBA=='}]});
                var decoded=atob('$encoded'),raw=new Uint8Array(decoded.length);
                for(var i=0;i<decoded.length;i++)raw[i]=decoded.charCodeAt(i);
                var blob=new Blob([raw.buffer],{type:'image/png'});window.fixtureConstructedSize=blob.size;
                tx.objectStore('assets').put({id:'$assetId',blob:blob});
              }catch(error){window.fixtureError=String(error);db.close();}
            };
          })();
        </script>"""
        val seed=ui{WebView(activity).apply{
            settings.javaScriptEnabled=true;settings.domStorageEnabled=true
            (activity.window.decorView as ViewGroup).addView(this,ViewGroup.LayoutParams(1,1))
            loadDataWithBaseURL("https://appassets.androidplatform.net/assets/public/index.html",fixture,"text/html","UTF-8",null)
        }}
        fun js(script:String):String{val latch=CountDownLatch(1);val value=AtomicReference<String>();ui{seed.evaluateJavascript(script){value.set(it);latch.countDown()}};check(latch.await(10,TimeUnit.SECONDS),"WebView JS timeout");return value.get()}
        await("legacy fixture seeded"){
            val error=js("window.fixtureError||''")
            check(error=="\"\""||error=="null"){"Legacy fixture JavaScript failed: $error"}
            js("window.fixtureReady===true")=="true"
        }
        result.putString("legacyBlobSource",js("JSON.stringify(window.fixtureSource)"))
        val migrationRequests=java.util.concurrent.ConcurrentLinkedQueue<String>()
        fun migrate(){
            val done=CountDownLatch(1);val success=AtomicReference<Boolean>();val error=AtomicReference<String>()
            ui{val migration=LegacyMigration(activity,repository,{error.set(it)}){success.set(it);done.countDown()};migration.requestObserved={migrationRequests.add(it)};val web=migration.start();(activity.window.decorView as ViewGroup).addView(web,ViewGroup.LayoutParams(1,1))}
            check(done.await(30,TimeUnit.SECONDS),"Migration timeout")
            result.putString("legacyMigrationRequestSchemes",migrationRequests.distinct().joinToString(","))
            check(success.get()==true,"Migration failed: ${error.get()}; request schemes=${migrationRequests.distinct()}")
            if(android.os.Build.VERSION.SDK_INT==23)check("owned-blob" in migrationRequests,"Old WebView FileReader Blob passthrough was not exercised")
        }
        migrate()
        val imported=requireNotNull(repository.document(id));val pages=repository.pageIds(id);check(pages.size==1,"Legacy page count")
        val page=requireNotNull(repository.page(pages.first()));check(page.objects.size==4,"Legacy ink, image, heading and tape retained")
        check(imported.title=="data: migration title"&&imported.data.array("tags").getString(0)=="asset: allocation","IndexedDB literal prefixes retained")
        check(imported.data.getJSONObject("opaqueFuture").getJSONArray("order").let{it.getInt(0)==3&&it.getInt(1)==1&&it.getInt(2)==2}&&
            imported.data.getJSONObject("opaqueFuture").getString("literal")=="data: untouched"&&
            page.meta.getJSONObject("opaquePage").getJSONArray("sequence").getString(1)=="second","Unknown legacy fields and array order retained")
        check(page.objects[2].getString("text")=="data: heading"&&!imported.continuous(true),"Legacy heading and document mode")
        check(InkGeometry.bounds(page.objects[3])==InkBounds(420f,620f,770f,674f),"IndexedDB tape endpoints")
        check(imported.outlineEntries(sequenceOf(page)).any{it.optString("title")=="data: heading"},"Computed legacy outline")
        check(imported.data.getString("lastPageId")==page.id&&imported.data.array("outline").getJSONObject(0).getString("pageId")==page.id&&imported.data.array("audio").getJSONObject(0).getString("pageId")==page.id,"IndexedDB page links remapped")
        val background=repository.asset(page.meta.getString("backgroundImage").removePrefix("asset:"));check(background.readBytes().contentEquals(bytes),"Legacy Blob background bytes")
        val audio=repository.asset(imported.data.array("audio").getJSONObject(0).getString("src").removePrefix("asset:"));check(audio.readBytes().contentEquals(byteArrayOf(1,2,3,4)),"Legacy base64 audio bytes")
        check(!repository.asset(background.name+".pending").exists()&&!repository.asset(audio.name+".pending").exists(),"Migration assets were not finalized")
        val count=repository.documents().size;migrate();check(repository.documents().size==count,"Migration retry must not duplicate notes")
        val sourceCheck="""(function(){var r=indexedDB.open('inkforge-notes-studio');
          r.onerror=function(){window.originalCheckError=String(r.error||'IndexedDB open failed');};
          r.onsuccess=function(){var db=r.result,q=db.transaction('documents','readonly').objectStore('documents').get('$id');
            q.onsuccess=function(){window.originalStillExists=!!q.result;db.close();};
            q.onerror=function(){window.originalCheckError=String(q.error||'Source read failed');db.close();};};
          return true;})()"""
        check(js(sourceCheck)=="true","Source check requested")
        await("legacy source retained"){
            val error=js("window.originalCheckError||''")
            check(error=="\"\""||error=="null"){"Legacy source read failed: $error"}
            js("window.originalStillExists===true")=="true"
        }
        ui{(seed.parent as? ViewGroup)?.removeView(seed);seed.destroy()}
        result.putString("migration","PASS: IndexedDB ink, Blob PDF background, embedded image/audio, resumability, original retained")
    }
    private fun s5aChecks(pageId:String,result:Bundle){
        val edits=java.util.concurrent.atomic.AtomicInteger()
        ui{val view=activity.inkView!!;val save=view.onEdit
            view.onEdit={id,change,positions->edits.incrementAndGet();save(id,change,positions)}
            view.tool=InkCanvasView.Tool.ERASER;view.wholeEraser=true;view.preciseEraser=false;view.scribbleErase=false}
        fun ids()=ui{activity.inkView!!.currentPage!!.objects.map{it.getString("id")}.toSet()}
        val originalIds=ids()
        motion(MotionEvent.ACTION_DOWN,200f,200f,0);motion(MotionEvent.ACTION_UP,200f,200f,0)
        check(ids()==originalIds&&edits.get()==0,"Whole eraser must not hit heart interior")
        motion(MotionEvent.ACTION_DOWN,500f,300f,0);motion(MotionEvent.ACTION_UP,500f,300f,0)
        check("target" !in ids()&&setOf("distant","locked","highlight","outline","sparse").all{it in ids()},
            "Whole eraser must remove only touched, unlocked visible ink")
        check(edits.get()==1,"Whole-object eraser is one edit")
        ui{activity.inkView!!.undo()};check(ids()==originalIds,"Whole eraser undo restores target and order")
        ui{activity.inkView!!.redo()};check("target" !in ids(),"Whole eraser redo")
        ui{activity.inkView!!.undo()};edits.set(0)

        ui{val view=activity.inkView!!;view.tool=InkCanvasView.Tool.PEN;view.wholeEraser=false}
        motion(MotionEvent.ACTION_DOWN,100f,400f,0);motion(MotionEvent.ACTION_MOVE,200f,400f,0)
        batchedStylusMove(300f,400f,500f,300f,MotionEvent.BUTTON_STYLUS_PRIMARY)
        motion(MotionEvent.ACTION_MOVE,500f,330f,MotionEvent.BUTTON_STYLUS_PRIMARY)
        motion(MotionEvent.ACTION_MOVE,600f,400f,0);motion(MotionEvent.ACTION_UP,700f,400f,0)
        val added=ui{activity.inkView!!.currentPage!!.objects.filter{it.optString("id") !in originalIds}}
        check("target" !in ids()&&added.size==2&&edits.get()==1,"Pen-eraser-pen is one edit with two ink segments")
        check(kotlin.math.abs(InkGeometry.points(added[0]).last().x-300f)<1f&&
            kotlin.math.abs(InkGeometry.points(added[1]).first().x-600f)<1f,
            "Batched old-mode sample and release ink start must both be retained")
        ui{activity.inkView!!.undo()};check(ids()==originalIds,"One undo reverses entire mixed contact")
        ui{activity.inkView!!.redo()};check("target" !in ids()&&ids().size==originalIds.size+1,"One redo restores entire mixed contact")
        ui{activity.inkView!!.undo()};edits.set(0)

        val beforeCancel=ui{activity.inkView!!.currentPage!!.objects.map{it.toString()}}
        motion(MotionEvent.ACTION_DOWN,100f,450f,0)
        motion(MotionEvent.ACTION_MOVE,500f,300f,MotionEvent.BUTTON_STYLUS_PRIMARY)
        motion(MotionEvent.ACTION_MOVE,600f,450f,0)
        motion(MotionEvent.ACTION_CANCEL,650f,450f,0)
        check(ui{activity.inkView!!.currentPage!!.objects.map{it.toString()}}==beforeCancel&&edits.get()==0,
            "Cancel after tool transitions must restore page without edit")

        val settled=CountDownLatch(1);repository.executor.execute{settled.countDown()}
        check(settled.await(15,TimeUnit.SECONDS),"Previous object changes settle before cancellation check")
        val generation=repository.generation(pageId)
        motion(MotionEvent.ACTION_DOWN,100f,500f,0);motion(MotionEvent.ACTION_MOVE,200f,500f,0)
        ui{activity.inkView!!.zoomBy(1.2f)}
        motion(MotionEvent.ACTION_CANCEL,200f,500f,0)
        check(ids()==originalIds&&edits.get()==0&&repository.generation(pageId)==generation,
            "Viewport transition cancels contact without generation change")
        ui{activity.inkView!!.resetZoom()}
        val paper=ui{activity.inkView!!.screenPoint(0f,0f)}
        motion(MotionEvent.ACTION_DOWN,100f,500f,0)
        twoPointerMotion(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            100f,500f,800f,800f)
        twoPointerMotion(MotionEvent.ACTION_MOVE,200f,500f,900f,900f)
        twoPointerMotion(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            200f,500f,900f,900f)
        motion(MotionEvent.ACTION_UP,250f,500f,0)
        val paperAfter=ui{activity.inkView!!.screenPoint(0f,0f)}
        check(kotlin.math.abs(paper.x-paperAfter.x)<.1f&&kotlin.math.abs(paper.y-paperAfter.y)<.1f&&
            ids().size==originalIds.size+1&&edits.get()==1,
            "Palm contact cannot zoom or pan while stylus writing continues")
        ui{activity.inkView!!.undo()};check(ids()==originalIds,"Palm-interrupted ink undo")
        edits.set(0);ui{activity.inkView!!.readOnly=true}
        motion(MotionEvent.ACTION_DOWN,100f,500f,0);motion(MotionEvent.ACTION_MOVE,200f,500f,0)
        motion(MotionEvent.ACTION_UP,250f,500f,0)
        check(ids()==originalIds&&edits.get()==0,"Read-only contact cannot commit ink")
        ui{activity.inkView!!.readOnly=false}

        ui{val view=activity.inkView!!;view.tool=InkCanvasView.Tool.ERASER;view.preciseEraser=true;view.wholeEraser=false}
        edits.set(0);motion(MotionEvent.ACTION_DOWN,500f,600f,0);motion(MotionEvent.ACTION_UP,500f,600f,0)
        check(ui{activity.inkView!!.currentPage!!.objects.count{it.optString("sourceStrokeId")=="sparse"}}==2&&edits.get()==1,
            "Sparse segment partial erase creates two fragments in one edit")
        ui{activity.inkView!!.undo()};check(ids()==originalIds,"Partial erase undo restores sparse stroke")
        ui{activity.inkView!!.redo()};check(ids().size==originalIds.size+1,"Partial erase redo restores fragments")
        ui{activity.inkView!!.undo()}

        ui{activity.inkView!!.tool=InkCanvasView.Tool.LASSO}
        motion(MotionEvent.ACTION_DOWN,400f,850f,0)
        motion(MotionEvent.ACTION_MOVE,600f,850f,0);motion(MotionEvent.ACTION_MOVE,600f,950f,0)
        motion(MotionEvent.ACTION_MOVE,400f,950f,0);motion(MotionEvent.ACTION_UP,400f,850f,0)
        check(ui{activity.inkView!!.selected().map{it.getString("id")}}==listOf("distant"),"Lasso selects only enclosed stroke")
        edits.set(0);motion(MotionEvent.ACTION_DOWN,500f,900f,0)
        motion(MotionEvent.ACTION_MOVE,550f,950f,0);motion(MotionEvent.ACTION_UP,550f,950f,0)
        val lassoY=ui{InkGeometry.points(activity.inkView!!.selected().single()).first().y}
        check(edits.get()==1&&kotlin.math.abs(lassoY-950f)<1f,
            "Lasso move is one edit (edits=${edits.get()}, y=$lassoY)")
        ui{activity.inkView!!.undo()}
        check(ui{InkGeometry.points(activity.inkView!!.currentPage!!.objects.first{it.optString("id")=="distant"}).first().y}==900f,
            "Lasso move undo restores geometry")
        val saved=CountDownLatch(1);repository.executor.execute{saved.countDown()}
        check(saved.await(15,TimeUnit.SECONDS),"Object deltas finish saving")
        check(repository.page(pageId)?.objects?.map{it.optString("id")}?.toSet()==originalIds,"Room delta and undo page agree")
        bitmapBrushChecks()
        result.putString("s5aDetails","PASS: exact whole/partial erase; historical button switch; cancel and viewport generation; palm/read-only; one-step undo/redo; lasso; Room delta; bitmap brushes")
    }
    private fun s5bChecks(result:Bundle){
        check(android.os.Build.VERSION.SDK_INT>=31,"Front buffer presentation requires the API 31 transaction commit listener")
        val screens=File(targetContext.filesDir,"s5b-screens/run-${System.currentTimeMillis()}").apply{mkdirs()}
        fun saveScreen(name:String){
            val screenshot=requireNotNull(uiAutomation.takeScreenshot())
            try{File(screens,"$name.png").outputStream().use{
                check(screenshot.compress(Bitmap.CompressFormat.PNG,100,it),"Screenshot encode: $name")
            }}finally{screenshot.recycle()}
        }
        fun surface():InkFrontBuffer=ui{
            fun find(view:View):InkFrontBuffer?{
                if(view is InkFrontBuffer)return view
                if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
                return null
            }
            requireNotNull(find(activity.window.decorView))
        }
        fun pixel(x:Float,y:Float):Int{
            val location=ui{val view=activity.inkView!!;val out=IntArray(2);view.getLocationOnScreen(out)
                val point=view.screenPoint(x,y)
                check(point.x>=0&&point.x<view.width&&point.y>=0&&point.y<view.height,
                    "Probe outside ink viewport: page=($x,$y) local=$point size=${view.width}x${view.height}")
                (out[0]+point.x).toInt() to (out[1]+point.y).toInt()}
            val screenshot=requireNotNull(uiAutomation.takeScreenshot())
            try{
                check(location.first in 0 until screenshot.width&&location.second in 0 until screenshot.height,
                    "Probe outside screenshot: page=($x,$y) screen=$location size=${screenshot.width}x${screenshot.height}")
                return screenshot.getPixel(location.first,location.second)
            }finally{screenshot.recycle()}
        }
        fun dark(color:Int)=Color.red(color)<100&&Color.green(color)<130&&Color.blue(color)<170
        fun difference(a:Int,b:Int)=kotlin.math.abs(Color.red(a)-Color.red(b))+
            kotlin.math.abs(Color.green(a)-Color.green(b))+kotlin.math.abs(Color.blue(a)-Color.blue(b))
        val front=surface()
        try{await("surface multi buffer presented"){ui{front.presented&&front.multiFrames.get()>0}}}
        catch(error:Throwable){
            val queueField=front.javaClass.getDeclaredField("renderedVersions").apply{isAccessible=true}
            val queue=queueField.get(front) as java.util.ArrayDeque<*>
            val sceneField=front.javaClass.getDeclaredField("scene").apply{isAccessible=true}
            val currentScene=sceneField.get(front)
            throw AssertionError("Surface startup: attached=${ui{front.isAttachedToWindow}} " +
            "size=${ui{front.width}}x${ui{front.height}} multi=${front.multiFrames.get()} " +
            "presented=${ui{front.presented}} releases=${front.releasedRenderers.get()} " +
            "queuedVersions=${synchronized(queue){queue.toList()}} scene=$currentScene",error)
        }
        val copy=Bitmap.createBitmap(ui{front.width},ui{front.height},Bitmap.Config.ARGB_8888)
        val copied=CountDownLatch(1);val status=java.util.concurrent.atomic.AtomicInteger(-1)
        ui{PixelCopy.request(front,copy,{status.set(it);copied.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))}
        check(copied.await(10,TimeUnit.SECONDS),"SurfaceView PixelCopy callback must complete")
        // graphics-core presents child SurfaceControls; copying the parent SurfaceView may report NO_DATA.
        result.putString("surfacePixelCopyStatus",status.get().toString())
        copy.recycle()
        await("canonical source ink visible"){dark(pixel(500f,450f))}
        saveScreen("01-canonical")

        val frontBefore=front.frontFrames.get();val multiBefore=front.multiCompletions.get()
        ui{val view=activity.inkView!!;view.tool=InkCanvasView.Tool.PEN;view.brush="fountain";view.inkWidth=16f;view.drawHold=false}
        check(!dark(pixel(370f,800f)),"Test point starts clear")
        motion(MotionEvent.ACTION_DOWN,250f,800f,0)
        motion(MotionEvent.ACTION_MOVE,450f,800f,0)
        await("active stroke in front buffer"){front.frontFrames.get()>frontBefore&&dark(pixel(370f,800f))}
        saveScreen("02-front-active")
        motion(MotionEvent.ACTION_UP,500f,800f,0)
        await("committed scene replaces front"){ui{front.presented&&front.multiCompletions.get()>multiBefore}&&dark(pixel(370f,800f))}
        saveScreen("03-multi-committed")
        val afterCommit=ui{activity.inkView!!.currentPage!!.objects.size}

        val transparentBase=pixel(370f,1080f)
        val alphaFrame=ui{val view=activity.inkView!!;val origin=view.screenPoint(0f,0f)
            InkFrontFrame(json("id" to "alpha-clear","type" to "stroke","brush" to "fountain",
                "width" to 16,"opacity" to .3,"color" to "#172033",
                "points" to JSONArray(listOf(InkPoint(250f,1080f).json(),InkPoint(500f,1080f).json()))).toString(),
                origin.x,origin.y,view.scale,view.currentPage!!.width,view.currentPage!!.height)}
        val alphaStart=front.frontFrames.get()
        check(ui{front.renderFront(alphaFrame)},"Direct alpha frame needs an active front renderer")
        await("transparent front frame visible"){
            front.frontFrames.get()>alphaStart&&difference(transparentBase,pixel(370f,1080f))>20}
        val firstAlpha=pixel(370f,1080f)
        saveScreen("04-alpha-first")
        check(ui{front.renderFront(alphaFrame)},"Repeat transparent frame")
        await("transparent front frame repeated"){front.frontFrames.get()>alphaStart+1}
        check(difference(firstAlpha,pixel(370f,1080f))<12,"Whole-frame CLEAR prevents accumulated alpha")
        saveScreen("05-alpha-repeat")
        ui{front.cancelFront()}
        await("transparent preview removed"){difference(transparentBase,pixel(370f,1080f))<12}

        val cancelledBefore=front.cancelledFrames.get()
        motion(MotionEvent.ACTION_DOWN,250f,870f,0)
        motion(MotionEvent.ACTION_MOVE,450f,870f,0)
        await("cancel candidate visible"){dark(pixel(370f,870f))}
        saveScreen("06-before-cancel")
        motion(MotionEvent.ACTION_CANCEL,500f,870f,0)
        await("cancel removes front without a committed stroke"){
            front.cancelledFrames.get()>cancelledBefore&&!dark(pixel(370f,870f))}
        saveScreen("07-after-cancel")
        check(ui{activity.inkView!!.currentPage!!.objects.size}==afterCommit,"Cancelled front stroke must not commit")

        val outsidePage=pixel(-4f,1030f)
        motion(MotionEvent.ACTION_DOWN,0f,1030f,0)
        motion(MotionEvent.ACTION_MOVE,120f,1030f,0)
        await("edge stroke appears inside page"){dark(pixel(60f,1030f))}
        check(difference(outsidePage,pixel(-4f,1030f))<12,"Front stroke must obey page clip at left edge")
        saveScreen("08-edge-clipped")
        motion(MotionEvent.ACTION_CANCEL,120f,1030f,0)

        val highlightBefore=pixel(370f,950f)
        ui{activity.inkView!!.tool=InkCanvasView.Tool.HIGHLIGHTER}
        motion(MotionEvent.ACTION_DOWN,250f,950f,0)
        motion(MotionEvent.ACTION_MOVE,450f,950f,0)
        check(ui{!front.presented},"Multiply highlighter must use the original Canvas during contact")
        await("highlighter preview visible"){difference(highlightBefore,pixel(370f,950f))>25}
        saveScreen("09-highlighter-fallback")
        motion(MotionEvent.ACTION_UP,500f,950f,0)
        await("highlighter committed scene visible"){
            ui{front.presented}&&difference(highlightBefore,pixel(370f,950f))>25}
        saveScreen("10-highlighter-committed")

        val beforeZoom=front.multiCompletions.get()
        ui{activity.inkView!!.zoomBy(1.2f)}
        await("zoomed canonical scene presented"){ui{front.presented&&front.multiCompletions.get()>beforeZoom}}
        saveScreen("11-zoomed")
        ui{activity.inkView!!.resetZoom()}
        await("reset viewport presented"){ui{front.presented}}

        val oldRelease=front.releasedRenderers.get()
        ui{val parent=front.parent as ViewGroup;val layout=front.layoutParams;parent.removeView(front);parent.addView(front,0,layout)}
        check(front.releasedRenderers.get()>oldRelease,"Detached surface releases renderer")
        await("recreated surface presents full scene"){ui{front.presented}}
        check(dark(pixel(370f,800f)),"Recreated surface retains committed ink")
        saveScreen("12-recreated")

        val portraitWidth=ui{activity.inkView!!.width}
        try{
            ui{activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            await("landscape editor recreated"){
                ui{activity.inkView?.width?.let{it>portraitWidth&&it>0}==true}&&surface().presented}
            saveScreen("13-landscape")
        }finally{
            ui{activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            await("portrait editor restored"){ui{activity.inkView?.currentPage?.id!=null}&&surface().presented}
        }
        saveScreen("14-portrait-restored")
        result.putString("s5bScreenDirectory",screens.absolutePath)
        result.putString("s5bDetails","PASS: composed-screen canonical/front/multi pixels, repeat alpha CLEAR, cancel, edge clip, multiply fallback, zoom, detach/recreate, rotation; parent SurfaceView PixelCopy status recorded separately")
    }
    private fun s5bFallbackChecks(result:Bundle){
        val screens=File(targetContext.filesDir,"s5b-fallback/run-${System.currentTimeMillis()}").apply{mkdirs()}
        result.putString("s5bScreenDirectory",screens.absolutePath)
        fun capture(name:String):Bitmap{
            val image=requireNotNull(uiAutomation.takeScreenshot())
            File(screens,"$name.png").outputStream().use{check(image.compress(Bitmap.CompressFormat.PNG,100,it),"Encode $name")}
            return image
        }
        fun probe(x:Float,y:Float):Pair<Int,Int> = ui{
            val view=activity.inkView!!;val origin=IntArray(2);view.getLocationOnScreen(origin)
                val local=view.screenPoint(x,y)
                check(local.x>=0&&local.x<view.width&&local.y>=0&&local.y<view.height,
                    "Probe outside ink viewport: page=($x,$y) local=$local size=${view.width}x${view.height}")
                (origin[0]+local.x).toInt() to (origin[1]+local.y).toInt()
        }
        fun pixel(x:Float,y:Float):Int{
            val point=probe(x,y)
            val image=requireNotNull(uiAutomation.takeScreenshot())
            try{
                check(point.first in 0 until image.width&&point.second in 0 until image.height,
                    "Probe outside screenshot: page=($x,$y) screen=$point size=${image.width}x${image.height}")
                return image.getPixel(point.first,point.second)
            }
            finally{image.recycle()}
        }
        fun countSurfaces(view:View):Int{
            var count=if(view is SurfaceView)1 else 0
            if(view is ViewGroup)for(i in 0 until view.childCount)count+=countSurfaces(view.getChildAt(i))
            return count
        }
        await("unobstructed editor window"){ui{activity.hasWindowFocus()&&activity.inkView?.isShown==true}}
        check(ui{countSurfaces(activity.window.decorView)}==0,"API 23-30 editor must not create SurfaceView")
        await("canonical ink visible before fallback baseline"){
            val color=pixel(500f,450f)
            Color.red(color)<130&&Color.green(color)<150&&Color.blue(color)<180
        }
        await("blank editor page visible on compositor"){
            val color=pixel(370f,800f)
            Color.red(color)>215&&Color.green(color)>215&&Color.blue(color)>215
        }
        val penProbe=probe(370f,800f)
        result.putString("s5bPenProbeScreenXY","${penProbe.first},${penProbe.second}")
        val baseline=pixel(370f,800f)
        capture("00-baseline").also{image->
            val color=image.getPixel(penProbe.first,penProbe.second)
            check(Color.red(color)>215&&Color.green(color)>215&&Color.blue(color)>215,
                "Saved baseline screenshot must show blank editor at $penProbe")
        }.recycle()
        ui{val view=activity.inkView!!;view.tool=InkCanvasView.Tool.PEN;view.brush="fountain";view.inkWidth=16f;view.drawHold=false}
        motion(MotionEvent.ACTION_DOWN,250f,800f,0);motion(MotionEvent.ACTION_MOVE,450f,800f,0)
        motion(MotionEvent.ACTION_UP,500f,800f,0)
        await("API23 Canvas pen pixel"){
            val color=pixel(370f,800f);Color.red(color)<100&&Color.green(color)<130&&Color.blue(color)<170}
        capture("01-committed").also{image->
            val color=image.getPixel(penProbe.first,penProbe.second)
            check(Color.red(color)<100&&Color.green(color)<130&&Color.blue(color)<170,
                "Saved committed screenshot must show ink at $penProbe")
        }.recycle()
        val committed=pixel(370f,800f)
        val delta=kotlin.math.abs(Color.red(baseline)-Color.red(committed))+
            kotlin.math.abs(Color.green(baseline)-Color.green(committed))+
            kotlin.math.abs(Color.blue(baseline)-Color.blue(committed))
        check(delta>80,"Canvas stroke changes screen pixels: baseline=$baseline committed=$committed delta=$delta")
        val beforeCancel=ui{activity.inkView!!.currentPage!!.objects.size}
        motion(MotionEvent.ACTION_DOWN,250f,870f,0);motion(MotionEvent.ACTION_MOVE,450f,870f,0)
        motion(MotionEvent.ACTION_CANCEL,500f,870f,0)
        check(ui{activity.inkView!!.currentPage!!.objects.size}==beforeCancel,"API23 cancel keeps canonical page")
        capture("02-after-cancel").recycle()
        val highlightBefore=pixel(370f,950f)
        ui{activity.inkView!!.tool=InkCanvasView.Tool.HIGHLIGHTER}
        motion(MotionEvent.ACTION_DOWN,250f,950f,0);motion(MotionEvent.ACTION_MOVE,450f,950f,0)
        motion(MotionEvent.ACTION_UP,500f,950f,0)
        val highlightAfter=pixel(370f,950f)
        val difference=kotlin.math.abs(Color.red(highlightBefore)-Color.red(highlightAfter))+
            kotlin.math.abs(Color.green(highlightBefore)-Color.green(highlightAfter))+
            kotlin.math.abs(Color.blue(highlightBefore)-Color.blue(highlightAfter))
        check(difference>25,"API23 multiply highlighter is visible")
        capture("03-highlighter").recycle()
        ui{activity.inkView!!.zoomBy(1.2f)}
        check(ui{countSurfaces(activity.window.decorView)}==0,"Viewport must keep ordinary Canvas owner")
        capture("04-zoomed").recycle()
        result.putString("s5bScreenDirectory",screens.absolutePath)
        result.putString("s5bDetails","PASS: API23 no SurfaceView, ordinary Canvas pen/highlighter pixels, cancelled contact and zoom")
    }
    private fun batchedStylusMove(hx:Float,hy:Float,x:Float,y:Float,buttons:Int){ui{
        val view=activity.inkView!!;val first=view.screenPoint(hx,hy);val last=view.screenPoint(x,y)
        val props=arrayOf(MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS})
        fun coords(px:Float,py:Float)=arrayOf(MotionEvent.PointerCoords().apply{this.x=px;this.y=py;pressure=.55f})
        val now=SystemClock.uptimeMillis()
        val event=MotionEvent.obtain(downTime,now,MotionEvent.ACTION_MOVE,1,props,coords(first.x,first.y),
            0,buttons,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
        event.addBatch(now+1,coords(last.x,last.y),0);view.onTouchEvent(event);event.recycle()
    }}
    private fun twoPointerMotion(action:Int,sx:Float,sy:Float,fx:Float,fy:Float){ui{
        val view=activity.inkView!!;val stylus=view.screenPoint(sx,sy);val finger=view.screenPoint(fx,fy)
        val props=arrayOf(MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS},
            MotionEvent.PointerProperties().apply{id=1;toolType=MotionEvent.TOOL_TYPE_FINGER})
        val coords=arrayOf(MotionEvent.PointerCoords().apply{x=stylus.x;y=stylus.y;pressure=.55f},
            MotionEvent.PointerCoords().apply{x=finger.x;y=finger.y;pressure=1f})
        val event=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,2,props,coords,
            0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
        view.onTouchEvent(event);event.recycle()
    }}
    private fun bitmapBrushChecks(){
        val renderer=NoteRenderer()
        val points=listOf(InkPoint(20f,40f,.1f,10,tilt=.3f,azimuth=1f),
            InkPoint(40f,55f,.7f,20,tilt=.5f,azimuth=1.4f),InkPoint(65f,35f,.4f,30,tilt=.2f,azimuth=.6f))
        for(brush in arrayOf("fountain","ballpoint","gel","brush","pencil","fineliner","highlighter"))
            for(zoom in floatArrayOf(1f,3.5f)){
                val stroke=json("id" to "pixels","type" to "stroke","brush" to brush,
                    "color" to "#253f88","width" to 4,"opacity" to if(brush=="highlighter") .32 else 1,
                    "points" to JSONArray(points.map{it.json()}))
                fun render(active:Boolean):IntArray{val bitmap=Bitmap.createBitmap(280,280,Bitmap.Config.ARGB_8888)
                    val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(Color.WHITE);canvas.scale(zoom,zoom)
                    if(active)renderer.stroke(canvas,stroke,points,zoom.toDouble())else renderer.draw(canvas,stroke,zoom.toDouble())
                    val pixels=IntArray(280*280);bitmap.getPixels(pixels,0,280,0,0,280,280);bitmap.recycle();return pixels}
                val committed=render(false);val live=render(true)
                check(committed.contentEquals(live),"Active and committed $brush bitmap differ at $zoom zoom")
                check(committed.any{it!=Color.WHITE},"$brush must render visible pixels at $zoom zoom")
            }
    }
    private fun rawCaptureChecks(pageId:String){
        val before=ui{activity.inkView!!.currentPage!!.objects.map{it.optString("id")}.toSet()}
        ui{
            val view=requireNotNull(activity.inkView)
            view.tool=InkCanvasView.Tool.PEN;view.drawHold=false
            val origin=view.screenPoint(300f,750f)
            val subpixel=view.screenPoint(300.05f,750f)
            val props=arrayOf(MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS})
            fun coords(x:Float,pressure:Float)=arrayOf(MotionEvent.PointerCoords().apply{
                this.x=x;this.y=origin.y;this.pressure=pressure
            })
            val now=SystemClock.uptimeMillis()
            fun event(action:Int,time:Long,x:Float,pressure:Float)=MotionEvent.obtain(now,time,action,1,
                props,coords(x,pressure),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
            event(MotionEvent.ACTION_DOWN,now,origin.x,.4f).also{view.onTouchEvent(it);it.recycle()}
            val move=event(MotionEvent.ACTION_MOVE,now+1,origin.x,.5f)
            move.addBatch(now+2,coords(origin.x,0f),0)
            move.addBatch(now+3,coords(origin.x,.8f),0)
            move.addBatch(now+4,coords(subpixel.x,.1f),0)
            view.onTouchEvent(move);move.recycle()
            event(MotionEvent.ACTION_UP,now+5,subpixel.x,0f).also{view.onTouchEvent(it);it.recycle()}
        }
        val stroke=ui{activity.inkView!!.currentPage!!.objects.single{it.optString("id") !in before}}
        val source=stroke.getJSONArray("points")
        check(source.length()==6,"All historical/current/stationary/UP samples must be stored")
        val pressure=listOf(.4,.5,0.0,.8,.1,0.0)
        for(i in 0 until source.length()){
            val p=source.getJSONObject(i)
            check(kotlin.math.abs(p.getDouble("p")-pressure[i])<.0001,"Raw pressure/order at $i")
            if(i>0)check(p.getLong("t")>source.getJSONObject(i-1).getLong("t"),"Raw time order")
        }
        check(source.getJSONObject(0).getDouble("x")==source.getJSONObject(3).getDouble("x"),
            "Stationary coordinates retained")
        val id=stroke.getString("id")
        await("raw samples Room saved"){repository.page(pageId)?.objects?.any{it.optString("id")==id}==true}
        check(repository.page(pageId)!!.objects.single{it.optString("id")==id}.getJSONArray("points").toString()==source.toString(),
            "Room persists every raw sample")
        ui{activity.inkView!!.undo()}
        await("raw sample undo saved"){repository.page(pageId)?.objects?.none{it.optString("id")==id}==true}
        ui{activity.inkView!!.redo()}
        await("raw sample redo saved"){repository.page(pageId)?.objects?.any{it.optString("id")==id}==true}
        check(repository.page(pageId)!!.objects.single{it.optString("id")==id}.getJSONArray("points").toString()==source.toString(),
            "Undo/Redo restores unchanged raw samples")
    }
    private fun motion(action:Int,x:Float,y:Float,buttons:Int,tool:Int=MotionEvent.TOOL_TYPE_STYLUS){
        ui{
            val view=requireNotNull(activity.inkView);val point=view.screenPoint(x,y)
            val props=MotionEvent.PointerProperties().apply{id=0;toolType=tool}
            val coords=MotionEvent.PointerCoords().apply{this.x=point.x;this.y=point.y;pressure=if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_HOVER_MOVE)0f else .55f}
            if(action==MotionEvent.ACTION_DOWN)downTime=SystemClock.uptimeMillis()
            val event=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,1,arrayOf(props),arrayOf(coords),0,buttons,1f,1f,0,0,if(tool==MotionEvent.TOOL_TYPE_FINGER)InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_STYLUS,0)
            if(action==MotionEvent.ACTION_HOVER_MOVE)view.onHoverEvent(event)else view.onTouchEvent(event)
            event.recycle()
        }
        SystemClock.sleep(30)
    }
}
