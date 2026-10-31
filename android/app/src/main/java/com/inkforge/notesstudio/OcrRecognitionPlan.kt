package com.inkforge.notesstudio

/** One immutable description of the actual native inputs, shared by execution and cache identity. */
internal class OcrRecognitionPlan private constructor(val source: PageOcrLayout.Region, val policy: String,
                                                     val regions: List<PageOcrLayout.Region>,
                                                     val languageTags: List<String>, val preContext: String) {
    val reviewReasons: List<String> = (source.reviewReasons + regions.flatMap { it.reviewReasons }).distinct()

    fun inputDigest(): String = OcrInput.textInputDigest(regions, languageTags, policy, preContext)

    companion object {
        fun create(region: PageOcrLayout.Region, policy: String, preContext: String = ""): OcrRecognitionPlan {
            val tags = languageTags(policy)
            val regions = if (tags.size > 1) OcrWordLayout.split(region) else listOf(region)
            return OcrRecognitionPlan(region, policy, regions, tags, preContext)
        }

        fun languageTags(policy: String): List<String> = when (policy) {
            "en" -> listOf("en-US")
            "en-primary" -> listOf("en-US", "ko")
            "ko-primary", "mixed-review" -> listOf("ko", "en-US")
            "ja" -> listOf("ja")
            "zh" -> listOf("zh-Hans")
            "pt" -> listOf("pt-BR")
            else -> listOf("ko")
        }
    }
}
