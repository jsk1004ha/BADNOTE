package com.inkforge.notesstudio

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class OcrResultPolicyTest {
    @Test fun failedAndUnresolvedStrokesNeverAppearInResultsOrSearch() {
        val result = json("status" to "partial", "regions" to JSONArray(listOf(
            json("id" to "ok", "status" to "complete", "selectedText" to "검색할 한글"),
            json("id" to "failed", "status" to "failed", "selectedText" to "잘못된 결과"),
            json("id" to "unresolved", "status" to "unresolved", "selectedText" to "미인식"),
            json("id" to "empty", "status" to "cached", "selectedText" to "")
        )))
        assertEquals(listOf("ok"), OcrResultPolicy.visibleRegions(result).map { it.optString("id") })
        assertEquals("검색할 한글", OcrResultPolicy.searchableText(result))
    }

    @Test fun correctionsAreSearchableAndLegacyTextRemainsIndexed() {
        val result = json("regions" to JSONArray(listOf(
            json("id" to "corrected", "status" to "cached",
                "selectedText" to "오인식", "correctedText" to "바른 글자"),
            json("id" to "legacy", "status" to "legacy", "selectedText" to "이전 필기")
        )))
        assertEquals("바른 글자\n이전 필기", OcrResultPolicy.searchableText(result))
        assertEquals(listOf("corrected"), OcrResultPolicy.visibleRegions(result).map { it.optString("id") })
    }

    @Test fun presentationAndSearchUseReadingOrderAndJoinBudgetPartsOnTheSameLine() {
        val result = json("readingOrder" to JSONArray(listOf("first/part1", "first/part2", "second")),
            "regions" to JSONArray(listOf(
                json("id" to "second", "order" to 1, "status" to "complete", "selectedText" to "둘째 줄"),
                json("id" to "first/part2", "order" to 0, "status" to "cached", "selectedText" to "world"),
                json("id" to "first/part1", "order" to 0, "status" to "complete", "selectedText" to "hello")
            )))
        assertEquals("hello world\n둘째 줄", OcrResultPolicy.visibleText(result))
        assertEquals(OcrResultPolicy.visibleText(result), OcrResultPolicy.searchableText(result))
        assertEquals(listOf("first/part1", "first/part2", "second"),
            OcrResultPolicy.searchableRegions(result).map { it.optString("id") })
    }

    @Test fun existingWordSpacingAndEmbeddedLineBreaksAreNotRewritten() {
        val result = json("regions" to JSONArray(listOf(
            json("id" to "a", "order" to 0, "status" to "complete", "selectedText" to "one  "),
            json("id" to "b", "order" to 0, "status" to "complete", "selectedText" to "two\n"),
            json("id" to "c", "order" to 1, "status" to "complete", "selectedText" to "three\nfour")
        )))
        assertEquals("one  two\nthree\nfour", OcrResultPolicy.visibleText(result))
        assertEquals(OcrResultPolicy.visibleText(result), OcrResultPolicy.searchableText(result))
    }

    @Test fun rasterAndInkWithEqualOrderAreSeparateLines() {
        val result = json("regions" to JSONArray(listOf(
            json("id" to "image", "order" to 0, "role" to "image",
                "status" to "complete", "selectedText" to "printed words"),
            json("id" to "ink", "order" to 0, "role" to "line",
                "status" to "complete", "selectedText" to "handwritten words")
        )))
        assertEquals("printed words\nhandwritten words", OcrResultPolicy.visibleText(result))
    }

    @Test fun wholeResultCorrectionPreservesExactWhitespaceAndOriginalRegions() {
        val region = json("id" to "original", "order" to 0, "status" to "complete",
            "selectedText" to "raw text", "correctedText" to "old regional correction")
        val result = json("regions" to JSONArray(listOf(region)),
            "correctedText" to "  edited  text\n\nsecond line \n")
        assertEquals("  edited  text\n\nsecond line \n", OcrResultPolicy.visibleText(result))
        assertEquals(OcrResultPolicy.visibleText(result), OcrResultPolicy.searchableText(result))
        assertEquals("raw text", region.optString("selectedText"))
        assertEquals("old regional correction", region.optString("correctedText"))
        result.put("correctedText", "")
        assertEquals("", OcrResultPolicy.visibleText(result))
        assertEquals("", OcrResultPolicy.searchableText(result))
    }
}
