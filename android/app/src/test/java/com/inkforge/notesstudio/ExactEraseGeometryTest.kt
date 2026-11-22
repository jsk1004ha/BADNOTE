package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class ExactEraseGeometryTest {
    private fun stroke(points:JSONArray,id:String="source")=json("id" to id,"type" to "stroke",
        "width" to 2,"revision" to 7,"points" to points,"vendor" to JSONObject("""{"raw":[null,3,1,2]}"""))

    private fun referenceStrokeHit(obj:JSONObject,center:InkPoint,radius:Float):Boolean {
        if(obj.optBoolean("locked")||obj.optBoolean("hidden")||
            !InkGeometry.bounds(obj).expanded(radius).contains(center.x,center.y))return false
        val angle=obj.f("rotation").toDouble()
        val point=if(angle==0.0)center else {
            val bounds=InkGeometry.unrotatedBounds(obj)
            val cx=(bounds.left+bounds.right)/2;val cy=(bounds.top+bounds.bottom)/2
            val dx=center.x-cx;val dy=center.y-cy
            center.copy(x=(cx+dx*kotlin.math.cos(angle)+dy*kotlin.math.sin(angle)).toFloat(),
                y=(cy-dx*kotlin.math.sin(angle)+dy*kotlin.math.cos(angle)).toFloat())
        }
        val r=radius+obj.f("width",4f)/2
        var prior:InkPoint?=null
        val raw=obj.array("points")
        for(i in 0 until raw.length()){
            val sample=raw.optJSONObject(i)?:continue
            val current=InkPoint(sample.f("x"),sample.f("y"))
            if(kotlin.math.hypot(current.x-point.x,current.y-point.y)<=r)return true
            val a=prior
            if(a!=null){
                val dx=current.x-a.x;val dy=current.y-a.y;val len=dx*dx+dy*dy
                val t=if(len<=.00001f)0f else (((point.x-a.x)*dx+(point.y-a.y)*dy)/len).coerceIn(0f,1f)
                if(kotlin.math.hypot(point.x-a.x-t*dx,point.y-a.y-t*dy)<=r)return true
            }
            prior=current
        }
        return false
    }

    // Frozen pre-optimization scalar/JSON behavior, including its tangent midpoint rule.
    private fun referenceErase(obj:JSONObject,center:InkPoint,radius:Float):List<JSONObject> {
        fun lerp(a:InkPoint,b:InkPoint,t:Float):InkPoint {
            if(t<=0f)return a
            if(t>=1f)return b
            val pressure=a.pressure+(b.pressure-a.pressure)*t
            val time=a.time+((b.time-a.time)*t).toLong()
            val tilt=a.tilt+(b.tilt-a.tilt)*t
            val orientation=a.orientation+(b.orientation-a.orientation)*t
            fun optional(first:Float?,second:Float?)=if(first!=null&&second!=null)first+(second-first)*t else first?:second
            val azimuth=optional(a.azimuth,b.azimuth);val tx=optional(a.tx,b.tx);val ty=optional(a.ty,b.ty)
            val original=(a.original?.copyJson()?:JSONObject()).put("p",pressure).put("t",time)
                .put("tilt",tilt).put("orientation",orientation).put("pointerId",a.pointerId)
            azimuth?.let{original.put("azimuth",it)};tx?.let{original.put("tx",it)};ty?.let{original.put("ty",it)}
            return InkPoint(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,pressure,time,tilt,orientation,
                a.pointerId,azimuth,tx,ty,original)
        }
        val points=InkGeometry.pagePoints(obj);val r=radius+obj.f("width",4f)/2
        if(points.size<2)return if(points.any{InkGeometry.distance(it,center)<=r})emptyList() else listOf(obj)
        val fragments=mutableListOf<MutableList<InkPoint>>()
        var current=mutableListOf<InkPoint>()
        fun flush(){if(current.size>=2)fragments+=current;current=mutableListOf()}
        for((a,b) in points.zipWithNext()){
            val dx=b.x-a.x;val dy=b.y-a.y;val ax=a.x-center.x;val ay=a.y-center.y
            val aa=dx*dx+dy*dy;val bb=2*(ax*dx+ay*dy);val cc=ax*ax+ay*ay-r*r
            val cuts=mutableListOf(0f,1f);val discriminant=bb*bb-4*aa*cc
            if(aa>1e-8f&&discriminant>0){
                val root=sqrt(discriminant)
                listOf((-bb-root)/(2*aa),(-bb+root)/(2*aa)).filter{it>0f&&it<1f}.forEach{cuts+=it}
            }
            cuts.sort()
            for((from,to) in cuts.zipWithNext()){
                if(InkGeometry.distance(lerp(a,b,(from+to)/2),center)>r){
                    val first=lerp(a,b,from);val last=lerp(a,b,to)
                    if(current.isEmpty()||InkGeometry.distance(current.last(),first)>.001f)current+=first
                    current+=last
                }else flush()
            }
        }
        flush()
        return fragments.mapIndexed{index,fragment->obj.copyJson().put("id",if(index==0)obj.getString("id") else "reference_$index")
            .put("sourceStrokeId",obj.optString("sourceStrokeId",obj.getString("id"))).put("fragmentOrder",index)
            .put("revision",obj.optLong("revision",0)+1).put("rotation",0).put("points",JSONArray(fragment.map{it.json()}))}
    }

    private fun assertJson(expected:Any?,actual:Any?,path:String="root") {
        when(expected){
            is JSONObject->{
                assertTrue("$path object",actual is JSONObject)
                actual as JSONObject
                assertEquals(path,expected.keys().asSequence().toSet(),actual.keys().asSequence().toSet())
                for(key in expected.keys())assertJson(expected.get(key),actual.get(key),"$path.$key")
            }
            is JSONArray->{
                assertTrue("$path array",actual is JSONArray)
                actual as JSONArray
                assertEquals(path,expected.length(),actual.length())
                for(i in 0 until expected.length())assertJson(expected.get(i),actual.get(i),"$path[$i]")
            }
            is Number->{
                assertEquals("$path numeric type",expected.javaClass,actual?.javaClass)
                assertEquals(path,expected,actual)
            }
            else->assertEquals(path,expected,actual)
        }
    }

    private fun assertReference(obj:JSONObject,center:InkPoint,radius:Float) {
        val before=obj.toString();val expected=referenceErase(obj,center,radius)
        val actual=InkGeometry.eraseParts(obj,center,radius)
        assertEquals("Source JSON changed",before,obj.toString())
        assertEquals(expected.size,actual.size)
        if(actual.size==1&&actual[0]===obj){
            assertEquals(0f,obj.f("rotation"),0f)
            assertTrue("Identity no-op changed surviving coordinates",InkGeometry.sameStrokeCoordinates(expected[0],obj))
            return
        }
        for(i in expected.indices){
            if(i>0){
                assertTrue(actual[i].getString("id").startsWith("stroke_"))
                assertNotEquals(obj.getString("id"),actual[i].getString("id"))
                expected[i].put("id",actual[i].getString("id"))
            }
            assertJson(expected[i],actual[i],"fragment[$i]")
        }
        assertEquals(actual.size,actual.map{it.getString("id")}.toSet().size)
    }

    @Test fun preciseEraseMatchesFloatReferenceAcrossSparseRepeatedRotatedAndImportedPoints(){
        val raw=JSONArray("""[
            {"x":0,"y":0,"p":0,"t":100,"tilt":0.4,"orientation":0.2,"azimuth":1.1,"tx":0,"ty":null,"vendor":{"axis":[9,null]}},
            null,42,
            {"x":500,"y":0,"p":0.8,"t":200,"azimuth":1.2,"tx":4,"vendor":{"axis":[10]}},
            {"x":500,"y":0,"ty":3,"pointerId":8},
            {"x":500.0001,"y":0.0001,"p":null},
            {"x":500,"y":400,"t":-20,"orientation":0.8,"azimuth":null}
        ]""")
        for(angle in listOf(0.0,.31,Math.PI/2,-Math.PI/3)){
            val obj=stroke(raw).put("rotation",angle).put("sourceStrokeId","ancestor")
            for(center in listOf(InkPoint(0f,0f),InkPoint(250f,0f),InkPoint(500f,0f),
                InkPoint(250f,10f),InkPoint(500f,200f),InkPoint(250f,200f),InkPoint(900f,900f)))
                for(radius in listOf(-1f,0f,.125f,9f,20f))assertReference(obj,center,radius)
        }
        val random=java.util.Random(149)
        repeat(48){
            val points=JSONArray()
            repeat(6){points.put(json("x" to (random.nextFloat()-.5f)*1000,"y" to (random.nextFloat()-.5f)*1000,
                "p" to random.nextFloat(),"t" to random.nextLong(),"extension" to JSONArray(listOf(null,it))))}
            assertReference(stroke(points).put("rotation",if(it%2==0)0 else .43),
                InkPoint((random.nextFloat()-.5f)*600,(random.nextFloat()-.5f)*600),random.nextFloat()*100)
        }
    }

    @Test fun tangentMidpointAndSparseAnalyticCutsRetainHistoricalRules(){
        val obj=stroke(JSONArray("""[{"x":0,"y":0},{"x":100,"y":0}]"""))
        assertTrue(InkGeometry.eraseParts(obj,InkPoint(50f,10f),9f).isEmpty())
        assertSame(obj,InkGeometry.eraseParts(obj,InkPoint(25f,10f),9f).single())
        val pieces=InkGeometry.eraseParts(obj,InkPoint(50f,0f),9f)
        assertEquals(2,pieces.size)
        assertEquals(40f,InkGeometry.points(pieces[0]).last().x,0f)
        assertEquals(60.000004f,InkGeometry.points(pieces[1]).first().x,.00001f)
        assertEquals(8L,pieces[0].getLong("revision"))
        assertEquals("source",pieces[1].getString("sourceStrokeId"))
        assertEquals(1,pieces[1].getInt("fragmentOrder"))
    }

    @Test fun hitAndNoOpNeverSerializeImportedExtensionsOrTheWholeStroke(){
        fun point(x:Float)=object:JSONObject(){
            override fun toString():String=error("Read-only/no-op geometry copied point metadata")
        }.put("x",x).put("y",0).put("extension",JSONObject("""{"raw":[null,1,2]}"""))
        val obj=object:JSONObject(){override fun toString():String=error("Geometry copied the entire source stroke")}
            .put("id","read-only").put("type","stroke").put("width",2)
            .put("points",JSONArray().put(point(0f)).put(JSONObject.NULL).put(point(500f)))
        val index=PageSpatialIndex();index.put(obj)
        assertTrue(index.hit(obj,InkPoint(250f,0f),9f))
        assertFalse(index.hit(obj,InkPoint(250f,40f),9f))
        assertSame(obj,InkGeometry.eraseParts(obj,InkPoint(250f,40f),9f).single())
        assertSame(obj,InkGeometry.eraseParts(obj,InkPoint(25f,10f),9f).single())
    }

    @Test fun fragmentJsonOwnsExtensionsWithoutCopyingDiscardedOriginalPoints(){
        val obj=object:JSONObject(){override fun toString():String=error("Fragment copied discarded source points")}
            .put("id","owned").put("type","stroke").put("width",2).put("revision",4)
            .put("future",JSONObject("""{"list":[null,1]}"""))
            .put("points",JSONArray("""[{"x":0,"y":0,"ty":null,"vendor":{"axis":9}},
                {"x":100,"y":0,"vendor":{"axis":10}}]"""))
        val parts=InkGeometry.eraseParts(obj,InkPoint(50f,0f),9f)
        assertEquals(2,parts.size)
        val start=parts[0].getJSONArray("points").getJSONObject(0)
        val cut=parts[1].getJSONArray("points").getJSONObject(0)
        assertTrue(start.isNull("ty"));assertTrue(cut.isNull("ty"))
        assertFalse(parts[1].getJSONArray("points").getJSONObject(1).has("ty"))
        start.getJSONObject("vendor").put("axis",99)
        cut.getJSONObject("vendor").put("axis",88)
        parts[0].getJSONObject("future").getJSONArray("list").put(1,99)
        assertEquals(9,obj.getJSONArray("points").getJSONObject(0).getJSONObject("vendor").getInt("axis"))
        assertEquals(9,parts[0].getJSONArray("points").getJSONObject(1).getJSONObject("vendor").getInt("axis"))
        assertEquals(1,obj.getJSONObject("future").getJSONArray("list").getInt(1))
        assertEquals(1,parts[1].getJSONObject("future").getJSONArray("list").getInt(1))
    }

    @Test fun runIndexedHitsMatchRawGeometryWithRotationLocksMalformedSamplesAndReplacement(){
        val raw=JSONArray()
        repeat(321){i->
            if(i%33==0)raw.put(JSONObject.NULL).put(42)
            raw.put(json("x" to i*3.25f,"y" to ((i%71)-35)*2.125f))
        }
        val index=PageSpatialIndex()
        for(angle in listOf(0.0,.31,Math.PI/2)){
            val obj=stroke(raw).put("rotation",angle);index.rebuild(listOf(obj))
            for(x in -100..1200 step 47)for(y in -600..600 step 43)for(radius in listOf(0f,.125f,9f,32f)){
                val p=InkPoint(x.toFloat(),y.toFloat())
                assertEquals("x=$x y=$y angle=$angle radius=$radius",InkGeometry.hit(obj,p,radius),index.hit(obj,p,radius))
            }
            val p=InkGeometry.pagePoints(obj)[160]
            obj.put("locked",true);assertFalse(index.hit(obj,p,20f));obj.remove("locked")
            obj.put("hidden",true);assertFalse(index.hit(obj,p,20f));obj.remove("hidden")
            val replacement=InkGeometry.transform(obj,250f,250f)
            assertEquals(InkGeometry.hit(replacement,p,20f),index.hit(replacement,p,20f))
            index.put(replacement);assertFalse(index.tracks(obj));assertTrue(index.tracks(replacement))
            assertEquals(InkGeometry.hit(replacement,p,20f),index.hit(replacement,p,20f))
            index.remove("source");assertEquals(InkGeometry.hit(replacement,p,20f),index.hit(replacement,p,20f))
        }
    }

    @Test fun tightResidualRejectionMatchesOldScalarOracleAtBoundariesExtremeFloatsAndRotations(){
        val line=JSONArray().apply{repeat(64){put(json("x" to it*10,"y" to 0))}}
        val wave=JSONArray().apply{repeat(64){put(json("x" to it*10,"y" to ((it%7)-3)*12))}}
        val cancellation=JSONArray().apply{
            repeat(33){put(json("x" to if(it%2==0)-16777216f else 0f,"y" to 0))}
            repeat(31){put(json("x" to 100,"y" to 100))}
        }
        val tiny=JSONArray().apply{repeat(64){put(json("x" to Float.MIN_VALUE*it,"y" to Float.MIN_VALUE*(it%3)))}}
        val huge=JSONArray().apply{repeat(64){put(json("x" to if(it%2==0)Float.MAX_VALUE/2 else -Float.MAX_VALUE/2,
            "y" to if(it%3==0)Float.MAX_VALUE/4 else -Float.MAX_VALUE/4))}}
        val overflowing=JSONArray().apply{repeat(64){put(json("x" to if(it%2==0)Float.MAX_VALUE else -Float.MAX_VALUE,"y" to 0))}}
        for((fixture,raw) in listOf(line,wave,cancellation,tiny,huge,overflowing).withIndex())
            for(angle in listOf(0.0,.31,Math.PI/2,-Math.PI/3)){
                val obj=stroke(raw).put("width",if(fixture==3)0 else 2).put("rotation",angle)
                val index=PageSpatialIndex();index.put(obj)
                val centers=mutableListOf(InkPoint(50f,10f),InkPoint(50f,Math.nextDown(10f)),
                    InkPoint(50f,Math.nextUp(10f)),InkPoint(1f,0f),InkPoint(0f,0f),
                    InkPoint(Float.POSITIVE_INFINITY,0f),InkPoint(Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY))
                val page=InkGeometry.pagePoints(obj)
                for(i in listOf(0,page.size/2,page.lastIndex)){
                    val point=page[i]
                    centers.add(point)
                    centers.add(InkPoint(Math.nextUp(point.x),point.y))
                    centers.add(InkPoint(Math.nextDown(point.x),point.y))
                    centers.add(InkPoint(point.x,Math.nextUp(point.y)))
                    centers.add(InkPoint(point.x,Math.nextDown(point.y)))
                }
                for(center in centers)for(radius in listOf(0f,Float.MIN_VALUE,9f,1e13f,Float.POSITIVE_INFINITY)){
                    val expected=referenceStrokeHit(obj,center,radius)
                    val message="fixture=$fixture angle=$angle center=$center radius=$radius"
                    assertEquals(message,expected,InkGeometry.hit(obj,center,radius))
                    assertEquals(message,expected,index.hit(obj,center,radius))
                    assertEquals(message,expected,index.hitAlong(obj,listOf(center),radius))
                }
            }
    }

    @Test fun midpointComponentRejectionKeepsPreciseCutsTangenciesAndRotatedFloatBehavior(){
        val raw=JSONArray("""[{"x":-16777216,"y":0,"vendor":{"keep":[null,9]}},
            {"x":0,"y":0},{"x":100,"y":0},{"x":100,"y":100}]""")
        for(angle in listOf(0.0,.31,Math.PI/2)){
            val obj=stroke(raw).put("rotation",angle)
            for(center in listOf(InkPoint(50f,10f),InkPoint(50f,Math.nextUp(10f)),
                InkPoint(50f,Math.nextDown(10f)),InkPoint(1f,0f),InkPoint(100f,100f)))
                for(radius in listOf(0f,Float.MIN_VALUE,9f))assertReference(obj,center,radius)
        }
    }

    @Test fun runRejectionRetainsFloatCancellationHitsOutsideIdealSegmentBounds(){
        val raw=JSONArray()
        repeat(33){raw.put(json("x" to if(it%2==0)-16777216f else 0f,"y" to 0))}
        repeat(95){raw.put(json("x" to 100,"y" to 100))}
        val obj=stroke(raw).put("width",0)
        val index=PageSpatialIndex();index.put(obj)
        val p=InkPoint(1f,0f)
        // (1 - -16777216) rounds to 16777216 before projection subtraction.
        assertTrue(InkGeometry.hit(obj,p,0f))
        assertTrue(index.hit(obj,p,0f))
    }

    @Test fun overlappingLongStrokeMissMatchesRawGeometryAfterIndexInstallation(){
        val raw=JSONArray()
        repeat(8193){i->raw.put(json("x" to minOf(i,4096),"y" to maxOf(0,i-4096)))}
        val obj=stroke(raw);val index=PageSpatialIndex();index.put(obj)
        assertFalse(index.hit(obj,InkPoint(1024f,1024f),9f))
        assertFalse(InkGeometry.hit(obj,InkPoint(1024f,1024f),9f,index.boundsFor("source")))
    }

    private fun sequentialErase(obj:JSONObject,centers:List<InkPoint>,radius:Float):List<JSONObject> {
        var survivors:List<JSONObject> = listOf(obj)
        for(center in centers)survivors=survivors.flatMap{source->
            if(!referenceStrokeHit(source,center,radius))listOf(source) else {
                val parts=InkGeometry.eraseParts(source,center,radius)
                if(parts.size==1&&parts[0].optString("id")==source.optString("id")&&
                    parts[0].f("rotation")==source.f("rotation")&&InkGeometry.sameStrokeCoordinates(source,parts[0]))
                    listOf(source) else parts
            }
        }
        return survivors
    }

    @Test fun batchedEraseMatchesSequentialHitSplitNoOpMetadataAndGeneratedIdLineage(){
        val raw=JSONArray("""[
            {"x":0,"y":0,"p":0,"t":100,"ty":null,"vendor":{"raw":[null,9,1]}},
            null,42,
            {"x":500,"y":0,"p":0.8,"t":200,"azimuth":1.2,"tx":4,"vendor":{"axis":10}},
            {"x":500,"y":0,"ty":3,"pointerId":8},
            {"x":500.0001,"y":0.0001,"p":null},
            {"x":500,"y":400,"t":-20,"orientation":0.8,"azimuth":null}
        ]""")
        raw.getJSONObject(0).put("p",.23f).put("orientation",.13f)
        for(angle in listOf(0.0,.31,Math.PI/2,-Math.PI/3)){
            val obj=stroke(raw).put("rotation",angle).put("sourceStrokeId","ancestor")
            val page=InkGeometry.pagePoints(obj);val a=page[0];val b=page[1]
            val centers=(0..30).map{i->val t=i/30f
                InkPoint(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t)}
            for(radius in listOf(0f,9f,20f)){
                val before=obj.toString()
                val expected=sequentialErase(obj,centers+centers.reversed(),radius)
                val actual=InkGeometry.eraseAlong(obj,centers+centers.reversed(),radius)
                assertEquals(before,obj.toString())
                assertEquals(expected.size,actual.size)
                for(i in expected.indices){
                    val expectedId=expected[i].getString("id");val actualId=actual[i].getString("id")
                    if(expectedId==obj.getString("id"))assertEquals(expectedId,actualId)
                    else {assertTrue(actualId.startsWith("stroke_"));expected[i].put("id",actualId)}
                    assertJson(expected[i],actual[i],"batch[$i]")
                }
            }
        }
        val obj=stroke(raw)
        assertSame(obj,InkGeometry.eraseAlong(obj,emptyList(),9f).single())
        assertSame(obj,InkGeometry.eraseAlong(obj,listOf(InkPoint(900f,900f)),9f).single())
        obj.put("locked",true)
        assertSame(obj,InkGeometry.eraseAlong(obj,listOf(InkPoint(250f,0f)),9f).single())
        obj.remove("locked");obj.put("hidden",true)
        assertSame(obj,InkGeometry.eraseAlong(obj,listOf(InkPoint(250f,0f)),9f).single())
    }

    @Test fun batchBorrowsImmutableExtensionsWithoutSerializingThemOrMutatingSourcePoints(){
        val vendor=object:JSONObject(){override fun toString():String=error("Batch serialized immutable extensions")}
            .put("axis",9)
        val raw=JSONArray()
        repeat(64){raw.put(json("x" to it*10,"y" to 0,"p" to .23f,"vendor" to vendor))}
        val obj=object:JSONObject(){override fun toString():String=error("Batch serialized whole source")}
            .put("id","borrowed").put("type","stroke").put("width",2).put("revision",7)
            .put("points",raw).put("settings",vendor)
        val parts=InkGeometry.eraseAlong(obj,listOf(InkPoint(250f,0f),InkPoint(260f,0f),InkPoint(270f,0f)),9f)
        assertEquals(2,parts.size)
        for(part in parts){
            assertSame(vendor,part.getJSONObject("settings"))
            for(i in 0 until part.getJSONArray("points").length()){
                val point=part.getJSONArray("points").getJSONObject(i)
                assertSame(vendor,point.getJSONObject("vendor"))
            }
        }
        assertEquals(64,raw.length())
        assertEquals(250.0,raw.getJSONObject(25).getDouble("x"),0.0)
        assertEquals(7L,obj.getLong("revision"))
        assertEquals(9,vendor.getInt("axis"))
    }

    @Test fun batchedHitsMatchSequentialHitAndMissGeometry(){
        val raw=JSONArray()
        repeat(64){i->raw.put(json("x" to i*10,"y" to if(i<32)0 else 400))}
        val obj=stroke(raw);val index=PageSpatialIndex();index.put(obj)
        val miss=listOf(InkPoint(50f,200f),InkPoint(100f,200f),InkPoint(150f,200f))
        val hit=miss+InkPoint(150f,0f)
        for(centers in listOf(miss,hit)){
            val expected=centers.any{InkGeometry.hit(obj,it,9f)}
            assertEquals(expected,index.hitAlong(obj,centers,9f))
        }
        for(angle in listOf(.31,Math.PI/2)){
            val rotated=stroke(raw).put("rotation",angle);index.put(rotated)
            assertEquals(hit.any{InkGeometry.hit(rotated,it,9f)},index.hitAlong(rotated,hit,9f))
        }
    }

    @Test fun descriptorBatchMatchesSequentialContentMetadataAndFragmentOrder(){
        val raw=JSONArray()
        repeat(64){i->raw.put(json("x" to i*10,"y" to 0,"p" to .23f,"t" to i*5,
            "vendor" to JSONObject("""{"keep":[null,9,1]}""")))}
        val centers=(0..40).map{InkPoint(100f+it*8,0f)}
        val obj=stroke(raw)
        val expected=sequentialErase(obj,centers,9f)
        val actual=InkGeometry.eraseAlong(obj,centers,9f)
        assertEquals(expected.size,actual.size)
        for(i in expected.indices){
            if(expected[i].getString("id")!=obj.getString("id"))expected[i].put("id",actual[i].getString("id"))
            assertJson(expected[i],actual[i],"descriptor[$i]")
        }
    }

    private fun assertSequentialFragments(expected:List<JSONObject>,actual:List<JSONObject>,originalId:String){
        assertEquals(expected.size,actual.size)
        for(i in expected.indices){
            val expectedId=expected[i].getString("id");val actualId=actual[i].getString("id")
            if(expectedId==originalId)assertEquals(expectedId,actualId)
            else {assertTrue(actualId.startsWith("stroke_"));expected[i].put("id",actualId)}
            assertJson(expected[i],actual[i],"swept[$i]")
        }
    }

    @Test fun sweptIndexedBatchesKeepCircleOrderGapsRotationsAndUnchangedOutsideRanges(){
        val raw=JSONArray()
        repeat(64){i->
            if(i%7==0)raw.put(JSONObject.NULL).put(42)
            raw.put(json("x" to i*15,"y" to ((i%9)-4)*18,"p" to .23f,"t" to i*7,
                "vendor" to JSONObject("""{"raw":[null,9,1],"null":null}""")))
        }
        repeat(97){raw.put(JSONObject.NULL)}
        val routes=listOf(
            (0..28).map{InkPoint(200f+it*12,-40f+it%5)},
            listOf(InkPoint(0f,400f),InkPoint(900f,400f),InkPoint(900f,-400f),InkPoint(0f,-400f)),
            listOf(InkPoint(1f,0f),InkPoint(300f,Math.nextDown(10f)),InkPoint(300f,Math.nextUp(10f)),
                InkPoint(945f,-72f),InkPoint(400f,0f),InkPoint(100f,0f)))
        for(angle in listOf(0.0,.31,Math.PI/2))for(route in routes)for(radius in listOf(0f,9f)){
            val obj=stroke(raw).put("rotation",angle);val before=obj.toString()
            val index=PageSpatialIndex();index.rebuild(listOf(obj))
            val centers=route+route.reversed();val event=InkEraseEvent(centers,radius)
            val expectedHit=centers.any{referenceStrokeHit(obj,it,radius)}
            assertEquals(expectedHit,index.hitAlong(obj,event))
            assertEquals(expectedHit,index.hitAlong(obj,centers,radius))
            val expected=sequentialErase(obj,centers,radius)
            assertSequentialFragments(expected,index.eraseAlong(obj,event),obj.getString("id"))
            assertSequentialFragments(expected,index.eraseAlong(obj,centers,radius),obj.getString("id"))
            assertSequentialFragments(expected,InkGeometry.eraseAlong(obj,centers,radius),obj.getString("id"))
            assertEquals("Indexed batch mutated imported JSON",before,obj.toString())
        }
    }

    @Test fun eventSnapshotAndPublicListCallsRespectLaterSameSizeListMutation(){
        val raw=JSONArray().apply{repeat(64){put(json("x" to it*10,"y" to 0))}}
        val obj=stroke(raw);val index=PageSpatialIndex();index.put(obj)
        val centers=mutableListOf(InkPoint(200f,100f),InkPoint(300f,100f))
        val snapshot=InkEraseEvent(centers,9f)
        centers[0]=InkPoint(200f,0f);centers[1]=InkPoint(300f,0f)
        assertFalse(index.hitAlong(obj,snapshot))
        assertSame(obj,index.eraseAlong(obj,snapshot).single())
        assertTrue(index.hitAlong(obj,centers,9f))
        val expected=sequentialErase(obj,centers,9f)
        assertSequentialFragments(expected,index.eraseAlong(obj,centers,9f),obj.getString("id"))
    }

    @Test fun sweptExtremeFloatCandidatesRetainOldScalarFallbackAndPreciseResults(){
        val fixtures=listOf(
            JSONArray().apply{repeat(64){put(json("x" to if(it%2==0)-16777216f else 0f,"y" to 0))}},
            JSONArray().apply{repeat(64){put(json("x" to Float.MIN_VALUE*it,"y" to Float.MIN_VALUE*(it%3)))}},
            JSONArray().apply{repeat(64){put(json("x" to if(it%2==0)Float.MAX_VALUE else -Float.MAX_VALUE,"y" to 0))}})
        for(raw in fixtures)for(angle in listOf(0.0,.31,Math.PI/2)){
            val obj=stroke(raw).put("rotation",angle);val index=PageSpatialIndex();index.put(obj)
            val page=InkGeometry.pagePoints(obj)
            val centers=listOf(InkPoint(1f,0f),page[0],page[31],InkPoint(50f,Math.nextUp(10f)),page.last())
            for(radius in listOf(0f,Float.MIN_VALUE,9f)){
                val event=InkEraseEvent(centers,radius)
                assertEquals(centers.any{referenceStrokeHit(obj,it,radius)},index.hitAlong(obj,event))
                val expected=sequentialErase(obj,centers,radius)
                assertSequentialFragments(expected,index.eraseAlong(obj,event),obj.getString("id"))
            }
        }
    }

    @Test fun repeatedEraseBorrowsCanonicalSurvivorsWithoutChangingSourceOrCutMetadata(){
        val raw=JSONArray().apply{repeat(101){put(json("x" to it*10f,"y" to 0f,"p" to .65f,
            "t" to it.toLong(),"tilt" to -0f,"orientation" to -0f,"azimuth" to -0f,
            "vendor" to json("nullable" to JSONObject.NULL,"sample" to it)))}}
        val obj=stroke(raw);val source=obj.toString()
        val first=InkGeometry.eraseAlong(obj,listOf(InkPoint(500f,0f)),9f).first()
        val untouched=first.toString();val origin=first.array("points").getJSONObject(0)
        val route=listOf(InkPoint(250f,0f),InkPoint(260f,0f))
        val next=InkGeometry.eraseAlong(first,route,9f)
        assertSequentialFragments(sequentialErase(first,route,9f),next,first.getString("id"))
        assertSame(origin,next.first().array("points").getJSONObject(0))
        assertEquals(untouched,first.toString());assertEquals(source,obj.toString())
    }

    @Test fun derivedGeometryStaysWithinRetainedMemoryBudgetAndClearsWithPage(){
        val raw=JSONArray();val a=json("x" to 0,"y" to 0);val b=json("x" to 100,"y" to 0)
        repeat(131073){raw.put(if(it%2==0)a else b)}
        val objects=(0..8).map{stroke(raw,"long_$it")}
        val index=PageSpatialIndex();index.rebuild(objects)
        val cached=PageSpatialIndex::class.java.getDeclaredField("strokeRuns").apply{isAccessible=true}
        fun assertRetainedBudget(){
            val runs=(cached.get(index) as Map<*,*>).values.filterIsInstance<StrokeHitRuns>()
            val bytes=runs.sumOf{(it.starts.size.toLong()+it.ends.size+it.boxes.size+(it.coordinates?.size?:0))*4+(it.tree?.bytes?:0)}
            assertTrue("Retained derived arrays exceed page budget: $bytes",bytes<=768L*1024+2L*1024*1024+4L*1024*1024)
            assertTrue("Retained derived records exceed page budget",runs.size<=4096)
        }
        assertRetainedBudget()
        assertTrue(index.hit(objects.last(),InkPoint(50f,0f),0f))
        index.remove("long_0")
        assertFalse(index.query(InkBounds(0f,-1f,100f,1f)).any{it.optString("id")=="long_0"})
        index.put(objects.last());assertRetainedBudget()
        val short=JSONArray();repeat(128){short.put(if(it%2==0)a else b)}
        val many=(0..4096).map{stroke(short,"short_$it")}
        index.rebuild(many);assertRetainedBudget()
        assertTrue(index.hitAlong(many.last(),listOf(InkPoint(50f,0f)),0f))
        val fallback=many.last()
        val untouched=fallback.toString()
        assertSame(fallback,index.eraseAlong(fallback,listOf(InkPoint(50f,20f)),9f).single())
        val route=listOf(InkPoint(50f,0f),InkPoint(60f,0f))
        assertSequentialFragments(sequentialErase(fallback,route,9f),index.eraseAlong(fallback,route,9f),fallback.getString("id"))
        assertEquals(untouched,fallback.toString())
        index.rebuild(emptyList())
        assertTrue(index.query(InkBounds(0f,-1f,100f,1f)).isEmpty())
        assertTrue((cached.get(index) as Map<*,*>).isEmpty())
    }
}
