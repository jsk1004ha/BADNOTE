package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Whole-result edits stay attached only to the exact set of original recognition inputs. */
internal object OcrDocumentCorrection {
    const val REGION_ID = "__document_text__"

    fun binding(result: JSONObject): String {
        val regions = result.array("regions").objects().map { region ->
            JSONArray(listOf(region.optString("id"), region.array("strokeIds").toString(),
                region.optString("sourceIdentityDigest"), region.optString("inputDigest"),
                region.optString("contentKind", "TEXT"))).toString()
        }.sorted()
        val source = JSONArray(listOf(result.optString("policy"), JSONArray(regions))).toString()
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun matches(correction: JSONObject, result: JSONObject): Boolean =
        correction.optString("scope") == "documentText" &&
            correction.optString("sourceBinding") == binding(result)
}
