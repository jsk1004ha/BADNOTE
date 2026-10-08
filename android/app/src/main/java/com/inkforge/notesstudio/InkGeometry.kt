package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

data class InkPoint(val x: Float, val y: Float, val pressure: Float = .5f, val time: Long = 0,
                    val tilt: Float = 0f, val orientation: Float = 0f) {
    fun json() = json("x" to x, "y" to y, "p" to pressure, "t" to time, "tilt" to tilt, "orientation" to orientation)
    companion object { fun from(p: JSONObject) = InkPoint(p.f("x"), p.f("y"), p.f("p", .5f), p.optLong("t"), p.f("tilt"), p.f("orientation")) }
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
    fun bounds(obj: JSONObject): InkBounds {
        if (obj.optString("type") == "stroke") {
            val p = points(obj)
            if (p.isEmpty()) return InkBounds(0f,0f,0f,0f)
            val r = obj.f("width",4f) / 2
            return InkBounds(p.minOf{it.x}-r,p.minOf{it.y}-r,p.maxOf{it.x}+r,p.maxOf{it.y}+r)
        }
        if (obj.optString("type") == "shape") return InkBounds(min(obj.f("x1"),obj.f("x2")), min(obj.f("y1"),obj.f("y2")), max(obj.f("x1"),obj.f("x2")), max(obj.f("y1"),obj.f("y2"))).expanded(obj.f("width",3f))
        return InkBounds(obj.f("x"),obj.f("y"),obj.f("x")+obj.f("w",300f),obj.f("y")+obj.f("h",80f))
    }
    fun distance(a: InkPoint,b: InkPoint) = hypot(a.x-b.x,a.y-b.y)
    fun segmentDistance(p: InkPoint,a: InkPoint,b: InkPoint): Float {
        val dx=b.x-a.x; val dy=b.y-a.y; val len=dx*dx+dy*dy
        val t=if(len<=.00001f) 0f else (((p.x-a.x)*dx+(p.y-a.y)*dy)/len).coerceIn(0f,1f)
        return hypot(p.x-a.x-t*dx,p.y-a.y-t*dy)
    }
    fun hit(obj: JSONObject,p: InkPoint,radius: Float): Boolean {
        if(obj.optBoolean("locked") || obj.optBoolean("hidden") || !bounds(obj).expanded(radius).contains(p.x,p.y)) return false
        if(obj.optString("type")!="stroke") return true
        val points=points(obj); val r=radius+obj.f("width",4f)/2
        return points.any{distance(it,p)<=r} || points.zipWithNext().any{segmentDistance(p,it.first,it.second)<=r}
    }
    private fun lerp(a: InkPoint,b: InkPoint,t: Float) = InkPoint(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,
        a.pressure+(b.pressure-a.pressure)*t, a.time+((b.time-a.time)*t).toLong(),a.tilt+(b.tilt-a.tilt)*t,a.orientation+(b.orientation-a.orientation)*t)

    /** Analytic segment/circle intersections preserve thin strokes with sparsely sampled points. */
    fun eraseParts(obj: JSONObject,center: InkPoint,radius: Float): List<JSONObject> {
        val points=points(obj)
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
            "shape" -> { put("x1",tx(f("x1")));put("y1",ty(f("y1")));put("x2",tx(f("x2")));put("y2",ty(f("y2")))
                if(has("cx")){put("cx",tx(f("cx")));put("cy",ty(f("cy")))} }
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
                val ink=InkGeometry.points(obj);var hits=0
                sweeps.any{range->pathsMeet(ink,points.subList(range.first,range.last+1),hitRadius+obj.f("width",4f)*.28f)&&++hits>=3}
            }
        }
    }
}
