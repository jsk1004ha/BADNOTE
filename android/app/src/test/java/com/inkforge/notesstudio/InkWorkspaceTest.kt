package com.inkforge.notesstudio

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class InkWorkspaceTest {
    @Test fun smallAndSparseShapesUseDisplaySize() {
        for (scale in listOf(.35f, .8f, 2.7f)) {
            fun point(x: Float, y: Float) = InkPoint(300 + x / scale, 500 + y / scale)
            val line = listOf(point(0f, 0f), point(25f, 11f))
            assertEquals("line", InkGeometry.recognizeShape(line, scale)?.name)
            val rectangle = listOf(point(0f, 0f), point(25f, 1f), point(26f, 20f),
                point(1f, 21f), point(0f, 3f))
            assertEquals("rectangle", InkGeometry.recognizeShape(rectangle, scale)?.name)
            val oval = (0..12).map {
                val angle = it * 2 * PI / 13
                point(18 * cos(angle).toFloat(), 12 * sin(angle).toFloat())
            }
            assertEquals("ellipse", InkGeometry.recognizeShape(oval, scale)?.name)
            assertNull(InkGeometry.recognizeShape(oval.take(8), scale))
            assertNull(InkGeometry.recognizeShape(listOf(point(0f, 0f), point(4f, 1f)), scale))
        }
    }

    @Test fun heldRoundShapesAllowAModestGapAndOvershoot() {
        for (scale in listOf(.35f, .8f, 2.7f)) for (direction in listOf(-1, 1)) {
            fun roundStroke(sweep: Double, yRadius: Float = 22f) = (0..32).map { i ->
                val angle = direction * (.48 + i * sweep / 32)
                val wobble = 1 + .035f * sin(4 * angle).toFloat()
                InkPoint(300 + 23 * cos(angle).toFloat() * wobble / scale,
                    500 + yRadius * sin(angle).toFloat() * wobble / scale)
            }
            val incomplete = roundStroke(2 * PI - .96)
            assertNull(InkGeometry.recognizeShape(incomplete, scale))
            val snapped = requireNotNull(InkGeometry.recognizeShape(incomplete, scale, held = true))
            assertEquals("circle", snapped.name)
            assertEquals(snapped.x2 - snapped.x1, snapped.y2 - snapped.y1, .001f)
            assertEquals("circle", InkGeometry.recognizeShape(roundStroke(2 * PI + .66), scale, held = true)?.name)
            assertEquals("ellipse", InkGeometry.recognizeShape(roundStroke(2 * PI, 14f), scale, held = true)?.name)
            assertNull(InkGeometry.recognizeShape(roundStroke(2.5 * PI), scale, held = true))
        }
    }

    @Test fun heldOpenCurvesPreserveTheirBendWithoutSnappingZigzags() {
        for (scale in listOf(.35f, .8f, 2.7f)) {
            fun point(x: Float, y: Float) = InkPoint(200 + x / scale, 350 + y / scale)
            val arc = (0..24).map { i ->
                val angle = i * PI / 24
                point(-34 * cos(angle).toFloat(), 24 * sin(angle).toFloat())
            }
            val curve = requireNotNull(InkGeometry.recognizeShape(arc, scale, held = true))
            assertEquals("curve", curve.name)
            assertEquals(arc.first().x, curve.x1, .001f)
            assertEquals(arc.last().x, curve.x2, .001f)
            val control = requireNotNull(curve.control)
            assertTrue((control.y - curve.y1) * scale > 30)
            assertTrue(abs(control.x - (curve.x1 + curve.x2) / 2) * scale < 2f)

            val sidewaysSemicircle = (0..32).map { i ->
                val angle = i * PI / 32
                point(110 * sin(angle).toFloat(), -110 * cos(angle).toFloat())
            }
            assertEquals("curve", InkGeometry.recognizeShape(sidewaysSemicircle, scale, held = true)?.name)

            val chevron = listOf(point(-34f, 0f), point(-17f, 12f), point(0f, 24f), point(34f, 0f))
            assertNull(InkGeometry.recognizeShape(chevron, scale, held = true))
            val wave = (0..24).map { i -> point(i * 3f, 12 * sin(i * PI / 12).toFloat()) }
            assertNull(InkGeometry.recognizeShape(wave, scale, held = true))
        }
    }

    @Test fun shortSparseScratchRequiresRepeatedContactWithUnlockedInk() {
        val stroke = json("id" to "target", "type" to "stroke", "width" to 3, "points" to JSONArray(
            listOf(InkPoint(100f, 70f).json(), InkPoint(100f, 110f).json())))
        val locked = stroke.copyJson().put("id", "locked").put("locked", true)
        val nearby = InkGeometry.transform(stroke, 30f, 0f).put("id", "nearby")
        val gestures = listOf(
            listOf(InkPoint(82f, 80f), InkPoint(118f, 91f), InkPoint(84f, 92f), InkPoint(114f, 103f)),
            listOf(InkPoint(82f, 80f), InkPoint(118f, 91f), InkPoint(84f, 92f), InkPoint(96f, 103f))
        )
        val candidates = listOf(stroke, locked, nearby)
        assertEquals(listOf("target"), InkGeometry.scratchTargets(candidates, gestures[0], .65f).map { it.getString("id") })
        assertTrue(InkGeometry.scratchTargets(candidates, gestures[1], .65f).isEmpty())
    }

    @Test fun graphiteAppendsOnlyNewGrainsDuringWriting() {
        val style = graphiteStyle()
        val points = graphitePoints(360, 2.0)
        val incremental = Grains()
        val replay = Grains()
        for (end in 6..points.size step 6) {
            val prefix = points.subList(0, end)
            incremental.path.update(style, prefix, 1)
            replay.path.update(style, prefix)
            assertArrayEquals("Graphite differs at sample $end", replay.geometry, incremental.geometry)
        }
        println("Graphite grain emissions across 60 frames: ${incremental.emitted} incremental, ${replay.emitted} full replay")
        assertTrue(incremental.emitted * 20 < replay.emitted)
    }

    @Test fun graphiteRebuildsAtGrainLimitsAndWhenGeometryIsReplaced() {
        val style = graphiteStyle()
        val points = graphitePoints(1750, 3.0)
        val incremental = Grains()
        val replay = Grains()
        for (end in listOf(1, 380, 500, 910, 1100, 1750)) {
            val prefix = points.subList(0, end)
            incremental.path.update(style, prefix, 1)
            replay.path.update(style, prefix)
            assertArrayEquals("Graphite differs at sample $end", replay.geometry, incremental.geometry)
        }
        val emitted = incremental.emitted
        incremental.path.update(style, points, 1)
        assertEquals(emitted, incremental.emitted)
        val replacement = points.map { it.copy(y = it.y + 4) }
        incremental.path.update(style, replacement, 2)
        replay.path.update(style, replacement)
        assertArrayEquals(replay.geometry, incremental.geometry)
    }

    private fun graphiteStyle() = GraphiteBrush.style(json("id" to "graphite-check",
        "width" to 4, "graphiteVersion" to GraphiteBrush.CURRENT_VERSION))

    private fun graphitePoints(count: Int, step: Double) = List(count) { i ->
        LegacyBrush.Point(i * step, sin(i / 16.0) * 20, .5 + .3 * sin(i / 27.0), null, 0.0, 32.0)
    }

    private class Grains {
        val geometry = LongArray(4)
        var emitted = 0
            private set
        val path = GraphiteBrush.PathBuilder({ geometry.fill(0) }) { band, x, y, radius ->
            geometry[band] = ((geometry[band] * 31 + x.toBits()) * 31 + y.toBits()) * 31 + radius.toBits()
            emitted++
        }
    }

    @Test fun xournalRoundTripKeepsPageOrderCoordinatesColorAndPressureWidths() {
        val input = """
            <xournal version="0.4.8"><title>Two pages</title>
            <page width="600" height="800"><background type="solid" style="graph" color="#f4f2eaff"/><layer>
            <stroke tool="pen" color="#182233a4" width="3 1 2">60 80 120 60 300 400</stroke>
            </layer></page>
            <page width="500" height="500"><background type="solid" style="plain" color="white"/><layer>
            <stroke tool="highlighter" color="#ffff0052" capStyle="butt" width="8">25 50 375 50</stroke>
            </layer></page></xournal>
        """.trimIndent()
        val pages = parse(input.byteInputStream())
        assertEquals(listOf(1333.3334f, 1000f), pages.map { it.height })
        assertEquals("grid", pages.first().meta.getString("template"))
        assertEquals("#f4f2ea", pages.first().meta.getString("paperColor"))
        val stroke = pages.first().objects.single()
        assertEquals("xournal", stroke.getString("widthProfile"))
        assertEquals(listOf(100f, 200f, 500f), InkGeometry.points(stroke).map { it.x })
        assertEquals(1.6667, LegacyBrush.widths(stroke)[0], .001)
        assertEquals(3.3333, LegacyBrush.widths(stroke)[1], .001)

        val output = ByteArrayOutputStream()
        XournalInk.write(output, "두 쪽 & 검증", pages.asSequence(), { error("editable strokes were flattened") })
        val reopened = parse(output.toByteArray().inputStream())
        assertEquals(listOf("#182233", "#ffff00"), reopened.map { it.objects.single().getString("color") })
        assertEquals(164 / 255.0, reopened.first().objects.single().getDouble("opacity"), .0001)
        assertEquals(82 / 255.0, reopened.last().objects.single().getDouble("opacity"), .0001)
        assertEquals("highlighter", reopened.last().objects.single().getString("brush"))
        assertEquals("butt", reopened.last().objects.single().getString("strokeCap"))
        assertEquals(300.0, reopened.first().objects.single().array("points").getJSONObject(2).getDouble("x") * .6, .001)
        assertEquals(3.3333, LegacyBrush.widths(reopened.first().objects.single())[1], .001)
    }

    @Test fun xournalRejectsExternalBackgroundsAndDocumentTypeDeclarations() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""<xournal><page width="600" height="800"><background type="pdf"/></page></xournal>""".byteInputStream())
        }
        assertThrows(org.xml.sax.SAXException::class.java) {
            parse("<!DOCTYPE xournal><xournal/>".byteInputStream())
        }
    }

    private fun parse(input: java.io.InputStream) = buildList {
        XournalInk.read(input, { error("unexpected embedded image") }, { add(it) })
    }
}
