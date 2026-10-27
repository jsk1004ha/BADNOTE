package com.inkforge.notesstudio

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class OcrWordLayoutTest {
    private fun stroke(id: String, left: Float, top: Float, right: Float, bottom: Float,
                       index: Int, captureSeq: Long? = null, fragmentOrder: Int = 0): PageOcrLayout.Stroke {
        val time = 100L + (captureSeq ?: index.toLong()) * 10 + fragmentOrder * 2
        val first = json("x" to left, "y" to top, "p" to 0.0, "t" to time,
            "vendorPoint" to json("tilt" to 17, "sample" to "original"))
        val points = JSONArray().put(first)
        if (left != right || top != bottom) points.put(json("x" to right, "y" to bottom,
            "p" to 0.75, "t" to time + 1, "vendorPoint" to json("tilt" to 23)))
        val session = if (captureSeq == null) null else "capture-session"
        val basis = if (captureSeq == null) "unknown" else "session-monotonic"
        val source = json("id" to id, "type" to "stroke", "points" to points,
            "sourceStrokeId" to "source:$id", "fragmentOrder" to fragmentOrder,
            "vendorStroke" to json("brushState" to JSONArray(listOf(1, 2, 3))),
            "timeBasis" to basis)
        if (captureSeq != null) source.put("captureSeq", captureSeq).put("captureSessionId", session)
        return PageOcrLayout.Stroke(id, source, InkBounds(left, top, right, bottom), points.length(),
            hypot(right - left, bottom - top), index, captureSeq, session, basis, "source:$id", fragmentOrder)
    }

    private fun line(strokes: List<PageOcrLayout.Stroke>, id: String = "line:source") =
        PageOcrLayout.knownRegion(id, "body", strokes, 7)

    private fun groups(words: List<PageOcrLayout.Region>) = words.map { word ->
        word.strokes.map { it.id }.toSet()
    }

    private fun splitConserved(region: PageOcrLayout.Region): List<PageOcrLayout.Region> {
        val originals = region.strokes.associateBy { it.id }
        val before = originals.mapValues { it.value.source.toString() }
        val words = OcrWordLayout.split(region)
        val assigned = words.flatMap { it.strokes }
        assertEquals(region.strokes.size, assigned.size)
        assertEquals(originals.keys, assigned.map { it.id }.toSet())
        assertEquals(assigned.size, assigned.map { it.id }.toSet().size)
        assigned.forEach { member ->
            assertSame(originals.getValue(member.id), member)
            assertSame(originals.getValue(member.id).source, member.source)
            assertEquals(before.getValue(member.id), member.source.toString())
        }
        assertEquals(before, originals.mapValues { it.value.source.toString() })
        return words
    }

    @Test fun closeDisjointKoreanJamoAndSyllablesDoNotBecomeWords() {
        val region = line(listOf(
            stroke("initial", 0f, 0f, 24f, 45f, 0),
            stroke("vowel", 54f, 0f, 54f, 75f, 1),
            stroke("crossbar", 54f, 35f, 66f, 35f, 2),
            stroke("final", 0f, 85f, 24f, 100f, 3),
            stroke("next-syllable", 90f, 0f, 115f, 100f, 4)))
        // The empty 30-unit jamo interval is not a word gap in a 100-unit line.
        assertSame(region, splitConserved(region).single())
    }

    @Test fun detachedLatinDotsStayWithTheirStemsAcrossOrdinaryLetterGaps() {
        val strokes = (0 until 3).flatMap { i ->
            val x = i * 32f
            listOf(stroke("stem$i", x, 25f, x + 8f, 100f, i * 3),
                stroke("dot$i", x + 3f, 0f, x + 5f, 2f, i * 3 + 1),
                stroke("dot-fragment$i", x + 4f, 4f, x + 4f, 4f, i * 3 + 2))
        }
        val region = line(strokes + stroke("next-word", 140f, 0f, 160f, 100f, 9))
        val words = splitConserved(region)
        assertEquals(listOf(strokes.map { it.id }.toSet(), setOf("next-word")), groups(words))
        assertEquals(9, words.first().strokes.size)
    }

    @Test fun genuineEmptyWordGapsProduceLeftToRightRegions() {
        val region = line(listOf(
            stroke("third", 150f, 0f, 165f, 100f, 0),
            stroke("first-right", 40f, 0f, 55f, 100f, 1),
            stroke("second", 95f, 0f, 110f, 100f, 2),
            stroke("first-left", 0f, 0f, 15f, 100f, 3)))
        val words = splitConserved(region)
        assertEquals(listOf(setOf("first-left", "first-right"), setOf("second"), setOf("third")), groups(words))
        assertEquals(listOf(0f, 95f, 150f), words.map { it.bounds.left })
        assertTrue(words.all { it.role == region.role && it.order == region.order })
    }

    @Test fun anOverlappingStrokePreventsAnInventedGapBetweenNestedStrokes() {
        val region = line(listOf(
            stroke("bridge", 0f, 0f, 140f, 100f, 0),
            stroke("inside-left", 10f, 0f, 20f, 100f, 1),
            stroke("inside-right", 100f, 0f, 110f, 100f, 2)))
        assertSame(region, splitConserved(region).single())
    }

    private fun observedMixedLine(scale: Float = 1f, dx: Float = 0f, dy: Float = 0f): PageOcrLayout.Region {
        // Rectangles reflect the observed mixed-line failure, not model-output fixtures.
        val boxes = listOf(
            InkBounds(105f, 144f, 145f, 188f),
            InkBounds(175f, 144f, 185f, 210f),
            InkBounds(105f, 215f, 145f, 241f),
            InkBounds(205f, 145f, 240f, 241f),
            InkBounds(265f, 144f, 285f, 230f),
            InkBounds(327f, 140f, 343f, 220f),
            InkBounds(361f, 140f, 379f, 220f),
            InkBounds(395f, 140f, 411f, 220f),
            InkBounds(427f, 140f, 447f, 220f),
            InkBounds(465f, 140f, 499f, 220f))
        return line(boxes.mapIndexed { i, box ->
            stroke("observed$i", box.left * scale + dx, box.top * scale + dy,
                box.right * scale + dx, box.bottom * scale + dy, i)
        }, "line:observed")
    }

    @Test fun observedFortyTwoUnitWordGapSplitsButThirtyUnitJamoGapDoesNot() {
        val words = splitConserved(observedMixedLine())
        assertEquals(2, words.size)
        assertEquals((0..4).map { "observed$it" }.toSet(), groups(words)[0])
        assertEquals((5..9).map { "observed$it" }.toSet(), groups(words)[1])
        assertEquals(InkBounds(105f, 144f, 285f, 241f), words[0].bounds)
        assertEquals(InkBounds(327f, 140f, 499f, 220f), words[1].bounds)
    }

    @Test fun translationAndScaleDoNotChangePartitionsOrWordIdentity() {
        val baseline = splitConserved(observedMixedLine())
        for ((scale, dx, dy) in listOf(Triple(.001f, -5f, 8f), Triple(.125f, 1200f, -300f),
            Triple(1f, -700f, 300f), Triple(64f, 123f, -800f))) {
            val words = splitConserved(observedMixedLine(scale, dx, dy))
            assertEquals(groups(baseline), groups(words))
            assertEquals(baseline.map { it.id }, words.map { it.id })
        }
    }

    @Test fun geometricSortingRestoresCaptureAndFragmentOrderWithoutChangingSourceJson() {
        val region = line(listOf(
            stroke("right-late", 170f, 0f, 180f, 100f, 0, captureSeq = 50),
            stroke("left-late", 25f, 0f, 30f, 100f, 1, captureSeq = 20, fragmentOrder = 1),
            stroke("right-early", 150f, 0f, 160f, 100f, 2, captureSeq = 30),
            stroke("left-early", 0f, 0f, 10f, 100f, 3, captureSeq = 20, fragmentOrder = 0)),
            "line:captured").copy(role = "left", reviewReasons = listOf("lineDiagnostic"))
        val words = splitConserved(region)
        assertEquals(listOf(listOf("left-early", "left-late"), listOf("right-early", "right-late")),
            words.map { word -> word.strokes.map { it.id } })
        assertEquals(listOf(0, 1), words[0].strokes.map { it.fragmentOrder })
        assertEquals(listOf(20L, 20L), words[0].strokes.map { it.captureSeq })
        assertEquals(listOf(300L, 400L), words.map { it.timeOrigin })
        assertTrue(words.all { it.timeMode == "relative" && it.role == "left" && it.order == 7 })
        assertTrue(words.all { it.reviewReasons == listOf("lineDiagnostic") })
        assertEquals(listOf("line:captured/word:left-late", "line:captured/word:right-late"), words.map { it.id })
        val reshuffled = region.copy(strokes = region.strokes.reversed())
        assertEquals(words.map { it.id }, splitConserved(reshuffled).map { it.id })
    }

    @Test fun singleSourceAndDegenerateInkSurviveWithoutMadeUpWordBoundaries() {
        val singleSources = listOf(stroke("point", 5f, 5f, 5f, 5f, 0),
            stroke("horizontal", 0f, 0f, 1000f, 0f, 0),
            stroke("vertical", 0f, 0f, 0f, 100f, 0))
        singleSources.forEach { source ->
            val region = line(listOf(source))
            assertSame(region, splitConserved(region).single())
        }
        val pointsOnly = line(listOf(stroke("p1", 0f, 5f, 0f, 5f, 0),
            stroke("p2", 100f, 5f, 100f, 5f, 1)))
        assertSame(pointsOnly, splitConserved(pointsOnly).single())
        val horizontalOnly = line(listOf(stroke("h1", 0f, 0f, 10f, 0f, 0),
            stroke("h2", 100f, 0f, 110f, 0f, 1)))
        assertSame(horizontalOnly, splitConserved(horizontalOnly).single())
        val verticalWords = line(listOf(stroke("v1", 0f, 0f, 0f, 100f, 0),
            stroke("v2", 80f, 0f, 80f, 100f, 1), stroke("dot", 2f, 0f, 2f, 0f, 2)))
        assertEquals(listOf(setOf("v1", "dot"), setOf("v2")), groups(splitConserved(verticalWords)))
    }

    @Test fun exactlySixtyFourWordsAreAllowedAndOverflowKeepsTheWholeRegionForReview() {
        val members = (0 until 65).map { i ->
            stroke("word$i", i * 60f, 0f, i * 60f + 10f, 100f, i)
        }
        assertEquals(64, splitConserved(line(members.take(64))).size)
        val region = line(members).copy(reviewReasons = listOf("lineDiagnostic"))
        val intact = splitConserved(region).single()
        assertSame(region.strokes, intact.strokes)
        assertEquals(region.id, intact.id)
        assertEquals(region.bounds, intact.bounds)
        assertEquals(region.timeMode, intact.timeMode)
        assertEquals(region.timeOrigin, intact.timeOrigin)
        assertEquals(listOf("lineDiagnostic", "wordBoundaryLimitReached"), intact.reviewReasons)
        assertSame(intact, splitConserved(intact).single())
    }
}
