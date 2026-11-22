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

    @Test fun translatedStrokeOwnsItsJsonAndPreservesCaptureAndMissingFields() {
        val original=stroke("translate","""[
            {"x":10,"y":20,"p":0,"t":100,"pointerId":7,"ty":null,"vendor":{"order":[3,1,2]}},
            {"x":30,"y":40,"tilt":0.4,"orientation":0.2,"azimuth":1.1}
        ]""").put("captureSeq",42).put("captureSessionId","session").put("timeBasis","session-monotonic")
            .put("revision",8).put("future",JSONObject("""{"values":[null,1,"x"]}"""))
        val before=original.toString()
        val moved=InkGeometry.transform(original,17f,-9f)
        assertEquals(before,original.toString())
        assertEquals(42,moved.getInt("captureSeq"))
        assertEquals("session",moved.getString("captureSessionId"))
        assertEquals("session-monotonic",moved.getString("timeBasis"))
        assertEquals(8,moved.getInt("revision"))
        val raw=moved.getJSONArray("points")
        assertEquals(27.0,raw.getJSONObject(0).getDouble("x"),0.0)
        assertEquals(11.0,raw.getJSONObject(0).getDouble("y"),0.0)
        assertTrue(raw.getJSONObject(0).isNull("ty"))
        assertFalse(raw.getJSONObject(1).has("p"))
        assertFalse(raw.getJSONObject(1).has("t"))
        assertFalse(raw.getJSONObject(1).has("pointerId"))
        raw.getJSONObject(0).getJSONObject("vendor").getJSONArray("order").put(0,99)
        moved.getJSONObject("future").getJSONArray("values").put(1,99)
        assertEquals(before,original.toString())
    }

    @Test fun indexedLassoPreservesRawPointRuleAndPageOrderForRotatedInk() {
        val raw=stroke("rotated","""[{"x":0,"y":0,"vendor":{"keep":1}},{"x":400,"y":0}]""")
            .put("rotation",Math.PI/2)
        val center=JSONObject("""{"id":"center","type":"text","x":-5,"y":-5,"w":10,"h":10}""")
        val far=stroke("far","""[{"x":700,"y":700},{"x":800,"y":800}]""")
        val objects=listOf(raw,center,far)
        val index=PageSpatialIndex();index.rebuild(objects)
        val polygon=listOf(InkPoint(-10f,-10f),InkPoint(10f,-10f),InkPoint(10f,10f),InkPoint(-10f,10f))
        val area=InkBounds(-10f,-10f,10f,10f)
        val candidates=index.query(area).map{it.getString("id")}.toSet()
        val selected=objects.filter{it.getString("id") in candidates&&InkGeometry.insideLasso(it,polygon,index.boundsFor(it.getString("id")))}
        assertEquals(listOf("rotated","center"),selected.map{it.getString("id")})
        assertEquals(InkGeometry.bounds(raw),index.boundsFor("rotated"))
        index.put(InkGeometry.transform(center,200f,200f))
        assertFalse(index.query(area).any{it.getString("id")=="center"})
        index.remove("rotated")
        assertNull(index.boundsFor("rotated"))
        assertTrue(index.query(area).isEmpty())
    }

    @Test fun consecutiveOwnedMovesKeepIndependentUndoAndRedoGeometry(){
        val first=stroke("first","""[{"x":10,"y":20,"p":0,"ty":null,"vendor":{"order":[3,1,2]}},{"x":30,"y":40}]""")
            .put("captureSeq",42).put("captureSessionId","session")
        val middle=JSONObject("""{"id":"locked","type":"text","x":50,"y":60,"w":20,"h":20,"locked":true}""")
        val last=stroke("last","""[{"x":70,"y":80},{"x":90,"y":100}]""")
        val page=NotePage("page",JSONObject(),mutableListOf(first,middle,last))
        val original=page.objects.map{it.toString()}
        val history=EditHistory()
        fun move(dx:Float,dy:Float){
            val before=page.objects.filter{!it.optBoolean("locked")}
            val positions=page.objects.mapIndexed{index,obj->obj.getString("id") to index}.toMap()
            val change=ObjectChange(before,before.map{InkGeometry.transform(it,dx,dy)},positions,positions)
            change.applyOwnedAfter(page);history.push(change)
        }
        move(15f,25f)
        val afterFirst=page.objects.map{it.toString()}
        move(-5f,35f)
        val afterSecond=page.objects.map{it.toString()}
        history.undo()!!.apply(page);assertEquals(afterFirst,page.objects.map{it.toString()})
        history.undo()!!.apply(page);assertEquals(original,page.objects.map{it.toString()})
        history.redo()!!.apply(page);assertEquals(afterFirst,page.objects.map{it.toString()})
        history.redo()!!.apply(page);assertEquals(afterSecond,page.objects.map{it.toString()})
        assertEquals(listOf("first","locked","last"),page.objects.map{it.getString("id")})
        assertEquals(42,page.objects.first().getInt("captureSeq"))
        assertTrue(page.objects.first().getJSONArray("points").getJSONObject(0).isNull("ty"))
        assertEquals("[3,1,2]",page.objects.first().getJSONArray("points").getJSONObject(0)
            .getJSONObject("vendor").getJSONArray("order").toString())
    }

    @Test fun transformedNumbersRenderIdenticallyBeforeAndAfterJsonReload(){
        val source=stroke("numeric","""[{"x":180,"y":280,"p":0.2,"vendor":{"axis":9}},{"x":180.35,"y":280.887}]""")
        val moved=InkGeometry.transform(source,95.99988f,64.00061f,1.013f)
        val reloaded=moved.copyJson()
        assertEquals(moved.getDouble("width"),reloaded.getDouble("width"),0.0)
        for(index in 0 until moved.getJSONArray("points").length()){
            val live=moved.getJSONArray("points").getJSONObject(index)
            val saved=reloaded.getJSONArray("points").getJSONObject(index)
            assertEquals(live.getDouble("x"),saved.getDouble("x"),0.0)
            assertEquals(live.getDouble("y"),saved.getDouble("y"),0.0)
        }
        val text=JSONObject("""{"id":"text","type":"text","x":180.35,"y":280.887,"w":231.4,"h":71.2,"fontSize":22.3}""")
        val resized=InkGeometry.transform(text,95.99988f,64.00061f,1.013f)
        val savedText=resized.copyJson()
        for(key in listOf("x","y","w","h","fontSize"))assertEquals(resized.getDouble(key),savedText.getDouble(key),0.0)
    }

    @Test fun transformCopiesMatchJsonRoundtripScalarTypesAndNestedOwnership(){
        fun unusualNumber(literal:String)=object:Number(){
            override fun toByte()=0.toByte()
            override fun toShort()=0.toShort()
            override fun toInt()=0
            override fun toLong()=0L
            override fun toFloat()=.5f
            override fun toDouble()=.5
            override fun toString()=literal
        }
        val values=listOf<Number>(0.toByte(),(-17).toShort(),Int.MIN_VALUE,Int.MAX_VALUE,
            42L,Int.MAX_VALUE.toLong()+1,9_007_199_254_740_993L,Long.MIN_VALUE,Long.MAX_VALUE,
            .23f,-0f,Float.MIN_VALUE,Float.MAX_VALUE,
            -0.0,Double.MIN_VALUE,Double.MAX_VALUE,Math.nextUp(1.0),
            9_007_199_254_740_992.0,Long.MAX_VALUE.toDouble(),1.0E20,1.0E-7,
            java.math.BigDecimal("1.2300"),java.math.BigInteger("9223372036854775808"),
            unusualNumber("1/2"),unusualNumber("001"),unusualNumber("true"))
        val numbers=org.json.JSONArray()
        for(value in values)numbers.put(value)
        for(index in values.size-3 until values.size)assertTrue("Custom numeric fixture was wrapped",numbers.get(index) is Number)
        val mutableString=StringBuilder("before-copy")
        val vendor=json("numbers" to numbers,"null" to JSONObject.NULL,
            "nested" to org.json.JSONArray().put(JSONObject.NULL).put(null as Any?)
                .put(json("array" to org.json.JSONArray(listOf("001","null","true","한글 🖋️","\"\\\n\u0000","\uD800")))),
            "wrapper" to mutableString)
        val source=stroke("scalar","""[{"x":1,"y":2},{"x":3,"y":4}]""")
            .put("captureSeq",Long.MAX_VALUE).put("vendor",vendor)
        val before=source.toString()
        val expected=source.copyJson()
        val moved=InkGeometry.transform(source,0f,0f)
        fun same(expectedValue:Any?,actualValue:Any?,path:String){
            when(expectedValue){
                is JSONObject->{
                    assertTrue("$path object",actualValue is JSONObject)
                    val actual=actualValue as JSONObject
                    assertEquals(path,expectedValue.keys().asSequence().toSet(),actual.keys().asSequence().toSet())
                    for(key in expectedValue.keys())same(expectedValue.get(key),actual.get(key),"$path.$key")
                }
                is org.json.JSONArray->{
                    assertTrue("$path array",actualValue is org.json.JSONArray)
                    val actual=actualValue as org.json.JSONArray
                    assertEquals(path,expectedValue.length(),actual.length())
                    for(index in 0 until expectedValue.length())same(expectedValue.get(index),actual.get(index),"$path[$index]")
                }
                is Number->{
                    assertEquals("$path normalized type",expectedValue.javaClass,actualValue?.javaClass)
                    if(expectedValue is Double)assertEquals("$path bits",
                        java.lang.Double.doubleToRawLongBits(expectedValue),
                        java.lang.Double.doubleToRawLongBits(actualValue as Double))
                    else assertEquals(path,expectedValue,actualValue)
                }
                else->assertEquals(path,expectedValue,actualValue)
            }
        }
        same(expected.getJSONObject("vendor"),moved.getJSONObject("vendor"),"vendor")
        same(expected.get("captureSeq"),moved.get("captureSeq"),"captureSeq")
        moved.getJSONObject("vendor").getJSONArray("nested").getJSONObject(2).getJSONArray("array").put(0,"changed")
        moved.getJSONObject("vendor").getJSONArray("numbers").put(0,99)
        assertEquals("Transform aliases imported nested values",before,source.toString())
        mutableString.append("-mutated")
        assertEquals("before-copy",moved.getJSONObject("vendor").getString("wrapper"))
    }

    @Test fun hitReadsSparseImportedCoordinatesWithoutSerializingPointExtensions(){
        fun point(x:Float,y:Float)=object:JSONObject(){
            override fun toString():String=error("Read-only hit serialized an imported point")
        }.put("x",x).put("y",y).put("vendor",JSONObject("""{"raw":[null,3,1,2]}"""))
        val raw=org.json.JSONArray().put(point(0f,0f)).put(JSONObject.NULL).put(42).put(point(500f,0f))
        val obj=json("id" to "readonly","type" to "stroke","width" to 4,"points" to raw)
        val bounds=InkGeometry.bounds(obj)
        assertTrue(InkGeometry.hit(obj,InkPoint(250f,5f),10f,bounds))
        assertFalse(InkGeometry.hit(obj,InkPoint(250f,40f),10f,bounds))
        val normalized=json("points" to org.json.JSONArray().put(raw.getJSONObject(0)).put(raw.getJSONObject(3)))
        assertTrue(InkGeometry.sameStrokeCoordinates(obj,normalized))
        obj.put("rotation",Math.PI/2)
        assertTrue(InkGeometry.hit(obj,InkPoint(250f,100f),1f,InkGeometry.bounds(obj)))
        assertFalse(InkGeometry.hit(obj,InkPoint(270f,100f),1f,InkGeometry.bounds(obj)))
        assertEquals(4,raw.length())
        assertEquals("[null,3,1,2]",raw.getJSONObject(0).getJSONObject("vendor").getJSONArray("raw").toString())
        val dot=json("id" to "dot","type" to "stroke","width" to 4,
            "points" to org.json.JSONArray().put(point(10f,20f)))
        assertTrue(InkGeometry.hit(dot,InkPoint(13f,20f),1f))
        assertFalse(InkGeometry.hit(dot,InkPoint(13.1f,20f),1f))
    }

    @Test fun richTextScalingKeepsRunsAndPaddingInPageUnitsWithoutMutatingOriginal(){
        val source=json("id" to "text","type" to "text","x" to 20,"y" to 30,"w" to 180,"h" to 80,
            "fontSize" to 24,"textPadding" to 8,"text" to "한글 English","textRuns" to org.json.JSONArray()
                .put(json("kind" to "size","start" to 0,"end" to 2,"value" to 40))
                .put(json("kind" to "color","start" to 3,"end" to 10,"value" to "#147bd1")))
        val before=source.toString();val moved=InkGeometry.transform(source,10f,15f,2f)
        assertEquals(48f,moved.f("fontSize"),0f);assertEquals(16f,moved.f("textPadding"),0f)
        assertEquals(80f,moved.getJSONArray("textRuns").getJSONObject(0).f("value"),0f)
        assertEquals("#147bd1",moved.getJSONArray("textRuns").getJSONObject(1).getString("value"))
        assertEquals(before,source.toString())
    }
    @Test fun indexDistinguishesInstalledReplacementFromMetadataOnlyPublication(){
        val original=stroke("ink","""[{"x":0,"y":0},{"x":100,"y":0}]""")
        val index=PageSpatialIndex();index.rebuild(listOf(original))
        val bounds=index.boundsFor("ink")
        original.put("revision",2)
        assertTrue(index.tracks(original))
        assertSame(bounds,index.boundsFor("ink"))
        val replacement=InkGeometry.transform(original,500f,500f)
        assertFalse(index.tracks(replacement));index.put(replacement)
        assertTrue(index.tracks(replacement))
        assertTrue(index.query(InkBounds(-10f,-10f,110f,10f)).isEmpty())
        assertEquals("ink",index.query(InkBounds(490f,490f,610f,510f)).single().getString("id"))
        index.remove("ink");assertFalse(index.tracks(replacement))
    }
}
