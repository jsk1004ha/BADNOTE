package com.inkforge.notesstudio

import org.json.JSONObject

/** Pre-29 PorterDuff.MULTIPLY becomes Modulate, an expanded rather than native PDF blend. */
internal object ExportRasterPolicy {
    fun legacyBlend(objects: Iterable<JSONObject>, sdk: Int): Boolean = sdk < 29 && objects.any {
        it.optString("type") == "stroke" && !it.optBoolean("hidden") &&
            (it.optString("brush") == "pencil" || it.optString("brush") == "highlighter") &&
            it.optJSONArray("points")?.length()?.let { size -> size > 0 } == true
    }
}
