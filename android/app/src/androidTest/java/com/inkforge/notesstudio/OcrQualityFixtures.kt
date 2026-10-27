package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/** Fixed geometric pen traces, NOT recordings of writers or an accuracy evaluation dataset. */
internal object OcrQualityFixtures {
    const val VERSION = "synthetic-geometric-v1"
    const val WARNING = "SYNTHETIC geometric handwriting, NOT real writer samples. Labels describe the intended drawing; model mismatches are measurements, not accuracy test failures."
    const val WIDTH = 1000f
    const val HEIGHT = 1414f

    const val LARGE_MATH_RECIPE_VERSION = "linear-dense-math-v1"
    private const val DENSE_STROKE_POINTS = 8193

    /**
     * Derived only at execution time: the fixed v1 manifest stays small and byte-comparable.
     * The single-stroke case is the existing exponent fixture's handwritten base digit, not
     * several pen-up strokes joined by invented connector lines.
     */
    data class MathVariant(val base: Fixture, val scale: Int, val singleStroke: Boolean) {
        val id = "dense-${if (singleStroke) "single-digit-2" else base.id}-${scale}x"
        val truth = if (singleStroke) "2" else base.truth
        val canonicalTruth = if (singleStroke) "2" else base.canonicalTruth
        val width = WIDTH * scale
        val height = HEIGHT * scale
        private val sourceIndices = if (singleStroke) listOf(0) else base.objects.indices.toList()
        private val denseIndex = sourceIndices.maxBy { base.objects[it].getJSONArray("points").length() }

        fun recipe(): JSONObject = json(
            "version" to LARGE_MATH_RECIPE_VERSION, "baseFixtureId" to base.id,
            "sourceStrokeIndices" to JSONArray(sourceIndices), "scale" to scale,
            "strokeMode" to if (singleStroke) "single" else "multiple",
            "denseSourceStrokeIndex" to denseIndex, "denseStrokePointCount" to DENSE_STROKE_POINTS,
            "interpolation" to "linear x/y and integer timestamp interpolation within each original segment; every original vertex retained",
            "scaling" to "page size, point x/y and brush width multiplied uniformly; capture order and remaining metadata copied",
            "manifestPolicy" to "recipe and source hashes only; generated point arrays are not dumped"
        )

        fun copyObjects(): List<JSONObject> = sourceIndices.map { index ->
            val source = base.objects[index]
            val copy = JSONObject(source.toString())
            val original = copy.getJSONArray("points")
            val target = if (index == denseIndex) DENSE_STROKE_POINTS else original.length()
            require(original.length() in 2..target) { "qualityDenseFixtureNeedsSegments" }
            val points = JSONArray()
            fun append(a: JSONObject, b: JSONObject, step: Int, steps: Int) {
                val point = JSONObject((if (step == steps) b else a).toString())
                point.put("x", (a.getDouble("x") + (b.getDouble("x") - a.getDouble("x")) * step / steps) * scale)
                    .put("y", (a.getDouble("y") + (b.getDouble("y") - a.getDouble("y")) * step / steps) * scale)
                    .put("t", a.getLong("t") + (b.getLong("t") - a.getLong("t")) * step / steps)
                points.put(point)
            }
            append(original.getJSONObject(0), original.getJSONObject(0), 1, 1)
            val segments = original.length() - 1
            for (segment in 0 until segments) {
                val steps = ((segment + 1L) * (target - 1) / segments -
                    segment.toLong() * (target - 1) / segments).toInt()
                val a = original.getJSONObject(segment)
                val b = original.getJSONObject(segment + 1)
                for (step in 1..steps) append(a, b, step, steps)
            }
            copy.put("points", points).put("width", source.getDouble("width") * scale)
        }
    }

    fun largeMathVariants(fixtures: List<Fixture>): List<MathVariant> {
        val structures = listOf("fraction", "exponent", "multiline-math").map { id ->
            fixtures.single { it.id == id }
        }
        return listOf(1, 4).flatMap { scale ->
            listOf(MathVariant(structures.single { it.id == "exponent" }, scale, singleStroke = true)) +
                structures.map { MathVariant(it, scale, singleStroke = false) }
        }
    }

    data class Fixture(
        val id: String,
        val truth: String,
        val language: String,
        val policy: String,
        val math: Boolean,
        val objects: List<JSONObject>,
        val canonicalTruth: String = truth
    ) {
        fun original(): JSONObject = json(
            "id" to id, "truth" to truth, "canonicalTruth" to canonicalTruth,
            "language" to language, "policy" to policy,
            "math" to math, "width" to WIDTH, "height" to HEIGHT,
            "objects" to JSONArray(objects)
        )
    }

    private data class Point(val x: Int, val y: Int)
    private fun path(vararg coordinates: Int): List<Point> {
        require(coordinates.size >= 4 && coordinates.size % 2 == 0)
        return coordinates.toList().chunked(2).map { Point(it[0], it[1]) }
    }

    private fun move(strokes: List<List<Point>>, x: Int, y: Int) =
        strokes.map { stroke -> stroke.map { Point(it.x + x, it.y + y) } }

