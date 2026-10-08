package com.inkforge.notesstudio

import android.graphics.*
import org.json.JSONObject
import kotlin.math.*

class NoteRenderer(private val image: (String)->Bitmap? = { null }) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    fun color(value: String,default: Int=Color.BLACK)=try{Color.parseColor(value)}catch(_:Exception){default}
    fun template(canvas: Canvas,page: NotePage) {
        canvas.drawColor(Color.WHITE)
        paint.reset();paint.isAntiAlias=true;paint.color=0xffdce2e9.toInt();paint.strokeWidth=max(1f,page.width/1000f)
        val sx=page.width/1000f;val sy=page.height/1414f
        when(page.meta.optString("template","blank")) {
            "grid","dotted" -> {
                val dotted=page.meta.optString("template")=="dotted";val spacing=(if(dotted)44 else 42)*sx
                if(dotted)paint.color=0xffcbd4de.toInt()
                var y=spacing;while(y<page.height){var x=spacing;while(x<page.width){
                    if(dotted)canvas.drawCircle(x,y,max(.8f,sx*1.5f),paint)
                    else if(y==spacing)canvas.drawLine(x,0f,x,page.height,paint)
                    x+=spacing
                };if(!dotted)canvas.drawLine(0f,y,page.width,y,paint);y+=spacing}
            }
            "lined","cornell" -> {var y=112*sy;while(y<page.height-50*sy){canvas.drawLine(62*sx,y,page.width-54*sx,y,paint);y+=52*sy}
                if(page.meta.optString("template")=="cornell"){paint.color=0xffbdc8d4.toInt();canvas.drawLine(255*sx,55*sy,255*sx,page.height-55*sy,paint);canvas.drawLine(55*sx,page.height-265*sy,page.width-55*sx,page.height-265*sy,paint)}}
            "planner" -> {
                paint.color=0xff536273.toInt();paint.textSize=28*sx;paint.typeface=Typeface.create("sans-serif",Typeface.BOLD);canvas.drawText("WEEKLY PLAN",60*sx,80*sy,paint)
                paint.textSize=15*sx;paint.typeface=Typeface.create("sans-serif",Typeface.NORMAL)
                val colW=(page.width-120*sx)/2;val rowH=155*sy
                listOf("MON","TUE","WED","THU","FRI","SAT","SUN").forEachIndexed{i,day->val x=60*sx+(i%2)*colW;val y=125*sy+(i/2)*rowH
                    paint.color=0xffcbd4de.toInt();paint.strokeWidth=2*sx;paint.style=Paint.Style.STROKE;canvas.drawRect(x,y,x+colW-18*sx,y+rowH-20*sy,paint)
                    paint.style=Paint.Style.FILL;paint.color=0xff536273.toInt();canvas.drawText(day,x+14*sx,y+26*sy,paint)}
            }
        }
    }
    fun draw(canvas: Canvas,obj: JSONObject) {
        if(obj.optBoolean("hidden")||obj.optString("type")=="ocrIndex")return
        paint.reset();paint.isAntiAlias=true;paint.color=color(obj.optString("color","#172033"));paint.alpha=(obj.f("opacity",1f)*255).toInt().coerceIn(0,255)
        val type=obj.optString("type")
        when(type) {
            "stroke" -> stroke(canvas,obj,InkGeometry.points(obj))
            "shape" -> shape(canvas,obj)
            "image" -> image(obj.optString("src"))?.let{canvas.drawBitmap(it,null,RectF(obj.f("x"),obj.f("y"),obj.f("x")+obj.f("w"),obj.f("y")+obj.f("h")),paint)}
            "tape" -> {
                val b=InkGeometry.bounds(obj);paint.color=color(obj.optString("color","#4c91dd"));paint.alpha=if(obj.optBoolean("revealed"))32 else 255
                canvas.drawRoundRect(b.left,b.top,b.right,b.bottom,7f,7f,paint)
                if(!obj.optBoolean("revealed")){paint.color=0x30ffffff;var x=b.left+8;while(x<b.right){canvas.drawRect(x,b.top+2,min(x+5,b.right),b.bottom-2,paint);x+=23}}
            }
            "text","sticky","math","sticker" -> {
                val x=obj.f("x");val y=obj.f("y");val w=obj.f("w",500f);val h=obj.f("h",100f)
                val pad=if(type=="sticky"||type=="math")18f else 0f
                if(type=="sticky"||type=="math"){
                    paint.color=color(if(type=="sticky")obj.optString("color","#ffe58d") else obj.optString("background","#edf5fc"))
                    canvas.drawRoundRect(x,y,x+w,y+h,10f,10f,paint);paint.color=if(type=="sticky")0xff26313d.toInt() else color(obj.optString("color","#225e9d"))
                }
                paint.textSize=obj.f("fontSize",if(type=="sticker")90f else 28f)
                paint.typeface=Typeface.create(if(type=="math")"monospace" else "sans-serif",if(obj.optInt("fontWeight",400)>=600)Typeface.BOLD else Typeface.NORMAL)
                val text=if(type=="math")"${obj.optString("expression")} = ${obj.opt("result") ?: ""}" else obj.optString("text")
                var line="";var base=y+pad-paint.fontMetrics.top;val lineHeight=paint.textSize*1.36f
                for(ch in text){
                    if(ch=='\n'||(line.isNotEmpty()&&paint.measureText(line+ch)>w-pad*2)){
                        canvas.drawText(line,x+pad,base,paint);base+=lineHeight;line=""
                    }
                    if(ch!='\n')line+=ch
                }
                canvas.drawText(line,x+pad,base,paint)
            }
        }
    }
    fun stroke(canvas: Canvas,obj: JSONObject,rawPoints: List<InkPoint>) {
        if(rawPoints.isEmpty())return
        val settings=obj.optJSONObject("settings")
        val smoothing=settings?.f("smoothing",0f)?.coerceIn(0f,.9f)?:0f
        val points=if(smoothing>0&&rawPoints.size>2)rawPoints.mapIndexed{i,p->
            if(i==0||i==rawPoints.lastIndex)p else p.copy(x=p.x*(1-smoothing)+(rawPoints[i-1].x+rawPoints[i+1].x)*smoothing/2,y=p.y*(1-smoothing)+(rawPoints[i-1].y+rawPoints[i+1].y)*smoothing/2)
        }else rawPoints
        paint.reset();paint.isAntiAlias=true;paint.color=color(obj.optString("color","#172033"))
        val brush=obj.optString("brush","fountain");val width=obj.f("width",4f)
        paint.alpha=(obj.f("opacity",if(brush=="highlighter").32f else 1f)*(settings?.f("opacity",1f)?:1f)*255).toInt().coerceIn(0,255)
        if(brush=="pencil")paint.alpha=(paint.alpha*(.55f+(settings?.f("hardness",.58f)?:.58f)*.36f)).toInt()
        val response=obj.optJSONObject("settings")?.f("pressure",when(brush){"ballpoint","fineliner"->.05f;"gel"->.16f;"brush"->.95f;"highlighter","marker"->0f;else->.7f})
            ?: when(brush){"ballpoint","fineliner"->.05f;"gel"->.16f;"brush"->.95f;"highlighter","marker"->0f;else->.7f}
        fun radius(p:InkPoint):Float {
            val pressure=p.pressure.coerceIn(.03f,1.5f)
            var r=width*.5f*((1-response)+response*(.2f+pressure*1.6f))
            if(brush=="pencil")r*=1+abs(sin(p.tilt))*(settings?.f("tiltShade",.82f)?:.82f)
            if(brush=="calligraphy")r*=.5f+abs(cos(p.orientation))
            if(brush=="fountain")r*=1-(settings?.f("sharpness",0f)?:0f)*.2f*abs(sin(p.orientation))
            return r.coerceAtLeast(.2f)
        }
        if(points.size==1){canvas.drawCircle(points[0].x,points[0].y,radius(points[0]),paint);return}
        // One filled outline avoids darker self-overlap at every pressure sample.
        val left=ArrayList<PointF>(points.size);val right=ArrayList<PointF>(points.size)
        points.forEachIndexed{i,p->val a=points[max(0,i-1)];val b=points[min(points.lastIndex,i+1)]
            val len=hypot(b.x-a.x,b.y-a.y).coerceAtLeast(.001f)
            val taper=settings?.f("taper",0f)?:0f;val ends=(min(i,points.lastIndex-i)/max(1f,points.lastIndex*.12f)).coerceIn(0f,1f)
            val r=radius(p)*(1-taper*.8f*(1-ends))
            val nx=-(b.y-a.y)/len*r;val ny=(b.x-a.x)/len*r
            left+=PointF(p.x+nx,p.y+ny);right+=PointF(p.x-nx,p.y-ny)}
        val path=Path();path.moveTo(left[0].x,left[0].y)
        for(i in 1 until left.size)path.lineTo(left[i].x,left[i].y)
        for(i in right.indices.reversed())path.lineTo(right[i].x,right[i].y)
        path.close();canvas.drawPath(path,paint)
        if(brush=="pencil"){
            val grain=settings?.f("grain",0f)?:0f
            if(grain>0){val alpha=paint.alpha;paint.color=Color.WHITE;paint.alpha=(grain*65).toInt()
                points.forEachIndexed{i,p->if(i%2==0)canvas.drawCircle(p.x+sin(i*2.39f)*width*.2f,p.y+cos(i*1.73f)*width*.2f,max(.3f,width*.08f),paint)}
                paint.color=color(obj.optString("color","#172033"));paint.alpha=alpha}
        }
        // Opaque pens can have round caps without multiplying highlighter alpha.
        if(brush!="highlighter"){canvas.drawCircle(points.first().x,points.first().y,radius(points.first()),paint);canvas.drawCircle(points.last().x,points.last().y,radius(points.last()),paint)}
    }
    private fun shape(canvas:Canvas,obj:JSONObject){
        val x1=obj.f("x1");val y1=obj.f("y1");val x2=obj.f("x2");val y2=obj.f("y2")
        val bounds=RectF(min(x1,x2),min(y1,y2),max(x1,x2),max(y1,y2))
        paint.style=Paint.Style.STROKE;paint.strokeWidth=obj.f("width",3f);paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND
        when(val shape=obj.optString("shape","line")){
            "ellipse","circle"->canvas.drawOval(bounds,paint)
            "rectangle","square"->canvas.drawRect(bounds,paint)
            "rounded-rectangle"->canvas.drawRoundRect(bounds,min(bounds.width(),bounds.height())*.12f,min(bounds.width(),bounds.height())*.12f,paint)
            "diamond","starshape","trapezoid","parallelogram"->{
                val vertices=when(shape){
                    "diamond"->listOf(.5f to 0f,1f to .5f,.5f to 1f,0f to .5f)
                    "trapezoid"->listOf(.2f to 0f,.8f to 0f,1f to 1f,0f to 1f)
                    "parallelogram"->listOf(.22f to 0f,1f to 0f,.78f to 1f,0f to 1f)
                    else->(0..9).map{i->val angle=-PI/2+i*PI/5;val r=if(i%2==0).5 else .22;(.5+cos(angle)*r).toFloat() to (.5+sin(angle)*r).toFloat()}
                };val path=Path();vertices.forEachIndexed{i,(x,y)->if(i==0)path.moveTo(bounds.left+x*bounds.width(),bounds.top+y*bounds.height())else path.lineTo(bounds.left+x*bounds.width(),bounds.top+y*bounds.height())};path.close();canvas.drawPath(path,paint)
            }
            "heartshape","cloudshape","speech"->{
                val normalized=when(shape){
                    "heartshape"->"M50 92 C10 63 0 40 6 21 C13 0 39 2 50 23 C61 2 87 0 94 21 C100 40 90 63 50 92 Z"
                    "cloudshape"->"M20 80 C0 83 -2 55 14 48 C0 27 23 10 36 22 C40 -4 72 0 76 20 C99 14 111 45 89 56 C105 79 73 96 59 79 C49 97 28 95 20 80 Z"
                    else->"M12 5 H88 Q98 5 98 15 V65 Q98 75 88 75 H40 L17 98 L22 75 H12 Q2 75 2 65 V15 Q2 5 12 5 Z"
                };val path=androidx.core.graphics.PathParser.createPathFromPathData(normalized)!!;val matrix=Matrix().apply{setScale(bounds.width()/100,bounds.height()/100);postTranslate(bounds.left,bounds.top)};path.transform(matrix);canvas.drawPath(path,paint)
            }
            "triangle","pentagon","hexagon"->{val n=if(shape=="triangle")3 else if(shape=="pentagon")5 else 6
                val path=Path();repeat(n){i->val a=-PI/2+2*PI*i/n;val x=bounds.centerX()+cos(a).toFloat()*bounds.width()/2;val y=bounds.centerY()+sin(a).toFloat()*bounds.height()/2
                    if(i==0)path.moveTo(x,y) else path.lineTo(x,y)};path.close();canvas.drawPath(path,paint)}
            "curve","arc"->{val path=Path();path.moveTo(x1,y1);path.quadTo(obj.f("cx",(x1+x2)/2),obj.f("cy",min(y1,y2)-abs(x2-x1)*.3f),x2,y2);canvas.drawPath(path,paint)}
            else->{canvas.drawLine(x1,y1,x2,y2,paint)
                if(shape=="arrow"||shape=="double-arrow"){
                    fun arrow(ax:Float,ay:Float,bx:Float,by:Float){val angle=atan2(by-ay,bx-ax);val size=max(14f,paint.strokeWidth*4)
                        for(sign in listOf(-1,1))canvas.drawLine(bx,by,bx-cos(angle+sign*.5f)*size,by-sin(angle+sign*.5f)*size,paint)}
                    arrow(x1,y1,x2,y2);if(shape=="double-arrow")arrow(x2,y2,x1,y1)
                }}
        }
        paint.style=Paint.Style.FILL
    }
}
