package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

data class InkPoint(val x: Float, val y: Float, val pressure: Float = .5f, val time: Long = 0,
                    val tilt: Float = 0f, val orientation: Float = 0f, val pointerId: Int = -1,
                    val azimuth: Float? = null, val tx: Float? = null, val ty: Float? = null,
                    val original: JSONObject? = null) {
    fun json(): JSONObject {
        // Imported points retain null vs missing fields and every unknown extension field.
        val value = original?.copyJson() ?: json("p" to pressure, "t" to time,
            "tilt" to tilt, "orientation" to orientation, "pointerId" to pointerId)
        value.put("x", x).put("y", y)
        if (original == null) {
            azimuth?.let { value.put("azimuth", it) }
            tx?.let { value.put("tx", it) };ty?.let { value.put("ty", it) }
        }
        return value
    }
    companion object { fun from(p: JSONObject) = InkPoint(p.f("x"), p.f("y"), p.f("p", .5f),
        p.optLong("t"), p.f("tilt"), p.f("orientation"), p.optInt("pointerId", -1),
        p.f("azimuth").takeIf { p.has("azimuth") && !p.isNull("azimuth") },
        p.f("tx").takeIf { p.has("tx") && !p.isNull("tx") },
        p.f("ty").takeIf { p.has("ty") && !p.isNull("ty") }, p.copyJson()) }
}
data class InkBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    fun overlaps(b: InkBounds) = left <= b.right && right >= b.left && top <= b.bottom && bottom >= b.top
    fun expanded(d: Float) = InkBounds(left-d, top-d, right+d, bottom+d)
}

