package com.inkforge.notesstudio

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class OcrCorrectionPolicyTest {
    private fun region(identity: String, input: String = "engine-v2") = json("strokeIds" to JSONArray(listOf("a","b")),
        "sourceIdentityDigest" to identity,"inputDigest" to input,"contentKind" to "TEXT")
    @Test fun sourceIdentitySurvivesEngineChangeButNotSplitMergeOrContentChange() {
        val correction=region("source","engine-v1")
        assertTrue(OcrCorrectionPolicy.matches(correction,region("source")))
        assertFalse(OcrCorrectionPolicy.matches(correction,region("moved")))
        assertFalse(OcrCorrectionPolicy.matches(correction,region("source").put("strokeIds",JSONArray(listOf("a")))))
        assertFalse(OcrCorrectionPolicy.matches(correction,region("source").put("contentKind","MATH")))
    }
    @Test fun legacyRequiresNonemptyExactInputAndStrokeList() {
        val correction=region("")
        assertTrue(OcrCorrectionPolicy.matches(correction,region("new-source")))
        assertFalse(OcrCorrectionPolicy.matches(correction,region("new-source","changed")))
        assertFalse(OcrCorrectionPolicy.matches(region("",""),region("","")))
    }
}
