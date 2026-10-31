package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PageSpatialMembershipTest {
    private fun stroke(id:String,x1:Float,y1:Float,x2:Float,y2:Float,rotation:Double=0.0):JSONObject {
        val points=JSONArray()
        repeat(64){index->val t=index/63f
            points.put(json("x" to x1+(x2-x1)*t,"y" to y1+(y2-y1)*t,
                "vendor" to json("keep" to JSONObject.NULL)))}
        return json("id" to id,"type" to "stroke","width" to 4,"rotation" to rotation,"points" to points)
    }
    private fun broad(obj:JSONObject):InkBounds {
        val page=InkGeometry.bounds(obj)
        if(obj.f("rotation")==0f)return page
        val raw=InkGeometry.unrotatedBounds(obj)
        return InkBounds(minOf(page.left,raw.left),minOf(page.top,raw.top),maxOf(page.right,raw.right),maxOf(page.bottom,raw.bottom))
    }
    private fun parity(index:PageSpatialIndex,source:List<JSONObject>,areas:List<InkBounds>){
        for(area in areas){
            val expected=source.filter{broad(it).overlaps(area)}.mapTo(HashSet()){it.getString("id")}
            val actual=index.query(area)
            assertEquals("query $area",expected,actual.mapTo(HashSet()){it.getString("id")})
            assertEquals("duplicate candidate $area",expected.size,actual.size)
            val point=InkPoint((area.left+area.right)/2,(area.top+area.bottom)/2)
            for(obj in index.query(area))assertEquals("exact hit ${obj.getString("id")}",
                InkGeometry.hit(obj,point,8f),index.hit(obj,point,8f))
        }
    }
    @Test fun queryKeepsOverflowOrderAndDeduplicatesBucketEdgesWithoutBorrowingItsResult(){
        val small=stroke("small",94f,94f,100f,100f)
        val wideA=stroke("wide-a",0f,0f,1000f,1400f)
        val wideB=stroke("wide-b",0f,0f,1000f,1400f)
        val later=stroke("later",95f,95f,99f,99f)
        val index=PageSpatialIndex();index.rebuild(listOf(small,wideA,later,wideB))
        val area=InkBounds(90f,90f,102f,102f)
        val first=index.query(area)
        assertEquals(listOf(wideA,wideB,small,later),first)
        val far=InkBounds(-40000f,-40000f,40000f,40000f)
        val all=index.query(far)
        assertEquals(listOf(wideA,wideB),all.take(2))
        assertEquals(4,all.size)
        assertEquals(setOf(wideA,wideB,small,later),all.toSet())
        index.remove("wide-a")
        index.put(stroke("small",2000f,2000f,3000f,3400f))
        assertEquals(listOf(wideB,later),index.query(area))
        assertEquals(listOf(wideA,wideB,small,later),first)
    }
    @Test fun longAndSmallObjectQueriesKeepExactBoundsAtGridEdgesAndRawRotatedPoints(){
        val objects=listOf(stroke("page-spanning",2f,3f,998f,1388f),
            stroke("small",94f,94f,100f,100f),stroke("negative",-200f,-100f,-95f,-95f),
            stroke("rotated",5f,300f,900f,305f,.73),stroke("huge",-30000f,-30000f,30000f,30000f))
        val original=objects.map{it.toString()}
        val index=PageSpatialIndex();index.rebuild(objects)
        parity(index,objects,listOf(InkBounds(96f,96f,96f,96f),InkBounds(192f,192f,193f,193f),
            InkBounds(-96f,-96f,-96f,-96f),InkBounds(498f,98f,502f,102f),
            InkBounds(2f,298f,8f,304f),InkBounds(950f,1300f,1000f,1400f),
            InkBounds(-31000f,-31000f,31000f,31000f),InkBounds(31000f,31000f,32000f,32000f)))
        assertEquals(original,objects.map{it.toString()})
    }
    @Test fun replacementsRemovalAndRebuildNeverLeaveOldSpanCandidates(){
        val initial=stroke("moving",0f,0f,1000f,1400f)
        val stable=stroke("stable",95f,95f,97f,97f)
        val source=mutableListOf(initial,stable)
        val index=PageSpatialIndex();index.rebuild(source)
        val areas=listOf(InkBounds(90f,90f,100f,100f),InkBounds(900f,1200f,1000f,1400f),
            InkBounds(-500f,-500f,-300f,-300f))
        parity(index,source,areas)
        val replacement=stroke("moving",-450f,-450f,-440f,-440f)
        source[0]=replacement;index.put(replacement);parity(index,source,areas)
        index.remove("moving");source.removeAt(0);parity(index,source,areas)
        source.add(initial);index.put(initial);parity(index,source,areas)
        index.rebuild(listOf(replacement));parity(index,listOf(replacement),areas)
    }
}
