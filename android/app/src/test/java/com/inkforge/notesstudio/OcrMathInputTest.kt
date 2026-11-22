package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.sqrt

/** Payload contracts; these assertions do not claim model recognition accuracy. */
class OcrMathInputTest {
    private fun source(id: String, count: Int, scale: Float = 1f, ordinal: Int = 0,
                       zigzag: Boolean = false): JSONObject =
        json("id" to id, "type" to "stroke", "width" to 2 * scale,
            "captureSeq" to ordinal, "captureSessionId" to "source-session",
            "timeBasis" to "session-monotonic", "extension" to json("keep" to true),
            "points" to JSONArray((0 until count).map { index ->
                val fraction = if (count == 1) 0f else index.toFloat() / (count - 1)
                val y = if (zigzag) (index % 2) * 40f
                    else if (fraction <= .5f) fraction * 80f else (1 - fraction) * 80f
                json("x" to (20 + ordinal * 120 + fraction * 100) * scale,
                    "y" to (30 + y) * scale, "t" to ordinal * 100000L + index + 1,
                    "p" to .7, "tilt" to .2, "extension" to "untouched")
            }))

    private fun region(objects: List<JSONObject>, scale: Float = 1f): PageOcrLayout.Region {
        val result = PageOcrLayout.analyze(objects, 100000 * scale, 100000 * scale)
        assertTrue(result.invalidInput.isEmpty())
        return PageOcrLayout.knownRegion("math", "displayMath",
            result.regions.flatMap { it.strokes } + result.unresolved, 0)
    }

    private fun assertBudget(input: OcrInkGeometry.ModelInput) {
        assertTrue(input.strokes.size <= OcrInput.SOFT_REGION_STROKES)
        assertTrue(input.pointCount <= OcrInput.SOFT_REGION_POINTS)
        assertTrue(input.byteUpperBound <= OcrInput.SOFT_REGION_BYTES)
        assertEquals(input.pointCount, input.strokes.sumOf { it.pointCount })
        input.strokes.forEach { stroke ->
            val indices = stroke.indices ?: IntArray(stroke.pointCount) { it }
            assertEquals(0, indices.first())
            assertEquals(stroke.source.pointCount - 1, indices.last())
            assertTrue((1 until indices.size).all { indices[it - 1] < indices[it] })
            val points = stroke.source.source.getJSONArray("points")
            indices.forEach { index ->
                val point = points.getJSONObject(index)
                assertTrue(input.space.x(point.getDouble("x").toFloat()).isFinite())
                assertTrue(input.space.y(point.getDouble("y").toFloat()).isFinite())
                assertTrue(point.getLong("t") > 0)
            }
        }
    }

    @Test fun normalizedDenseSingleAndMultipleStrokesStayWithinActualSelectedPayloadBudgetAtBothScales() {
        for (count in listOf(1, 16)) {
            val pointsPerStroke = if (count == 1) 9001 else 2049
            val smallObjects = (0 until count).map { source("s$it", pointsPerStroke, ordinal = it) }
            val largeObjects = (0 until count).map { source("s$it", pointsPerStroke, 4f, it) }
            val before = (smallObjects + largeObjects).map { it.toString() }
            val small = OcrInkGeometry.mathInput(region(smallObjects))
            val large = OcrInkGeometry.mathInput(region(largeObjects, 4f))
            assertBudget(small)
            assertBudget(large)
            assertTrue(small.resampled)
            assertEquals(small.pointCount, large.pointCount)
            assertEquals(small.space.width, large.space.width, .001f)
            assertEquals(small.space.height, large.space.height, .001f)
            small.strokes.zip(large.strokes).forEach { (a, b) ->
                assertArrayEquals(a.indices, b.indices)
                assertSame(smallObjects.first { it.getString("id") == a.source.id }, a.source.source)
                a.indices!!.forEach { index ->
                    val ap = a.source.source.getJSONArray("points").getJSONObject(index)
                    val bp = b.source.source.getJSONArray("points").getJSONObject(index)
                    assertEquals(small.space.x(ap.getDouble("x").toFloat()),
                        large.space.x(bp.getDouble("x").toFloat()), .001f)
                    assertEquals(small.space.y(ap.getDouble("y").toFloat()),
                        large.space.y(bp.getDouble("y").toFloat()), .001f)
                    assertEquals(ap.getLong("t"), bp.getLong("t"))
                }
            }
            assertEquals(before, (smallObjects + largeObjects).map { it.toString() })
        }
    }

