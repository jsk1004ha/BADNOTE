package com.inkforge.notesstudio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InkGeometryEditTest {
    private fun stroke(id:String,points:String,brush:String="fountain") = JSONObject("""{
        "id":"$id","type":"stroke","brush":"$brush","width":4,"points":$points
    }""")

    @Test fun rawPointFieldsSurviveTransformAndPartialErase() {
        val original=stroke("raw", """[
            {"x":0,"y":0,"p":0,"t":100,"tilt":0.4,"orientation":0.2,"azimuth":1.1,"tx":0,"ty":null,"vendor":{"axis":9}},
            {"x":500,"y":0,"p":0.8,"t":200,"azimuth":1.2,"tx":4,"vendor":{"axis":10}}
        ]""")
        val shifted=InkGeometry.transform(original,10f,20f)
        val start=shifted.getJSONArray("points").getJSONObject(0)
        assertEquals(10.0,start.getDouble("x"),0.0)
        assertEquals(0.0,start.getDouble("p"),0.0)
        assertTrue(start.isNull("ty"))
        assertEquals(9,start.getJSONObject("vendor").getInt("axis"))
        assertEquals(1.1,start.getDouble("azimuth"),0.0)
        val fragments=InkGeometry.eraseParts(shifted,InkPoint(260f,20f),12f)
        assertEquals(2,fragments.size)
        assertEquals("raw",fragments.first().getString("id"))
        assertEquals("raw",fragments.last().getString("sourceStrokeId"))
        val first=fragments.first().getJSONArray("points").getJSONObject(0)
        val last=fragments.last().getJSONArray("points").getJSONObject(0)
        assertEquals(9,first.getJSONObject("vendor").getInt("axis"))
        assertTrue(first.isNull("ty"))
        assertTrue(last.has("azimuth"))
        assertTrue(last.has("tx"))
        val finalPoint=fragments.last().getJSONArray("points").getJSONObject(1)
        assertEquals(10,finalPoint.getJSONObject("vendor").getInt("axis"))
        assertFalse(finalPoint.has("ty"))
    }

    @Test fun sparseSegmentsAndVisibleShapeOutlineAreExactHits() {
        val line=stroke("sparse","""[{"x":0,"y":0},{"x":500,"y":0}]""")
        assertTrue(InkGeometry.hit(line,InkPoint(250f,5f),10f))
        assertFalse(InkGeometry.hit(line,InkPoint(250f,40f),10f))
        val locked=line.copyJson().put("locked",true)
        assertFalse(InkGeometry.hit(locked,InkPoint(250f,0f),10f))
        val heart=JSONObject("""{"id":"heart","type":"shape","shape":"heartshape",
            "x1":0,"y1":0,"x2":100,"y2":100,"width":2}""")
        assertTrue(InkGeometry.hit(heart,InkPoint(50f,23f),3f))
        assertFalse(InkGeometry.hit(heart,InkPoint(50f,65f),3f))
        val curve=JSONObject("""{"id":"arc","type":"shape","shape":"arc",
            "x1":0,"y1":100,"x2":100,"y2":100,"cx":50,"cy":0,"width":2}""")
        assertTrue(InkGeometry.hit(curve,InkPoint(50f,50f),3f))
        assertFalse(InkGeometry.hit(curve,InkPoint(50f,90f),3f))
    }

    @Test fun spatialIndexNarrowsCandidatesButTracksFragmentsAndMoves() {
        val near=stroke("near","""[{"x":0,"y":0},{"x":500,"y":0}]""")
        val far=stroke("far","""[{"x":700,"y":700},{"x":800,"y":800}]""")
        val index=PageSpatialIndex()
        index.rebuild(listOf(near,far))
        val touch=InkBounds(240f,-10f,260f,10f)
        assertEquals(listOf("near"),index.query(touch).map{it.getString("id")})
        val fragments=InkGeometry.eraseParts(near,InkPoint(250f,0f),12f)
        index.remove("near");fragments.forEach(index::put)
        assertTrue(index.query(touch).isEmpty())
        val moved=InkGeometry.transform(far,-450f,-700f)
        index.put(moved)
        assertEquals("far",index.query(touch).single().getString("id"))
    }
}
