package com.inkforge.notesstudio

/** Only effective recognition settings participate; display theme and zoom do not. */
data class OcrPipeline(val layoutVersion: Int = if (BuildConfig.OCR_LAYOUT_V2) 2 else 1,
                       val layoutConfigVersion: Int = 4,
                       val routerVersion: Int = if (BuildConfig.OCR_MIXED_SOURCES) 2 else 1,
                       val candidatePolicyVersion: Int = 4) {
    init { require(layoutVersion in 1..2) }
    val fingerprint: String get() = OcrInput.Hasher().apply {
        string("badnote-ocr-pipeline-v4-actual-word-plan")
        int(layoutVersion); int(layoutConfigVersion); int(routerVersion); int(candidatePolicyVersion)
        string(OcrInput.MODEL_EPOCH)
    }.hex()

    companion object { val CURRENT get() = OcrPipeline() }
}
