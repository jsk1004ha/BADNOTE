package com.inkforge.notesstudio

import org.json.JSONObject

/** Presentation/search never exposes failed or unassigned strokes as recognized text. */
internal object OcrResultPolicy {
    private val searchableStatuses = setOf("complete", "recognized", "ok", "cached", "legacy")
    private val visibleStatuses = setOf("complete", "cached")

    private fun text(region: JSONObject) =
        region.optString("correctedText", region.optString("selectedText"))

    fun visibleRegions(result: JSONObject): List<JSONObject> =
        result.array("regions").objects().filter {
            it.optString("status") in visibleStatuses && text(it).isNotBlank()
        }

    fun searchableText(result: JSONObject): String =
        result.array("regions").objects().filter { it.optString("status") in searchableStatuses }
            .map(::text).filter(String::isNotBlank).joinToString("\n")
}
