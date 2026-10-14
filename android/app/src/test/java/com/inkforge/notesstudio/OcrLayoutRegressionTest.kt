package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic bounds from LayoutProbe.kt (2026-10-14); these are not S Pen accuracy samples. */
class OcrLayoutRegressionTest {
    private fun stroke(id: String, l: Float, t: Float, r: Float, b: Float) = json("id" to id,
        "type" to "stroke", "points" to JSONArray(listOf(json("x" to l,"y" to t),json("x" to r,"y" to b))))
    private fun gak(dy: Float = 0f, suffix: String = "") = listOf(
        stroke("initial_giyeok$suffix",100f,100f+dy,112f,111f+dy),
        stroke("vowel_vertical$suffix",118f,100f+dy,118f,122f+dy),
        stroke("vowel_horizontal$suffix",118f,111f+dy,129f,111f+dy),
        stroke("final_giyeok$suffix",100f,126f+dy,124f,136f+dy))
    private fun decimal(dotTop: Float) = listOf(
        stroke("digit_1",100f,100f,100f,130f),
        stroke("decimal_point",113f,dotTop,114f,dotTop+1),
        stroke("digit_2",122f,100f,142f,130f))
    private fun analyze(strokes: List<JSONObject>,version: Int = 2) = PageOcrLayout.analyze(strokes,700f,1000f,version)

    @Test fun baselineReproducesGakSplitAndLowDecimalUnresolved() {
        assertEquals(2,analyze(gak(),1).regions.size)
        assertEquals(listOf("decimal_point"),analyze(decimal(134f),1).unresolved.map { it.id })
        assertTrue(analyze(decimal(129f),1).unresolved.isEmpty())
    }
    @Test fun gakComponentsStayInOneRecognitionRegionWithoutChangingOriginal() {
        val input=gak(); val original=input.map { it.toString() }
        val result=analyze(input)
        assertEquals(input.map { it.getString("id") },result.regions.single().strokes.map { it.id })
        assertTrue(result.unresolved.isEmpty())
        assertEquals(original,input.map { it.toString() })
    }
    @Test fun lowAndNormalDecimalAreActuallyForwardedAndIsolatedDotIsPreserved() {
        for (top in listOf(129f,134f)) {
            val result=analyze(decimal(top))
            assertEquals(setOf("digit_1","decimal_point","digit_2"),result.regions.single().strokes.map { it.id }.toSet())
            assertTrue(result.unresolved.isEmpty())
        }
        val result=analyze(decimal(134f)+stroke("distant_dot",210f,175f,211f,176f))
        assertEquals(listOf("distant_dot"),result.unresolved.map { it.id })
    }
    @Test fun sameHorizontalSyllablePositionsOnTwoRowsNeverTransitivelyMerge() {
        val input=gak()+gak(65f,"2")
        val result=analyze(input)
        assertEquals(2,result.regions.size)
        assertEquals(4,result.regions[0].strokes.size);assertEquals(4,result.regions[1].strokes.size)
        result.assertConserved(input.map { it.getString("id") }.toSet())
    }
    @Test fun closeRowsAndVeryDifferentFontSizesDoNotGrowByTransitiveConnections() {
        val close=listOf(stroke("a",100f,100f,115f,120f),stroke("b",100f,125f,115f,145f))
        assertEquals(2,analyze(close).regions.size)
        val sizes=listOf(stroke("title",90f,20f,130f,60f),stroke("body",100f,85f,112f,100f))
        assertEquals(2,analyze(sizes).regions.size)
    }
    @Test fun zeroDimensionStrokesAndLateFinalStrokeRetainCaptureOrderAndUnknownTime() {
        val input=gak().mapIndexed { i,s -> s.put("captureSeq",i.toLong()).put("captureSessionId","s")
            .put("timeBasis","session-monotonic").apply {
                getJSONArray("points").getJSONObject(0).put("t",100+i*2)
                getJSONArray("points").getJSONObject(1).put("t",101+i*2)
            } }
        input.last().put("captureSeq",999).put("captureSessionId","later-session")
        val region=analyze(input).regions.single()
        assertEquals("final_giyeok",region.strokes.last().id)
        assertEquals("none",region.timeMode)
        assertEquals(1,analyze(listOf(stroke("horizontal",1f,2f,16f,2f))).regions.size)
        assertEquals(1,analyze(listOf(stroke("vertical",1f,2f,1f,17f))).regions.size)
    }
    @Test fun inputAndEngineIdentityAreSeparateAndPipelineVersionsInvalidatePage() {
        val input=gak()
        val page=NotePage("p",json("width" to 700,"height" to 1000),input.toMutableList())
        val a=OcrPipeline(1);val b=OcrPipeline(2)
        assertNotEquals(a.fingerprint,b.fingerprint)
        assertNotEquals(OcrInput.pageDigest(page,"ko-primary",a),OcrInput.pageDigest(page,"ko-primary",b))
        assertNotEquals(b.fingerprint,b.copy(layoutConfigVersion=2).fingerprint)
        assertNotEquals(b.fingerprint,b.copy(routerVersion=b.routerVersion+1).fingerprint)
        val region=analyze(input).regions.single()
        assertEquals(OcrInput.sourceIdentityDigest(region),OcrInput.sourceIdentityDigest(region.copy(id="other",order=12)))
        val moved=input.map { s -> s.copyJson().apply {
            array("points").objects().forEach { it.put("x",it.getDouble("x")+1) }
        } }
        assertNotEquals(OcrInput.sourceIdentityDigest(region),OcrInput.sourceIdentityDigest(analyze(moved).regions.single()))
    }
}
