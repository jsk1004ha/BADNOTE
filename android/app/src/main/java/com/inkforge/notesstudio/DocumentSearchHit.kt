package com.inkforge.notesstudio

import org.json.JSONObject

/** Search rectangles are in the same page coordinates as ink, never screen/PDF coordinates. */
data class DocumentSearchHit(val index: Int, val pageId: String, val snippet: String, val bounds: List<InkBounds>)

internal object DocumentSearchGeometry {
    data class Span(val start: Int, val end: Int, val bounds: InkBounds)

    fun occurrences(text: String, term: String): List<IntRange> {
        if (term.isEmpty()) return emptyList()
        val result = ArrayList<IntRange>()
        var offset = 0
        while (offset <= text.length - term.length) {
            val at = text.indexOf(term, offset, ignoreCase = true)
            if (at < 0) break
            result += at until at + term.length
            offset = at + term.length
        }
        return result
    }

    fun matchingBounds(text: String, term: String, spans: List<Span>): List<InkBounds> {
        val matches = occurrences(text, term)
        return spans.filter { span -> matches.any { it.first < span.end && it.last >= span.start } }
            .map { it.bounds }.distinct()
    }

    /** A correction has no new word alignment: retain only the original region extent. */
    fun ocrSpans(result: JSONObject, text: String): List<Span> {
        if (result.has("correctedText")) {
            val original = result.copyJson().apply { remove("correctedText") }
            if (text != OcrResultPolicy.searchableText(original)) return emptyList()
        }
        val spans = ArrayList<Span>()
        var cursor = 0
        for (region in OcrResultPolicy.searchableRegions(result)) {
            val regionText = region.optString("correctedText", region.optString("selectedText"))
            if (regionText.isBlank()) continue
            val start = text.indexOf(regionText, cursor)
            if (start < 0) continue
            cursor = start + regionText.length
            val words = region.array("wordRegions").objects()
            val wordTexts = words.map { it.optString("correctedText", it.optString("selectedText")) }
            val wordBounds = words.map { readBounds(it.optJSONObject("bounds")) }
            if (words.isNotEmpty() && wordTexts.all { it.isNotBlank() } &&
                wordTexts.joinToString(" ") == regionText && wordBounds.all { it != null }) {
                var offset = start
                wordTexts.forEachIndexed { index, word ->
                    spans += Span(offset, offset + word.length, requireNotNull(wordBounds[index]))
                    offset += word.length + 1
                }
            } else if (region.optString("role") != "image" &&
                !region.array("reviewReasons").toString().contains("pageLevelRasterMapping")) {
                readBounds(region.optJSONObject("bounds"))?.let {
                    spans += Span(start, cursor, it)
                }
            }
        }
        return spans
    }

    fun readBounds(value: JSONObject?): InkBounds? {
        if (value == null) return null
        val coordinates = listOf("left", "top", "right", "bottom").map {
            value.optDouble(it, Double.NaN).toFloat()
        }
        if (coordinates.any { !it.isFinite() }) return null
        return InkBounds(coordinates[0], coordinates[1], coordinates[2], coordinates[3])
            .takeIf { it.width > 0f && it.height > 0f }
    }
}