    // Hand-designed syllable/jamo order: ㅎ, ㅏ, ㄴ, ㄱ, ㅡ, ㄹ.
    private fun korean() = listOf(
        path(14, 5, 42, 4),
        path(5, 20, 51, 19),
        path(32, 31, 16, 33, 8, 43, 12, 54, 28, 60, 44, 55, 50, 44, 43, 34, 32, 31),
        path(69, 5, 68, 72),
        path(69, 36, 92, 35),
        path(13, 75, 13, 101, 79, 100),
        path(115, 7, 177, 6, 176, 36),
        path(109, 47, 185, 46),
        path(117, 64, 178, 63, 177, 79, 116, 80, 117, 100, 184, 99)
    )

    private fun english() = listOf(
        path(2, 80, 5, 6, 7, 0, 10, 6, 8, 51, 16, 39, 27, 34, 37, 39, 39, 78),
        path(51, 57, 73, 52, 74, 43, 66, 36, 54, 40, 48, 53, 51, 69, 62, 77, 77, 72),
        path(89, 72, 102, 33, 105, 8, 102, 2, 96, 8, 91, 34, 90, 64, 94, 76, 104, 73),
        path(115, 72, 128, 31, 131, 7, 128, 1, 122, 7, 117, 34, 116, 64, 120, 76, 130, 73),
        path(158, 37, 145, 40, 139, 53, 142, 68, 152, 77, 166, 74, 174, 61, 172, 46, 162, 37, 158, 37)
    )

    private fun two() = listOf(
        path(2, 21, 10, 8, 26, 3, 42, 8, 49, 19, 47, 32, 37, 45, 5, 73, 2, 80, 51, 80)
    )

    private fun arithmetic() = listOf(
        path(0, 20, 15, 6, 15, 81),
        path(51, 28, 51, 68),
        path(30, 48, 74, 48)
    ) + move(two(), 94, 0)

    private fun fraction() = listOf(
        path(45, 14, 57, 3, 56, 58),
        path(8, 79, 112, 78)
    ) + move(two(), 33, 99)

    private fun exponent() = move(two(), 0, 52) + listOf(
        path(83, 9, 95, 2, 108, 4, 116, 12, 111, 22, 100, 26,
            113, 29, 120, 38, 116, 49, 103, 54, 88, 50)
    )

    private fun parentheses() = listOf(
        path(20, 0, 9, 13, 3, 32, 2, 54, 8, 75, 20, 89)
    ) + move(arithmetic(), 42, 4) + listOf(
        path(205, 0, 216, 13, 222, 32, 223, 54, 217, 75, 205, 89)
    )

    fun all(): List<Fixture> {
        data class Design(val id: String, val truth: String, val language: String,
                          val policy: String, val math: Boolean, val strokes: List<List<Point>>,
                          val canonicalTruth: String = truth)
        val designs = listOf(
            Design("ko", "한글", "ko", "ko-primary", false, korean()),
            Design("en", "hello", "en", "en-primary", false, english()),
            Design("mixed", "한글 hello", "mixed-review", "mixed-review", false,
                korean() + move(english(), 225, 0)),
            Design("fraction", "1/2", "en", "en-primary", true, fraction(), "((1)/(2))"),
            Design("exponent", "2^3", "en", "en-primary", true, exponent(), "(2)^(3)"),
            Design("parentheses", "(1+2)", "en", "en-primary", true, parentheses()),
            Design("multiline-text", "한글 hello\nhello", "mixed-review", "mixed-review", false,
                korean() + move(english(), 225, 0) + move(english(), 0, 230)),
            Design("multiline-math", "1+2\n2^3", "en", "en-primary", true,
                arithmetic() + move(exponent(), 0, 220), "1+2\n(2)^(3)")
        )
        return designs.map { design ->
            var clock = 1_000_000L
            val objects = design.strokes.mapIndexed { index, controls ->
                val started = clock
                val points = JSONArray()
                fun append(point: Point) {
                    points.put(json("x" to point.x + 100, "y" to point.y + 140,
                        "p" to 0.55, "t" to clock, "tilt" to 0, "orientation" to 0))
                    clock += 8
                }
                append(controls.first())
                controls.zipWithNext().forEach { (a, b) ->
                    // Integer interpolation makes every exported coordinate stable across copies.
                    val steps = ((max(abs(b.x - a.x), abs(b.y - a.y)) + 3) / 4).coerceAtLeast(1)
                    for (step in 1..steps) {
                        append(Point(a.x + (b.x - a.x) * step / steps,
                            a.y + (b.y - a.y) * step / steps))
                    }
                }
                clock += 80
                json("id" to "quality-${design.id}-stroke-$index", "type" to "stroke",
                    "brush" to "fountain", "width" to 3.2, "color" to "#172033", "opacity" to 1,
                    "createdAt" to started, "captureSeq" to index,
                    "captureSessionId" to "quality-${design.id}-session",
                    "timeBasis" to "session-monotonic", "points" to points)
            }
            Fixture(design.id, design.truth, design.language, design.policy, design.math, objects,
                design.canonicalTruth)
        }
    }
}
