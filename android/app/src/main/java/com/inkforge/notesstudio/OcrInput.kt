package com.inkforge.notesstudio

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Versioned digests cover actual ordered input, not object IDs or point counts alone. */
object OcrInput {
    const val LAYOUT_VERSION = 1
    const val MODEL_EPOCH = "mlkit-digital-ink-19.0.0"
    const val SOFT_REGION_BYTES = 256 * 1024
    const val SOFT_REGION_POINTS = 8192
    const val SOFT_REGION_STROKES = 512
    const val HARD_REGION_BYTES = 1024 * 1024
    const val HARD_REGION_POINTS = 32768
    const val HARD_REGION_STROKES = 2048

    fun pageDigest(page: NotePage, policy: String): String = hash { h ->
        h.string("badnote-page-ocr-v1"); h.string(MODEL_EPOCH); h.string(pageSourceDigest(page, policy))
    }

    private fun pageSourceDigest(page: NotePage, policy: String): String = hash { h ->
        h.string("badnote-page-source-v1"); h.string(policy)
        h.float(page.width); h.float(page.height)
        h.value(page.meta, setOf("ocrText", "ocrSignature"))
        page.objects.forEachIndexed { index, obj ->
            h.int(index); h.value(obj)
        }
    }

    fun imageKey(page: NotePage, policy: String, rendered: Bitmap): String = hash { h ->
        h.string("badnote-image-input-v1"); h.string(pageSourceDigest(page, policy)); h.bitmap(rendered)
    }

    fun regionKey(region: PageOcrLayout.Region, languageTag: String, preContext: String,
                  writingWidth: Float, writingHeight: Float, timeMode: String,
                  modelGeneration: Long = 0): String = hash { h ->
        h.string("badnote-region-cache-v2"); h.string(MODEL_EPOCH); h.long(modelGeneration)
        h.string(regionInputDigest(region, languageTag, preContext, writingWidth, writingHeight, timeMode))
    }

    fun regionInputDigest(region: PageOcrLayout.Region, languageTag: String, preContext: String,
                          writingWidth: Float, writingHeight: Float, timeMode: String): String = hash { h ->
        h.string("badnote-region-input-v1"); h.int(LAYOUT_VERSION)
        h.int(5); h.string(languageTag); h.string(preContext); h.string(timeMode)
        h.float(writingWidth); h.float(writingHeight)
        h.float(region.bounds.left); h.float(region.bounds.top)
        region.strokes.forEach { stroke ->
            h.string(stroke.id); h.long(stroke.captureSeq ?: -1)
            h.string(stroke.captureSessionId ?: ""); h.string(stroke.timeBasis)
            h.string(stroke.sourceStrokeId ?: ""); h.int(stroke.fragmentOrder)
            val points = stroke.source.optJSONArray("points") ?: JSONArray()
            h.int(points.length())
            for (i in 0 until points.length()) {
                val point = requireNotNull(points.optJSONObject(i)) { "invalidInput:${stroke.id}:pointObject" }
                val x = number(point, "x").toFloat() - region.bounds.left
                val y = number(point, "y").toFloat() - region.bounds.top
                h.float(x); h.float(y)
                h.float(number(point, "p", .5).toFloat())
                h.float(number(point, "tilt", 0.0).toFloat())
                h.float(number(point, "orientation", 0.0).toFloat())
                if (timeMode == "relative") h.long(point.getLong("t") - region.timeOrigin)
            }
        }
    }

    fun number(obj: JSONObject, key: String, default: Double? = null): Double {
        if (!obj.has(key)) return default ?: throw IllegalArgumentException("invalidInput:$key:missing")
        val value = obj.optDouble(key, Double.NaN)
        require(value.isFinite() && value in -10_000_000.0..10_000_000.0) { "invalidInput:$key:nonFiniteOrRange" }
        return value
    }

    private inline fun hash(block: (Hasher) -> Unit): String = Hasher().also(block).hex()

    class Hasher {
        private val digest = MessageDigest.getInstance("SHA-256")
        fun int(value: Int) { digest.update(ByteBuffer.allocate(4).putInt(value).array()) }
        fun long(value: Long) { digest.update(ByteBuffer.allocate(8).putLong(value).array()) }
        fun float(value: Float) { int(java.lang.Float.floatToIntBits(value)) }
        fun bitmap(bitmap: Bitmap) {
            int(bitmap.width); int(bitmap.height)
            val pixels = IntArray(bitmap.width)
            val row = ByteBuffer.allocate(bitmap.width * 4)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
                row.clear(); pixels.forEach { row.putInt(it) }
                digest.update(row.array(), 0, row.position())
            }
        }
        fun string(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); int(bytes.size); digest.update(bytes) }
        fun value(value: Any?, ignored: Set<String> = emptySet()) {
            when (value) {
                null, JSONObject.NULL -> string("null")
                is JSONObject -> {
                    string("object")
                    value.keys().asSequence().filter { it !in ignored }.sorted().forEach { key -> string(key); value(value.opt(key)) }
                    string("end")
                }
                is JSONArray -> { string("array"); int(value.length()); for (i in 0 until value.length()) value(value.opt(i)) }
                is Number -> { string("number"); string(value.toString()) }
                is Boolean -> { string(if (value) "true" else "false") }
                else -> { string("text"); string(value.toString()) }
            }
        }
        fun hex(): String = digest.digest().joinToString("") { "%02x".format(it) }
    }
}
