package com.inkforge.notesstudio

import com.inkforge.ocr.contracts.MathAvailability
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

/** Synthetic layout contracts, not a claim about handwriting-model accuracy. */
class OcrMathLayoutTest {
    private fun stroke(id: String, x: Float, y: Float, w: Float, h: Float): JSONObject =
        json("id" to id, "type" to "stroke", "width" to 2,
            "points" to JSONArray(listOf(json("x" to x, "y" to y, "p" to 0.0, "t" to 100),
                json("x" to x + w, "y" to y + h, "p" to 0.7, "t" to 110))))

    private fun fraction() = listOf(stroke("numerator", 120f, 100f, 20f, 40f),
        stroke("bar", 100f, 150f, 70f, 0f), stroke("denominator", 120f, 160f, 20f, 40f))
    private fun dense(source: JSONObject, count: Int, scale: Float): JSONObject {
        val first = source.getJSONArray("points").getJSONObject(0)
        val last = source.getJSONArray("points").getJSONObject(1)
        return JSONObject(source.toString()).put("width", 2 * scale).put("points",
            JSONArray((0 until count).map { index ->
                val fraction = index.toDouble() / (count - 1)
                json("x" to (first.getDouble("x") + (last.getDouble("x") - first.getDouble("x")) * fraction) * scale,
                    "y" to (first.getDouble("y") + (last.getDouble("y") - first.getDouble("y")) * fraction) * scale,
                    "p" to .7, "t" to 100L + index)
            }))
    }


    private fun recognize(objects: List<JSONObject>, native: (PageOcrLayout.Region) -> List<String>) =
        OcrMathLayout.recognize(objects, 1000f, 1414f, native)

    @Test fun fractionUsesActualCandidatesAndKeepsBarInOriginalRegion() {
        val input = fraction()
        val seen = mutableListOf<List<String>>()
        val result = recognize(input) { region ->
            seen += region.strokes.map { it.id }
            when (region.strokes.single().id) {
                "numerator" -> listOf("1", "l")
                "denominator" -> listOf("2")
                else -> error("The structural fraction bar is not a recognized symbol")
            }
        }.single()
        assertEquals(listOf("((1)/(2))", "((l)/(2))"), result.candidates)
        assertEquals(listOf(listOf("numerator"), listOf("denominator")), seen)
        assertEquals(input.map { it.getString("id") }, result.region.strokes.map { it.id })
        assertTrue(result.reviewReasons.contains("mathCandidateAmbiguous"))
        assertTrue(result.reviewReasons.contains("mathSupportedSubset"))
        assertEquals(MathAvailability.SUPPORTED_SUBSET, OcrMathLayout.availability)
    }

    @Test fun unsupportedNativeAlternativeDoesNotDiscardValidFractionTopOrCallWholeFallback() {
        val seen = mutableListOf<String>()
        val result = recognize(fraction()) { region ->
            val id = region.strokes.single().id
            seen += id
            when (id) {
                "numerator" -> listOf("1", "l", "|")
                "denominator" -> listOf("2")
                else -> error("A valid native top must not invoke whole-fraction fallback")
            }
        }.single()
        assertEquals(listOf("((1)/(2))", "((l)/(2))"), result.candidates)
        assertEquals(listOf("numerator", "denominator"), seen)
        assertTrue(result.reviewReasons.contains("mathUnsupportedNotation"))
        assertFalse(result.reviewReasons.contains("mathStructureUnresolved"))
    }

    @Test fun invalidComposedAlternativeDoesNotDiscardValidTopSequence() {
        val input = listOf(stroke("prefix", 20f, 130f, 60f, 40f)) + fraction()
        val result = recognize(input) { region ->
            when (region.strokes.single().id) {
                "prefix" -> listOf("a+", "*")
                "numerator" -> listOf("1")
                "denominator" -> listOf("2")
                else -> error("A valid top sequence must not invoke whole-region fallback")
            }
        }.single()
        assertEquals(listOf("a+((1)/(2))"), result.candidates)
        assertTrue(result.reviewReasons.contains("mathUnsupportedNotation"))
        assertFalse(result.reviewReasons.contains("mathStructureUnresolved"))
    }

    @Test fun nativeOperatorFragmentsAndParenthesesCanSpanAFraction() {
        val input = listOf(stroke("prefix", 20f, 130f, 60f, 40f)) + fraction() +
            stroke("suffix", 190f, 130f, 10f, 40f)
        val result = recognize(input) { region ->
            listOf(when (region.strokes.single().id) {
                "prefix" -> "(a+"
                "suffix" -> ")"
                "numerator" -> "1"
                "denominator" -> "2"
                else -> error("Unexpected model region")
            })
        }.single()
        assertEquals(listOf("(a+((1)/(2)))"), result.candidates)
        assertFalse(result.reviewReasons.contains("mathUnsupportedNotation"))
        assertFalse(result.reviewReasons.contains("mathUnbalancedParentheses"))
    }