object InkGeometry {
    fun points(obj: JSONObject) = obj.array("points").objects().map(InkPoint::from)
    fun pagePoints(obj: JSONObject): List<InkPoint> {
        val points = points(obj); val angle = obj.f("rotation").toDouble()
        if (angle == 0.0) return points
        val b = unrotatedBounds(obj); val cx = (b.left+b.right)/2; val cy = (b.top+b.bottom)/2
        return points.map { p -> val x=p.x-cx; val y=p.y-cy
            p.copy(x=(cx+x*cos(angle)-y*sin(angle)).toFloat(),y=(cy+x*sin(angle)+y*cos(angle)).toFloat()) }
    }
    fun unrotatedBounds(obj: JSONObject): InkBounds {
        if (obj.optString("type") == "stroke") {
            val p = points(obj)
            if (p.isEmpty()) return InkBounds(0f,0f,0f,0f)
            val r = obj.f("width",4f) / 2
            return InkBounds(p.minOf{it.x}-r,p.minOf{it.y}-r,p.maxOf{it.x}+r,p.maxOf{it.y}+r)
        }
        if (obj.optString("type") == "shape" || (obj.optString("type") == "tape" && obj.has("x1") && obj.has("x2"))) {
            val x1=obj.f("x1");val y1=obj.f("y1");val x2=obj.f("x2");val y2=obj.f("y2")
            if(obj.optString("type")!="shape")return InkBounds(min(x1,x2),min(y1,y2),max(x1,x2),max(y1,y2))
            val shape=obj.optString("shape","line");val width=obj.f("width",3f)
            var box=InkBounds(min(x1,x2),min(y1,y2),max(x1,x2),max(y1,y2))
            if(shape=="curve"||shape=="arc"){
                val cx=obj.f("cx",(x1+x2)/2);val cy=obj.f("cy",min(y1,y2)-abs(x2-x1)*.3f)
                box=InkBounds(min(box.left,cx),min(box.top,cy),max(box.right,cx),max(box.bottom,cy))
            }
            if(shape=="cloudshape")box=InkBounds(box.left-box.width*.02f,box.top-box.height*.04f,
                box.right+box.width*.11f,box.bottom)
            return box.expanded(if(shape=="arrow"||shape=="double-arrow")max(14f,width*4) else width)
        }
        return InkBounds(obj.f("x"),obj.f("y"),obj.f("x")+obj.f("w",300f),obj.f("y")+obj.f("h",80f))
    }
    fun bounds(obj: JSONObject): InkBounds {
        val b = unrotatedBounds(obj)
        val angle = obj.f("rotation").toDouble()
        if (angle == 0.0) return b
        val halfW = b.width / 2; val halfH = b.height / 2
        val c = abs(cos(angle)).toFloat(); val s = abs(sin(angle)).toFloat()
        val x = (b.left + b.right) / 2; val y = (b.top + b.bottom) / 2
        val w = halfW * c + halfH * s; val h = halfW * s + halfH * c
        return InkBounds(x-w,y-h,x+w,y+h)
    }
    fun distance(a: InkPoint,b: InkPoint) = hypot(a.x-b.x,a.y-b.y)
    fun segmentDistance(p: InkPoint,a: InkPoint,b: InkPoint): Float {
        val dx=b.x-a.x; val dy=b.y-a.y; val len=dx*dx+dy*dy
        val t=if(len<=.00001f) 0f else (((p.x-a.x)*dx+(p.y-a.y)*dy)/len).coerceIn(0f,1f)
        return hypot(p.x-a.x-t*dx,p.y-a.y-t*dy)
    }
    fun hit(obj: JSONObject,p: InkPoint,radius: Float): Boolean {
        if(obj.optBoolean("locked") || obj.optBoolean("hidden") || !bounds(obj).expanded(radius).contains(p.x,p.y)) return false
        val angle = obj.f("rotation").toDouble()
        val point = if (angle == 0.0) p else {
            val b = unrotatedBounds(obj); val cx = (b.left+b.right)/2; val cy = (b.top+b.bottom)/2
            val dx = p.x-cx; val dy = p.y-cy
            p.copy(x=(cx+dx*cos(angle)+dy*sin(angle)).toFloat(),y=(cy-dx*sin(angle)+dy*cos(angle)).toFloat())
        }
        if(obj.optString("type")=="shape") return shapeHit(obj,point,radius)
        if(obj.optString("type")!="stroke") return unrotatedBounds(obj).expanded(radius).contains(point.x,point.y)
        val points=points(obj); val r=radius+obj.f("width",4f)/2
        return points.any{distance(it,point)<=r} || points.zipWithNext().any{segmentDistance(point,it.first,it.second)<=r}
    }
    private fun shapeHit(obj:JSONObject,point:InkPoint,radius:Float):Boolean {
        val x1=obj.f("x1");val y1=obj.f("y1");val x2=obj.f("x2");val y2=obj.f("y2")
        val left=min(x1,x2);val top=min(y1,y2);val right=max(x1,x2);val bottom=max(y1,y2)
        val w=max(1f,right-left);val h=max(1f,bottom-top)
        val r=radius+obj.f("width",3f)/2
        val shape=obj.optString("shape","line")
        fun vertex(x:Float,y:Float)=InkPoint(x,y)
        fun polygon(vertices:List<InkPoint>) =
            (vertices+vertices.first()).zipWithNext().any{
                segmentDistance(point,it.first,it.second)<=r }
        if(shape in setOf("line","arrow","double-arrow")) {
            val a=vertex(x1,y1);val b=vertex(x2,y2)
            if(segmentDistance(point,a,b)<=r)return true
            if(shape=="line")return false
            fun arrow(from:InkPoint,to:InkPoint):Boolean {
                val angle=atan2(to.y-from.y,to.x-from.x)
                val size=max(14f,obj.f("width",3f)*4)
                return listOf(-.5f,.5f).any{sign->segmentDistance(point,to,
                    vertex(to.x-cos(angle+sign)*size,to.y-sin(angle+sign)*size))<=r}
            }
            return arrow(a,b) || shape=="double-arrow"&&arrow(b,a)
        }
        if(shape=="ellipse"||shape=="circle") {
            val cx=(left+right)/2;val cy=(top+bottom)/2
            val rx=w/2;val ry=h/2
            val count=128
            val start=0.0;val end=PI*2
            var prior=vertex(cx+cos(start).toFloat()*rx,cy+sin(start).toFloat()*ry)
            for(i in 1..count){val angle=start+(end-start)*i/count
                val next=vertex(cx+cos(angle).toFloat()*rx,cy+sin(angle).toFloat()*ry)
                if(segmentDistance(point,prior,next)<=r)return true
                prior=next
            }
            return false
        }
        if(shape=="curve"||shape=="arc") {
            val cx=obj.f("cx",(x1+x2)/2);val cy=obj.f("cy",min(y1,y2)-abs(x2-x1)*.3f)
            var prior=vertex(x1,y1)
            for(i in 1..64){val t=i/64f;val inv=1-t
                val next=vertex(inv*inv*x1+2*inv*t*cx+t*t*x2,inv*inv*y1+2*inv*t*cy+t*t*y2)
                if(segmentDistance(point,prior,next)<=r)return true
                prior=next
            }
            return false
        }
        if(shape=="heartshape"||shape=="cloudshape"||shape=="speech"||shape=="rounded-rectangle") {
            val outline=mutableListOf<Pair<Double,Double>>()
            var current=0.0 to 0.0
            fun move(x:Double,y:Double){current=x to y;outline+=current}
            fun line(x:Double,y:Double){current=x to y;outline+=current}
            fun quad(cx:Double,cy:Double,x:Double,y:Double){val (sx,sy)=current
                for(i in 1..16){val t=i/16.0;val a=1-t
                    outline+=(a*a*sx+2*a*t*cx+t*t*x) to (a*a*sy+2*a*t*cy+t*t*y)}
                current=x to y
            }
            fun cubic(ax:Double,ay:Double,bx:Double,by:Double,x:Double,y:Double){val (sx,sy)=current
                for(i in 1..16){val t=i/16.0;val a=1-t
                    outline+=(a*a*a*sx+3*a*a*t*ax+3*a*t*t*bx+t*t*t*x) to
                        (a*a*a*sy+3*a*a*t*ay+3*a*t*t*by+t*t*t*y)}
                current=x to y
            }
            when(shape){
                "heartshape"->{move(50.0,92.0);cubic(10.0,63.0,0.0,40.0,6.0,21.0)
                    cubic(13.0,0.0,39.0,2.0,50.0,23.0);cubic(61.0,2.0,87.0,0.0,94.0,21.0)
                    cubic(100.0,40.0,90.0,63.0,50.0,92.0)}
                "cloudshape"->{move(20.0,80.0);cubic(0.0,83.0,-2.0,55.0,14.0,48.0)
                    cubic(0.0,27.0,23.0,10.0,36.0,22.0);cubic(40.0,-4.0,72.0,0.0,76.0,20.0)
                    cubic(99.0,14.0,111.0,45.0,89.0,56.0);cubic(105.0,79.0,73.0,96.0,59.0,79.0)
                    cubic(49.0,97.0,28.0,95.0,20.0,80.0)}
                "speech"->{move(12.0,5.0);line(88.0,5.0);quad(98.0,5.0,98.0,15.0)
                    line(98.0,65.0);quad(98.0,75.0,88.0,75.0);line(40.0,75.0)
                    line(17.0,98.0);line(22.0,75.0);line(12.0,75.0)
                    quad(2.0,75.0,2.0,65.0);line(2.0,15.0);quad(2.0,5.0,12.0,5.0)}
                else->{val corner=min(w,h)*.12f;val rx=corner/w*100;val ry=corner/h*100
                    move(rx.toDouble(),0.0);line(100-rx.toDouble(),0.0)
                    quad(100.0,0.0,100.0,ry.toDouble());line(100.0,100-ry.toDouble())
                    quad(100.0,100.0,100-rx.toDouble(),100.0);line(rx.toDouble(),100.0)
                    quad(0.0,100.0,0.0,100-ry.toDouble());line(0.0,ry.toDouble())
                    quad(0.0,0.0,rx.toDouble(),0.0)}
            }
            return polygon(outline.map{(x,y)->vertex((left+x*w/100).toFloat(),(top+y*h/100).toFloat())})
        }
        val normalized:List<Pair<Float,Float>> = when(shape) {
            "triangle" -> (0 until 3).map { i -> val angle=-PI/2+2*PI*i/3
                (.5+cos(angle)*.5).toFloat() to (.5+sin(angle)*.5).toFloat() }
            "diamond" -> listOf(.5f to 0f,1f to .5f,.5f to 1f,0f to .5f)
            "pentagon","hexagon" -> { val n=if(shape=="pentagon")5 else 6
                (0 until n).map { i -> val angle=-PI/2+2*PI*i/n
                    (.5+cos(angle)*.5).toFloat() to (.5+sin(angle)*.5).toFloat() } }
            "starshape" -> (0 until 10).map { i -> val angle=-PI/2+i*PI/5;val size=if(i%2==0).5 else .22
                (.5+cos(angle)*size).toFloat() to (.5+sin(angle)*size).toFloat() }
            "trapezoid" -> listOf(.2f to 0f,.8f to 0f,1f to 1f,0f to 1f)
            "parallelogram" -> listOf(.22f to 0f,1f to 0f,.78f to 1f,0f to 1f)
            else -> listOf(0f to 0f,1f to 0f,1f to 1f,0f to 1f)
        }
        return polygon(normalized.map{(x,y)->vertex(left+x*w,top+y*h)})
    }
    private fun lerp(a: InkPoint,b: InkPoint,t: Float): InkPoint {
        if(t<=0f)return a
        if(t>=1f)return b
        val pressure=a.pressure+(b.pressure-a.pressure)*t
        val time=a.time+((b.time-a.time)*t).toLong()
        val tilt=a.tilt+(b.tilt-a.tilt)*t
        val orientation=a.orientation+(b.orientation-a.orientation)*t
        fun optional(first: Float?, second: Float?) = if(first!=null&&second!=null)first+(second-first)*t else first?:second
        val azimuth=optional(a.azimuth,b.azimuth);val tx=optional(a.tx,b.tx);val ty=optional(a.ty,b.ty)
        val original=(a.original?.copyJson() ?: JSONObject()).put("p",pressure).put("t",time)
            .put("tilt",tilt).put("orientation",orientation).put("pointerId",a.pointerId)
        azimuth?.let{original.put("azimuth",it)};tx?.let{original.put("tx",it)};ty?.let{original.put("ty",it)}
        return InkPoint(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,pressure,time,tilt,orientation,
            a.pointerId,azimuth,tx,ty,original)
    }

