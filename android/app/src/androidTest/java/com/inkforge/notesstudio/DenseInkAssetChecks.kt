package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import android.view.MotionEvent
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import kotlin.math.ceil

/** Real asset publication while a contact and cached, locally damaged scene remain active. */
internal object DenseInkAssetChecks {
    fun run(activity:MainActivity,ui:(()->Unit)->Unit):String {
        val repository=activity.repository
        val document=repository.create("dense-asset-raster-regression","root","blank")
        val assets=ArrayList<String>()
        lateinit var view:InkCanvasView
        var viewCreated=false
        val actual=Bitmap.createBitmap(1080,1600,Bitmap.Config.ARGB_8888)
        val expected=Bitmap.createBitmap(1080,1600,Bitmap.Config.ARGB_8888)
        var referenceImage:Bitmap?=null
        try{
            val png=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).let{image->
                try{
                    for(y in 0 until 64)for(x in 0 until 64)image.setPixel(x,y,
                        Color.argb(60+(x+y)%160,30+x*3,80+y*2,190))
                    ByteArrayOutputStream().use{output->check(image.compress(Bitmap.CompressFormat.PNG,100,output));output.toByteArray()}
                }finally{image.recycle()}
            }
            referenceImage=checkNotNull(BitmapFactory.decodeByteArray(png,0,png.size))
            val imageAsset=png.inputStream().use{repository.storeAsset(it,"png")};assets.add(imageAsset)
            val page=checkNotNull(repository.page(repository.pageIds(document.id).single()))
            fun stroke(id:String,y:Float,color:String,width:Int,brush:String="ballpoint")=json(
                "id" to id,"type" to "stroke","brush" to brush,"color" to color,"width" to width,"opacity" to .45,
                "points" to JSONArray(listOf(json("x" to 250,"y" to y,"p" to .7),json("x" to 750,"y" to y,"p" to .7))))
            fun erasable()=json("id" to "erase-during-load","type" to "stroke","brush" to "ballpoint","width" to 10,
                "points" to JSONArray(listOf(json("x" to 100,"y" to 100),json("x" to 150,"y" to 100))))
            page.objects.add(erasable())
            page.objects.add(stroke("under-image",550f,"#b32821",70))
            page.objects.add(json("id" to "alpha-image","type" to "image","src" to "asset:$imageAsset",
                "x" to 300,"y" to 400,"w" to 400,"h" to 400,"opacity" to .55,"rotation" to .15))
            page.objects.add(stroke("over-image",650f,"#ffe066",60,"highlighter"))
            ui{view=InkCanvasView(activity,repository);viewCreated=true;view.layout(0,0,1080,1600);view.open(listOf(page.id),listOf(page.meta))}
            val loaded=java.util.concurrent.CountDownLatch(1)
            repository.executor.execute{loaded.countDown()}
            check(loaded.await(30,java.util.concurrent.TimeUnit.SECONDS))
            lateinit var input:StylusContactChecks.Input
            fun draw(){actual.eraseColor(Color.TRANSPARENT);view.draw(Canvas(actual))}
            fun pixel(x:Float,y:Float):Int{val p=view.screenPoint(x,y);return actual.getPixel(p.x.toInt(),p.y.toInt())}
            fun awaitPixels(label:String,ready:()->Boolean){
                val end=SystemClock.uptimeMillis()+30_000
                while(SystemClock.uptimeMillis()<end){
                    var present=false;ui{draw();present=ready()}
                    if(present)return
                    SystemClock.sleep(25)
                }
                error("$label did not publish while cached contact remained active")
            }
            fun compareImageRegion(){
                expected.eraseColor(0xfff7f7f8.toInt())
                val canvas=Canvas(expected);val origin=view.screenPoint(0f,0f)
                canvas.translate(origin.x,origin.y);canvas.scale(view.scale,view.scale);canvas.clipRect(0f,0f,page.width,page.height)
                val renderer=NoteRenderer();renderer.template(canvas,page)
                for(obj in page.objects)if(obj.optString("type")=="image")renderer.drawImage(canvas,obj,checkNotNull(referenceImage))
                    else renderer.draw(canvas,obj,view.scale.toDouble())
                val first=view.screenPoint(230f,340f);val last=view.screenPoint(780f,880f)
                val left=ceil(first.x).toInt();val right=last.x.toInt()
                val a=IntArray(right-left);val b=IntArray(right-left)
                for(y in ceil(first.y).toInt() until last.y.toInt()){
                    actual.getPixels(a,0,a.size,left,y,a.size,1);expected.getPixels(b,0,b.size,left,y,b.size,1)
                    check(a.contentEquals(b)){"Loaded image alpha/interleaving raster changed at row $y"}
                }
            }
            ui{
                view.replacePage(page);view.tool=InkCanvasView.Tool.ERASER;view.eraserRadius=6f
                input=StylusContactChecks.Input(view)
                // Worker results cannot publish until this main-thread block returns. Draw a cache
                // hit and a local erase before then; unrelated image requests must remain active.
                draw();draw();input.touch(MotionEvent.ACTION_DOWN,125f,100f)
                input.touch(MotionEvent.ACTION_MOVE,200f,100f);draw()
                check(page.objects.none{it.optString("id")=="erase-during-load"})
            }
            awaitPixels("PNG image"){pixel(500f,500f)!=Color.WHITE}
            ui{compareImageRegion();input.touch(MotionEvent.ACTION_UP,200f,100f);draw();compareImageRegion()}

            val pdfBytes=ByteArrayOutputStream().use{output->
                val pdf=PdfDocument()
                try{
                    val sheet=pdf.startPage(PdfDocument.PageInfo.Builder(1000,1414,1).create())
                    sheet.canvas.drawColor(Color.rgb(210,230,250))
                    val paint=Paint().apply{color=Color.rgb(80,120,170);strokeWidth=3f}
                    sheet.canvas.drawLine(650f,950f,850f,1150f,paint)
                    pdf.finishPage(sheet);pdf.writeTo(output)
                }finally{pdf.close()}
                output.toByteArray()
            }
            val pdfAsset=pdfBytes.inputStream().use{repository.storeAsset(it,"pdf")};assets.add(pdfAsset)
            val pdfPage=NotePage(page.id,json("width" to 1000,"height" to 1414,"template" to "blank",
                "pdfSource" to "asset:$pdfAsset","pdfPageNumber" to 1),mutableListOf(erasable()))
            ui{
                view.replacePage(pdfPage);draw();draw()
                input.touch(MotionEvent.ACTION_DOWN,125f,100f);input.touch(MotionEvent.ACTION_MOVE,200f,100f);draw()
            }
            awaitPixels("PDF background"){
                val p=pixel(700f,900f);Color.red(p) in 190..225&&Color.blue(p)>235
            }
            ui{
                val erased=pixel(125f,100f)
                check(Color.red(erased)>190&&Color.blue(erased)>235){"In-contact eraser left stale ink over loaded PDF"}
                input.touch(MotionEvent.ACTION_UP,200f,100f);draw()
                check(Color.blue(pixel(700f,900f))>235){"PDF background disappeared at contact completion"}
            }
            return "PASS: real PNG alpha/order before and after lift; pending PNG/PDF publication during cached local erase"
        }finally{
            if(viewCreated)ui{view.close()}
            referenceImage?.recycle();actual.recycle();expected.recycle()
            repository.deleteDocument(document.id);assets.forEach{repository.asset(it).delete()}
        }
    }
}
