package com.inkforge.notesstudio

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Native strings reproduce observed model disagreements; this tests policy, not writer accuracy. */
class OcrTextCompositionTest {
    private fun word(id: String, x: Float, models: List<RecognitionService.ModelResult>): RecognitionService.WordResult {
        val source = json("id" to id, "type" to "stroke", "extension" to json("kept" to true),
            "points" to JSONArray(listOf(json("x" to x, "y" to 100), json("x" to x + 30, "y" to 140))))
        val region = PageOcrLayout.analyze(listOf(source), 1000f, 1414f, 2).regions.single()
        return RecognitionService.WordResult(region, models)
    }

    @Test fun independentlyRecognizedWordsFixObservedMixedLineWithoutCuttingNativeStrings() {
        val korean = word("korean", 100f, listOf(
            RecognitionService.ModelResult("ko", listOf("한글", "한 글")),
            RecognitionService.ModelResult("en-US", listOf("ELE", "ELLE"))))
        val english = word("english", 220f, listOf(
            RecognitionService.ModelResult("ko", listOf("heddo", "hedo")),
            RecognitionService.ModelResult("en-US", listOf("hello", "Hello"))))
        val source = listOf(korean, english).map { it.region.strokes.single().source.toString() }
        val result = OcrTextComposition.compose(listOf(korean, english), "mixed-review")
        assertEquals("한글 hello", result.candidates.first())
        assertEquals("wordComposition", result.candidateKind)
        assertEquals("mul", result.languageTag)
        assertEquals(listOf(korean, english), result.words)
        assertEquals(source, result.words.map { it.region.strokes.single().source.toString() })
        assertEquals(listOf("heddo", "hedo"), result.words.last().models.first().candidates)
        assertEquals(listOf("hello", "Hello"), result.words.last().models.last().candidates)
    }

    @Test fun latinOnlyNextLineUsesLatinModelInsteadOfKoreanLatinMisspelling() {
        val models = listOf(RecognitionService.ModelResult("ko", listOf("gardem", "gardem!")),
            RecognitionService.ModelResult("en-US", listOf("garden", "Garden")))
        for (policy in listOf("mixed-review", "ko-primary")) {
            assertEquals("garden", OcrCandidatePolicy.preferred(models, policy).candidates.first())
            assertTrue(OcrCandidatePolicy.reviewReasons(models, policy).contains("scriptRoutedCandidate"))
        }
        assertEquals(models.first(), OcrCandidatePolicy.preferred(models, "ko"))
    }

    @Test fun compositionAlternativesRemainBoundedAndChangeOnlyOneRecognizedWord() {
        val words = (0 until 4).map { index -> word("word$index", 100f + index * 100f, listOf(
            RecognitionService.ModelResult("en-US", listOf("word$index", "alternative$index")),
            RecognitionService.ModelResult("ko", listOf("other$index")))) }
        val result = OcrTextComposition.compose(words, "en-primary")
        val top = result.candidates.first().split(' ')
        assertEquals(5, result.candidates.size)
        result.candidates.drop(1).forEach { value ->
            assertEquals(1, value.split(' ').zip(top).count { (a, b) -> a != b })
        }
    }

    @Test fun emptyNativeWordFailsInsteadOfDroppingSourceOrInventingText() {
        val missing = word("missing", 100f, listOf(RecognitionService.ModelResult("ko", emptyList()),
            RecognitionService.ModelResult("en-US", emptyList())))
        val error = runCatching { OcrTextComposition.compose(listOf(missing), "mixed-review") }.exceptionOrNull()
        assertEquals("emptyCandidates", error?.message)
        assertTrue(missing.region.strokes.single().source.getJSONObject("extension").getBoolean("kept"))
    }
}