    @Test fun adjacentOrdinaryStrokesUseOneRealTextHypothesis() {
        val input = listOf(stroke("a", 100f, 150f, 20f, 40f),
            stroke("operator", 130f, 170f, 20f, 0f), stroke("b", 160f, 150f, 20f, 40f))
        var calls = 0
        val result = recognize(input) { region ->
            calls++
            assertEquals(listOf("a", "operator", "b"), region.strokes.map { it.id })
            listOf("a-b")
        }.single()
        assertEquals(1, calls)
        assertEquals(listOf("a-b"), result.candidates)
    }

    @Test fun powersRaiseOnlyFinalAtomOrMatchedParenthesizedGroup() {
        val base = stroke("base", 100f, 150f, 80f, 40f)
        val exponent = stroke("exponent", 185f, 122f, 12f, 22f)
        for ((native, expected) in listOf("1+2" to "1+(2)^(3)",
            "(1+2)" to "((1+2))^(3)", "12" to "(12)^(3)")) {
            val result = recognize(listOf(base, exponent)) { region ->
                if (region.strokes.single().id == "base") listOf(native) else listOf("3")
            }.single()
            assertEquals(listOf(expected), result.candidates)
        }
    }

    @Test fun mixedHeightBasesKeepTheirOwnExponentsAcrossInterveningBaselineObjects() {
        val input = fraction() + listOf(
            stroke("firstExponent", 174f, 120f, 12f, 22f),
            stroke("plus", 187f, 166f, 5f, 10f),
            stroke("x", 196f, 150f, 10f, 40f),
            stroke("secondExponent", 210f, 120f, 12f, 22f))
        val seen = mutableListOf<Set<String>>()
        val result = recognize(input) { region ->
            val ids = region.strokes.map { it.id }.toSet()
            seen += ids
            listOf(when (ids) {
                setOf("numerator") -> "1"
                setOf("denominator") -> "2"
                setOf("firstExponent") -> "2"
                setOf("plus", "x") -> "+x"
                setOf("secondExponent") -> "3"
                else -> error("Separate bases must not share one exponent-model region: $ids")
            })
        }.single()
        assertEquals(listOf("(((1)/(2)))^(2)+(x)^(3)"), result.candidates)
        assertTrue(seen.contains(setOf("firstExponent")))
        assertTrue(seen.contains(setOf("secondExponent")))
        assertEquals(input.map { it.getString("id") }.toSet(), result.region.strokes.map { it.id }.toSet())
        assertEquals(input.size, result.region.strokes.size)
    }

    @Test fun tinyLegibleExponentIsForwardedWithoutChangingItsPoints() {
        val input = listOf(stroke("base", 100f, 150f, 25f, 40f),
            stroke("tiny", 130f, 143f, 4f, 4f))
        val before = input.map { it.toString() }
        val result = recognize(input) { region ->
            if (region.strokes.single().id == "base") listOf("x") else listOf("2")
        }.single()
        assertEquals(listOf("(x)^(2)"), result.candidates)
        assertEquals(before, input.map { it.toString() })
    }

    @Test fun parenthesesAndOnlyEquivalentNativeOperatorsArePreserved() {
        val input = listOf(stroke("expression", 100f, 150f, 100f, 40f))
        val result = recognize(input) { listOf("(a+2)×(b−3)÷4·x", "O+l") }.single()
        assertEquals(listOf("(a+2)*(b-3)/4*x", "O+l"), result.candidates)
    }

    @Test fun multipleLinesRemainSeparateAndInPageOrder() {
        val input = listOf(stroke("second", 100f, 300f, 90f, 40f),
            stroke("first", 100f, 100f, 90f, 40f))
        val result = recognize(input) { region ->
            listOf(if (region.strokes.single().id == "first") "1+2" else "(a-b)")
        }
        assertEquals(listOf("1+2", "(a-b)"), result.map { it.candidates.single() })
        assertEquals(listOf(0, 1), result.map { it.region.order })
        assertEquals(listOf("first", "second"), result.flatMap { it.region.strokes.map { stroke -> stroke.id } })
    }