    /** Analytic segment/circle intersections preserve thin strokes with sparsely sampled points. */
    fun eraseParts(obj: JSONObject,center: InkPoint,radius: Float): List<JSONObject> {
        val points=pagePoints(obj)
        if(points.size<2) return if(points.any{distance(it,center)<=radius+obj.f("width",4f)/2}) emptyList() else listOf(obj)
        val r=radius+obj.f("width",4f)/2
        val fragments=mutableListOf<MutableList<InkPoint>>()
        var current=mutableListOf<InkPoint>()
        fun flush() { if(current.size>=2) fragments+=current; current=mutableListOf() }
        for((a,b) in points.zipWithNext()) {
            val dx=b.x-a.x; val dy=b.y-a.y; val ax=a.x-center.x; val ay=a.y-center.y
            val aa=dx*dx+dy*dy; val bb=2*(ax*dx+ay*dy); val cc=ax*ax+ay*ay-r*r
            val cuts=mutableListOf(0f,1f)
            val discriminant=bb*bb-4*aa*cc
            if(aa>1e-8f && discriminant>0) {
                val root=sqrt(discriminant)
                listOf((-bb-root)/(2*aa),(-bb+root)/(2*aa)).filter{it>0f&&it<1f}.forEach{cuts+=it}
            }
            cuts.sort()
            for((from,to) in cuts.zipWithNext()) {
                if(distance(lerp(a,b,(from+to)/2),center)>r) {
                    val first=lerp(a,b,from); val last=lerp(a,b,to)
                    if(current.isEmpty() || distance(current.last(),first)>.001f) current+=first
                    current+=last
                } else flush()
            }
        }
        flush()
        return fragments.mapIndexed{index,fragment->obj.copyJson().put("id",if(index==0)obj.getString("id") else uid("stroke"))
            .put("sourceStrokeId",obj.optString("sourceStrokeId",obj.getString("id"))).put("fragmentOrder",index)
            .put("revision",obj.optLong("revision",0)+1)
            .put("rotation",0)
            .put("points",JSONArray(fragment.map{it.json()}))}
    }
    fun inside(point: InkPoint,polygon: List<InkPoint>): Boolean {
        var inside=false; var j=polygon.lastIndex
        for(i in polygon.indices) { val a=polygon[i]; val b=polygon[j]
            if((a.y>point.y)!=(b.y>point.y) && point.x<(b.x-a.x)*(point.y-a.y)/(b.y-a.y)+a.x) inside=!inside
            j=i
        }
        return inside
    }
    fun transform(obj: JSONObject,dx: Float,dy: Float,scale: Float=1f,cx: Float=0f,cy: Float=0f): JSONObject = obj.copyJson().apply {
        fun tx(x: Float)=(x-cx)*scale+cx+dx
        fun ty(y: Float)=(y-cy)*scale+cy+dy
        when(optString("type")) {
            "stroke" -> { put("points",JSONArray(points(this).map{it.copy(x=tx(it.x),y=ty(it.y)).json()})); put("width",f("width",4f)*scale) }
            "shape", "tape" -> if(optString("type")=="shape" || (has("x1") && has("x2"))) { put("x1",tx(f("x1")));put("y1",ty(f("y1")));put("x2",tx(f("x2")));put("y2",ty(f("y2")))
                if(has("cx")){put("cx",tx(f("cx")));put("cy",ty(f("cy")))} }
                else { put("x",tx(f("x")));put("y",ty(f("y")));put("w",f("w",300f)*scale);put("h",f("h",80f)*scale) }
            else -> { put("x",tx(f("x")));put("y",ty(f("y")));put("w",f("w",300f)*scale);put("h",f("h",80f)*scale)
                if(has("fontSize"))put("fontSize",f("fontSize")*scale) }
        }
    }
    fun recognizeShape(points: List<InkPoint>): String? {
        if(points.size<5) return null
        val a=points.first();val b=points.last();val length=points.zipWithNext().sumOf{distance(it.first,it.second).toDouble()}.toFloat()
        if(length<45) return null
        if(distance(a,b)>length*.94f) return "line"
        val box=InkBounds(points.minOf{it.x},points.minOf{it.y},points.maxOf{it.x},points.maxOf{it.y})
        if(box.width<30||box.height<30||distance(a,b)>max(box.width,box.height)*.22f)return null
        val cx=(box.left+box.right)/2;val cy=(box.top+box.bottom)/2
        val radial=points.map{abs(hypot((it.x-cx)/(box.width/2),(it.y-cy)/(box.height/2))-1)}.average()
        if(radial<.14)return "ellipse"
        val edge=points.count{min(min(abs(it.x-box.left),abs(it.x-box.right)),min(abs(it.y-box.top),abs(it.y-box.bottom)))<min(box.width,box.height)*.10f}
        if(edge>points.size*.88)return "rectangle"
        return null
    }
    /** CSS-pixel/dp thresholds and principal-axis sweeps match the 3.x input behavior.
     * Reversals are measured from extrema, not individual samples: dense S Pen events
     * and perfectly horizontal scratches must behave exactly like sparse events.
     */
    fun scratchSweeps(points: List<InkPoint>, screenScale: Float): List<IntRange> {
        if(points.size < 10 || screenScale <= 0f) return emptyList()
        val cx=points.map { it.x.toDouble() }.average()
        val cy=points.map { it.y.toDouble() }.average()
        var xx=0.0; var yy=0.0; var xy=0.0
        for(p in points){val x=p.x-cx;val y=p.y-cy;xx+=x*x;yy+=y*y;xy+=x*y}
        val angle=.5*atan2(2*xy,xx-yy);val cos=cos(angle).toFloat();val sin=sin(angle).toFloat()
        val x=FloatArray(points.size){(points[it].x*cos+points[it].y*sin)*screenScale}
        val y=FloatArray(points.size){(-points[it].x*sin+points[it].y*cos)*screenScale}
        val left=x.min();val width=x.max()-left;val height=y.max()-y.min()
        if(width<24 || width>560 || width<height*1.5f) return emptyList()
        var length=0f;var along=0f;var across=0f
        for(i in 1..points.lastIndex){val dx=abs(x[i]-x[i-1]);val dy=abs(y[i]-y[i-1]);along+=dx;across+=dy;length+=hypot(dx,dy)}
        if(along<across*3 || length/max(1f,hypot(width,height))<3.5f) return emptyList()
        val noise=max(2.5f,width*.025f);val minimum=max(12f,width*.6f);val middle=left+width/2
        val sweeps=mutableListOf<IntRange>()
        var start=0;var extreme=0;var direction=0
        fun accept(){if(abs(x[extreme]-x[start])>=minimum && (x[start]-middle)*(x[extreme]-middle)<=0)sweeps+=start..extreme}
        for(i in 1..points.lastIndex){
            if(direction==0){val dx=x[i]-x[start];if(abs(dx)>=noise){direction=if(dx>0)1 else -1;extreme=i};continue}
            if(if(direction>0)x[i]>x[extreme] else x[i]<x[extreme]){extreme=i;continue}
            val reversal=x[i]-x[extreme]
            if(abs(reversal)<noise || (if(reversal>0)1 else -1)==direction)continue
            accept();start=extreme;extreme=i;direction=if(reversal>0)1 else -1
        }
        if(direction!=0)accept()
        return if(sweeps.size>=4)sweeps else emptyList()
    }

