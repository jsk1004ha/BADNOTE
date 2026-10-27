package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import org.json.JSONArray

/** Pixel checks for incremental composition, including a later append after non-append damage. */
internal object InkSceneCacheAppendChecks {
    fun run():String {
        val width=768;val height=640;val x=13.25f;val y=19.5f;val scale=.91f
        val cache=InkSceneCache();val renderer=NoteRenderer()
        data class Entry(val picture:Picture,val bounds:InkBounds)
        val entries=ArrayList<Entry>()
        val output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val expected=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val image=Bitmap.createBitmap(110,90,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff63979d.toInt())}
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        fun screen(bounds:InkBounds)=InkBounds(x+bounds.left*scale-2,y+bounds.top*scale-2,
            x+bounds.right*scale+2,y+bounds.bottom*scale+2)
        fun inPage(canvas:Canvas,draw:()->Unit){val saved=canvas.save();canvas.translate(x,y);canvas.scale(scale,scale)
            canvas.clipRect(0f,0f,800f,680f);draw();canvas.restoreToCount(saved)}
        fun render(canvas:Canvas,damage:List<Rect>?){
            canvas.drawColor(0xfff7f7f8.toInt())
            inPage(canvas){
                canvas.drawColor(Color.WHITE)
                paint.color=0xffd9e0e6.toInt();paint.strokeWidth=.8f
                for(line in 0..22)canvas.drawLine(0f,line*30f,800f,line*30f,paint)
                canvas.drawBitmap(image,160f,150f,null)
                for(entry in entries){val b=screen(entry.bounds)
                    if(damage==null||damage.any{b.left<=it.right&&b.right>=it.left&&b.top<=it.bottom&&b.bottom>=it.top})
                        canvas.drawPicture(entry.picture)
                }
            }
        }
        fun stroke(id:String,color:String,brush:String,opacity:Double,start:Float,rotation:Double=0.0):Entry {
            val points=JSONArray()
            for(index in 0..18)points.put(json("x" to start+index*12f,"y" to 190f+index%5*23f,
                "p" to .2f+index%7*.1f,"t" to index*11))
            val obj=json("id" to id,"type" to "stroke","brush" to brush,"color" to color,
                "width" to 24,"opacity" to opacity,"rotation" to rotation,"points" to points)
            val picture=Picture();val canvas=picture.beginRecording(800,680)
            val bounds=requireNotNull(renderer.drawMeasured(canvas,obj,scale.toDouble()))
            picture.endRecording();return Entry(picture,bounds)
        }
        var compared=0L
        fun exact(label:String){
            render(Canvas(expected),null)
            val actual=IntArray(width*height);val golden=IntArray(actual.size)
            output.getPixels(actual,0,width,0,0,width,height);expected.getPixels(golden,0,width,0,0,width,height)
            for(index in actual.indices)check(actual[index]==golden[index]){"$label differs at ${index%width},${index/width}"}
            compared+=actual.size
        }
        fun draw(){check(cache.prepare(width,height,x,y,scale));cache.draw(Canvas(output),::render)}
        fun append(entry:Entry){entries.add(entry);val b=screen(entry.bounds)
            check(cache.append(width,height,x,y,scale,b.left,b.top,b.right,b.bottom){target->inPage(target){target.drawPicture(entry.picture)}})
            draw()
        }
        try {
            entries.add(stroke("base","#4b3355","fountain",.7,65f))
            draw();exact("cold base")
            append(stroke("multiply","#ffe066","highlighter",.32,85f));exact("multiply append")
            append(stroke("alpha","#b91f65","ballpoint",.43,110f,.19));exact("alpha rotated append")
            val removed=entries.removeAt(0);val damage=screen(removed.bounds)
            cache.invalidate(damage.left,damage.top,damage.right,damage.bottom)
            draw();exact("partial removal")
            append(stroke("after-removal","#145acc","fountain",.65,405f));exact("append after partial removal")
            return "PASS: $compared pixels; multiply/alpha/rotation/image ordering and canonical base after partial damage"
        } finally {cache.clear();output.recycle();expected.recycle();image.recycle()}
    }
}