    @Test fun unsupportedAndUnbalancedNativeOutputIsNotRepairedOrDiscarded() {
        val input = listOf(stroke("expression", 100f, 150f, 100f, 40f))
        val result = recognize(input) { listOf("√x", "(x+2", "∑x") }.single()
        assertEquals(listOf("√x", "(x+2", "∑x"), result.candidates)
        assertTrue(result.reviewReasons.contains("mathUnsupportedNotation"))
        assertTrue(result.reviewReasons.contains("mathUnbalancedParentheses"))
        assertTrue(result.reviewReasons.contains("mathCandidateAmbiguous"))
    }

    @Test fun emptyModelNeverGetsReplacementNumbersOrSymbolsFromGeometry() {
        val result = recognize(fraction()) { emptyList() }.single()
        assertTrue(result.candidates.isEmpty())
        assertTrue(result.reviewReasons.contains("mathNoCandidate"))
        assertTrue(result.reviewReasons.contains("mathStructureUnresolved"))
        assertEquals(setOf("numerator", "bar", "denominator"), result.region.strokes.map { it.id }.toSet())
    }

    @Test fun unsupportedStructuredLeavesFallBackToRealWholeRegionHypothesis() {
        val result = recognize(fraction()) { region ->
            when (region.strokes.map { it.id }.toSet()) {
                setOf("numerator") -> listOf("√x")
                setOf("denominator") -> listOf("2")
                else -> listOf("√x / 2")
            }
        }.single()
        assertEquals(listOf("√x / 2"), result.candidates)
        assertTrue(result.reviewReasons.contains("mathUnsupportedNotation"))
        assertTrue(result.reviewReasons.contains("mathStructureUnresolved"))
    }

    @Test fun parallelBarsAreNotInventedAsOneFraction() {
        val input = fraction() + stroke("secondBar", 100f, 154f, 70f, 0f)
        val result = recognize(input) { listOf("1=2") }
        assertTrue(result.all { line -> line.candidates.none { it.contains("/(") } })
        assertTrue(result.any { it.reviewReasons.contains("mathAmbiguousLayout") })
        assertEquals(input.map { it.getString("id") }.toSet(),
            result.flatMap { it.region.strokes.map { stroke -> stroke.id } }.toSet())
    }

    @Test fun zeroHeightBaselineKeepsActualNativeHypothesisWithoutInventingPower() {
        val input = listOf(stroke("first", 100f, 150f, 20f, 0f), stroke("second", 130f, 150f, 20f, 0f))
        val result = recognize(input) { region ->
            assertEquals(input.map { it.getString("id") }.toSet(), region.strokes.map { it.id }.toSet())
            listOf("−")
        }.single()
        assertEquals(listOf("-"), result.candidates)
        assertFalse(result.candidates.any { it.contains('^') })
        assertEquals(input.size, result.region.strokes.size)
    }

    @Test fun candidatesAreBoundedWithoutCartesianProducts() {
        val result = recognize(fraction()) { region ->
            if (region.strokes.single().id == "numerator") listOf("1", "2", "3", "4")
            else listOf("5", "6", "7", "8")
        }.single()
        assertEquals(listOf("((1)/(5))", "((2)/(5))", "((1)/(6))"), result.candidates)
        assertEquals(3, result.candidates.size)
    }

    @Test fun hiddenHighlighterAndTransparentInkAreOmittedWithoutSourceMutation() {
        val input = fraction().mapIndexed { i, source ->
            source.put("captureSeq", 10 + i).put("captureSessionId", "session")
                .put("timeBasis", "session-monotonic").put("sourceStrokeId", "original-$i")
                .put("fragmentOrder", i).put("extension", json("unchanged" to true)).apply {
                    getJSONArray("points").getJSONObject(0).put("t", 100 + i * 20)
                    getJSONArray("points").getJSONObject(1).put("t", 110 + i * 20)
                }
        } + listOf(stroke("hidden", 10f, 10f, 10f, 10f).put("hidden", true),
            stroke("highlighter", 20f, 20f, 10f, 10f).put("brush", "highlighter"),
            stroke("transparent", 30f, 30f, 10f, 10f).put("opacity", 0),
            stroke("settingsTransparent", 40f, 40f, 10f, 10f).put("settings", json("opacity" to 0)))
        val before = input.map { it.toString() }
        val originals = input.associateBy { it.getString("id") }
        val result = recognize(input) { region ->
            region.strokes.forEach { assertSame(originals[it.id], it.source) }
            if (region.strokes.single().id == "numerator") listOf("1") else listOf("2")
        }.single()
        assertEquals(listOf("numerator", "bar", "denominator"), result.region.strokes.map { it.id })
        result.region.strokes.forEach { assertSame(originals[it.id], it.source) }
        assertEquals("relative", result.region.timeMode)
        assertEquals(100L, result.region.timeOrigin)
        assertEquals(before, input.map { it.toString() })
    }

