package com.inkforge.notesstudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Seeded persistence provenance only: no recognizer or model is constructed or invoked. */
internal object OcrWordDuplicationChecks {
    private const val POLICY = "mixed-review"
    private const val MODEL_GENERATION = "seeded-ko:7|en-US:4"

    fun run(context: Context) {
        val database = "ocr-word-duplication-${UUID.randomUUID()}.db"
        try {
            NoteRepository(context, database).use { repository ->
                checkDuplicate(repository, budgetSplit = false)
                checkDuplicate(repository, budgetSplit = true)
            }
        } finally { context.deleteDatabase(database) }
    }

    private fun checkDuplicate(repository: NoteRepository, budgetSplit: Boolean) {
        val doc = repository.create("Seeded OCR word duplication", "root", "grid")
        val page = requireNotNull(repository.page(repository.pageIds(doc.id).single()))
        val pointCount = if (budgetSplit) 1500 else 2
        repeat(4) { word ->
            repeat(2) { member ->
                val id = "source-word-$word-stroke-$member"
                val captureSeq = word * 2L + 2 - member
                val origin = word * 10_000L + if (member == 0) 4000L else 1000L
                val points = JSONArray()
                repeat(pointCount) { index ->
                    points.put(json("x" to (100 + word * 100 + member * 2 + index % 2 * 10),
                        "y" to (100 + index % 2 * 20), "p" to .4, "t" to (origin + index)))
                }
                page.objects += json("id" to id, "type" to "stroke", "brush" to "fountain",
                    "color" to "#123456", "opacity" to .8, "captureSeq" to captureSeq,
                    "captureSessionId" to "seeded-session-$word", "timeBasis" to "session-monotonic",
                    "points" to points, "sourceExtension" to json("word" to word, "member" to member)).apply {
                    if (member == 1) put("sourceStrokeId", "source-word-$word-stroke-0")
                }
            }
        }
        repository.putPage(doc.id, page, 0)
        val storedPage = requireNotNull(repository.page(page.id))
        val layout = PageOcrLayout.analyze(storedPage.objects, storedPage.width, storedPage.height)
        check(layout.invalidInput.isEmpty() && layout.unresolved.isEmpty())
        val line = layout.regions.single()
        val sourceRegions = requireNotNull(PageOcrLayout.splitForBudget(line))
        check(if (budgetSplit) sourceRegions.size > 1 else sourceRegions.size == 1) {
            "Seeded ink must exercise both ordinary lines and naturally budget-split /part regions"
        }
        val seededRegions = JSONArray(sourceRegions.mapIndexed { index, region ->
            val plan = OcrRecognitionPlan.create(region, POLICY)
            check(plan.regions.size > 1) { "Each seeded outer region must contain multiple real word sources" }
            json("id" to region.id, "strokeIds" to JSONArray(region.strokes.map { it.id }),
                "sourceKind" to "DIGITAL_INK", "contentKind" to "TEXT", "role" to region.role,
                "order" to index, "bounds" to bounds(region), "timeMode" to region.timeMode,
                "timeOrigin" to region.timeOrigin, "sourceIdentityDigest" to OcrInput.sourceIdentityDigest(region),
                "inputDigest" to plan.inputDigest(), "status" to "complete",
                "selectedText" to "seeded outer text $index", "rawText" to "seeded outer text $index",
                "languageTag" to "mixed", "reviewReasons" to JSONArray(region.reviewReasons),
                "candidates" to JSONArray(listOf(json("rank" to 0, "nativeRank" to JSONObject.NULL,
                    "text" to "seeded outer text $index", "rawText" to "seeded outer text $index",
                    "engineId" to "seeded-composition-engine", "candidateKind" to "composed",
                    "languageTag" to "mixed", "requestId" to "seeded-outer-request-$index",
                    "hypothesisId" to region.id, "score" to JSONObject.NULL, "confidence" to JSONObject.NULL))),
                "wordRegions" to JSONArray(plan.regions.mapIndexed { wordIndex, word ->
                    seededWord(word, "$index-$wordIndex")
                }))
        })
        val digest = OcrInput.pageDigest(storedPage, POLICY)
        val seeded = json("schemaVersion" to 1, "documentId" to doc.id, "pageId" to page.id,
            "pageDigest" to digest, "sourceDigest" to OcrInput.pageSourceDigest(storedPage, POLICY),
            "generation" to repository.generation(page.id), "pipelineFingerprint" to OcrPipeline.CURRENT.fingerprint,
            "modelGeneration" to MODEL_GENERATION, "status" to "complete", "regions" to seededRegions,
            "readingOrder" to JSONArray(sourceRegions.map { it.id }))
        check(repository.putOcrResult(doc.id, page.id, repository.generation(page.id), digest, POLICY, seeded))
        sourceRegions.forEachIndexed { index, region ->
            check(repository.correctOcrRegion(page.id, region.id, "outer correction $index"))
        }
        val originalResult = requireNotNull(repository.ocrResult(page.id))
        val originalPageJson = storedPage.json()
        val originalPageState = canonical(originalPageJson)
        val originalResultState = canonical(originalResult)
        val originalGeneration = repository.generation(page.id)
        val modelGenerations = listOf(repository.modelGeneration("ko"), repository.modelGeneration("en-US"))
        val cacheKey = "seeded-word-duplication-$budgetSplit"
        val cached = json("wordRegions" to originalResult.array("regions").getJSONObject(0).array("wordRegions"))
        repository.cacheOcr(cacheKey, cached)
        val cacheState = canonical(cached)

        val duplicate = repository.duplicatePage(doc.id, page.id, 1)
        val clonedPage = requireNotNull(repository.page(duplicate.id))
        val clonedSourcePage = clonedPage.json()
        val clonedResult = requireNotNull(repository.ocrResult(duplicate.id))
        val sourceObjects = originalPageJson.array("objects").objects()
        val clonedObjects = clonedSourcePage.array("objects").objects()
        check(sourceObjects.size == clonedObjects.size)
        val objectIds = sourceObjects.zip(clonedObjects).associate { (source, clone) ->
            source.getString("id") to clone.getString("id")
        }
        check(objectIds.values.toSet().size == sourceObjects.size && objectIds.values.none { it in objectIds.keys }) {
            "Every cloned source object must have a fresh unique ID"
        }
        sourceObjects.zip(clonedObjects).forEach { (source, clone) ->
            val expected = source.copyJson().put("id", objectIds.getValue(source.getString("id")))
            source.optString("sourceStrokeId").takeIf(String::isNotBlank)?.let {
                expected.put("sourceStrokeId", objectIds.getValue(it))
            }
            check(canonical(expected) == canonical(clone)) {
                "Duplication must retain source geometry, timing, capture order and unknown source attributes"
            }
        }
        val clonedStrokes = PageOcrLayout.analyze(clonedObjects, clonedPage.width, clonedPage.height)
            .regions.flatMap { it.strokes }.associateBy { it.id }
        val originalRegions = originalResult.array("regions").objects()
        val clonedRegions = clonedResult.array("regions").objects()
        check(originalRegions.size == clonedRegions.size)
        check(strings(clonedResult.array("readingOrder")) == clonedRegions.map { it.getString("id") })
        originalRegions.zip(clonedRegions).forEachIndexed { index, (source, clone) ->
            val clonedId = clone.getString("id")
            check(clonedId != source.getString("id") &&
                clonedId.removePrefix("line:").substringBefore('/') in objectIds.values)
            check(!budgetSplit || "/part" in clonedId) { "Budget-part identity must bind to the cloned line" }
            check(strings(clone.array("strokeIds")) == strings(source.array("strokeIds")).map(objectIds::getValue))
            val clonedRegion = assertSource(clone, clonedStrokes, clone.getString("role"), clone.getInt("order"))
            check(clone.getString("inputDigest") == OcrRecognitionPlan.create(clonedRegion, POLICY).inputDigest())
            check(clone.getString("inputDigest") != source.getString("inputDigest"))
            check(clone.getString("sourceIdentityDigest") != source.getString("sourceIdentityDigest"))
            check(clone.getString("correctedText") == "outer correction $index") {
                "The attached outer correction must follow the remapped source, including budget parts"
            }
            assertCandidates(source, clone)
            val sourceWords = source.array("wordRegions").objects()
            val clonedWords = clone.array("wordRegions").objects()
            check(sourceWords.size == clonedWords.size && clonedWords.size > 1)
            sourceWords.zip(clonedWords).forEach { (sourceWord, clonedWord) ->
                check(strings(clonedWord.array("strokeIds")) ==
                    strings(sourceWord.array("strokeIds")).map(objectIds::getValue)) {
                    "Word membership and capture ordering must survive duplication"
                }
                val rebuilt = assertSource(clonedWord, clonedStrokes, clonedRegion.role, clonedRegion.order)
                val firstSource = rebuilt.strokes.minBy { it.originalIndex }
                check(clonedWord.getString("id") == "$clonedId/word:${firstSource.id}")
                check(clonedWord.getString("id") != sourceWord.getString("id"))
                check(clonedWord.getString("sourceIdentityDigest") != sourceWord.getString("sourceIdentityDigest"))
                val sourceFields = setOf("id", "strokeIds", "sourceIdentityDigest", "bounds", "timeMode", "timeOrigin", "candidates")
                fun retained(value: JSONObject) = value.copyJson().apply { sourceFields.forEach { remove(it) } }
                check(canonical(retained(sourceWord)) == canonical(retained(clonedWord))) {
                    "Word text, language, review diagnostics and unknown attributes must remain native provenance"
                }
                assertCandidates(sourceWord, clonedWord)
            }
        }
        check(clonedResult.getString("pageId") == duplicate.id && clonedResult.getString("documentId") == doc.id)
        check(clonedResult.getLong("generation") == repository.generation(duplicate.id))
        check(clonedResult.getString("pageDigest") == OcrInput.pageDigest(clonedPage, POLICY))
        check(clonedResult.getString("sourceDigest") == OcrInput.pageSourceDigest(clonedPage, POLICY))
        check(repository.hasCurrentOcrIndex(duplicate.id, repository.generation(duplicate.id),
            clonedResult.getString("pageDigest"), POLICY, MODEL_GENERATION))
        check(canonical(requireNotNull(repository.page(page.id)).json()) == originalPageState)
        check(canonical(requireNotNull(repository.ocrResult(page.id))) == originalResultState) {
            "Duplicating OCR must not change the source page, its words, candidates or corrections"
        }
        check(repository.generation(page.id) == originalGeneration)
        check(listOf(repository.modelGeneration("ko"), repository.modelGeneration("en-US")) == modelGenerations)
        check(canonical(requireNotNull(repository.cachedOcr(cacheKey))) == cacheState) {
            "Duplication must not rewrite candidates in the disposable recognition cache"
        }
        repository.deleteDocument(doc.id)
    }