    fun deliberateScratch(points: List<InkPoint>,screenScale: Float) = scratchSweeps(points,screenScale).isNotEmpty()

    private fun pathsMeet(a:List<InkPoint>,b:List<InkPoint>,radius:Float):Boolean {
        if(a.isEmpty()||b.isEmpty())return false
        if(a.size==1)return b.zipWithNext().any{segmentDistance(a[0],it.first,it.second)<=radius} || distance(a[0],b[0])<=radius
        if(b.size==1)return a.zipWithNext().any{segmentDistance(b[0],it.first,it.second)<=radius}
        fun cross(a:InkPoint,b:InkPoint,c:InkPoint)=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
        for(i in 1..a.lastIndex)for(j in 1..b.lastIndex){
            val p=a[i-1];val q=a[i];val r=b[j-1];val s=b[j]
            if(max(p.x,q.x)+radius<min(r.x,s.x)||max(r.x,s.x)+radius<min(p.x,q.x)||
                max(p.y,q.y)+radius<min(r.y,s.y)||max(r.y,s.y)+radius<min(p.y,q.y))continue
            if(segmentDistance(p,r,s)<=radius||segmentDistance(q,r,s)<=radius||
                segmentDistance(r,p,q)<=radius||segmentDistance(s,p,q)<=radius)return true
            if(cross(p,q,r)*cross(p,q,s)<0&&cross(r,s,p)*cross(r,s,q)<0)return true
        }
        return false
    }

    fun scratchTargets(objects:List<JSONObject>,points:List<InkPoint>,screenScale:Float):List<JSONObject>{
        val sweeps=scratchSweeps(points,screenScale)
        if(sweeps.isEmpty())return emptyList()
        val hitRadius=2f/screenScale
        val box=InkBounds(points.minOf{it.x},points.minOf{it.y},points.maxOf{it.x},points.maxOf{it.y}).expanded(hitRadius)
        return objects.filter { obj ->
            if(obj.optString("type")!="stroke"||obj.optString("brush")=="highlighter"||obj.optBoolean("locked")||obj.optBoolean("hidden")||!bounds(obj).overlaps(box))false
            else {
                val ink=pagePoints(obj);var hits=0
                sweeps.any{range->pathsMeet(ink,points.subList(range.first,range.last+1),hitRadius+obj.f("width",4f)*.28f)&&++hits>=3}
            }
        }
    }
}
