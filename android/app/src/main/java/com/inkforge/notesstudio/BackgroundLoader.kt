package com.inkforge.notesstudio

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.*

/** Only visible PDF tiles are rendered. The source PDF is retained without JPEG conversion. */
class BackgroundLoader(private val repository: NoteRepository, private val invalidate: ()->Unit):Closeable {
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val pending=HashSet<String>()
    private var closed=false
    private val cache=object:LruCache<String,Bitmap>(24*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount}
    private val pdfs=LinkedHashMap<String,PdfRenderer>()
    fun image(reference:String,maxSide:Int=1000):Bitmap? {
        if(!reference.startsWith("asset:"))return null
        val size=when{maxSide<=256->256;maxSide<=512->512;maxSide<=1024->1024;else->2000}
        val key="img:$reference:$size"
        cache.get(key)?.let{return it}
        request(key){decodeImage(repository.asset(reference.removePrefix("asset:")),size)}
        return null
    }
    private fun request(key:String,load:()->Bitmap?){
        if(closed||!pending.add(key))return
        worker.execute{
            val bitmap=try{load()}catch(_:Exception){null}
            main.post{pending.remove(key);if(!closed&&bitmap!=null){cache.put(key,bitmap);invalidate()}}
        }
    }
    private fun renderer(reference:String):PdfRenderer {
        pdfs[reference]?.let{return it}
        while(pdfs.size>=2){val key=pdfs.keys.first();pdfs.remove(key)?.close()}
        val renderer=PdfRenderer(ParcelFileDescriptor.open(repository.asset(reference.removePrefix("asset:")),ParcelFileDescriptor.MODE_READ_ONLY))
        pdfs[reference]=renderer;return renderer
    }
    fun draw(canvas:Canvas,page:NotePage,visible:RectF,scale:Float){
        val ref=page.meta.optString("pdfSource")
        if(ref.startsWith("asset:")) {
            val bucket=(ceil(scale*2)/2).coerceIn(.5f,6f)
            val tileLogical=512f/bucket
            val startX=floor(max(0f,visible.left)/tileLogical).toInt();val endX=floor(min(page.width,visible.right)/tileLogical).toInt()
            val startY=floor(max(0f,visible.top)/tileLogical).toInt();val endY=floor(min(page.height,visible.bottom)/tileLogical).toInt()
            for(y in startY..endY)for(x in startX..endX){
                val left=x*tileLogical;val top=y*tileLogical
                if(left>=page.width||top>=page.height)continue
                val w=min(tileLogical,page.width-left);val h=min(tileLogical,page.height-top)
                val index=page.meta.optInt("pdfPageNumber",1)-1
                val key="$ref:$index:$bucket:$x:$y"
                val bitmap=cache.get(key)
                if(bitmap!=null)canvas.drawBitmap(bitmap,null,RectF(left,top,left+w,top+h),null)
                else request(key){
                    renderer(ref).openPage(index).use{pdf->
                        val bmp=Bitmap.createBitmap(ceil(w*bucket).toInt().coerceAtLeast(1),ceil(h*bucket).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        val matrix=Matrix().apply{setScale(page.width/pdf.width*bucket,page.height/pdf.height*bucket);postTranslate(-left*bucket,-top*bucket)}
                        pdf.render(bmp,null,matrix,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);bmp
                    }
                }
            }
        } else image(page.meta.optString("backgroundImage"),(max(page.width,page.height)*scale).roundToInt())?.let{canvas.drawBitmap(it,null,RectF(0f,0f,page.width,page.height),null)}
    }
    override fun close(){closed=true;worker.execute{pdfs.values.forEach{it.close()};pdfs.clear()};worker.shutdown();cache.evictAll()}
    companion object {
        fun decodeImage(file:File,maxSide:Int):Bitmap?{
            val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,options)
            var sample=1;while(max(options.outWidth,options.outHeight)/sample>maxSide)sample*=2
            return BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=sample})
        }
    }
}

object PdfImporter {
    fun import(repository:NoteRepository,input:java.io.InputStream,title:String,folder:String,appendTo:String?=null,insertAt:Int=0):DocumentInfo {
        val asset=input.use{repository.storeAsset(it,"pdf")}
        try {
            val renderer=PdfRenderer(ParcelFileDescriptor.open(repository.asset(asset),ParcelFileDescriptor.MODE_READ_ONLY))
            renderer.use { pdf ->
                require(pdf.pageCount>0){"PDF에 페이지가 없습니다."}
                val doc=appendTo?.let{requireNotNull(repository.document(it))}?:DocumentInfo(uid("doc"),json("title" to title,"folderId" to folder,"version" to 5,"schema" to "com.inkforge.ifnote","pdfSourceName" to title))
                repository.transaction {
                    if(appendTo==null)repository.putDocument(doc)
                    repeat(pdf.pageCount){i->pdf.openPage(i).use { source->
                        val page=NotePage.blank("blank",1000f,1000f*source.height/source.width)
                        page.meta.put("pdfSource","asset:$asset").put("pdfPageNumber",i+1).put("pdfSourceName",title)
                            .put("pdfPointWidth",source.width).put("pdfPointHeight",source.height).put("importedFromPdf",true)
                        if(Build.VERSION.SDK_INT>=35)try{page.meta.put("pdfText",source.textContents.joinToString(" "){it.text})}catch(_:Exception){}
                        if(appendTo!=null)repository.addPage(doc.id,page,insertAt+i) else repository.putPage(doc.id,page,i)
                    }}
                }
                return doc
            }
        } catch(e:Exception){repository.asset(asset).delete();throw e}
    }
}