    private fun seededWord(region: PageOcrLayout.Region, label: String): JSONObject {
        fun candidate(rank: Int, nativeRank: Int, text: String, language: String, confidence: Any) =
            json("rank" to rank, "nativeRank" to nativeRank, "text" to text, "rawText" to text,
                "engineId" to "seeded-native-engine", "candidateKind" to "native", "languageTag" to language,
                "requestId" to "seeded-native-request-$label-$language", "hypothesisId" to region.id,
                "score" to JSONObject.NULL, "confidence" to confidence)
        return json("id" to region.id, "strokeIds" to JSONArray(region.strokes.map { it.id }),
            "bounds" to bounds(region), "timeMode" to region.timeMode, "timeOrigin" to region.timeOrigin,
            "sourceIdentityDigest" to OcrInput.sourceIdentityDigest(region), "selectedText" to "  한글 $label  ",
            "languageTag" to "ko", "reviewReasons" to JSONArray(region.reviewReasons),
            "nativeExtension" to "retain-$label", "candidates" to JSONArray(listOf(
                candidate(0, 2, "  한글 $label  ", "ko", .87),
                candidate(1, 0, " e\u0301-native $label ", "en-US", JSONObject.NULL),
                candidate(2, 4, "다른 원문 $label", "ko", .12))))
    }

