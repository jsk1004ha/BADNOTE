package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Original web/app.js execution fixture. Double tolerance: abs 1e-9 + relative 1e-10. */
class LegacyBrushReferenceTest {
    private val reference by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/brush-reference.json"))
        JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }
    private fun assertNear(expected: Double, actual: Double, at: String = "") = assertEquals(at, expected, actual,
        1e-9 + kotlin.math.abs(expected) * 1e-10)
    private fun fixtureStroke(fixture: JSONObject) = fixture.getJSONObject("input").copyJson().apply {
        fixture.optJSONObject("settings")?.let { put("settings", it.copyJson()) }
    }
    private fun assertJson(expected: Any?, actual: Any?, at: String) {
        when (expected) {
            is Number -> { assertTrue("$at: number", actual is Number); assertNear(expected.toDouble(), (actual as Number).toDouble(), at) }
            is JSONObject -> { assertTrue("$at: object", actual is JSONObject)
                val found = actual as JSONObject
                assertEquals("$at: keys", expected.keys().asSequence().toSet(), found.keys().asSequence().toSet())
                expected.keys().forEach { key -> assertJson(expected.get(key), found.get(key), "$at.$key") }
            }
            is JSONArray -> { assertTrue("$at: array", actual is JSONArray)
                val found = actual as JSONArray
                assertEquals("$at: size", expected.length(), found.length())
                for (index in 0 until expected.length()) assertJson(expected.get(index), found.get(index), "$at[$index]")
            }
            else -> assertEquals(at, expected, actual)
        }
    }

    @Test fun originalArithmeticAndUtf16Seed() {
        val random = reference.getJSONArray("randomFixtures")
        for (index in 0 until random.length()) {
            val fixture = random.getJSONObject(index)
            val next = LegacyBrush.Random(fixture.getString("seed"))
            val values = fixture.getJSONArray("values")
            for (n in 0 until values.length()) assertEquals("seed $index value $n",
                values.getDouble(n), next.next(), 0.0)
        }
        val widths = reference.getJSONArray("widthFixtures")
        for (index in 0 until widths.length()) {
            val fixture = widths.getJSONObject(index)
            val css = fixture.getDouble("cssWidth"); val width = fixture.getDouble("width")
            assertNear(fixture.getDouble("screenWidth"), LegacyBrush.screenToolWidthToPage(css, width))
            assertNear(fixture.getDouble("toolWidth"), LegacyBrush.toolStrokeWidthToPage(css, width))
        }
        val fixtures = reference.getJSONArray("fixtures")
        for (index in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(index); val stroke = fixtureStroke(fixture)
            assertJson(fixture.getJSONArray("smoothed"), JSONArray(LegacyBrush.smoothed(stroke)), "${fixture.getString("name")}.smooth")
            val rendered = if (stroke.optString("brush") == "pencil") LegacyBrush.pencilRenderPoints(stroke)
                else LegacyBrush.smoothed(stroke)
            assertJson(fixture.getJSONArray("renderPoints"), JSONArray(rendered), "${fixture.getString("name")}.renderPoints")
            val expected = fixture.getJSONArray("widths"); val actual = LegacyBrush.widths(stroke)
            assertEquals(fixture.getString("name"), expected.length(), actual.size)
            actual.forEachIndexed { n, value -> assertNear(expected.getDouble(n), value) }
        }
    }

    @Test fun publishedSamplesStayImmutableAcrossLongStrokeGrowthAndReset() {
        val publication = InkPointPublication()
        val samples = ArrayList<InkPoint>()
        val retained = ArrayList<Pair<Int, List<InkPoint>>>()
        val boundaries = setOf(0, 1, 2, 3, 63, 64, 65, 2047, 2048, 2049, 65535, 65536, 65537)
        retained += 0 to publication.snapshot()
        repeat(65539) { index ->
            val point = InkPoint(index * .125f, -index * .0625f, (index % 100) * .01f,
                index.toLong(), azimuth = index * .001f, tx = (index % 61 - 30).toFloat(),
                ty = (30 - index % 61).toFloat(), original = if (index % 1024 == 0)
                    json("p" to .23, "vendor" to json("sequence" to index)) else null)
            samples += point
            publication.append(point)
            if (samples.size in boundaries) retained += samples.size to publication.snapshot()
        }
        retained += samples.size to publication.snapshot()
        publication.clear()
        publication.append(InkPoint(-17f, 91f, .7f, 999999L))
        for ((size, snapshot) in retained) {
            assertEquals("Published sample count changed after append/reset", size, snapshot.size)
            for (index in snapshot.indices) {
                assertEquals("Published raw sample changed at $size/$index", samples[index], snapshot[index])
                if (index % 1024 == 0) assertJson(samples[index].json(), snapshot[index].json(),
                    "published extensions $size/$index")
            }
        }
        assertEquals(listOf(InkPoint(-17f, 91f, .7f, 999999L)), publication.snapshot())
    }
}
