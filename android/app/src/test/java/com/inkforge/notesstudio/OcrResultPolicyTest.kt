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
}
