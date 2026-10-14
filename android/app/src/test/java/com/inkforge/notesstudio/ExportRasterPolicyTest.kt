package com.inkforge.notesstudio

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ExportRasterPolicyTest {
    private fun stroke(brush: String) = json("type" to "stroke", "brush" to brush,
        "points" to JSONArray().put(json("x" to 20, "y" to 30)))

    @Test fun oldMultiplyPagesFallbackWithoutChangingOrdinaryPens() {
        for (brush in listOf("pencil", "highlighter")) {
            assertTrue(ExportRasterPolicy.legacyBlend(listOf(stroke(brush)), 23))
            assertTrue(ExportRasterPolicy.legacyBlend(listOf(stroke(brush).put("locked", true)), 28))
            assertFalse(ExportRasterPolicy.legacyBlend(listOf(stroke(brush)), 29))
        }
        assertFalse(ExportRasterPolicy.legacyBlend(listOf(stroke("fountain")), 23))
    }

    @Test fun invisibleEmptyAndNonInkObjectsDoNotLoseVectorOutput() {
        assertFalse(ExportRasterPolicy.legacyBlend(listOf(stroke("pencil").put("hidden", true)), 23))
        assertFalse(ExportRasterPolicy.legacyBlend(listOf(stroke("pencil").put("points", JSONArray())), 23))
        assertFalse(ExportRasterPolicy.legacyBlend(listOf(stroke("pencil").put("type", "ocrIndex")), 23))
    }
}
