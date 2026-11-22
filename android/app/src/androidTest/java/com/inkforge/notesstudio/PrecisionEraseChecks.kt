package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.abs

/** Pixel assertions catch width inflation and accidental deletion of the rest of an object. */
internal object PrecisionEraseChecks {
    fun run(result:Bundle) {
        val fixtures=listOf(
            json("id" to "highlight","type" to "stroke","brush" to "highlighter","width" to 100,"color" to "#ffe066",
                "points" to JSONArray(listOf(InkPoint(60f,120f).json(),InkPoint(400f,120f).json()))),
            json("id" to "shape","type" to "shape","shape" to "rectangle","x1" to 60,"y1" to 70,"x2" to 400,"y2" to 230,"width" to 30),
            json("id" to "text","type" to "text","text" to "Goodnotes 한글","fontSize" to 64,"x" to 60,"y" to 70,"w" to 400,"h" to 180,"textLayoutVersion" to 1))
        fun render(obj:JSONObject)=Bitmap.createBitmap(512,360,Bitmap.Config.ARGB_8888).also {
            NoteRenderer().draw(Canvas(it),obj)
        }
        for(source in fixtures)for(angle in listOf(0f,.32f)) {
            val obj=source.copyJson().put("rotation",angle)
            val before=render(obj)
            // Pick actual painted pixels, including the off-center edge of the thick highlighter.
            val center=if(source.optString("id")=="highlight"&&angle==0f)InkPoint(230f,90f) else {
                var found:InkPoint?=null
                for(y in 85..230) { for(x in 90..350)if(Color.alpha(before.getPixel(x,y))>20) {found=InkPoint(x.toFloat(),y.toFloat());break}
                    if(found!=null)break }
                checkNotNull(found)
            }
            val radius=12f;val snapshot=obj.toString()
            val erased=ObjectEraseMask.erase(obj,listOf(center),radius)
            check(erased!==obj && obj.toString()==snapshot) { "Mask mutated source or missed ${source.optString("id")}" }
            check(ObjectEraseMask.erase(erased,listOf(center),radius)===erased) { "Repeated erasing rewrites unchanged mask" }
            val after=render(JSONObject(erased.toString()))
            var removed=0;var surviving=0
            for(y in 0 until before.height)for(x in 0 until before.width) {
                val distance=hypot(x+.5f-center.x,y+.5f-center.y)
                val old=before.getPixel(x,y);val new=after.getPixel(x,y)
                if(distance>radius+2) {
                    // Skia's complex clip slightly changes coverage at existing antialiased edges
                    // (e.g. alpha 251 -> 255); compare the visible result over white, within 8/255.
                    fun overWhite(value:Int,shift:Int):Int {
                        val alpha=Color.alpha(value);return 255-alpha+((value ushr shift) and 255)*alpha/255
                    }
                    check(listOf(0,8,16).all{shift->abs(overWhite(old,shift)-overWhite(new,shift))<=8}) {
                        "Erased beyond circle: ${source.optString("id")} angle=$angle at $x,$y old=${Integer.toHexString(old)} new=${Integer.toHexString(new)} center=$center"
                    }
                }
                if(distance<radius-2&&Color.alpha(old)>0) {check(Color.alpha(new)==0);removed++}
                if(distance>radius+2&&Color.alpha(new)>0)surviving++
            }
            check(removed>0&&surviving>0) { "Whole object removed or cutout invisible" }
            val moved=InkGeometry.transform(erased,20f,10f)
            val movedImage=render(moved)
            check(Color.alpha(movedImage.getPixel(center.x.toInt()+20,center.y.toInt()+10))==0) { "Move detached mask" }
            // Object-local clipping must reveal underlying ink, not clear the page itself.
            val layered=Bitmap.createBitmap(512,360,Bitmap.Config.ARGB_8888)
            Canvas(layered).apply { drawColor(Color.MAGENTA);NoteRenderer().draw(this,erased) }
            check(layered.getPixel(center.x.toInt(),center.y.toInt())==Color.MAGENTA)
            before.recycle();after.recycle();movedImage.recycle();layered.recycle()
        }
        result.putString("precisionErasePixels","PASS: text/shape/thick highlighter, circle boundary, rotation, move, repeated sample, JSON reload, underlying content")
    }
}
