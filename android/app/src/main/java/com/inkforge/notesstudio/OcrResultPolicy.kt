package com.inkforge.notesstudio

import org.json.JSONObject

/** Presentation/search never exposes failed or unassigned strokes as recognized text. */
internal object OcrResultPolicy {
    private val searchableStatuses = setOf("complete", "recognized", "ok", "cached", "legacy")
    private val visibleStatuses = setOf("complete", "cached")

    private fun text(region: JSONObject) =
        region.optString("correctedText", region.optString("selectedText"))

    private fun orderedRegions(result: JSONObject): List<JSONObject> {
        val order = result.array("readingOrder")
        val positions = HashMap<String, Int>()
        for (index in 0 until order.length()) {
            val id = order.optString(index)
            if (!positions.containsKey(id)) positions[id] = index
        }
        return result.array("regions").objects().sortedWith(
            compareBy<JSONObject> { positions[it.optString("id")] ?: Int.MAX_VALUE }
                .thenBy { it.optInt("order", Int.MAX_VALUE) }
        )
    }

    fun visibleRegions(result: JSONObject): List<JSONObject> =
        orderedRegions(result).filter {
            it.optString("status") in visibleStatuses && text(it).isNotBlank()
        }

    fun searchableRegions(result: JSONObject): List<JSONObject> =
        orderedRegions(result).filter {
            it.optString("status") in searchableStatuses && text(it).isNotBlank()
        }

    /** Budget-split regions retain their line order; they are not separate paragraphs. */
    private fun compose(regions: List<JSONObject>): String = buildString {
        var previous: JSONObject? = null
        regions.forEach { region ->
            val value = text(region)
            previous?.let { before ->
                val sameLine = before.has("order") && region.has("order") &&
                    before.optInt("order") == region.optInt("order") &&
                    before.optString("role") != "image" && region.optString("role") != "image"
                if (sameLine) {
                    if (lastOrNull()?.isWhitespace() != true && value.firstOrNull()?.isWhitespace() != true)
                        append(' ')
                } else if (lastOrNull() != '\n' && value.firstOrNull() != '\n') append('\n')
            }
            append(value)
            previous = region
        }
    }

    fun visibleText(result: JSONObject): String =
        if (result.has("correctedText") && !result.isNull("correctedText"))
            result.optString("correctedText") else compose(visibleRegions(result))

    fun searchableText(result: JSONObject): String =
        if (result.has("correctedText") && !result.isNull("correctedText"))
            result.optString("correctedText") else compose(searchableRegions(result))
}
