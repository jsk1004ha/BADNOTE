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
        assertEquals("7eb50a657039faf8eef3ea6126701bdb73c10e7581ee6d0d579ca3f56455bfa3",
            reference.getString("sourceSha256"))
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

    @Test fun originalDrawingCommandsAllBrushesAtNormalAndZoomedScale() {
        val fixtures = reference.getJSONArray("fixtures").let { array ->
            (0 until array.length()).associate { i -> array.getJSONObject(i).let { it.getString("name") to it } }
        }
        val renders = reference.getJSONArray("renderFixtures")
        for (index in 0 until renders.length()) {
            val expected = renders.getJSONObject(index)
            val name = expected.getString("name")
            val stroke = fixtureStroke(requireNotNull(fixtures[name])).put("color", "#172033")
            val actual = ArrayList<LegacyBrush.Operation>()
            LegacyBrush.render(stroke, expected.getDouble("scale")) { actual += it }
            val commands = expected.getJSONArray("operations")
            assertEquals("$name operations", commands.length(), actual.size)
            for (command in actual.indices) {
                val wanted = commands.getJSONArray(command)
                val emitted = actual[command]
                assertEquals("$name #$command", wanted.getString(0), emitted.name)
                assertEquals("$name #$command args", wanted.length() - 1, emitted.args.size)
                emitted.args.forEachIndexed { arg, value -> assertJson(wanted.get(arg + 1), value, "$name #$command arg$arg") }
            }
        }
    }
}
