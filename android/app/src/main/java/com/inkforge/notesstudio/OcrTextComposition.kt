package com.inkforge.notesstudio

/** Composition uses only independently recognized spatial words, never fragments cut from a
 * whole-line hypothesis, a dictionary, spelling repair, or cross-model confidence. */
internal object OcrTextComposition {
    const val ENGINE_ID = "mlkit-bilingual-word-composition-v1"
    private const val MAX_CANDIDATES = 5

    fun compose(words: List<RecognitionService.WordResult>, policy: String): RecognitionService.ModelResult {
        require(words.isNotEmpty()) { "emptyCandidates" }
        val selected = words.map { OcrCandidatePolicy.preferred(it.models, policy) }
        val top = selected.map { it.candidates.first() }
        val candidates = LinkedHashSet<String>()
        candidates += top.joinToString(" ")
        // The native top of each spatial word is composed first. Alternatives change one actual
        // word at a time; there is no Cartesian beam search or invented common ranking score.
        for (rank in 0 until MAX_CANDIDATES) for (index in words.indices) {
            if (candidates.size >= MAX_CANDIDATES) break
            val primary = selected[index]
            val models = words[index].models
            for (modelIndex in 0..models.size) {
                val model = if (modelIndex == 0) primary else models[modelIndex - 1]
                if (modelIndex > 0 && model === primary) continue
                val alternative = model.candidates.getOrNull(rank)?.takeIf(String::isNotBlank) ?: continue
                if (alternative == top[index]) continue
                val values = top.toMutableList()
                values[index] = alternative
                candidates += values.joinToString(" ")
                if (candidates.size >= MAX_CANDIDATES) break
            }
        }
        return RecognitionService.ModelResult("mul", candidates.toList(), "wordComposition", words,
            words.flatMap { it.region.reviewReasons + it.models.flatMap { model -> model.reviewReasons } }.distinct())
    }
}
