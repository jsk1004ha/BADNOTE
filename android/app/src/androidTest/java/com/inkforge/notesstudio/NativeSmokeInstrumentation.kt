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
import org.json.JSONArray
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
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);uiOnly=arguments?.getString("uiOnly")=="true";start()}
    private fun <T> ui(block:()->T):T{val value=AtomicReference<T>();val failure=AtomicReference<Throwable>();runOnMainSync{try{value.set(block())}catch(e:Throwable){failure.set(e)}};failure.get()?.let{throw it};return value.get()}
    private fun await(label:String,condition:()->Boolean){val end=SystemClock.uptimeMillis()+30000;while(SystemClock.uptimeMillis()<end){if(condition())return;SystemClock.sleep(50)};error("Timeout: $label")}
    private fun check(value:Boolean,message:String){if(!value)throw AssertionError(message)}
    override fun onStart(){
        val result=Bundle()
        try{
            check(targetContext.packageName.endsWith(".debug"),"Refusing to test a release app")
            NoteRepository(targetContext).use{it.setting("legacy-migration-complete","true",true);it.setting("preferences","{\"autoOcr\":false,\"drawHold\":false,\"scribbleErase\":false,\"stylusOnly\":true}",true)}
            activity=startActivitySync(Intent(Intent.ACTION_MAIN).setClassName(targetContext.packageName,MainActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            repository=activity.repository
            val doc=repository.create("네이티브 필기 검증","root","grid")
            val page=requireNotNull(repository.page(repository.pageIds(doc.id).first()))
            fun stroke(id:String,y:Int)=json("id" to id,"type" to "stroke","brush" to "fountain","width" to 4,"color" to "#172033","points" to JSONArray(listOf(InkPoint(450f,y.toFloat()).json(),InkPoint(550f,y.toFloat()).json())))
            page.objects.addAll(listOf(stroke("first",450),stroke("second",600)))
            repository.transaction{repository.putPage(doc.id,page,0)}
            ui{activity.openDocument(doc.id)}
            await("native editor ready"){ui{activity.inkView?.currentPage?.id==page.id&&activity.inkView!!.width>0}}
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
            check(ui{activity.inkView!!.currentPage!!.objects.isEmpty()},"Undo new stroke")
            ui{activity.inkView!!.undo()}
            check(ui{activity.inkView!!.currentPage!!.objects.size==2},"Undo erase restores both original strokes")
            ui{activity.inkView!!.redo()}
            check(ui{activity.inkView!!.currentPage!!.objects.isEmpty()},"Redo erase")
            motion(MotionEvent.ACTION_DOWN,200f,300f,0,MotionEvent.TOOL_TYPE_FINGER)
            motion(MotionEvent.ACTION_MOVE,250f,320f,0,MotionEvent.TOOL_TYPE_FINGER)
            motion(MotionEvent.ACTION_UP,250f,320f,0,MotionEvent.TOOL_TYPE_FINGER)
            check(ui{activity.inkView!!.currentPage!!.objects.isEmpty()},"Finger cannot write in stylus-only mode")
            ui{activity.inkView!!.goTo(0);activity.inkView!!.preciseEraser=true
                activity.inkView!!.add(stroke("sparse",450))}
            motion(MotionEvent.ACTION_DOWN,500f,450f,32);motion(MotionEvent.ACTION_UP,500f,450f,32)
            check(ui{activity.inkView!!.currentPage!!.objects.size==2},"Precise eraser splits sparse stroke")
            result.putString("stylus","PASS: hover, button eraser, same-contact pen restore, cancel, finger rejection, undo/redo, partial erase")
            await("partial erase persisted"){repository.page(page.id)?.objects?.size==2}
            scratchAndClassicUi(result)
            CompatibilityChecks.run(this,activity,result)
            ui{activity.openDocument(doc.id)}
            await("original document ready"){ui{activity.inkView?.currentPage?.id==page.id}}
            if(uiOnly){result.putString("passed","true");finish(Activity.RESULT_OK,result);return}

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
            result.putString("passed","true");finish(Activity.RESULT_OK,result)
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
        fun click(label:String){ui{check(find(label)?.performClick()==true,"Control is reachable: $label")};waitForIdleSync()}
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
        ui{activity.showLibrary()};waitForIdleSync();click("설정");click("완료");click("신규");click("닫기")
        result.putString("classicUi","PASS: page/outline/audio sidebar; highlighter overflow; pen settings; color sheet; library/settings/new-note; original paper width")
    }
    private fun migrateLegacyFixture(result:Bundle){
        val id=uid("legacy_fixture")
        val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.CYAN)}
        val bytes=java.io.ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        val encoded=android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
        val fixture="""<!doctype html><script>
          (async()=>{
            const request=indexedDB.open('inkforge-notes-studio',4);
            request.onupgradeneeded=()=>{for(const name of ['documents','assets','settings'])if(!request.result.objectStoreNames.contains(name))request.result.createObjectStore(name,{keyPath:name==='settings'?'key':'id'});};
            const db=await new Promise((resolve,reject)=>{request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error)});
            const tx=db.transaction(['documents','assets'],'readwrite');
            tx.objectStore('documents').put({id:'$id',title:'data: migration title',tags:['asset: allocation','data: tag'],folderId:'root',version:4,settings:{pageMode:'single'},lastPageId:'legacy_page',outline:[{pageId:'legacy_page'}],pages:[{id:'legacy_page',title:'Chapter',template:'grid',backgroundAssetId:'legacy_bg',objects:[{id:'legacy_ink',type:'stroke',brush:'fountain',width:4,points:[{x:10,y:20,p:.5},{x:50,y:60,p:.6}]},{id:'legacy_image',type:'image',src:'data:image/png;base64,$encoded',x:100,y:100,w:8,h:8},{id:'heading',type:'text',fontSize:30,text:'data: heading',x:20,y:90},{id:'old_tape',type:'tape',x1:420,y1:620,x2:770,y2:674}]}],audio:[{id:'old_audio',pageId:'legacy_page',src:'data:audio/mp4;base64,AQIDBA=='}]});
            const raw=Uint8Array.from(atob('$encoded'),c=>c.charCodeAt(0));tx.objectStore('assets').put({id:'legacy_bg',blob:new Blob([raw],{type:'image/png'})});
            await new Promise((resolve,reject)=>{tx.oncomplete=resolve;tx.onerror=()=>reject(tx.error)});db.close();window.fixtureReady=true;
          })().catch(e=>window.fixtureError=String(e));
        </script>"""
        val seed=ui{WebView(activity).apply{
            settings.javaScriptEnabled=true;settings.domStorageEnabled=true
            (activity.window.decorView as ViewGroup).addView(this,ViewGroup.LayoutParams(1,1))
            loadDataWithBaseURL("https://appassets.androidplatform.net/assets/public/index.html",fixture,"text/html","UTF-8",null)
        }}
        fun js(script:String):String{val latch=CountDownLatch(1);val value=AtomicReference<String>();ui{seed.evaluateJavascript(script){value.set(it);latch.countDown()}};check(latch.await(10,TimeUnit.SECONDS),"WebView JS timeout");return value.get()}
        await("legacy fixture seeded"){js("window.fixtureReady===true")=="true"}
        fun migrate(){
            val done=CountDownLatch(1);val success=AtomicReference<Boolean>();val error=AtomicReference<String>()
            ui{val migration=LegacyMigration(activity,repository,{error.set(it)}){success.set(it);done.countDown()};val web=migration.start();(activity.window.decorView as ViewGroup).addView(web,ViewGroup.LayoutParams(1,1))}
            check(done.await(30,TimeUnit.SECONDS),"Migration timeout");check(success.get()==true,"Migration failed: ${error.get()}")
        }
        migrate()
        val imported=requireNotNull(repository.document(id));val pages=repository.pageIds(id);check(pages.size==1,"Legacy page count")
        val page=requireNotNull(repository.page(pages.first()));check(page.objects.size==4,"Legacy ink, image, heading and tape retained")
        check(imported.title=="data: migration title"&&imported.data.array("tags").getString(0)=="asset: allocation","IndexedDB literal prefixes retained")
        check(page.objects[2].getString("text")=="data: heading"&&!imported.continuous(true),"Legacy heading and document mode")
        check(InkGeometry.bounds(page.objects[3])==InkBounds(420f,620f,770f,674f),"IndexedDB tape endpoints")
        check(imported.outlineEntries(sequenceOf(page)).any{it.optString("title")=="data: heading"},"Computed legacy outline")
        check(imported.data.getString("lastPageId")==page.id&&imported.data.array("outline").getJSONObject(0).getString("pageId")==page.id&&imported.data.array("audio").getJSONObject(0).getString("pageId")==page.id,"IndexedDB page links remapped")
        val background=repository.asset(page.meta.getString("backgroundImage").removePrefix("asset:"));check(background.readBytes().contentEquals(bytes),"Legacy Blob background bytes")
        val audio=repository.asset(imported.data.array("audio").getJSONObject(0).getString("src").removePrefix("asset:"));check(audio.readBytes().contentEquals(byteArrayOf(1,2,3,4)),"Legacy base64 audio bytes")
        val count=repository.documents().size;migrate();check(repository.documents().size==count,"Migration retry must not duplicate notes")
        check(js("new Promise(resolve=>{const r=indexedDB.open('inkforge-notes-studio');r.onsuccess=()=>{const db=r.result;const q=db.transaction('documents').objectStore('documents').get('$id');q.onsuccess=()=>{window.originalStillExists=!!q.result;db.close();resolve(true)}}});true")=="true","Source check requested")
        await("legacy source retained"){js("window.originalStillExists===true")=="true"}
        ui{(seed.parent as? ViewGroup)?.removeView(seed);seed.destroy()}
        result.putString("migration","PASS: IndexedDB ink, Blob PDF background, embedded image/audio, resumability, original retained")
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
