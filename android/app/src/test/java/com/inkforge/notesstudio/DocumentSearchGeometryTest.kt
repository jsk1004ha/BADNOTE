package com.inkforge.notesstudio

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class DocumentSearchGeometryTest {
    private val first = InkBounds(10f, 20f, 50f, 40f)
    private val second = InkBounds(70f, 20f, 110f, 40f)
    private fun bounds(box: InkBounds) = json("left" to box.left, "top" to box.top,
        "right" to box.right, "bottom" to box.bottom)
    private fun result() = json("regions" to JSONArray(listOf(json("id" to "line", "order" to 0,
        "status" to "complete", "selectedText" to "alpha beta", "bounds" to bounds(InkBounds(10f, 20f, 110f, 40f)),
        "wordRegions" to JSONArray(listOf(json("selectedText" to "alpha", "bounds" to bounds(first)),
            json("selectedText" to "beta", "bounds" to bounds(second))))))))

    @Test fun actualWordsWinOverTheWholeLineAndEveryOccurrenceIsIncluded() {
        val result = result()
        val text = OcrResultPolicy.searchableText(result)
        val spans = DocumentSearchGeometry.ocrSpans(result, text)
        assertEquals(listOf(second), DocumentSearchGeometry.matchingBounds(text, "BETA", spans))
        assertEquals(listOf(first, second), DocumentSearchGeometry.matchingBounds(text, "alpha beta", spans))
        assertEquals(listOf(0..3, 5..8), DocumentSearchGeometry.occurrences("beta beta", "beta"))
    }

    @Test fun regionCorrectionsUseOnlyKnownRegionExtentAndNeverOldWordAlignment() {
        val result = result()
        result.array("regions").getJSONObject(0).put("correctedText", "a corrected word")
        val text = OcrResultPolicy.searchableText(result)
        assertEquals(listOf(InkBounds(10f, 20f, 110f, 40f)), DocumentSearchGeometry.matchingBounds(text,
            "corrected", DocumentSearchGeometry.ocrSpans(result, text)))
    }

    @Test fun wholeResultCorrectionsNeverInventCoordinates() {
        val result = result().put("correctedText", "different text")
        assertTrue(DocumentSearchGeometry.ocrSpans(result, "different text").isEmpty())
        result.put("correctedText", "alpha beta")
        assertEquals(listOf(second), DocumentSearchGeometry.matchingBounds("alpha beta", "beta",
            DocumentSearchGeometry.ocrSpans(result, "alpha beta")))
    }

    @Test fun rasterPageAndLegacyTextHaveNoSyntheticWordBoxes() {
        val result = result()
        result.array("regions").getJSONObject(0).apply { remove("wordRegions"); put("role", "image") }
        assertTrue(DocumentSearchGeometry.ocrSpans(result, "alpha beta").isEmpty())
        val legacy = json("regions" to JSONArray(listOf(json("status" to "legacy", "selectedText" to "old"))))
        assertTrue(DocumentSearchGeometry.ocrSpans(legacy, "old").isEmpty())
    }

    @Test fun invalidBoundsAreNotConvertedIntoRealSearchAreas() {
        assertNull(DocumentSearchGeometry.readBounds(json("left" to 10, "right" to 5, "top" to 0, "bottom" to 10)))
        assertNull(DocumentSearchGeometry.readBounds(json("left" to 0, "right" to 10, "bottom" to 10)))
    }

    @Test fun wholeResultBindingIgnoresOrderingButNotSourceChanges() {
        fun region(id: String) = json("id" to id, "strokeIds" to JSONArray(listOf(id)),
            "sourceIdentityDigest" to "source-$id", "inputDigest" to "input-$id")
        val first = region("a"); val second = region("b")
        val result = json("policy" to "ko-primary", "regions" to JSONArray(listOf(first, second)))
        val correction = json("scope" to "documentText", "sourceBinding" to OcrDocumentCorrection.binding(result))
        result.put("regions", JSONArray(listOf(second, first)))
        assertTrue(OcrDocumentCorrection.matches(correction, result))
        first.put("inputDigest", "different")
        assertFalse(OcrDocumentCorrection.matches(correction, result))
    }
}
