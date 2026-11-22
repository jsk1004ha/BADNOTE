package com.inkforge.notesstudio

/** Whole native hypotheses only. Native rank is never a confidence shared by two models. */
internal object OcrCandidatePolicy {
    private fun hangul(text: String) = text.any { it in '\uac00'..'\ud7a3' || it in '\u3131'..'\u318e' ||
        it in '\u1100'..'\u11ff' || it in '\ua960'..'\ua97f' || it in '\ud7b0'..'\ud7ff' }
    private fun latin(text: String) = text.any { it in 'a'..'z' || it in 'A'..'Z' }
    private fun mixed(text: String) = hangul(text) && latin(text)

    fun preferred(models: List<RecognitionService.ModelResult>, policy: String): RecognitionService.ModelResult {
        val available = models.filter { it.candidates.firstOrNull()?.isNotBlank() == true }
        require(available.isNotEmpty()) { "emptyCandidates" }
        if (policy == "mixed-review") available.firstOrNull { mixed(it.candidates.first()) }?.let { return it }
        if (policy == "mixed-review" || policy == "ko-primary") {
            val korean = available.firstOrNull { it.languageTag == "ko" }
            val english = available.firstOrNull { it.languageTag == "en-US" }
            // This is script routing, not a confidence comparison: when the Korean model itself
            // emits only Latin letters, use the Latin language model's complete native hypothesis.
            // Native Hangul is retained; no word spelling or expected text is consulted.
            if (korean != null && hangul(korean.candidates.first())) return korean
            if (korean != null && english != null && latin(korean.candidates.first()) &&
                latin(english.candidates.first()) && !hangul(english.candidates.first())) return english
        }
        return available.first()
    }

    fun reviewReasons(models: List<RecognitionService.ModelResult>, policy: String): List<String> {
        val tops = models.mapNotNull { it.candidates.firstOrNull()?.takeIf(String::isNotBlank) }
        return buildList {
            if (tops.distinct().size > 1) add("languageDisagreement")
            if (policy == "mixed-review" && tops.any(::mixed)) add("mixedScriptReview")
            if (models.any { it.candidateKind == "wordComposition" }) add("wordComposition")
            if (models.isNotEmpty() && tops.isNotEmpty() && preferred(models, policy) !== models.first())
                add("scriptRoutedCandidate")
            models.forEach { addAll(it.reviewReasons) }
        }.distinct()
    }
}