    @Test fun pointAndConservativeByteBoundariesCannotLeakAnOversizedPayload() {
        // 256 bytes context + 32 bytes stroke + 32 bytes/point reaches exactly 256 KiB.
        for (count in listOf(8182, 8183, 8184, 8192, 8193, 32769)) {
            val original = source("boundary", count)
            val before = original.toString()
            val input = OcrInkGeometry.mathInput(region(listOf(original)))
            assertBudget(input)
            assertEquals(count > 8183, input.resampled)
            if (count == 8183) assertEquals(OcrInput.SOFT_REGION_BYTES.toLong(), input.byteUpperBound)
            assertEquals(before, original.toString())
        }
    }

    @Test fun sourceOnlyExtensionBytesAreNotTreatedAsModelCoordinates() {
        val original = source("metadata", 2).put("largeExtension", "x".repeat(OcrInput.SOFT_REGION_BYTES))
        val before = original.toString()
        val input = OcrInkGeometry.mathInput(region(listOf(original)))
        assertBudget(input)
        assertFalse(input.resampled)
        assertNull(input.strokes.single().indices)
        assertEquals(before, original.toString())
    }

    @Test fun everyDiscardedPointRemainsWithinNormalizedGeometricError() {
        val input = OcrInkGeometry.mathInput(region(listOf(source("corner", 18001))))
        val stroke = input.strokes.single()
        val indices = stroke.indices!!
        val points = stroke.source.source.getJSONArray("points")
        var segment = 0
        for (index in 0 until points.length()) {
            while (segment + 1 < indices.lastIndex && index > indices[segment + 1]) segment++
            val a = points.getJSONObject(indices[segment])
            val b = points.getJSONObject(indices[segment + 1])
            val p = points.getJSONObject(index)
            val ax = input.space.x(a.getDouble("x").toFloat()).toDouble()
            val ay = input.space.y(a.getDouble("y").toFloat()).toDouble()
            val dx = input.space.x(b.getDouble("x").toFloat()) - ax
            val dy = input.space.y(b.getDouble("y").toFloat()) - ay
            val px = input.space.x(p.getDouble("x").toFloat()) - ax
            val py = input.space.y(p.getDouble("y").toFloat()) - ay
            val length = dx * dx + dy * dy
            val fraction = if (length == 0.0) 0.0 else ((px * dx + py * dy) / length).coerceIn(0.0, 1.0)
            assertTrue(sqrt((px - fraction * dx) * (px - fraction * dx) +
                (py - fraction * dy) * (py - fraction * dy)) <= .351)
        }
    }

    @Test fun irreducibleDetailIsRejectedRatherThanTruncated() {
        val original = source("zigzag", 9001, zigzag = true)
        val before = original.toString()
        try {
            OcrInkGeometry.mathInput(region(listOf(original)))
            fail("Expected honest unsupported-detail state")
        } catch (error: OcrInkGeometry.InputLimitException) {
            assertEquals("mathInputDetailExceeded", error.reason)
        }
        assertEquals(before, original.toString())
    }

    @Test fun cancellationDuringDenseSimplificationPropagatesWithoutChangingSource() {
        val original = source("cancel", 18001)
        val value = region(listOf(original))
        val before = original.toString()
        val cancellation = CancellationException("superseded")
        var checks = 0
        try {
            OcrInkGeometry.mathInput(value) { if (++checks == 8) throw cancellation }
            fail("Expected cancellation during point traversal")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertEquals(before, original.toString())
    }
}
