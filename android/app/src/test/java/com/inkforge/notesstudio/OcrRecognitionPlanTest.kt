package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OcrRecognitionPlanTest {
    private fun stroke(id: String, x: Float, session: String, sequence: Int, start: Long, end: Long) =
        json("id" to id, "type" to "stroke", "color" to "#111111", "captureSeq" to sequence,
            "captureSessionId" to session, "timeBasis" to "session-monotonic",
            "extension" to json("kept" to true), "points" to JSONArray(listOf(
                json("x" to x, "y" to 100, "t" to start), json("x" to x + 10, "y" to 120, "t" to end))))

    private fun source(firstStart: Long = 100, firstEnd: Long = 110) = listOf(
        stroke("first", 100f, "session-a", 0, firstStart, firstEnd),
        stroke("second", 180f, "session-b", 1, 200, 210))

    private fun region(objects: List<JSONObject>) = PageOcrLayout.analyze(objects, 4000f, 1000f, 2).regions.single()
    private fun plan(objects: List<JSONObject>) = OcrRecognitionPlan.create(region(objects), "mixed-review")

    @Test fun cacheIdentityHashesEffectiveWordTimingEvenWhenParentHasNoSharedSession() {
        val first = plan(source())
        val changed = plan(source(firstEnd = 119))
        assertEquals("none", first.source.timeMode)
        assertEquals(2, first.regions.size)
        assertTrue(first.regions.all { it.timeMode == "relative" })
        val space = OcrInkGeometry.space(first.source)
        assertEquals(OcrInput.regionInputDigest(first.source, "ko", "", space.width, space.height, "none"),
            OcrInput.regionInputDigest(changed.source, "ko", "", space.width, space.height, "none"))
        assertNotEquals(first.inputDigest(), changed.inputDigest())
        // Absolute origins are not SDK inputs; preserving every relative interval permits reuse.
        assertEquals(first.inputDigest(), plan(source(firstStart = 300, firstEnd = 310)).inputDigest())
    }

    @Test fun sourceOnlyEditReusesCandidatesButRebuildsEveryWordBindingFromCurrentPlan() {
        val input = source()
        val before = plan(input)
        val live = OcrTextComposition.compose(before.regions.mapIndexed { index, word ->
            RecognitionService.WordResult(word, listOf(
                RecognitionService.ModelResult("ko", listOf("단어$index", "대안$index")),
                RecognitionService.ModelResult("en-US", listOf("word$index"))))
        }, before.policy)
        val cache = OcrModelCache.encode(listOf(live))
        val edited = input.map { it.copyJson() }
        edited.first().put("color", "#ff0000").getJSONObject("extension").put("other", 7)
        val current = plan(edited)
        assertEquals(before.inputDigest(), current.inputDigest())
        val cached = OcrModelCache.decode(cache, current).single()
        assertEquals(live.candidates, cached.candidates)
        assertEquals(live.languageTag, cached.languageTag)
        assertEquals(live.candidateKind, cached.candidateKind)
        assertEquals(live.words.size, cached.words.size)
        for (index in cached.words.indices) {
            val rebound = cached.words[index]
            assertSame(current.regions[index], rebound.region)
            assertEquals(live.words[index].models.map { it.languageTag to it.candidates },
                rebound.models.map { it.languageTag to it.candidates })
            assertEquals(OcrInput.sourceIdentityDigest(current.regions[index]),
                OcrInput.sourceIdentityDigest(rebound.region))
        }
        assertNotEquals(OcrInput.sourceIdentityDigest(before.regions.first()),
            OcrInput.sourceIdentityDigest(cached.words.first().region))
        assertEquals("#111111", input.first().getString("color"))
    }

    @Test fun wordLimitReasonSurvivesNativeFallbackCandidateCacheAndPolicyPresentation() {
        val objects = (0 until 65).map { index -> stroke("word$index", 100f + index * 30f,
            "session", index, 100L + index * 20, 110L + index * 20) }
        val original = objects.map { it.toString() }
        val plan = plan(objects)
        assertEquals(1, plan.regions.size)
        assertTrue(plan.reviewReasons.contains("wordBoundaryLimitReached"))
        val native = RecognitionService.ModelResult("ko", listOf("native whole-line result"),
            reviewReasons = plan.regions.single().reviewReasons)
        val cached = OcrModelCache.decode(OcrModelCache.encode(listOf(native)), plan)
        assertTrue(cached.single().reviewReasons.contains("wordBoundaryLimitReached"))
        assertTrue(OcrCandidatePolicy.reviewReasons(cached, plan.policy).contains("wordBoundaryLimitReached"))
        assertEquals(objects.size, plan.regions.single().strokes.size)
        assertEquals(original, objects.map { it.toString() })
    }
}
