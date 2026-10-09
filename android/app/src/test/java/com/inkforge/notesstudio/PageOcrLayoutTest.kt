package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PageOcrLayoutTest {
    private fun stroke(id: String, x: Float, y: Float, w: Float = 15f, h: Float = 12f,
                       time: Long = 0L): JSONObject = json("id" to id, "type" to "stroke",
        "points" to JSONArray(listOf(json("x" to x, "y" to y, "p" to 0.0, "t" to time),
            json("x" to x + w, "y" to y + h, "p" to 0.5, "t" to (time + 1)))))

    @Test fun columnsTitleDotsAndLongVerticalKeepEveryStrokeOnce() {
        val title = stroke("title", 100f, 15f, 470f, 12f)
        val left = (0..3).map { stroke("l$it", 70f, 90f + it * 45) }
        val right = (0..3).map { stroke("r$it", 680f, 90f + it * 45) }
        val dot = stroke("dot", 82f, 89f, 1f, 1f)
        val giant = stroke("vertical", 495f, 65f, 0f, 300f)
        val highlighter = stroke("highlight", 50f, 350f).put("brush", "highlighter")
        val objects = listOf(title) + left + right + listOf(dot, giant, highlighter)
        val layout = PageOcrLayout.analyze(objects, 1000f, 500f)
        val assigned = layout.regions.flatMap { it.strokes.map(PageOcrLayout.Stroke::id) } +
            layout.unresolved.map { it.id } + layout.nonTextPreserved.map { it.id }
        assertEquals(objects.size, assigned.size)
        assertEquals(objects.map { it.getString("id") }.toSet(), assigned.toSet())
        assertTrue(layout.unresolved.any { it.id == "vertical" })
        assertTrue(layout.nonTextPreserved.any { it.id == "highlight" })
        assertTrue(layout.regions.any { it.role == "left" })
        assertTrue(layout.regions.any { it.role == "right" })
        assertTrue(assigned.contains("dot"))
    }

    @Test fun continuousFullWidthRowsDoNotBecomeTwoColumns() {
        val rows = (0 until 20).flatMap { row ->
            (0 until 100).map { column -> stroke("row${row}stroke$column", 8f + column * 9.8f,
                80f + row * 22f, 7f, 10f) }
        }
        val layout = PageOcrLayout.analyze(rows, 1000f, 600f)
        assertFalse(layout.regions.any { it.role == "left" || it.role == "right" })
        val assigned = layout.regions.flatMap { it.strokes.map(PageOcrLayout.Stroke::id) } +
            layout.unresolved.map { it.id } + layout.nonTextPreserved.map { it.id }
        assertEquals(rows.size, assigned.size)
        assertEquals(rows.map { it.getString("id") }.toSet(), assigned.toSet())
    }

    @Test fun invalidPointsAreReportedWithoutMakingReplacementCoordinates() {
        val bad = stroke("bad", 0f, 0f).put("points", JSONArray(listOf(json("x" to 1))))
        val good = stroke("good", 10f, 20f)
        val layout = PageOcrLayout.analyze(listOf(bad, good), 1000f, 1414f)
        assertEquals("bad", layout.invalidInput.single().id)
        assertEquals(listOf("good"), (layout.regions.flatMap { it.strokes } + layout.unresolved).map { it.id })
    }

    @Test fun contentDigestDistinguishesSamePointCountCoordinatesOrderTimeAndPressure() {
        val a = stroke("a", 10f, 20f)
        val b = stroke("b", 20f, 20f)
        fun page(objects: List<JSONObject>) = NotePage("p", json("width" to 1000, "height" to 1414), objects.toMutableList())
        val base = OcrInput.pageDigest(page(listOf(a, b)), "ko-primary")
        val moved = a.copyJson().put("points", JSONArray(listOf(
            json("x" to 11, "y" to 20), json("x" to 26, "y" to 32))))
        assertNotEquals(base, OcrInput.pageDigest(page(listOf(moved, b)), "ko-primary"))
        assertNotEquals(base, OcrInput.pageDigest(page(listOf(b, a)), "ko-primary"))
        val timed = a.copyJson()
        timed.getJSONArray("points").getJSONObject(0).put("t", 12)
        assertNotEquals(base, OcrInput.pageDigest(page(listOf(timed, b)), "ko-primary"))
        val pressured = a.copyJson()
        pressured.getJSONArray("points").getJSONObject(0).put("p", 0.1)
        assertNotEquals(base, OcrInput.pageDigest(page(listOf(pressured, b)), "ko-primary"))
    }

    @Test fun onlyOneSharedSessionUsesRelativeTimeForWholeRegion() {
        val a = stroke("a", 10f, 20f, time = 100).put("captureSeq", 1)
            .put("captureSessionId", "s").put("timeBasis", "session-monotonic")
        val b = stroke("b", 30f, 20f, time = 110).put("captureSeq", 2)
            .put("captureSessionId", "s").put("timeBasis", "session-monotonic")
        assertEquals("relative", PageOcrLayout.analyze(listOf(a, b), 1000f, 1414f).regions.single().timeMode)
        b.put("captureSessionId", "other")
        assertEquals("none", PageOcrLayout.analyze(listOf(a, b), 1000f, 1414f).regions.single().timeMode)
    }

    @Test fun softLimitSplitsOnlyAtClearWordGapAndKeepsEveryStroke() {
        val left = (0 until 300).map { stroke("left$it", 40f + it * .05f, 80f) }
        val right = (0 until 300).map { stroke("right$it", 550f + it * .05f, 80f) }
        val layout = PageOcrLayout.analyze(left + right, 1000f, 1414f)
        val members = layout.regions.flatMap { it.strokes }
        val region = PageOcrLayout.knownRegion("combined", "body", members, 0)
        val parts = requireNotNull(PageOcrLayout.splitForBudget(region))
        assertEquals(2, parts.size)
        assertEquals((left + right).map { it.getString("id") }.toSet(),
            parts.flatMap { it.strokes.map(PageOcrLayout.Stroke::id) }.toSet())
        assertTrue(parts.all { it.strokes.size <= OcrInput.SOFT_REGION_STROKES })

        val dense = (0 until 600).map { stroke("dense$it", 40f + it * .05f, 80f) }
        val denseMembers = PageOcrLayout.analyze(dense, 1000f, 1414f).regions.flatMap { it.strokes }
        assertNull(PageOcrLayout.splitForBudget(PageOcrLayout.knownRegion("dense", "body", denseMembers, 0)))
    }
}
