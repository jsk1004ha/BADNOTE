package com.inkforge.notesstudio

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativeCoreTest{
    private fun sampled(anchors:List<InkPoint>,step:Float=1f):List<InkPoint> = buildList {
        anchors.zipWithNext().forEach{(a,b)->val count=kotlin.math.ceil(InkGeometry.distance(a,b)/step).toInt().coerceAtLeast(1)
            repeat(count){i->val t=i.toFloat()/count;add(InkPoint(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t))}}
        add(anchors.last())
    }
    private fun scratch(flat:Boolean=false)=sampled(listOf(InkPoint(435f,if(flat)500f else 489f),InkPoint(565f,if(flat)500f else 511f),
        InkPoint(437f,if(flat)500f else 491f),InkPoint(563f,if(flat)500f else 509f),InkPoint(439f,if(flat)500f else 493f),InkPoint(561f,if(flat)500f else 507f)),.55f)
    private fun ink(id:String,y:Float=500f,brush:String="fountain")=json("id" to id,"type" to "stroke","brush" to brush,"width" to 3,
        "points" to JSONArray(listOf(InkPoint(450f,y).json(),InkPoint(550f,y).json())))
    @Test fun denseAndFlatScratchesEraseAtEveryDirectionAndZoom(){
        for(flat in listOf(false,true))for(scale in listOf(.5f,1f,3.8f))for(angle in listOf(0.0,Math.PI/4,Math.PI/2)){
            fun rotate(p:InkPoint)=p.copy(x=(500+(p.x-500)*kotlin.math.cos(angle)-(p.y-500)*kotlin.math.sin(angle)).toFloat(),
                y=(500+(p.x-500)*kotlin.math.sin(angle)+(p.y-500)*kotlin.math.cos(angle)).toFloat())
            val path=scratch(flat).map(::rotate)
            val target=ink("target").put("points",JSONArray(InkGeometry.points(ink("target")).map{rotate(it).json()}))
            assertEquals("flat=$flat scale=$scale angle=$angle",listOf("target"),InkGeometry.scratchTargets(listOf(target),path,scale).map{it.getString("id")})
        }
    }
    @Test fun scratchOnlyErasesInkCrossedOnThreePasses(){
        val objects=listOf(ink("target"),ink("nearby",540f),ink("highlight",brush="highlighter"),ink("locked").put("locked",true),
            ink("hidden").put("hidden",true),json("id" to "text","type" to "text","x" to 460,"y" to 480,"w" to 100,"h" to 30))
        assertEquals(listOf("target"),InkGeometry.scratchTargets(objects,scratch(),1f).map{it.getString("id")})
        val singlePass=sampled(listOf(InkPoint(435f,470f),InkPoint(565f,475f),InkPoint(435f,480f),InkPoint(565f,485f),InkPoint(435f,490f),InkPoint(565f,515f)))
        assertTrue(InkGeometry.scratchTargets(listOf(ink("target")),singlePass,1f).isEmpty())
        val sparse=sampled(listOf(InkPoint(435f,470f),InkPoint(565f,530f),InkPoint(435f,470f),InkPoint(565f,530f),InkPoint(435f,470f)),70f)
        assertEquals(listOf("target"),InkGeometry.scratchTargets(listOf(ink("target")),sparse,1f).map{it.getString("id")})
    }
    @Test fun ordinaryLettersLoopsAndCursiveNeverErase(){
        val paths=listOf(
            sampled(listOf(InkPoint(460f,475f),InkPoint(540f,475f),InkPoint(540f,500f),InkPoint(460f,500f),InkPoint(460f,525f),InkPoint(540f,525f))),
            sampled(listOf(InkPoint(440f,475f),InkPoint(470f,525f),InkPoint(500f,485f),InkPoint(530f,525f),InkPoint(560f,475f))),
            (0..96).map{InkPoint((500+62*kotlin.math.cos(it*Math.PI/16)).toFloat(),(500+22*kotlin.math.sin(it*Math.PI/16)).toFloat())},
            (0..64).map{InkPoint(435f+it*2,500f+kotlin.math.sin(it/2f)*19)})
        for(path in paths)assertTrue(InkGeometry.scratchTargets(listOf(ink("original")),path,1f).isEmpty())
    }
    @Test fun importedPageLinksAreRemappedWithoutRewritingTextOrObjectIds(){
        val data=JSONObject("""{"lastPageId":"p2","outline":[{"id":"p1","pageId":"p1","text":"p1"}],"audio":[{"pageId":"p2"}],"objects":[{"targetPageId":"p2"}]}""")
        rewritePageReferences(data,mapOf("p1" to "new1","p2" to "new2"))
        assertEquals("new2",data.getString("lastPageId"))
        assertEquals("new1",data.array("outline").getJSONObject(0).getString("pageId"))
        assertEquals("p1",data.array("outline").getJSONObject(0).getString("id"))
        assertEquals("p1",data.array("outline").getJSONObject(0).getString("text"))
        assertEquals("new2",data.array("audio").getJSONObject(0).getString("pageId"))
        assertEquals("new2",data.array("objects").getJSONObject(0).getString("targetPageId"))
    }
    @Test fun sparseStrokeSplitsAtExactEraserBoundary(){
        val stroke=json("id" to "s","type" to "stroke","width" to 2,"points" to JSONArray(listOf(InkPoint(0f,0f).json(),InkPoint(100f,0f).json())))
        assertTrue(InkGeometry.hit(stroke,InkPoint(50f,0f),10f))
        val pieces=InkGeometry.eraseParts(stroke,InkPoint(50f,0f),9f)
        assertEquals(2,pieces.size)
        assertEquals(40f,InkGeometry.points(pieces[0]).last().x,.01f)
        assertEquals(60f,InkGeometry.points(pieces[1]).first().x,.01f)
        assertFalse(InkGeometry.hit(stroke.copyJson().put("locked",true),InkPoint(50f,0f),10f))
    }
    @Test fun objectUndoPreservesUnrelatedObjectsAndOrder(){
        val a=json("id" to "a","text" to "A");val b=json("id" to "b","text" to "B");val c=json("id" to "c","text" to "C")
        val page=NotePage("p",JSONObject(),mutableListOf(a,b,c))
        val edit=ObjectChange(listOf(b),listOf(b.copyJson().put("text","changed")),mapOf("b" to 1),mapOf("b" to 1))
        edit.apply(page);assertEquals("changed",page.objects[1].getString("text"))
        edit.reversed().apply(page);assertEquals(listOf("a","b","c"),page.objects.map{it.getString("id")})
        assertEquals("B",page.objects[1].getString("text"))
    }
    @Test fun mathHandlesPrecedenceVariablesAndInvalidInput(){
        assertEquals(-4.0,MathEngine().calculate("-2^2").value,0.0)
        assertEquals(.25,MathEngine().calculate("2^-2").value,0.0)
        assertEquals(512.0,MathEngine().calculate("2^3^2").value,0.0)
        assertEquals(1.5,MathEngine().calculate("\\frac{3}{2}").value,0.0)
        assertEquals(1.0,MathEngine(degrees=true).calculate("sin(90)").value,.0001)
        assertEquals(5.0,MathEngine(mapOf("x" to 3.0)).calculate("x+2").value,0.0)
        assertEquals("x",MathEngine().calculate("x=2+3").variable)
        for(value in listOf("1/0","sqrt(-1)","2+","foo(2)","2;exit"))assertThrows(Exception::class.java){MathEngine().calculate(value)}
    }
    @Test fun archiveRejectsTraversalDuplicateEntriesAndExpansionLimit(){
        val directory=Files.createTempDirectory("badnote-archive-").toFile()
        try{
            fun zip(name:String,payload:ByteArray)=ByteArrayOutputStream().also{stream->ZipOutputStream(stream).use{it.putNextEntry(ZipEntry(name));it.write(payload);it.closeEntry()}}.toByteArray()
            assertThrows(IllegalArgumentException::class.java){ArchiveCodec.extract(ByteArrayInputStream(zip("../escape",byteArrayOf(1))),File(directory,"bad"))}
            assertThrows(IllegalArgumentException::class.java){ArchiveCodec.extract(ByteArrayInputStream(zip("assets/x",ByteArray(1000))),File(directory,"huge"),limit=100)}
            assertThrows(InterruptedIOException::class.java){ArchiveCodec.write(ByteArrayOutputStream(),sequenceOf(ArchiveCodec.Entry("manifest.json"){ByteArrayInputStream(byteArrayOf(1))}),{true})}
        }finally{directory.deleteRecursively()}
    }
    @Test fun legacyReaderStreamsPagesAndExtractsAssetWithoutWholeDocument(){
        val directory=Files.createTempDirectory("badnote-legacy-").toFile()
        try{
            val bytes=ByteArray(256*1024){(it%251).toByte()};val base64=Base64.getEncoder().encodeToString(bytes)
            val pages=mutableListOf<JSONObject>();var index=0
            val text="""{"title":"한글 노트","pages":[{"id":"p1","backgroundImage":"data:image/png;base64,$base64","objects":[]},{"id":"p2","objects":[]}],"audio":[{"src":"data:audio/mp4;base64,$base64"}]}"""
            val meta=LegacyJsonReader(StringReader(text),{_->val id="asset-${index++}";"asset:$id" to File(directory,id).outputStream()},{pages+=it}).read()
            assertEquals(2,pages.size);assertFalse(meta.has("pages"));assertEquals("한글 노트",meta.getString("title"))
            assertTrue(pages[0].getString("backgroundImage").startsWith("asset:"));assertArrayEquals(bytes,File(directory,"asset-0").readBytes());assertArrayEquals(bytes,File(directory,"asset-1").readBytes())
        }finally{directory.deleteRecursively()}
    }
    @Test fun latinLettersAreNotScratchErase(){
        val w=listOf(InkPoint(0f,0f),InkPoint(20f,40f),InkPoint(40f,5f),InkPoint(60f,40f),InkPoint(80f,0f))
        assertFalse(InkGeometry.deliberateScratch(w,1f))
        assertFalse(InkGeometry.deliberateScratch((0..80).map{InkPoint(it.toFloat(),kotlin.math.sin(it/5f)*20)},1f))
    }
}