    private fun assertSource(value: JSONObject, strokes: Map<String, PageOcrLayout.Stroke>,
                             role: String, order: Int): PageOcrLayout.Region {
        val ids = strings(value.array("strokeIds"))
        check(ids.isNotEmpty() && ids.distinct().size == ids.size && ids.all { it in strokes }) {
            "Every OCR source ID must resolve exactly once in the cloned sourcePage"
        }
        val rebuilt = PageOcrLayout.knownRegion(value.getString("id"), role, ids.map(strokes::getValue), order)
        check(ids == rebuilt.strokes.map { it.id }) { "Source bindings must retain capture order" }
        check(value.getString("sourceIdentityDigest") == OcrInput.sourceIdentityDigest(rebuilt))
        val actualBounds = value.getJSONObject("bounds")
        val expectedBounds = bounds(rebuilt)
        check(listOf("left", "top", "right", "bottom").all {
            actualBounds.getDouble(it) == expectedBounds.getDouble(it)
        })
        check(value.getString("timeMode") == rebuilt.timeMode && value.getLong("timeOrigin") == rebuilt.timeOrigin)
        return rebuilt
    }

    private fun assertCandidates(source: JSONObject, clone: JSONObject) {
        val sourceCandidates = source.array("candidates").objects()
        val clonedCandidates = clone.array("candidates").objects()
        check(sourceCandidates.isNotEmpty() && sourceCandidates.size == clonedCandidates.size)
        sourceCandidates.zip(clonedCandidates).forEach { (original, copied) ->
            check(copied.getString("hypothesisId") == clone.getString("id"))
            check(copied.getString("hypothesisId") != original.getString("hypothesisId"))
            val expected = original.copyJson().put("hypothesisId", clone.getString("id"))
            check(canonical(expected) == canonical(copied)) {
                "Native text, raw text, language, rank, confidence, request and engine provenance must be untouched"
            }
        }
    }

    private fun bounds(region: PageOcrLayout.Region) = json("left" to region.bounds.left,
        "top" to region.bounds.top, "right" to region.bounds.right, "bottom" to region.bounds.bottom)

    private fun strings(values: JSONArray) = (0 until values.length()).map { values.getString(it) }
    private fun canonical(value: JSONObject) = OcrInput.Hasher().apply { this.value(value) }.hex()
}
