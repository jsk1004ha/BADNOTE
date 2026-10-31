package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.*

data class InkPoint(val x: Float, val y: Float, val pressure: Float = .5f, val time: Long = 0,
                    val tilt: Float = 0f, val orientation: Float = 0f, val pointerId: Int = -1,
                    val azimuth: Float? = null, val tx: Float? = null, val ty: Float? = null,
                    val original: JSONObject? = null) {
    fun json(): JSONObject {
        // Imported points retain null vs missing fields and every unknown extension field.
        val value = original?.copyJson() ?: JSONObject().put("p",pressure).put("t",time)
            .put("tilt",tilt).put("orientation",orientation).put("pointerId",pointerId)
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

/** Derived coordinates only; storage and admission are bounded by the owning page index. */
internal class StrokeHitRuns(
    val starts:IntArray,val ends:IntArray,val boxes:FloatArray,val size:Int,val localBounds:InkBounds,
    val coordinates:FloatArray?=null,val tree:StrokeSegmentTree?=null
)

private fun segmentMayHitSweep(ax:Float,ay:Float,bx:Float,by:Float,box:InkBounds,r:Float):Boolean {
    val dx=bx-ax;val dy=by-ay
    return !((box.left-ax)-max(0f,dx)>r||(box.right-ax)-min(0f,dx) < -r||
        (box.top-ay)-max(0f,dy)>r||(box.bottom-ay)-min(0f,dy) < -r)
}
internal class StrokeSegmentSelection(val segments:IntArray,val size:Int)
private val NO_SEGMENT_SELECTION=StrokeSegmentSelection(IntArray(0),0)

/** Snapshot once per MotionEvent; public list-based calls never cache mutable-list identity. */
internal class InkEraseEvent(centers:List<InkPoint>,val radius:Float) {
    val centers=centers.toList()
    val bounds:InkBounds
    init {
        var left=Float.POSITIVE_INFINITY;var top=Float.POSITIVE_INFINITY
        var right=Float.NEGATIVE_INFINITY;var bottom=Float.NEGATIVE_INFINITY
        for(center in this.centers){left=min(left,center.x);top=min(top,center.y);right=max(right,center.x);bottom=max(bottom,center.y)}
        bounds=InkBounds(left,top,right,bottom)
    }
}

/** Immutable source-order BVH of rounded Float starts/deltas, never ideal segment AABBs. */
internal class StrokeSegmentTree private constructor(
    val values:FloatArray,val ranges:IntArray,val pointCount:Int
) {
    val bytes get()=(values.size+ranges.size)*4
    companion object {
        private const val LEAF_SEGMENTS=4
        fun create(coordinates:FloatArray,pointCount:Int,maxBytes:Int):StrokeSegmentTree? {
            if(pointCount<2)return null
            fun nodes(count:Int):Int=if(count<=LEAF_SEGMENTS)1 else 1+nodes(count/2)+nodes(count-count/2)
            val count=nodes(pointCount-1)
            if(count.toLong()*48>maxBytes)return null
            val values=FloatArray(count*8);val ranges=IntArray(count*4)
            var next=0
            fun build(from:Int,to:Int):Int {
                val node=next++;val at=node*4;val value=node*8
                ranges[at]=from;ranges[at+1]=to
                if(to-from<=LEAF_SEGMENTS){
                    ranges[at+2]=-1;ranges[at+3]=-1
                    for(axis in 0..3){values[value+axis*2]=Float.POSITIVE_INFINITY;values[value+axis*2+1]=Float.NEGATIVE_INFINITY}
                    for(segment in from until to){
                        val x=coordinates[segment*2];val y=coordinates[segment*2+1]
                        val dx=coordinates[segment*2+2]-x;val dy=coordinates[segment*2+3]-y
                        values[value]=min(values[value],x);values[value+1]=max(values[value+1],x)
                        values[value+2]=min(values[value+2],y);values[value+3]=max(values[value+3],y)
                        values[value+4]=min(values[value+4],dx);values[value+5]=max(values[value+5],dx)
                        values[value+6]=min(values[value+6],dy);values[value+7]=max(values[value+7],dy)
                    }
                }else{
                    val middle=(from+to)/2;val left=build(from,middle);val right=build(middle,to)
                    ranges[at+2]=left;ranges[at+3]=right
                    for(axis in 0..3){
                        values[value+axis*2]=min(values[left*8+axis*2],values[right*8+axis*2])
                        values[value+axis*2+1]=max(values[left*8+axis*2+1],values[right*8+axis*2+1])
                    }
                }
                return node
            }
            build(0,pointCount-1)
            return StrokeSegmentTree(values,ranges,pointCount)
        }
    }
    fun select(coordinates:FloatArray,box:InkBounds,r:Float):StrokeSegmentSelection {
        var selected:IntArray?=null;var count=0
        fun visit(node:Int){
            val at=node*8
            if((box.left-values[at+1])-max(0f,values[at+5])>r||
                (box.right-values[at])-min(0f,values[at+4]) < -r||
                (box.top-values[at+3])-max(0f,values[at+7])>r||
                (box.bottom-values[at+2])-min(0f,values[at+6]) < -r)return
            val range=node*4;val left=ranges[range+2]
            if(left<0){
                for(segment in ranges[range] until ranges[range+1]){
                    val offset=segment*2
                    if(segmentMayHitSweep(coordinates[offset],coordinates[offset+1],coordinates[offset+2],coordinates[offset+3],box,r)){
                        var output=selected
                        if(output==null){output=IntArray(min(8,pointCount-1));selected=output}
                        else if(count==output.size){output=output.copyOf(min(pointCount-1,output.size*2));selected=output}
                        output[count++]=segment
                    }
                }
            }else{visit(left);visit(ranges[range+3])}
        }
        visit(0)
        val result=selected?:return NO_SEGMENT_SELECTION
        return StrokeSegmentSelection(if(count==result.size)result else result.copyOf(count),count)
    }
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
            val raw=obj.array("points");var left=Float.POSITIVE_INFINITY;var top=Float.POSITIVE_INFINITY
            var right=Float.NEGATIVE_INFINITY;var bottom=Float.NEGATIVE_INFINITY;var found=false
            for(i in 0 until raw.length()){val p=raw.optJSONObject(i)?:continue
                val x=p.f("x");val y=p.f("y");left=min(left,x);top=min(top,y);right=max(right,x);bottom=max(bottom,y);found=true}
            if(!found)return InkBounds(0f,0f,0f,0f)
            val r = obj.f("width",4f) / 2
            return InkBounds(left-r,top-r,right+r,bottom+r)
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
        return segmentDistance(p.x,p.y,a.x,a.y,b.x,b.y)
    }
    private fun segmentDistance(px:Float,py:Float,ax:Float,ay:Float,bx:Float,by:Float):Float {
        val dx=bx-ax;val dy=by-ay;val len=dx*dx+dy*dy
        val t=if(len<=.00001f)0f else (((px-ax)*dx+(py-ay)*dy)/len).coerceIn(0f,1f)
        return hypot(px-ax-t*dx,py-ay-t*dy)
    }
    private fun strokePointHit(x:Float,y:Float,px:Float,py:Float,r:Float):Boolean {
        val dx=x-px;val dy=y-py
        if(abs(dx)>r||abs(dy)>r)return false
        return hypot(dx,dy)<=r
    }
    private fun strokeSegmentHit(px:Float,py:Float,ax:Float,ay:Float,bx:Float,by:Float,r:Float):Boolean {
        val dx=bx-ax;val dy=by-ay;val qx=px-ax;val qy=py-ay
        // For the old clamped t, fl(t*d) is in [min(0,d),max(0,d)]. Bound the
        // residual in its original subtraction order; do not approximate an ideal AABB.
        if(qx-max(0f,dx)>r||qx-min(0f,dx) < -r||
            qy-max(0f,dy)>r||qy-min(0f,dy) < -r)return false
        return segmentDistance(px,py,ax,ay,bx,by)<=r
    }
    /** Read-only narrow phase: imported point extensions are never copied just to test a hit. */
    fun hit(obj: JSONObject,p: InkPoint,radius: Float,cachedBounds:InkBounds?=null): Boolean =
        hitWithRuns(obj,p,radius,cachedBounds,null)

    internal fun hitWithRuns(obj:JSONObject,p:InkPoint,radius:Float,cachedBounds:InkBounds?,runs:StrokeHitRuns?):Boolean {
        if(obj.optBoolean("locked") || obj.optBoolean("hidden"))return false
        val box=cachedBounds?:bounds(obj)
        if(!(p.x>=box.left-radius&&p.x<=box.right+radius&&p.y>=box.top-radius&&p.y<=box.bottom+radius))return false
        val angle=obj.f("rotation").toDouble()
        var x=p.x;var y=p.y
        if(angle!=0.0){
            val b=runs?.localBounds?:unrotatedBounds(obj);val cx=(b.left+b.right)/2;val cy=(b.top+b.bottom)/2
            val dx=p.x-cx;val dy=p.y-cy
            x=(cx+dx*cos(angle)+dy*sin(angle)).toFloat()
            y=(cy-dx*sin(angle)+dy*cos(angle)).toFloat()
        }
        if(obj.optString("type")=="shape")return shapeHit(obj,if(angle==0.0)p else p.copy(x=x,y=y),radius)
        if(obj.optString("type")!="stroke")return unrotatedBounds(obj).expanded(radius).contains(x,y)
        val raw=obj.array("points");val r=radius+obj.f("width",4f)/2
        return strokeHitWithRuns(raw,x,y,r,runs)
    }
    internal fun hitAlongWithRuns(obj:JSONObject,event:InkEraseEvent,cachedBounds:InkBounds?,runs:StrokeHitRuns?):Boolean {
        val centers=event.centers;val radius=event.radius
        if(centers.isEmpty()||obj.optBoolean("locked")||obj.optBoolean("hidden"))return false
        if(obj.optString("type")!="stroke"){
            for(center in centers)if(hitWithRuns(obj,center,radius,cachedBounds,null))return true
            return false
        }
        if(runs?.tree!=null&&runs.coordinates!=null)return prepareStrokeEvent(obj,event,cachedBounds,runs).firstHit>=0
        val box=cachedBounds?:bounds(obj);val angle=obj.f("rotation").toDouble()
        val local=if(angle==0.0)null else runs?.localBounds?:unrotatedBounds(obj)
        val cx=if(local==null)0f else (local.left+local.right)/2
        val cy=if(local==null)0f else (local.top+local.bottom)/2
        val cosine=if(angle==0.0)1.0 else cos(angle);val sine=if(angle==0.0)0.0 else sin(angle)
        val raw=obj.array("points");val r=radius+obj.f("width",4f)/2
        for(center in centers){
            if(!(center.x>=box.left-radius&&center.x<=box.right+radius&&
                center.y>=box.top-radius&&center.y<=box.bottom+radius))continue
            val dx=center.x-cx;val dy=center.y-cy
            val x=if(angle==0.0)center.x else (cx+dx*cosine+dy*sine).toFloat()
            val y=if(angle==0.0)center.y else (cy-dx*sine+dy*cosine).toFloat()
            if(strokeHitWithRuns(raw,x,y,r,runs))return true
        }
        return false
    }
    private class PreparedStrokeEvent(val selection:StrokeSegmentSelection,val firstHit:Int)
    private fun prepareStrokeEvent(obj:JSONObject,event:InkEraseEvent,cachedBounds:InkBounds?,runs:StrokeHitRuns):PreparedStrokeEvent {
        val coordinates=runs.coordinates!!;val tree=runs.tree!!
        val page=cachedBounds?:bounds(obj);val angle=obj.f("rotation").toDouble()
        val cx=(runs.localBounds.left+runs.localBounds.right)/2;val cy=(runs.localBounds.top+runs.localBounds.bottom)/2
        val cosine=if(angle==0.0)1.0 else cos(angle);val sine=if(angle==0.0)0.0 else sin(angle)
        val transformed=if(angle==0.0)null else FloatArray(event.centers.size*2)
        var left=Float.POSITIVE_INFINITY;var top=Float.POSITIVE_INFINITY
        var right=Float.NEGATIVE_INFINITY;var bottom=Float.NEGATIVE_INFINITY;var found=false
        if(transformed!=null)for(i in event.centers.indices){
            val center=event.centers[i];val radius=event.radius
            if(!(center.x>=page.left-radius&&center.x<=page.right+radius&&center.y>=page.top-radius&&center.y<=page.bottom+radius))continue
            val dx=center.x-cx;val dy=center.y-cy
            val x=(cx+dx*cosine+dy*sine).toFloat();val y=(cy-dx*sine+dy*cosine).toFloat()
            transformed[i*2]=x;transformed[i*2+1]=y
            left=min(left,x);top=min(top,y);right=max(right,x);bottom=max(bottom,y);found=true
        }
        if(transformed!=null&&!found)return PreparedStrokeEvent(NO_SEGMENT_SELECTION,-1)
        val sweep=if(transformed==null)event.bounds else InkBounds(left,top,right,bottom)
        val r=event.radius+obj.f("width",4f)/2
        val selected=tree.select(coordinates,sweep,r)
        val last=(tree.pointCount-1)*2
        for(i in event.centers.indices){
            val center=event.centers[i];val radius=event.radius
            if(!(center.x>=page.left-radius&&center.x<=page.right+radius&&center.y>=page.top-radius&&center.y<=page.bottom+radius))continue
            val x=transformed?.get(i*2)?:center.x;val y=transformed?.get(i*2+1)?:center.y
            // Terminal vertex is not a segment start and must retain its direct old predicate.
            if(strokePointHit(coordinates[last],coordinates[last+1],x,y,r))return PreparedStrokeEvent(selected,i)
            for(candidate in 0 until selected.size){
                val offset=selected.segments[candidate]*2
                if(strokePointHit(coordinates[offset],coordinates[offset+1],x,y,r)||
                    strokeSegmentHit(x,y,coordinates[offset],coordinates[offset+1],coordinates[offset+2],coordinates[offset+3],r))
                    return PreparedStrokeEvent(selected,i)
            }
        }
        return PreparedStrokeEvent(selected,-1)
    }
    private fun strokeHitWithRuns(raw:JSONArray,x:Float,y:Float,r:Float,runs:StrokeHitRuns?):Boolean {
        if(runs==null)return strokeHit(raw,x,y,r,0,raw.length()-1)
        for(i in 0 until runs.size){
            val offset=i*4;val left=runs.boxes[offset];val top=runs.boxes[offset+1]
            val right=runs.boxes[offset+2];val bottom=runs.boxes[offset+3]
            val spanX=right-left;val spanY=bottom-top
            // Float subtraction/projection can cancel outside an ideal segment AABB. These
            // monotone interval bounds include that rounding, rather than changing hit tolerance.
            if((x-right)-spanX>r||(x-left)+spanX < -r||
                (y-bottom)-spanY>r||(y-top)+spanY < -r)continue
            val coordinates=runs.coordinates
            if(if(coordinates==null)strokeHit(raw,x,y,r,runs.starts[i],runs.ends[i])
                else strokeHit(coordinates,x,y,r,runs.starts[i],runs.ends[i]))return true
        }
        return false
    }
    private fun strokeHit(raw:JSONArray,px:Float,py:Float,r:Float,start:Int,end:Int):Boolean {
        var priorX=0f;var priorY=0f;var hasPrior=false
        for(i in start..end){
            val sample=raw.optJSONObject(i)?:continue
            val x=sample.f("x");val y=sample.f("y")
            if(strokePointHit(x,y,px,py,r)||hasPrior&&strokeSegmentHit(px,py,priorX,priorY,x,y,r))return true
            priorX=x;priorY=y;hasPrior=true
        }
        return false
    }
    private fun strokeHit(coordinates:FloatArray,px:Float,py:Float,r:Float,start:Int,end:Int):Boolean {
        var priorX=0f;var priorY=0f;var hasPrior=false
        for(i in start..end){
            val x=coordinates[i*2];val y=coordinates[i*2+1]
            if(strokePointHit(x,y,px,py,r)||hasPrior&&strokeSegmentHit(px,py,priorX,priorY,x,y,r))return true
            priorX=x;priorY=y;hasPrior=true
        }
        return false
    }
    internal fun sameStrokeCoordinates(first:JSONObject,second:JSONObject):Boolean{
        if(first===second)return true
        val a=first.array("points");val b=second.array("points")
        var i=0;var j=0
        while(true){
            var p:JSONObject?=null;var q:JSONObject?=null
            while(i<a.length()&&p==null)p=a.optJSONObject(i++)
            while(j<b.length()&&q==null)q=b.optJSONObject(j++)
            if(p==null||q==null)return p==null&&q==null
            if(hypot(p.f("x")-q.f("x"),p.f("y")-q.f("y"))>=.001f)return false
        }
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
    private fun eraseCoordinate(a:Float,b:Float,t:Float)=when{
        t<=0f->a
        t>=1f->b
        else->a+(b-a)*t
    }

    private inline fun eraseSegmentIntervals(ax:Float,ay:Float,bx:Float,by:Float,center:InkPoint,r:Float,
        visit:(Float,Float,Boolean)->Boolean):Boolean {
        val dx=bx-ax;val dy=by-ay;val x=ax-center.x;val y=ay-center.y
        val aa=dx*dx+dy*dy;val bb=2*(x*dx+y*dy);val cc=x*x+y*y-r*r
        val discriminant=bb*bb-4*aa*cc
        var cutCount=0;var firstCut=1f;var secondCut=1f
        if(aa>1e-8f&&discriminant>0){
            val root=sqrt(discriminant)
            val first=(-bb-root)/(2*aa);val second=(-bb+root)/(2*aa)
            if(first>0f&&first<1f){firstCut=first;cutCount++}
            if(second>0f&&second<1f){
                if(cutCount==0)firstCut=second else secondCut=second
                cutCount++
            }
        }
        var from=0f
        for(interval in 0..cutCount){
            val to=if(interval==cutCount)1f else if(interval==0)firstCut else secondCut
            val middle=(from+to)/2
            val mx=eraseCoordinate(ax,bx,middle);val my=eraseCoordinate(ay,by,middle)
            val ex=mx-center.x;val ey=my-center.y
            // Preserve every cut/duplicate sample. Only bypass hypot when a midpoint
            // component proves it outside; NaNs retain the old hypot classification.
            val outside=if(ex==ex&&ey==ey&&(abs(ex)>r||abs(ey)>r))true else hypot(ex,ey)>r
            if(!visit(from,to,outside))return false
            from=to
        }
        return true
    }

    /** Visits the old Float quadratic intervals, including duplicate cuts and tangent midpoints. */
    private inline fun eraseIntervals(raw:JSONArray,center:InkPoint,r:Float,angle:Double,cx:Float,cy:Float,
        visit:(JSONObject,JSONObject,Float,Float,Float,Float,Float,Float,Boolean)->Boolean) {
        val cosine=if(angle==0.0)1.0 else cos(angle);val sine=if(angle==0.0)0.0 else sin(angle)
        var prior:JSONObject?=null;var priorX=0f;var priorY=0f
        for(i in 0 until raw.length()){
            val sample=raw.optJSONObject(i)?:continue
            val x=sample.f("x");val y=sample.f("y")
            val px=if(angle==0.0)x else (cx+(x-cx)*cosine-(y-cy)*sine).toFloat()
            val py=if(angle==0.0)y else (cy+(x-cx)*sine+(y-cy)*cosine).toFloat()
            val a=prior
            if(a!=null&&!eraseSegmentIntervals(priorX,priorY,px,py,center,r){from,to,outside->
                visit(a,sample,priorX,priorY,px,py,from,to,outside)
            })return
            prior=sample;priorX=px;priorY=py
        }
    }

    /** Only emitted endpoints/intersections acquire owned extension JSON. */
    private fun erasePoint(a:JSONObject,b:JSONObject,ax:Float,ay:Float,bx:Float,by:Float,t:Float):JSONObject {
        if(t<=0f)return a.copyJson().put("x",ax).put("y",ay)
        if(t>=1f)return b.copyJson().put("x",bx).put("y",by)
        val pressure=a.f("p",.5f)+(b.f("p",.5f)-a.f("p",.5f))*t
        val time=a.optLong("t")+((b.optLong("t")-a.optLong("t"))*t).toLong()
        val tilt=a.f("tilt")+(b.f("tilt")-a.f("tilt"))*t
        val orientation=a.f("orientation")+(b.f("orientation")-a.f("orientation"))*t
        val value=a.copyJson().put("p",copyTransformValue(pressure)).put("t",copyTransformValue(time))
            .put("tilt",copyTransformValue(tilt)).put("orientation",copyTransformValue(orientation))
            .put("pointerId",a.optInt("pointerId",-1))
        for(key in ERASE_OPTIONAL_FIELDS){
            val first=a.f(key).takeIf{a.has(key)&&!a.isNull(key)}
            val second=b.f(key).takeIf{b.has(key)&&!b.isNull(key)}
            val interpolated=if(first!=null&&second!=null)first+(second-first)*t else first?:second
            if(interpolated!=null)value.put(key,copyTransformValue(interpolated))
        }
        value.put("x",eraseCoordinate(ax,bx,t)).put("y",eraseCoordinate(ay,by,t))
        return value
    }
    private val ERASE_OPTIONAL_FIELDS=arrayOf("azimuth","tx","ty")

    /** Normalize top-level scalars only; nested editor-owned extensions/settings are immutable. */
    private class ErasedPoint:JSONObject()
    private val erasedNegativeZero:Float by lazy{(copyTransformValue(-0f) as Number).toFloat()}
    private fun shallowEraseJson(source:JSONObject,withoutPoints:Boolean=false,withoutCoordinates:Boolean=false,
                                 point:Boolean=false):JSONObject {
        val result=if(point)ErasedPoint() else JSONObject();val keys=source.keys()
        while(keys.hasNext()){
            val key=keys.next()
            if(withoutPoints&&key=="points"||withoutCoordinates&&(key=="x"||key=="y"))continue
            val value=source.opt(key)
            result.put(key,if(value is JSONObject||value is JSONArray)value else copyTransformValue(value))
        }
        return result
    }

    /** One coordinate read per sample per event; JSON is acquired only by cuts/final survivors. */
    private class EraseSample(val raw:JSONObject,val x:Float,val y:Float,canonical:Boolean=false,
                              private val generated:InkPoint?=null) {
        // Already-normalized survivors are immutable editor-owned samples. Later MOVE
        // events can borrow them without cloning their scalar metadata again. A rotation
        // changes coordinates, so that case still creates a new normalized point.
        private var owned:JSONObject?=if(generated==null&&(canonical||raw is ErasedPoint&&
            raw.f("x").toRawBits()==x.toRawBits()&&raw.f("y").toRawBits()==y.toRawBits()))raw else null
        private var rawAttributes:InkPoint?=null
        private var canonicalAttributes:InkPoint?=null
        private var outsideNext:EraseSample?=null
        private var outsideValue=false
        // These wrappers belong to one erase event and one stroke width. A cut keeps
        // most adjacent pairs unchanged, even though it creates new fragment objects.
        fun outsideEntireEvent(next:EraseSample,box:InkBounds,radius:Float):Boolean {
            if(outsideNext!==next){
                outsideValue=eraseAlwaysOutside(this,next,box,radius)
                outsideNext=next
            }
            return outsideValue
        }
        fun json():JSONObject {
            var value=owned
            if(value==null){
                value=shallowEraseJson(raw,withoutCoordinates=true,point=true).put("x",x).put("y",y)
                generated?.let{point->
                    value.put("p",copyTransformValue(point.pressure)).put("t",copyTransformValue(point.time))
                        .put("tilt",copyTransformValue(point.tilt)).put("orientation",copyTransformValue(point.orientation))
                        .put("pointerId",point.pointerId)
                    point.azimuth?.let{value.put("azimuth",copyTransformValue(it))}
                    point.tx?.let{value.put("tx",copyTransformValue(it))}
                    point.ty?.let{value.put("ty",copyTransformValue(it))}
                }
                owned=value
            }
            return value
        }
        fun metadata(canonical:Boolean)=if(canonical)json() else raw
        fun attributes(canonical:Boolean):InkPoint {
            generated?.let{return it}
            val cached=if(canonical)canonicalAttributes else rawAttributes
            if(cached!=null)return cached
            val p=metadata(canonical)
            val value=InkPoint(x,y,p.f("p",.5f),p.optLong("t"),p.f("tilt"),p.f("orientation"),
                p.optInt("pointerId",-1),p.f("azimuth").takeIf{p.has("azimuth")&&!p.isNull("azimuth")},
                p.f("tx").takeIf{p.has("tx")&&!p.isNull("tx")},
                p.f("ty").takeIf{p.has("ty")&&!p.isNull("ty")},p)
            if(canonical)canonicalAttributes=value else rawAttributes=value
            return value
        }
    }
    private fun eraseSample(a:EraseSample,b:EraseSample,t:Float,canonical:Boolean):EraseSample {
        if(t<=0f)return a
        if(t>=1f)return b
        val first=a.attributes(canonical);val second=b.attributes(canonical)
        fun optional(left:Float?,right:Float?)=if(left!=null&&right!=null)left+(right-left)*t else left?:right
        val pressure=first.pressure+(second.pressure-first.pressure)*t
        val time=first.time+((second.time-first.time)*t).toLong()
        val tilt=first.tilt+(second.tilt-first.tilt)*t
        val orientation=first.orientation+(second.orientation-first.orientation)*t
        val x=eraseCoordinate(a.x,b.x,t);val y=eraseCoordinate(a.y,b.y,t)
        // Intermediate circle cuts are often removed by the next circle. Keep their
        // scalar interpolation, but materialize JSON only for final survivors. JSON's
        // normalization round-trips finite Float attributes, except signed zero.
        fun normalized(value:Float)=if(value==0f&&value.toRawBits()<0)erasedNegativeZero else value
        val point=InkPoint(x,y,normalized(pressure),time,normalized(tilt),normalized(orientation),first.pointerId,
            optional(first.azimuth,second.azimuth)?.let(::normalized),
            optional(first.tx,second.tx)?.let(::normalized),optional(first.ty,second.ty)?.let(::normalized))
        return EraseSample(a.raw,x,y,generated=point)
    }
    /** Sufficient proof only: bound the old Float quadratic and its sole no-cut midpoint. */
    private fun eraseAlwaysOutside(a:EraseSample,b:EraseSample,box:InkBounds,r:Float):Boolean {
        if(!box.left.isFinite()||!box.right.isFinite()||!box.top.isFinite()||!box.bottom.isFinite()||!r.isFinite())return false
        val mx=eraseCoordinate(a.x,b.x,.5f);val my=eraseCoordinate(a.y,b.y,.5f)
        if(!mx.isFinite()||!my.isFinite())return false
        if(!((mx-box.right)>r||(mx-box.left) < -r||(my-box.bottom)>r||(my-box.top) < -r))return false
        val dx=b.x-a.x;val dy=b.y-a.y;val aa=dx*dx+dy*dy
        if(aa<=1e-8f)return true
        val x0=a.x-box.right;val x1=a.x-box.left;val y0=a.y-box.bottom;val y1=a.y-box.top
        val bb0=2*(min(x0*dx,x1*dx)+min(y0*dy,y1*dy))
        val bb1=2*(max(x0*dx,x1*dx)+max(y0*dy,y1*dy))
        fun squareMin(low:Float,high:Float)=if(low<=0f&&high>=0f)0f else min(low*low,high*high)
        val cc0=squareMin(x0,x1)+squareMin(y0,y1)-r*r
        val discriminantMax=max(bb0*bb0,bb1*bb1)-(4*aa)*cc0
        if(!aa.isFinite()||!bb0.isFinite()||!bb1.isFinite()||!cc0.isFinite()||!discriminantMax.isFinite())return false
        if(discriminantMax<=0f)return true
        val rootMax=sqrt(discriminantMax);val denominator=2*aa
        val cutMax=(-bb0+rootMax)/denominator;val cutMin=(-bb1-rootMax)/denominator
        return cutMax<=0f||cutMin>=1f
    }
    private class EraseStroke(val points:List<EraseSample>,val width:Float,val rotation:Float,
        val id:String,val revision:Long,val order:Int,val event:InkEraseEvent,val original:JSONObject?=null,
        private var selection:StrokeSegmentSelection?=null) {
        val angle=rotation.toDouble()
        val local:InkBounds
        val page:InkBounds
        val cx:Float
        val cy:Float
        val cosine=if(angle==0.0)1.0 else cos(angle)
        val sine=if(angle==0.0)0.0 else sin(angle)
        private var rotated:List<EraseSample>?=null
        private var outsideEvent:ByteArray?=null
        private fun selectedSegments():StrokeSegmentSelection {
            val cached=selection
            if(cached!=null)return cached
            var sweep=event.bounds
            if(angle!=0.0){
                var left=Float.POSITIVE_INFINITY;var top=Float.POSITIVE_INFINITY
                var right=Float.NEGATIVE_INFINITY;var bottom=Float.NEGATIVE_INFINITY
                for(center in event.centers){
                    val dx=center.x-cx;val dy=center.y-cy
                    val x=(cx+dx*cosine+dy*sine).toFloat();val y=(cy-dx*sine+dy*cosine).toFloat()
                    left=min(left,x);top=min(top,y);right=max(right,x);bottom=max(bottom,y)
                }
                sweep=InkBounds(left,top,right,bottom)
            }
            val selected=IntArray(max(0,points.size-1));var count=0;val r=event.radius+width/2
            for(i in 1 until points.size){
                val a=points[i-1];val b=points[i]
                if(segmentMayHitSweep(a.x,a.y,b.x,b.y,sweep,r))selected[count++]=i-1
            }
            val result=if(count==0)NO_SEGMENT_SELECTION else StrokeSegmentSelection(if(count==selected.size)selected else selected.copyOf(count),count)
            selection=result
            return result
        }
        private fun outsideSegments(source:List<EraseSample>):ByteArray {
            val cached=outsideEvent
            if(cached!=null)return cached
            val result=ByteArray(source.size-1);val r=event.radius+width/2
            for(i in 1 until source.size)if(source[i-1].outsideEntireEvent(source[i],event.bounds,r))result[i-1]=1
            outsideEvent=result
            return result
        }
        init {
            var left=Float.POSITIVE_INFINITY;var top=Float.POSITIVE_INFINITY
            var right=Float.NEGATIVE_INFINITY;var bottom=Float.NEGATIVE_INFINITY
            for(point in points){left=min(left,point.x);top=min(top,point.y);right=max(right,point.x);bottom=max(bottom,point.y)}
            local=if(points.isEmpty())InkBounds(0f,0f,0f,0f) else InkBounds(left-width/2,top-width/2,right+width/2,bottom+width/2)
            cx=(local.left+local.right)/2;cy=(local.top+local.bottom)/2
            if(angle==0.0)page=local else {
                val halfW=local.width/2;val halfH=local.height/2
                val c=abs(cosine).toFloat();val s=abs(sine).toFloat()
                val w=halfW*c+halfH*s;val h=halfW*s+halfH*c
                page=InkBounds(cx-w,cy-h,cx+w,cy+h)
            }
        }
        fun hit(center:InkPoint,radius:Float):Boolean {
            if(!(center.x>=page.left-radius&&center.x<=page.right+radius&&
                center.y>=page.top-radius&&center.y<=page.bottom+radius))return false
            val dx=center.x-cx;val dy=center.y-cy
            val x=if(angle==0.0)center.x else (cx+dx*cosine+dy*sine).toFloat()
            val y=if(angle==0.0)center.y else (cy-dx*sine+dy*cosine).toFloat()
            val r=radius+width/2
            val last=points.lastOrNull()
            if(last!=null&&strokePointHit(last.x,last.y,x,y,r))return true
            val selected=selectedSegments()
            for(candidate in 0 until selected.size){
                val segment=selected.segments[candidate];val a=points[segment];val b=points[segment+1]
                if(strokePointHit(a.x,a.y,x,y,r)||strokeSegmentHit(x,y,a.x,a.y,b.x,b.y,r))return true
            }
            return false
        }
        fun pagePoints():List<EraseSample> {
            if(angle==0.0)return points
            val cached=rotated
            if(cached!=null)return cached
            val result=ArrayList<EraseSample>(points.size)
            for(point in points){
                val x=point.x-cx;val y=point.y-cy
                result.add(EraseSample(point.raw,(cx+x*cosine-y*sine).toFloat(),(cy+x*sine+y*cosine).toFloat()))
            }
            rotated=result
            return result
        }
        fun erase(center:InkPoint,radius:Float):List<EraseStroke> {
            val source=pagePoints();val r=radius+width/2
            if(source.size<2)return if(source.isNotEmpty()&&hypot(source[0].x-center.x,source[0].y-center.y)<=r)
                emptyList() else listOf(this)
            val fragments=ArrayList<List<EraseSample>>()
            var current:ArrayList<EraseSample>?=null
            val outside=outsideSegments(source)
            for(i in 1 until source.size){
                val a=source[i-1];val b=source[i]
                if(outside[i-1].toInt()!=0){
                    var fragment=current
                    if(fragment==null){fragment=ArrayList();current=fragment}
                    val last=fragment.lastOrNull()
                    if(last==null||last!==a&&hypot(last.x-a.x,last.y-a.y)>.001f)fragment.add(a)
                    fragment.add(b)
                    continue
                }
                eraseSegmentIntervals(a.x,a.y,b.x,b.y,center,r){from,to,outside->
                    if(outside){
                        var fragment=current
                        if(fragment==null){fragment=ArrayList();current=fragment}
                        val firstX=eraseCoordinate(a.x,b.x,from);val firstY=eraseCoordinate(a.y,b.y,from)
                        val last=fragment.lastOrNull()
                        if(last==null||!(from<=0f&&last===a||from>=1f&&last===b)&&hypot(last.x-firstX,last.y-firstY)>.001f)
                            fragment.add(eraseSample(a,b,from,original==null))
                        fragment.add(eraseSample(a,b,to,original==null))
                    }else{
                        current?.let{if(it.size>=2)fragments.add(it)}
                        current=null
                    }
                    true
                }
            }
            current?.let{if(it.size>=2)fragments.add(it)}
            if(rotation==0f&&fragments.size==1&&fragments[0].size==points.size){
                var same=true
                for(i in points.indices){
                    val a=points[i];val b=fragments[0][i]
                    if(a!==b&&hypot(a.x-b.x,a.y-b.y)>=.001f){same=false;break}
                }
                if(same)return listOf(this)
            }
            return fragments.mapIndexed{index,fragment->EraseStroke(fragment,width,0f,
                if(index==0)id else uid("stroke"),revision+1,index,event)}
        }
    }

    /** Same per-circle hit/split/no-op semantics, materializing only final surviving JSON. */
    internal fun eraseAlong(obj:JSONObject,centers:List<InkPoint>,radius:Float):List<JSONObject> =
        eraseAlongWithRuns(obj,InkEraseEvent(centers,radius),null,null)

    internal fun eraseAlongWithRuns(obj:JSONObject,event:InkEraseEvent,cachedBounds:InkBounds?,runs:StrokeHitRuns?):List<JSONObject> {
        val centers=event.centers;val radius=event.radius
        if(centers.isEmpty()||obj.optString("type")!="stroke"||obj.optBoolean("locked")||obj.optBoolean("hidden"))
            return listOf(obj)
        val prepared=if(runs?.tree!=null&&runs.coordinates!=null)prepareStrokeEvent(obj,event,cachedBounds,runs) else null
        if(prepared!=null&&prepared.firstHit<0)return listOf(obj)
        // When the bounded segment tree is exhausted, most broad-phase candidates still
        // miss. Reject them using the same hit predicate before allocating a sample copy.
        if(prepared==null&&!hitAlongWithRuns(obj,event,cachedBounds,runs))return listOf(obj)
        val raw=obj.array("points");val points=ArrayList<EraseSample>(raw.length())
        val coordinates=runs?.coordinates
        for(i in 0 until raw.length()){
            val point=raw.optJSONObject(i)?:continue
            val offset=points.size*2
            points.add(EraseSample(point,coordinates?.get(offset)?:point.f("x"),coordinates?.get(offset+1)?:point.f("y")))
        }
        val initial=EraseStroke(points,obj.f("width",4f),obj.f("rotation"),obj.getString("id"),obj.optLong("revision"),0,event,obj,prepared?.selection)
        var survivors:List<EraseStroke> = listOf(initial)
        for(centerIndex in (prepared?.firstHit?:0) until centers.size){
            val center=centers[centerIndex]
            var next:ArrayList<EraseStroke>?=null
            for(i in survivors.indices){
                val source=survivors[i]
                val knownHit=source===initial&&prepared!=null&&centerIndex==prepared.firstHit
                val parts=if(knownHit||source.hit(center,radius))source.erase(center,radius) else null
                if(parts!=null&&!(parts.size==1&&parts[0]===source)){
                    if(next==null){
                        next=ArrayList(survivors.size+parts.size)
                        for(prior in 0 until i)next.add(survivors[prior])
                    }
                    next.addAll(parts)
                }else next?.add(source)
            }
            if(next!=null)survivors=next
            if(survivors.isEmpty())return emptyList()
        }
        return survivors.map{stroke->
            if(stroke===initial)obj else {
                val output=JSONArray()
                for(point in stroke.points)output.put(point.json())
                shallowEraseJson(obj,withoutPoints=true).put("id",stroke.id)
                    .put("sourceStrokeId",obj.optString("sourceStrokeId",obj.getString("id")))
                    .put("fragmentOrder",stroke.order).put("revision",stroke.revision)
                    .put("rotation",0).put("points",output)
            }
        }
    }

    /** Analytic segment/circle intersections preserve thin strokes with sparsely sampled points. */
    fun eraseParts(obj:JSONObject,center:InkPoint,radius:Float):List<JSONObject> {
        val raw=obj.array("points");val r=radius+obj.f("width",4f)/2
        val angle=obj.f("rotation").toDouble()
        val box=if(angle==0.0)null else unrotatedBounds(obj)
        val cx=if(box==null)0f else (box.left+box.right)/2
        val cy=if(box==null)0f else (box.top+box.bottom)/2
        var count=0;var only:JSONObject?=null
        for(i in 0 until raw.length()){
            val sample=raw.optJSONObject(i)?:continue
            only=sample
            if(++count==2)break
        }
        if(count<2){
            val sample=only?:return listOf(obj)
            val x=sample.f("x");val y=sample.f("y")
            val px=if(angle==0.0)x else (cx+(x-cx)*cos(angle)-(y-cy)*sin(angle)).toFloat()
            val py=if(angle==0.0)y else (cy+(x-cx)*sin(angle)+(y-cy)*cos(angle)).toFloat()
            return if(hypot(px-center.x,py-center.y)<=r)emptyList() else listOf(obj)
        }
        if(angle==0.0){
            var changed=false
            eraseIntervals(raw,center,r,angle,cx,cy){_,_,_,_,_,_,from,to,outside->
                // Rounded roots can add surviving samples even if every midpoint stays outside.
                changed=!outside||from!=0f||to!=1f
                !changed
            }
            if(!changed)return listOf(obj)
        }
        val fragments=ArrayList<JSONArray>()
        var current=JSONArray();var lastX=0f;var lastY=0f
        fun flush(){
            if(current.length()==0)return
            if(current.length()>=2)fragments.add(current)
            current=JSONArray()
        }
        eraseIntervals(raw,center,r,angle,cx,cy){a,b,ax,ay,bx,by,from,to,outside->
            if(outside){
                val firstX=eraseCoordinate(ax,bx,from);val firstY=eraseCoordinate(ay,by,from)
                if(current.length()==0||hypot(lastX-firstX,lastY-firstY)>.001f)
                    current.put(erasePoint(a,b,ax,ay,bx,by,from))
                current.put(erasePoint(a,b,ax,ay,bx,by,to))
                lastX=eraseCoordinate(ax,bx,to);lastY=eraseCoordinate(ay,by,to)
            }else flush()
            true
        }
        flush()
        return fragments.mapIndexed{index,fragment->
            val copy=JSONObject().apply{
                val keys=obj.keys()
                while(keys.hasNext()){
                    val key=keys.next()
                    if(key!="points")put(key,copyTransformValue(obj.opt(key)))
                }
            }
            copy.put("id",if(index==0)obj.getString("id") else uid("stroke"))
                .put("sourceStrokeId",obj.optString("sourceStrokeId",obj.getString("id"))).put("fragmentOrder",index)
                .put("revision",obj.optLong("revision",0)+1).put("rotation",0).put("points",fragment)
        }
    }
    fun inside(point: InkPoint,polygon: List<InkPoint>) = inside(point.x,point.y,polygon)
    private fun inside(x:Float,y:Float,polygon:List<InkPoint>):Boolean{
        var inside=false; var j=polygon.lastIndex
        for(i in polygon.indices) { val a=polygon[i]; val b=polygon[j]
            if((a.y>y)!=(b.y>y) && x<(b.x-a.x)*(y-a.y)/(b.y-a.y)+a.x) inside=!inside
            j=i
        }
        return inside
    }
    /** Preserve the existing raw-point lasso rule, without copying imported point extensions. */
    fun insideLasso(obj:JSONObject,polygon:List<InkPoint>,cachedBounds:InkBounds?=null):Boolean{
        if(obj.optString("type")=="stroke"){
            val raw=obj.array("points")
            for(i in 0 until raw.length()){val p=raw.optJSONObject(i)?:continue
                if(inside(p.f("x"),p.f("y"),polygon))return true}
            return false
        }
        val b=cachedBounds?:bounds(obj)
        return inside((b.left+b.right)/2,(b.top+b.bottom)/2,polygon)
    }
    // Android parses decimal JSON as Double; the JVM reference library may use BigDecimal.
    private val transformDecimalIsDouble=JSONTokener("0.5").nextValue() is Double
    private fun copyTransformNumber(value:Number):Any{
        when(value){
            is Int->return value
            is Byte,is Short->return value.toInt()
            is Long->return if(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())value.toInt() else value
        }
        if(value !is Double&&value !is Float){
            // A JSON writer may quote an unusual Number's non-standard literal.
            return (JSONTokener(JSONArray().put(value).toString()).nextValue() as JSONArray).get(0)
        }
        if(transformDecimalIsDouble&&value is Double&&value.isFinite()&&value!=value.toLong().toDouble()){
            // Android numberToString would emit this Double verbatim, and parse it back unchanged.
            return value
        }
        val literal=JSONObject.numberToString(value)
        if(transformDecimalIsDouble){
            literal.toLongOrNull()?.let{return if(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())it.toInt() else it}
            // A finite non-integral Double's JSON representation round-trips to the same immutable value.
            return if(value is Double)value else literal.toDouble()
        }
        return JSONTokener(literal).nextValue()
    }
    /** Edit-local deep ownership copy, preserving the platform JSON round-trip scalar rules. */
    private fun copyTransformValue(value:Any?):Any=when(value){
        null,JSONObject.NULL->JSONObject.NULL
        is JSONObject->JSONObject().apply{
            val keys=value.keys()
            while(keys.hasNext()){val key=keys.next();put(key,copyTransformValue(value.opt(key)))}
        }
        is JSONArray->JSONArray().apply{
            for(index in 0 until value.length())put(copyTransformValue(value.opt(index)))
        }
        is Number->copyTransformNumber(value)
        is String,is Boolean->value
        // Preserve the JSON library's handling of non-JSON scalar wrappers without sharing mutable values.
        else->(JSONTokener(JSONArray().put(value).toString()).nextValue() as JSONArray).get(0)
    }
    fun transform(obj: JSONObject,dx: Float,dy: Float,scale: Float=1f,cx: Float=0f,cy: Float=0f): JSONObject = (copyTransformValue(obj) as JSONObject).apply {
        // Adopted edit objects must render exactly like their saved/reloaded JSON without another tree copy.
        fun normalized(value:Float)=JSONObject.numberToString(value).toDouble()
        fun tx(x: Float)=normalized((x-cx)*scale+cx+dx)
        fun ty(y: Float)=normalized((y-cy)*scale+cy+dy)
        fun size(value:Float)=normalized(value*scale)
        when(optString("type")) {
            "stroke" -> { val raw=array("points");val moved=JSONArray()
                for(i in 0 until raw.length()){val point=raw.optJSONObject(i)?:continue
                    point.put("x",tx(point.f("x"))).put("y",ty(point.f("y")));moved.put(point)}
                put("points",moved);put("width",size(f("width",4f))) }
            "shape", "tape" -> if(optString("type")=="shape" || (has("x1") && has("x2"))) { put("x1",tx(f("x1")));put("y1",ty(f("y1")));put("x2",tx(f("x2")));put("y2",ty(f("y2")))
                if(has("cx")){put("cx",tx(f("cx")));put("cy",ty(f("cy")))} }
                else { put("x",tx(f("x")));put("y",ty(f("y")));put("w",size(f("w",300f)));put("h",size(f("h",80f))) }
            else -> { put("x",tx(f("x")));put("y",ty(f("y")));put("w",size(f("w",300f)));put("h",size(f("h",80f)))
                if(has("fontSize"))put("fontSize",size(f("fontSize")))
                if(has("textPadding"))put("textPadding",size(f("textPadding")))
                optJSONArray("textRuns")?.let{runs->for(i in 0 until runs.length())runs.optJSONObject(i)?.let{run->
                    if(run.optString("kind")=="size")run.put("value",size(run.f("value",28f))) }} }
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