    @Test fun omittedObjectsDoNotRenumberOriginalSourceIndices() {
        val visible = fraction()
        val input = listOf(stroke("hidden", 0f, 0f, 10f, 10f).put("hidden", true),
            visible[0], json("type" to "text", "id" to "nativeText"), visible[1], visible[2])
        val result = recognize(input) { region ->
            if (region.strokes.single().id == "numerator") listOf("1") else listOf("2")
        }.single()
        assertEquals(listOf(1, 3, 4), result.region.strokes.map { it.originalIndex })
    }

    @Test fun modelRequestBudgetFailsHonestlyWithoutDroppingOriginalStrokes() {
        val input = (0 until 65).flatMap { column ->
            val x = column * 100f
            listOf(stroke("n$column", x + 20f, 100f, 20f, 40f),
                stroke("bar$column", x, 150f, 70f, 0f),
                stroke("d$column", x + 20f, 160f, 20f, 40f))
        }
        var calls = 0
        val result = OcrMathLayout.recognize(input, 20000f, 1414f, { calls++; listOf("1") }).single()
        assertEquals(128, calls)
        assertTrue(result.candidates.isEmpty())
        assertTrue(result.reviewReasons.contains("mathRecognitionBudgetExceeded"))
        assertEquals(input.map { it.getString("id") }.toSet(), result.region.strokes.map { it.id }.toSet())
    }

    @Test fun deepStructureIsBoundedAndPreservedForReview() {
        val input = mutableListOf(stroke("initial", 990f, 100f, 20f, 20f))
        var bottom = 120f
        for (level in 1..8) {
            val barY = bottom + 10f
            val width = (barY - 100f + 80f) * 2
            input += stroke("nestedBar$level", 1000f - width / 2, barY, width, 0f)
            input += stroke("nestedDenominator$level", 990f, barY + 10f, 20f, 20f)
            bottom = barY + 30f
        }
        val result = OcrMathLayout.recognize(input, 3000f, 1414f, { listOf("1") })
        assertTrue(result.any { it.reviewReasons.contains("mathLayoutDepthExceeded") })
        val ids = result.flatMap { it.region.strokes.map { stroke -> stroke.id } }
        assertEquals(input.size, ids.size)
        assertEquals(input.map { it.getString("id") }.toSet(), ids.toSet())
    }

    @Test fun isolatedRaisedDotRemainsNativeAmbiguousTextNotAnInventedPower() {
        val input = listOf(stroke("base", 100f, 150f, 25f, 40f), stroke("dot", 130f, 143f, 1f, 1f))
        val result = recognize(input) { region ->
            assertEquals(setOf("base", "dot"), region.strokes.map { it.id }.toSet())
            listOf("x.")
        }.single()
        assertEquals(listOf("x."), result.candidates)
        assertFalse(result.candidates.any { it.contains('^') })
        assertTrue(result.reviewReasons.contains("mathAmbiguousLayout"))
    }

    @Test fun emptyInputDoesNotCallTheModel() {
        assertTrue(recognize(emptyList()) { error("No model input exists") }.isEmpty())
    }

