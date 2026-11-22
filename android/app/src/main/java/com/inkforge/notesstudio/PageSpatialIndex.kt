package com.inkforge.notesstudio

import org.json.JSONObject
import kotlin.math.floor

/** Page-local broad phase plus bounded, read-only exact stroke hit acceleration. */
class PageSpatialIndex(private val cell: Float = 96f) {
    private val buckets = HashMap<Long, MutableSet<String>>()
    private val wide = LinkedHashSet<String>()
    private val objects = HashMap<String, JSONObject>()
    private val occupied = HashMap<String, List<Long>>()
    private val bounds = HashMap<String, InkBounds>()
    private val broadBounds = HashMap<String, InkBounds>()
    fun boundsFor(id:String):InkBounds?=bounds[id]
    // Per page: <=768 KiB runs, <=2 MiB coordinates, <=4 MiB BVH arrays, <=4096 records.
    // No raw samples/extensions are cloned, and exhausted budgets fall back to JSON geometry.
    private val strokeRuns=HashMap<String,StrokeHitRuns>()
    private var allocatedRuns=0
    private var allocatedCoordinatePoints=0
    private var allocatedTreeBytes=0
    private companion object {
        const val MIN_INDEXED_POINTS=16
        const val SEGMENTS_PER_RUN=32
        const val MAX_RUNS_PER_STROKE=4096
        const val MAX_PAGE_RUNS=32768
        const val MAX_INDEXED_STROKES=4096
        const val MAX_COORDINATE_POINTS=262144
        const val MAX_COORDINATE_POINTS_PER_STROKE=16384
        const val MAX_SEGMENT_TREE_BYTES=4*1024*1024
        const val MAX_OBJECT_MEMBERSHIPS=16
    }
    /** Installed geometry is immutable until put/remove; metadata-only publication is safe. */
    fun hit(obj:JSONObject,p:InkPoint,radius:Float):Boolean {
        val id=obj.optString("id")
        return if(objects[id]===obj)InkGeometry.hitWithRuns(obj,p,radius,bounds[id],strokeRuns[id])
            else InkGeometry.hit(obj,p,radius)
    }
    fun hitAlong(obj:JSONObject,centers:List<InkPoint>,radius:Float)=hitAlong(obj,InkEraseEvent(centers,radius))
    internal fun hitAlong(obj:JSONObject,event:InkEraseEvent):Boolean {
        val id=obj.optString("id")
        return if(objects[id]===obj)InkGeometry.hitAlongWithRuns(obj,event,bounds[id],strokeRuns[id])
            else InkGeometry.hitAlongWithRuns(obj,event,null,null)
    }
    fun eraseAlong(obj:JSONObject,centers:List<InkPoint>,radius:Float)=eraseAlong(obj,InkEraseEvent(centers,radius))
    internal fun eraseAlong(obj:JSONObject,event:InkEraseEvent):List<JSONObject> {
        val id=obj.optString("id")
        return if(objects[id]===obj)InkGeometry.eraseAlongWithRuns(obj,event,bounds[id],strokeRuns[id])
            else InkGeometry.eraseAlongWithRuns(obj,event,null,null)
    }
    private fun indexStroke(obj:JSONObject,localBounds:InkBounds) {
        if(obj.optString("type")!="stroke"||strokeRuns.size>=MAX_INDEXED_STROKES)return
        val raw=obj.array("points");val length=raw.length()
        if(length<MIN_INDEXED_POINTS)return
        val capacity=minOf(MAX_RUNS_PER_STROKE,MAX_PAGE_RUNS-allocatedRuns,
            ((length.toLong()-1+SEGMENTS_PER_RUN-1)/SEGMENTS_PER_RUN).toInt())
        if(capacity<=0)return
        val perRun=maxOf(SEGMENTS_PER_RUN,((length.toLong()-1+capacity-1)/capacity).toInt())
        val starts=IntArray(capacity);val ends=IntArray(capacity);val boxes=FloatArray(capacity*4)
        val coordinates=if(length<=MAX_COORDINATE_POINTS_PER_STROKE&&
            length<=MAX_COORDINATE_POINTS-allocatedCoordinatePoints)FloatArray(length*2) else null
        var count=0;var start=-1;var end=-1;var segments=0;var valid=0
        var left=0f;var top=0f;var right=0f;var bottom=0f
        fun append(){
            starts[count]=start;ends[count]=end
            val offset=count*4;boxes[offset]=left;boxes[offset+1]=top
            boxes[offset+2]=right;boxes[offset+3]=bottom
            count++
        }
        for(i in 0 until length){
            val point=raw.optJSONObject(i)?:continue
            val x=point.f("x");val y=point.f("y")
            val sample=if(coordinates==null)i else valid
            if(coordinates!=null){coordinates[valid*2]=x;coordinates[valid*2+1]=y}
            valid++
            if(start<0){start=sample;left=x;top=y;right=x;bottom=y}
            else{
                left=minOf(left,x);top=minOf(top,y);right=maxOf(right,x);bottom=maxOf(bottom,y)
                segments++
            }
            end=sample
            if(segments==perRun){
                append()
                // Adjacent runs share their endpoint, retaining sparse cross-run segments.
                start=sample;segments=0;left=x;top=y;right=x;bottom=y
            }
        }
        if(start>=0&&(segments>0||count==0))append()
        if(count>0){
            val tree=if(coordinates==null)null else StrokeSegmentTree.create(coordinates,valid,MAX_SEGMENT_TREE_BYTES-allocatedTreeBytes)
            strokeRuns[obj.optString("id")]=StrokeHitRuns(starts,ends,boxes,count,localBounds,coordinates,tree)
            allocatedRuns+=capacity;allocatedCoordinatePoints+=(coordinates?.size?:0)/2
            allocatedTreeBytes+=tree?.bytes?:0
        }
    }
    /** Geometry is editor-owned; a replacement object must be put even when its ID is unchanged. */
    internal fun tracks(obj:JSONObject)=objects[obj.optString("id")]===obj
    private fun key(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
    private fun cells(box: InkBounds,maxCells:Int=256):List<Long>? {
        val minX = floor(box.left / cell).toInt(); val maxX = floor(box.right / cell).toInt()
        val minY = floor(box.top / cell).toInt(); val maxY = floor(box.bottom / cell).toInt()
        val columns=maxX.toLong()-minX+1;val rows=maxY.toLong()-minY+1
        if(columns<=0||rows<=0||columns>maxCells||rows>maxCells||columns*rows>maxCells)return null
        return buildList { for (x in minX..maxX) for (y in minY..maxY) add(key(x, y)) }
    }
    fun rebuild(source: List<JSONObject>) {
        buckets.clear();wide.clear();objects.clear();occupied.clear();bounds.clear();broadBounds.clear()
        strokeRuns.clear();allocatedRuns=0;allocatedCoordinatePoints=0;allocatedTreeBytes=0
        source.forEach(::put)
    }
    fun put(obj: JSONObject) {
        val id = obj.optString("id");if (id.isBlank()) return
        remove(id)
        val box = InkGeometry.bounds(obj)
        if (!box.left.isFinite() || !box.top.isFinite() || !box.right.isFinite() || !box.bottom.isFinite()) return
        objects[id] = obj;bounds[id] = box
        // Lasso historically tests raw stroke points, even on rotated strokes. Keep that broad phase conservative.
        val raw=if(obj.optString("type")=="stroke"&&obj.f("rotation")!=0f)InkGeometry.unrotatedBounds(obj) else box
        indexStroke(obj,raw)
        val broad=InkBounds(minOf(box.left,raw.left),minOf(box.top,raw.top),maxOf(box.right,raw.right),maxOf(box.bottom,raw.bottom))
        broadBounds[id]=broad
        // Long strokes belong to the existing overflow set rather than hundreds of buckets.
        // Queries still use their full area and exact bounds/BVH predicates; only edit bookkeeping changes.
        val keys=cells(broad,MAX_OBJECT_MEMBERSHIPS)
        if (keys == null) wide += id
        else { occupied[id] = keys;keys.forEach { buckets.getOrPut(it) { LinkedHashSet() } += id } }
    }
    fun remove(id: String) {
        wide.remove(id);objects.remove(id);bounds.remove(id);broadBounds.remove(id)
        strokeRuns.remove(id)?.let{
            allocatedRuns-=it.starts.size;allocatedCoordinatePoints-=(it.coordinates?.size?:0)/2
            allocatedTreeBytes-=it.tree?.bytes?:0
        }
        occupied.remove(id)?.forEach { cellKey -> buckets[cellKey]?.let { bucket ->
            bucket.remove(id);if (bucket.isEmpty()) buckets.remove(cellKey)
        } }
    }
    fun query(box: InkBounds): List<JSONObject> {
        val result = ArrayList<JSONObject>()
        fun append(id:String) {
            if(broadBounds[id]?.overlaps(box)==true)objects[id]?.let(result::add)
        }
        // Overflow strokes occupy no cell buckets. Iterate them directly: copying all
        // of their IDs into a fresh hash table on every MOVE costs O(page size) allocations,
        // even when the query touches only a few strokes. Keep the historical result order.
        for(id in wide)append(id)
        val keys = cells(box)
        if(keys==null){
            for(id in objects.keys)if(id !in wide)append(id)
        }else{
            val seen=HashSet<String>()
            for(key in keys)for(id in buckets[key]?:continue)if(seen.add(id))append(id)
        }
        return result
    }
}
