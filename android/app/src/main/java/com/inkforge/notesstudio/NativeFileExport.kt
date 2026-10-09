package com.inkforge.notesstudio

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/** Files are fully prepared on disk before invoking the Android Sharesheet or SAF. */
class NativeFileExport(private val activity:Activity,private val repository:NoteRepository,
                       private val message:(String)->Unit, private val busy:(Boolean)->Unit) {
    private var pending:File?=null
    private var copying=false
    private val cancelled=AtomicBoolean(false)
    val isBusy get()=pending!=null||copying
    fun cancel(){cancelled.set(true)}
    fun prepare(doc:DocumentInfo,format:String,share:Boolean,currentPage:String?=null){
        if(isBusy){message("내보내기가 진행 중입니다.");return}
        cancelled.set(false);copying=true;busy(true)
        val directory=File(activity.cacheDir,"shared").apply{mkdirs()}
        // Retain shared files for recipients that read asynchronously. Expire after seven days.
        directory.listFiles()?.filter{System.currentTimeMillis()-it.lastModified()>7L*86400000}?.forEach{it.delete()}
        val name=doc.title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"),"_").take(80).ifBlank{"노트"}
        val file=File(directory,"$name-${System.currentTimeMillis()}${if(format=="legacy")"-3x.ifnote"else".$format"}")
        repository.executor.execute{
            try{
                when(format){
                    "ifnote"->repository.export(doc.id,file,{cancelled.get()})
                    "legacy"->repository.exportLegacy(doc.id,file,{cancelled.get()})
                    "pdf"->StreamingPdf.write(repository,doc.id,file,{cancelled.get()})
                    "png"->StreamingPdf.render(repository,requireNotNull(repository.page(requireNotNull(currentPage))),1800).let{bmp->
                        try{file.outputStream().use{require(bmp.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{bmp.recycle()}}
                    "xfdf"->XfdfExport.write(repository,doc.id,file)
                    "json"->{
                        val page=requireNotNull(repository.page(requireNotNull(currentPage))){"현재 페이지가 없습니다."}
                        val result=requireNotNull(repository.ocrResult(page.id)){"먼저 현재 페이지의 손글씨 OCR을 실행해 주세요."}
                        require(result.optString("documentId")==doc.id){"OCR 결과의 문서가 다릅니다."}
                        val sessions=page.objects.mapNotNull{it.optString("captureSessionId").takeIf(String::isNotBlank)}.toSet()
                        val diagnostic=json("schemaVersion" to 1,"sampleId" to java.util.UUID.randomUUID().toString(),
                            "documentId" to doc.id,"pageId" to page.id,"pageDigest" to result.getString("pageDigest"),
                            "provenance" to json("kind" to "real","writerId" to "",
                                "sessionId" to sessions.singleOrNull().orEmpty(),"deviceId" to "",
                                "language" to result.optString("policy")),
                            "truthRegions" to JSONArray(),"result" to result,
                            "measurements" to (result.optJSONObject("measurements")?:JSONObject()),
                            "sourcePage" to page.json())
                        FileOutputStream(file).use{out->out.write(diagnostic.toString().toByteArray(Charsets.UTF_8));out.fd.sync()}
                    }
                    else->error("지원하지 않는 파일 형식")
                }
                if(cancelled.get())throw InterruptedIOException("내보내기를 취소했습니다.")
                activity.runOnUiThread{
                    copying=false;busy(false)
                    if(activity.isFinishing||activity.isDestroyed){file.delete();return@runOnUiThread}
                    if(share) share(file) else {
                        pending=file
                        try{activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType(mime(file)).putExtra(Intent.EXTRA_TITLE,if(format=="legacy")"$name-3x.ifnote"else"$name.$format"),REQUEST_SAVE)}
                        catch(e:Exception){pending=null;file.delete();message(e.message?:"저장 창을 열 수 없습니다.")}
                    }
                }
            }catch(e:Exception){file.delete();activity.runOnUiThread{copying=false;busy(false);message(e.message?:"내보내기 실패")}}
        }
    }
    private fun share(file:File){
        try{
            val intent=shareIntent(activity,file)
            activity.startActivity(Intent.createChooser(intent,"노트 공유"))
        }catch(e:Exception){message("공유 실패: ${e.message}")}
    }
    fun result(result:Int,data:Intent?){
        val file=pending?:return;pending=null
        val uri=data?.data
        if(result!=Activity.RESULT_OK||uri==null){file.delete();message("저장을 취소했습니다.");return}
        copying=true;busy(true)
        repository.executor.execute{
            var failure:Exception?=null
            try{activity.contentResolver.openOutputStream(uri,"wt").use{out->
                requireNotNull(out){"저장 위치를 열 수 없습니다."};file.inputStream().use{ArchiveCodec.copy(it,out,{cancelled.get()})};out.flush()}}
            catch(e:Exception){failure=e;try{DocumentsContract.deleteDocument(activity.contentResolver,uri)}catch(_:Exception){}}
            finally{file.delete()}
            activity.runOnUiThread{copying=false;busy(false);message(failure?.let{"저장 실패: ${it.message}"}?:"파일을 저장했습니다.")}
        }
    }
    fun restore(path:String?){if(path!=null){val file=File(path);val root=File(activity.cacheDir,"shared").canonicalPath+File.separator
        if(file.canonicalPath.startsWith(root)&&file.isFile)pending=file}}
    fun pendingPath()=pending?.path
    companion object{
        const val REQUEST_SAVE=4174
        fun shareIntent(activity:Activity,file:File):Intent{
            require(file.isFile){"공유할 파일 준비가 끝나지 않았습니다."}
            val uri=FileProvider.getUriForFile(activity,"${activity.packageName}.fileprovider",file)
            return Intent(Intent.ACTION_SEND).setType(mime(file)).putExtra(Intent.EXTRA_STREAM,uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply{clipData=ClipData.newRawUri(file.name,uri)}
        }
        fun mime(file:File)=when(file.extension){"pdf"->"application/pdf";"png"->"image/png";"xfdf"->"application/vnd.adobe.xfdf";"json"->"application/json";else->"application/octet-stream"}
    }
}

/** One raster page and one JPEG spool at a time, including at 300 MB total asset size. */
object StreamingPdf {
    fun render(repository:NoteRepository,page:NotePage,width:Int):Bitmap {
        val height=(width*page.height/page.width).roundToInt().coerceIn(1,5000)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try{
            val canvas=Canvas(bitmap);canvas.scale(width/page.width,height/page.height)
            val imageCache=mutableMapOf<String,Bitmap>()
            val renderer=NoteRenderer { ref->
                if(!ref.startsWith("asset:"))null else imageCache.getOrPut(ref){
                    BackgroundLoader.decodeImage(repository.asset(ref.removePrefix("asset:")),2000)?:error("이미지 자산을 읽지 못했습니다.")
                }
            }
            try{
                renderer.template(canvas,page)
                val pdfRef=page.meta.optString("pdfSource")
                if(pdfRef.startsWith("asset:")){
                    PdfRenderer(ParcelFileDescriptor.open(repository.asset(pdfRef.removePrefix("asset:")),ParcelFileDescriptor.MODE_READ_ONLY)).use{pdf->
                        pdf.openPage(page.meta.optInt("pdfPageNumber",1)-1).use{source->
                            val background=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                            try{background.eraseColor(Color.WHITE);source.render(background,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                canvas.drawBitmap(background,null,RectF(0f,0f,page.width,page.height),null)}finally{background.recycle()}
                        }}
                }else if(page.meta.optString("backgroundImage").startsWith("asset:")){
                    val bg=BackgroundLoader.decodeImage(repository.asset(page.meta.getString("backgroundImage").removePrefix("asset:")),2400)?:error("PDF 배경 자산을 읽지 못했습니다.")
                    try{canvas.drawBitmap(bg,null,RectF(0f,0f,page.width,page.height),null)}finally{bg.recycle()}
                }
                page.objects.forEach{obj->renderer.draw(canvas,obj)
                    // Images are consumed in order, so a page with hundreds of images stays bounded.
                    imageCache.values.forEach{it.recycle()};imageCache.clear()}
            }finally{imageCache.values.forEach{it.recycle()}}
            return bitmap
        }catch(e:Exception){bitmap.recycle();throw e}
    }
    fun write(repository:NoteRepository,id:String,file:File,cancelled:()->Boolean){
        val ids=repository.pageIds(id)
        val offsets=LongArray(3+ids.size*3)
        var position=0L
        file.outputStream().buffered(ArchiveCodec.BUFFER_SIZE).use{out->
            fun text(s:String){val bytes=s.toByteArray(Charsets.US_ASCII);out.write(bytes);position+=bytes.size}
            fun start(number:Int){offsets[number]=position;text("$number 0 obj\n")}
            text("%PDF-1.4\n")
            start(1);text("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
            start(2);text("<< /Type /Pages /Count ${ids.size} /Kids [${ids.indices.joinToString(" "){"${3+it*3} 0 R"}}] >>\nendobj\n")
            ids.forEachIndexed{index,pageId->
                if(cancelled())throw InterruptedIOException("내보내기를 취소했습니다.")
                val page=requireNotNull(repository.page(pageId));val pageIdNumber=3+index*3
                val bmp=render(repository,page,1800)
                val spool=File.createTempFile("pdf-page-",".jpg",repository.workDirectory)
                try{
                    val width=bmp.width;val height=bmp.height
                    try{spool.outputStream().use{require(bmp.compress(Bitmap.CompressFormat.JPEG,90,it))}}finally{bmp.recycle()}
                    val pw=page.meta.f("pdfPointWidth",595f);val ph=page.meta.f("pdfPointHeight",pw*page.height/page.width)
                    start(pageIdNumber);text("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pw $ph] /Resources << /XObject << /Im0 ${pageIdNumber+1} 0 R >> >> /Contents ${pageIdNumber+2} 0 R >>\nendobj\n")
                    start(pageIdNumber+1);text("<< /Type /XObject /Subtype /Image /Width $width /Height $height /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${spool.length()} >>\nstream\n")
                    spool.inputStream().use{position+=ArchiveCodec.copy(it,out,cancelled)};text("\nendstream\nendobj\n")
                    val command="q $pw 0 0 $ph 0 0 cm /Im0 Do Q\n"
                    start(pageIdNumber+2);text("<< /Length ${command.length} >>\nstream\n${command}endstream\nendobj\n")
                }finally{spool.delete()}
            }
            val xref=position;text("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
            for(i in 1 until offsets.size)text(String.format(Locale.US,"%010d 00000 n \n",offsets[i]))
            text("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        }
    }
}

object XfdfExport{
    private fun xml(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    fun write(repository:NoteRepository,id:String,file:File){
        file.bufferedWriter().use{out->
            val source=repository.document(id)?.data?.optString("pdfSourceName","")?:""
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><xfdf xmlns=\"http://ns.adobe.com/xfdf/\" xml:space=\"preserve\"><f href=\"${xml(source)}\"/><annots>")
            repository.pageIds(id).forEachIndexed{index,pageId->val page=requireNotNull(repository.page(pageId));val pw=page.meta.f("pdfPointWidth",595f);val ph=page.meta.f("pdfPointHeight",842f)
                page.objects.filter{!it.optBoolean("hidden")&&it.optString("type")!="ocrIndex"}.forEach{obj->
                    val b=InkGeometry.bounds(obj)
                    fun x(v:Float)=v/page.width*pw
                    fun y(v:Float)=ph-v/page.height*ph
                    val attrs="page=\"$index\" name=\"${xml(obj.optString("id"))}\" rect=\"${x(b.left)},${y(b.bottom)},${x(b.right)},${y(b.top)}\" color=\"${xml(obj.optString("color","#172033"))}\" opacity=\"${obj.f("opacity",1f)}\" flags=\"print\""
                    if(obj.optString("type")=="stroke"){
                        out.write("<ink $attrs width=\"${obj.f("width",3f)/page.width*pw}\"><inklist><gesture>")
                        InkGeometry.points(obj).forEach{out.write("${x(it.x)},${y(it.y)} ")};out.write("</gesture></inklist></ink>")
                    }else if(obj.optString("type")=="shape"){
                        val shape=obj.optString("shape")
                        val strokeWidth=obj.f("width",3f)/page.width*pw
                        when(shape){
                            "line","arrow","double-arrow","curve","arc"->out.write("<line $attrs width=\"$strokeWidth\" start=\"${x(obj.f("x1"))},${y(obj.f("y1"))}\" end=\"${x(obj.f("x2"))},${y(obj.f("y2"))}\"/>")
                            "ellipse","circle"->out.write("<circle $attrs width=\"$strokeWidth\"/>")
                            else->out.write("<square $attrs width=\"$strokeWidth\"/>")
                        }
                    }else out.write("<freetext $attrs><contents>${xml(obj.optString("text",obj.optString("expression",obj.optString("type"))))}</contents><defaultappearance>0 0 0 rg /Helv ${obj.f("fontSize",28f)/page.width*pw} Tf</defaultappearance></freetext>")
                }}
            out.write("</annots></xfdf>")
        }
    }
}