    @Test fun cancellationBeforeModelAndImmediatelyAfterModelPropagatesUnchanged() {
        val cancellation = CancellationException("superseded")
        var calls = 0
        try {
            OcrMathLayout.recognize(fraction(), 1000f, 1414f,
                { calls++; listOf("1") }, { throw cancellation })
            fail("Expected cancellation")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertEquals(0, calls)
        var stale = false
        try {
            OcrMathLayout.recognize(fraction(), 1000f, 1414f,
                { calls++; stale = true; listOf("1") }, { if (stale) throw cancellation })
            fail("Expected cancellation")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertEquals(1, calls)
    }

    @Test fun realModelFailureIsAnExplicitFailedRowAndDoesNotDiscardOtherRows() {
        val input = fraction() + stroke("otherLine", 100f, 350f, 90f, 40f)
        val result = recognize(input) { region ->
            if (region.strokes.single().id == "otherLine") listOf("3+4")
            else throw IllegalStateException("modelNotDownloaded")
        }
        assertEquals(2, result.size)
        assertTrue(result[0].candidates.isEmpty())
        assertEquals("modelNotDownloaded", result[0].errorCode)
        assertTrue(result[0].reviewReasons.contains("mathModelFailure"))
        assertEquals(listOf("3+4"), result[1].candidates)
        assertNull(result[1].errorCode)
        assertEquals(input.map { it.getString("id") }.toSet(),
            result.flatMap { it.region.strokes.map { stroke -> stroke.id } }.toSet())
    }

    @Test fun denseLargeSourceKeepsFractionsPowersAndMultipleLinesWhileEveryModelPayloadFits() {
        val original = fraction() + listOf(stroke("base", 100f, 350f, 25f, 40f),
            stroke("exponent", 130f, 322f, 12f, 22f), stroke("third", 100f, 550f, 90f, 40f))
        for (scale in listOf(1f, 4f)) {
            val input = original.map { dense(it, 9001, scale) }
            val before = input.map { it.toString() }
            assertTrue(input.sumOf { it.getJSONArray("points").length() } > OcrInput.HARD_REGION_POINTS)
            val payloads = mutableListOf<OcrInkGeometry.ModelInput>()
            val result = OcrMathLayout.recognize(input, 1000 * scale, 1414 * scale, { region ->
                val payload = OcrInkGeometry.mathInput(region)
                payloads += payload
                assertTrue(payload.pointCount <= OcrInput.SOFT_REGION_POINTS)
                assertTrue(payload.strokes.size <= OcrInput.SOFT_REGION_STROKES)
                assertTrue(payload.byteUpperBound <= OcrInput.SOFT_REGION_BYTES)
                listOf(when (region.strokes.single().id) {
                    "numerator" -> "1"
                    "denominator" -> "2"
                    "base" -> "x"
                    "exponent" -> "3"
                    "third" -> "a+1"
                    else -> error("Fraction bar must remain structural")
                })
            })
            assertEquals(listOf("((1)/(2))", "(x)^(3)", "a+1"), result.map { it.candidates.single() })
            assertEquals(5, payloads.size)
            assertTrue(payloads.all { it.resampled })
            assertEquals(before, input.map { it.toString() })
            assertEquals(input.map { it.getString("id") }.toSet(),
                result.flatMap { it.region.strokes.map { stroke -> stroke.id } }.toSet())
        }
    }

    @Test fun penUpBudgetBoundarySplitsOnlyWholeStrokesAcrossEmptyMathSpace() {
        for (count in listOf(OcrInput.SOFT_REGION_STROKES, OcrInput.SOFT_REGION_STROKES + 1)) {
            val input = (0 until count).map { stroke("s$it", it * 30f, 100f, 20f, 40f) }
            val before = input.map { it.toString() }
            val payloads = mutableListOf<OcrInkGeometry.ModelInput>()
            val result = OcrMathLayout.recognize(input, 20000f, 1414f, { region ->
                val payload = OcrInkGeometry.mathInput(region)
                payloads += payload
                assertTrue(payload.strokes.size <= OcrInput.SOFT_REGION_STROKES)
                assertTrue(payload.pointCount <= OcrInput.SOFT_REGION_POINTS)
                assertTrue(payload.byteUpperBound <= OcrInput.SOFT_REGION_BYTES)
                listOf(if (region.strokes.size == count) "1+2"
                    else if (region.strokes.any { it.id == "s0" }) "1+" else "2")
            }).single()
            assertEquals(listOf("1+2"), result.candidates)
            assertEquals(if (count == OcrInput.SOFT_REGION_STROKES) 1 else 2, payloads.size)
            assertEquals(count, payloads.sumOf { it.strokes.size })
            assertEquals(count > OcrInput.SOFT_REGION_STROKES, result.reviewReasons.contains("mathModelInputSplit"))
            assertEquals(before, input.map { it.toString() })
            assertEquals(input.map { it.getString("id") }.toSet(), result.region.strokes.map { it.id }.toSet())
        }
    }

    @Test fun inseparableOverBudgetRowIsHonestAndDoesNotRejectTheOtherLine() {
        val dense = (0..OcrInput.SOFT_REGION_STROKES).map { stroke("overlap$it", 100f, 100f, 20f, 40f) }
        val input = dense + stroke("other", 100f, 350f, 20f, 40f)
        var actualPayloads = 0
        val result = recognize(input) { region ->
            OcrInkGeometry.mathInput(region)
            actualPayloads++
            listOf("2")
        }
        assertEquals(2, result.size)
        assertTrue(result.first().candidates.isEmpty())
        assertTrue(result.first().reviewReasons.contains("mathStrokeBudgetExceeded"))
        assertEquals(listOf("2"), result.last().candidates)
        assertEquals(1, actualPayloads)
        assertEquals(input.map { it.getString("id") }.toSet(),
            result.flatMap { it.region.strokes.map { stroke -> stroke.id } }.toSet())
    }
}
