package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Picture
import android.graphics.Paint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual pixel ownership across an obsolete worker result and a later append. */
internal object InkSceneRasterizerChecks {
    fun run(ui:((()->Unit)->Unit)):String {
        val finished=CountDownLatch(1)
        val failure=AtomicReference<Throwable?>()
        val blue=0xff2458b8.toInt();val green=0xff22aa44.toInt()
        fun scene(color:Int)=Picture().apply{val canvas=beginRecording(300,280);canvas.drawColor(color);endRecording()}
        val rasterizer=InkSceneRasterizer{_,prepared->
            val cache=InkSceneCache();var installed=false
            try{
                check(prepared.bitmap.getPixel(150,140)==blue){"An obsolete canonical scene was published"}
                cache.install(prepared);installed=true
                val output=Bitmap.createBitmap(300,280,Bitmap.Config.ARGB_8888)
                try{
                    val brush=Paint().apply{color=green}
                    cache.append(300,280,0f,0f,1f,100f,100f,200f,200f){canvas->
                        canvas.drawRect(100f,100f,200f,200f,brush)}
                    cache.draw(Canvas(output)){canvas,_->canvas.drawColor(blue);canvas.drawRect(100f,100f,200f,200f,brush)}
                    check(output.getPixel(150,140)==green&&output.getPixel(20,20)==blue){"Installed base/append pixels differ"}
                    check(prepared.images[0].getPixel(150,140)==blue){"A published immutable tile was mutated by an append"}
                }finally{output.recycle()}
            }catch(t:Throwable){failure.compareAndSet(null,t)}
            finally{cache.clear();if(!installed)prepared.discard();finished.countDown()}
        }
        try{
            ui{
                val old=rasterizer.generation
                rasterizer.request(old,scene(Color.RED),300,280,0f,0f,1f)
                val current=rasterizer.advance()
                rasterizer.request(current,scene(blue),300,280,0f,0f,1f)
                // Main-thread publication cannot run before both requests/generation changes finish.
            }
            check(finished.await(10,TimeUnit.SECONDS)){"Canonical raster result did not publish"}
            failure.get()?.let{throw it}
            return "PASS: obsolete result excluded; new scene pixels, append and immutable published tiles preserved"
        }finally{ui{rasterizer.close()}}
    }
}
