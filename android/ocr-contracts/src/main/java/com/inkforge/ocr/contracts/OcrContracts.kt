package com.inkforge.ocr.contracts

enum class SourceKind { NATIVE_TEXT, DIGITAL_INK, PDF_TEXT, RASTER_IMAGE }
enum class ContentKind { TEXT, MATH, DRAWING, MIXED, UNKNOWN }
enum class LayoutRole { TITLE, BODY, MARGIN, TABLE_CELL, DISPLAY_MATH, INLINE_MATH, UNKNOWN }

/** Native rank only orders candidates from this one request/hypothesis/model. */
data class RecognitionCandidate(val rawText: String, val engineId: String, val languageTag: String,
                                val requestId: String, val hypothesisId: String, val nativeRank: Int,
                                val score: Double? = null) {
    init { require(nativeRank >= 0); require(score == null || score.isFinite()) }
}

/** Text model availability says nothing about a two-dimensional mathematics backend. */
interface MathRecognitionEngine { val availability: MathAvailability }
enum class MathAvailability { READY, UNAVAILABLE }
class MathRecognitionUnavailable : MathRecognitionEngine {
    override val availability = MathAvailability.UNAVAILABLE
    companion object { const val ERROR_CODE = "mathEngineUnavailable" }
}
