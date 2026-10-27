package com.inkforge.notesstudio

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class OcrRecognitionPolicyTest {
    private fun region(scale: Float = 1f) = PageOcrLayout.analyze(listOf(
        json("id" to "a", "type" to "stroke", "captureSeq" to 2,
            "captureSessionId" to "s", "timeBasis" to "session-monotonic",
            "extension" to json("preserve" to true),
            "points" to JSONArray(listOf(json("x" to 20 * scale, "y" to 30 * scale, "t" to 100),
                json("x" to 30 * scale, "y" to 50 * scale, "t" to 101)))),
        json("id" to "b", "type" to "stroke", "captureSeq" to 1,
            "captureSessionId" to "s", "timeBasis" to "session-monotonic",
            "points" to JSONArray(listOf(json("x" to 40 * scale, "y" to 30 * scale, "t" to 90),
                json("x" to 50 * scale, "y" to 50 * scale, "t" to 91))))
    ), 700 * scale, 1000 * scale, 2).regions.single()

    @Test fun normalizedLineUsesSameUnitsForPointsAndWritingAreaAtEveryScale() {
        val a = region(); val b = region(4f)
        val aa = OcrInkGeometry.space(a); val bb = OcrInkGeometry.space(b)
        assertEquals(aa.width, bb.width, .001f)
        assertEquals(aa.height, bb.height, .001f)
        assertEquals(aa.x(20f), bb.x(80f), .001f)
        assertEquals(aa.y(50f), bb.y(200f), .001f)
        assertTrue(aa.x(a.bounds.left) > 0)
        assertTrue(aa.y(a.bounds.bottom) < aa.height)
    }

    @Test fun normalizationDoesNotReorderHorizontalOrCaptureSourcesOrMutateExtensions() {
        val value = region(); val source = value.strokes.map { it.source.toString() }
        OcrInkGeometry.space(value)
        assertEquals(listOf("b", "a"), value.strokes.map { it.id })
        assertEquals("relative", value.timeMode)
        assertEquals(source, value.strokes.map { it.source.toString() })
        assertTrue(value.strokes.last().source.getJSONObject("extension").getBoolean("preserve"))
    }

    @Test fun mixedReviewUsesOnlyCompleteNativeTopHypothesesNeverStitchesWords() {
        val models = listOf(RecognitionService.ModelResult("ko", listOf("안녕", "hello 안녕")),
            RecognitionService.ModelResult("en-US", listOf("hello 안녕", "hello")))
        assertEquals("en-US", OcrCandidatePolicy.preferred(models, "mixed-review").languageTag)
        assertEquals("ko", OcrCandidatePolicy.preferred(models, "ko-primary").languageTag)
        assertEquals("안녕", OcrCandidatePolicy.preferred(listOf(models.first()), "mixed-review").candidates.first())
    }

    @Test fun rankIsNeverTreatedAsCrossLanguageConfidence() {
        val models = listOf(RecognitionService.ModelResult("ko", listOf("안녕 hello", "안녕")),
            RecognitionService.ModelResult("en-US", listOf("hello 안녕")))
        assertEquals(models.first(), OcrCandidatePolicy.preferred(models, "mixed-review"))
        assertEquals(setOf("languageDisagreement", "mixedScriptReview"),
            OcrCandidatePolicy.reviewReasons(models, "mixed-review").toSet())
    }

    @Test fun decomposedJamoMixedCandidateIsKeptWholeWithoutUnicodeRewriting() {
        val native = "hello \u1100\u1161"
        val models = listOf(RecognitionService.ModelResult("ko", listOf("가")),
            RecognitionService.ModelResult("en-US", listOf(native)))
        assertEquals(native, OcrCandidatePolicy.preferred(models, "mixed-review").candidates.first())
        assertEquals("가", models.first().candidates.first())
    }
}
