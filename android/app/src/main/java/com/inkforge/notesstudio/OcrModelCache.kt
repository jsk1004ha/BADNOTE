package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject

/** Disposable candidate values only. All source bindings are rebuilt from the current input plan. */
internal object OcrModelCache {
    fun encode(models: List<RecognitionService.ModelResult>): JSONObject =
        json("models" to JSONArray(models.map(::encodeModel)))

    fun decode(cached: JSONObject, plan: OcrRecognitionPlan): List<RecognitionService.ModelResult> =
        cached.array("models").objects().map { decodeModel(it, plan.regions) }

    private fun encodeModel(model: RecognitionService.ModelResult): JSONObject = json(
        "languageTag" to model.languageTag, "candidateKind" to model.candidateKind,
        "candidates" to JSONArray(model.candidates), "reviewReasons" to JSONArray(model.reviewReasons),
        "words" to JSONArray(model.words.map { word ->
            json("models" to JSONArray(word.models.map(::encodeModel)))
        }))

    private fun decodeModel(value: JSONObject, regions: List<PageOcrLayout.Region>): RecognitionService.ModelResult {
        val text = value.array("candidates")
        val reasons = value.array("reviewReasons")
        val storedWords = value.array("words").objects()
        val words = if (storedWords.isEmpty()) emptyList() else {
            require(storedWords.size == regions.size) { "cachedWordPlanMismatch" }
            storedWords.mapIndexed { index, word ->
                val current = regions[index]
                RecognitionService.WordResult(current, word.array("models").objects().map {
                    decodeModel(it, listOf(current))
                })
            }
        }
        return RecognitionService.ModelResult(value.getString("languageTag"),
            (0 until text.length()).map { text.getString(it) }, value.optString("candidateKind", "native"), words,
            ((0 until reasons.length()).map { reasons.getString(it) } + regions.flatMap { it.reviewReasons }).distinct())
    }
}
