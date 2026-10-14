package com.inkforge.notesstudio

import org.json.JSONObject

internal object OcrCorrectionPolicy {
    fun matches(correction: JSONObject, region: JSONObject): Boolean {
        if (correction.array("strokeIds").toString() != region.array("strokeIds").toString()) return false
        val identity = correction.optString("sourceIdentityDigest")
        if (identity.isNotEmpty()) return identity == region.optString("sourceIdentityDigest") &&
            correction.optString("contentKind","TEXT") == region.optString("contentKind","TEXT")
        // v1 has no source identity: retain its original conservative input equality.
        return correction.optString("inputDigest").isNotEmpty() &&
            correction.optString("inputDigest") == region.optString("inputDigest")
    }
}
