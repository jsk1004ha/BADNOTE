package com.inkforge.notesstudio

import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Opt-in, background-thread-only diagnostics using the real RecognitionService and ML Kit Tasks.
 * Neither entry point asserts recognition accuracy on these synthetic drawings.
 *
 * run() also derives large/dense 1x/4x mathematics from the fixed manifest and exercises
 * OcrMathLayout -> mathInput -> recognizeMathRegion with real English ML Kit Tasks.
 * runTextOnly() omits that extension and retains the legacy handwriting math-unavailable
 * receipt policy. These helpers now require the production math-input API, not beta2 sources.
 */
object OcrQualityChecks {
    const val FULL_RECEIPT = "ocr-quality/receipt-full.json"
    const val TEXT_ONLY_RECEIPT = "ocr-quality/receipt-text-only.json"
    const val FIXTURE_MANIFEST = "ocr-quality/fixtures-v1.json"

    /** The JSON is already saved when this exception reports model, download, or API failures. */
    class Failure(val receipt: JSONObject, val receiptRelativePath: String, cause: Throwable?) :
        IllegalStateException("ocrQualityFailed: ${receipt.optInt("failureCount")} operation(s); receipt=files/$receiptRelativePath", cause)

    fun run(context: Context): JSONObject = runChecks(context, requireMath = true)

    fun runTextOnly(context: Context): JSONObject = runChecks(context, requireMath = false)

    @Suppress("DEPRECATION")
    private fun runChecks(context: Context, requireMath: Boolean): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper()) { "ocrQualityRequiresBackgroundThread" }
        require(context.packageName.endsWith(".debug")) { "ocrQualityRequiresDebugApp" }
        val started = SystemClock.elapsedRealtimeNanos()
        val fixtures = OcrQualityFixtures.all()
        val manifest = json("schemaVersion" to 1, "fixtureVersion" to OcrQualityFixtures.VERSION,
            "warning" to OcrQualityFixtures.WARNING,
            "fixtures" to JSONArray(fixtures.map { it.original() }))
        val fixtureHash = sha256(canonical(manifest))
        val receiptPath = if (requireMath) FULL_RECEIPT else TEXT_ONLY_RECEIPT
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val errors = JSONArray()
        val records = JSONArray()
        val preparations = JSONArray()
        val largeMathRecords = JSONArray()
        val largeMath = json("recipeVersion" to OcrQualityFixtures.LARGE_MATH_RECIPE_VERSION,
            "status" to if (requireMath) "running" else "notRequested",
            "apiPath" to "OcrMathLayout.recognize -> OcrInkGeometry.mathInput -> RecognitionService.recognizeMathRegion -> English ML Kit DigitalInkRecognizer.recognize Task",
            "policy" to "Synthetic results and structure require review; only actual operation/invariant exceptions fail this diagnostic. No truth, candidate-count, or mathematical-equivalence assertion.",
            "sourcePolicy" to "Clone fixed fixture strokes, linearly interpolate an existing stroke beyond 8192 points, scale page/coordinates/width uniformly; never mutate or dump generated original point arrays.",
            "cases" to largeMathRecords)
        val events = JSONArray()
        val report = json("schemaVersion" to 1, "status" to "running",
            "mode" to if (requireMath) "FULL" else "TEXT_ONLY",
            "warning" to OcrQualityFixtures.WARNING,
            "accuracyAssertion" to "none; literal truth comparisons are diagnostic only, not writer accuracy or mathematical equivalence",
            "fixtureVersion" to OcrQualityFixtures.VERSION, "fixtureSha256" to fixtureHash,
            "fixtureHashAlgorithm" to "SHA-256 over UTF-8 recursively key-sorted compact JSON; array order retained",
            "fixtureManifestPath" to "files/$FIXTURE_MANIFEST", "receiptPath" to "files/$receiptPath",
            "mathRequirement" to if (requireMath) "required, including dense actual-model regression"
                else "legacy handwriting mathEngineUnavailable is accepted; dense regression not requested",
            "app" to json("packageName" to context.packageName, "versionName" to packageInfo.versionName,
                "versionCode" to packageInfo.versionCode, "lastUpdateTime" to packageInfo.lastUpdateTime),
            "device" to json("manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
                "sdkInt" to Build.VERSION.SDK_INT, "fingerprint" to Build.FINGERPRINT),
            "engine" to json("service" to "com.inkforge.notesstudio.RecognitionService",
                "textProvider" to "Google ML Kit Digital Ink Recognition",
                "sdkArtifactEpoch" to OcrInput.MODEL_EPOCH,
                "requiredLanguageTags" to JSONArray(listOf("ko", "en-US")),
                "mathLayoutClassPresent" to mathLayoutPresent(context),
                "mathApi" to "RecognitionService.handwriting(objects,width,height,language,math=true)",
                "mathRegionApi" to "RecognitionService.recognizeMathRegion(originalRegion)",
                "remoteModelRevision" to JSONObject.NULL,
                "modelRevisionLimitation" to "ML Kit public model API exposes language identity and installation state, not downloaded model revision; fixed fixture hashes do not freeze remote models"),
            "timing" to json("clock" to "SystemClock.elapsedRealtimeNanos",
                "caseScope" to "production handwriting, math calculator consumption, separate candidate probe and source checks; recognition timing excludes calculator; model preparation reported separately",
                "warmup" to "none requested; earlier cases can warm recognizers; model preparation may download models"),
            "modelPreparations" to preparations, "modelEvents" to events,
            "cases" to records, "largeMathRegression" to largeMath, "errors" to errors)
        save(context, FIXTURE_MANIFEST, manifest)
        var firstError: Throwable? = null
        var stage = "serviceCreation"
        fun failed(scope: String, error: Throwable) {
            if (firstError == null) firstError = error
            errors.put(json("scope" to scope, "error" to errorJson(error)))
        }
        var service: RecognitionService? = null
        try {
            val active = RecognitionService()
            service = active
            active.onModelState = { language, state ->
                events.put(json("languageTag" to language, "state" to state, "stage" to stage,
                    "elapsedMs" to elapsedMs(started)))
            }
            active.onModelInvalidated = { language ->
                events.put(json("languageTag" to language, "state" to "invalidated", "stage" to stage,
                    "elapsedMs" to elapsedMs(started)))
            }
            listOf("ko" to "ko-primary", "en-US" to "en-primary").forEach { (tag, policy) ->
                stage = "prepareModel:$tag"
                val preparation = json("languageTag" to tag, "policy" to policy,
                    "downloadedBefore" to JSONObject.NULL, "downloadedAfter" to JSONObject.NULL,
                    "status" to "running", "error" to JSONObject.NULL)
                val modelStarted = SystemClock.elapsedRealtimeNanos()
                try {
                    val identifier = requireNotNull(DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)) {
                        "requiredModelIdentifierUnavailable:$tag"
                    }
                    val model = DigitalInkRecognitionModel.builder(identifier).build()
                    val manager = RemoteModelManager.getInstance()
                    preparation.put("downloadedBefore", active.infer(60, { manager.isModelDownloaded(model) }))
                    active.ensureModels(policy)
                    val installed = active.infer(60, { manager.isModelDownloaded(model) })
                    preparation.put("downloadedAfter", installed)
                    check(installed) { "requiredModelNotDownloaded:$tag" }
                    preparation.put("status", "ready")
                } catch (error: Throwable) {
                    preparation.put("status", "failed").put("error", errorJson(error))
                    failed(stage, error)
                } finally {
                    preparation.put("elapsedMs", elapsedMs(modelStarted))
                    preparations.put(preparation)
                }
            }
            fixtures.forEach { fixture ->
                val caseStarted = SystemClock.elapsedRealtimeNanos()
                val original = fixture.original()
                val record = json("id" to fixture.id, "truth" to fixture.truth,
                    "canonicalTruth" to fixture.canonicalTruth,
                    "original" to original, "fixtureSha256" to sha256(canonical(original)),
                    "strokeSha256" to sha256(canonical(original.getJSONArray("objects"))),
                    "requiredLanguageTags" to JSONArray(active.modelTags(fixture.policy)))
                val input = copyStrokes(fixture)
                val sourceBefore = canonical(JSONArray(input))
                val recognition = json("api" to "RecognitionService.handwriting",
                    "language" to fixture.language, "math" to fixture.math,
                    "status" to "running", "actualOutput" to JSONObject.NULL,
                    "error" to JSONObject.NULL, "allCandidates" to JSONObject.NULL,
                    "candidateCoverage" to "handwriting returns only a selected String; separate modelProbe contains every candidate exposed by the region service, not alternative structural-math results")
                val recognitionStarted = SystemClock.elapsedRealtimeNanos()
                stage = "${fixture.id}:handwriting"
                try {
                    val output = active.handwriting(input, OcrQualityFixtures.WIDTH,
                        OcrQualityFixtures.HEIGHT, fixture.language, fixture.math)
                    recognition.put("status", "complete").put("actualOutput", output)
                        .put("exactTruthMatch", output == fixture.truth)
                        .put("exactCanonicalTruthMatch", output == fixture.canonicalTruth)
                        .put("whitespaceInsensitiveTruthMatch",
                            output.filterNot(Char::isWhitespace) == fixture.truth.filterNot(Char::isWhitespace))
                    recognition.put("elapsedMs", elapsedMs(recognitionStarted))
                    if (fixture.math) record.put("calculator", calculateRecognized(output) { scope, error ->
                        failed("${fixture.id}:$scope", error)
                    })
                } catch (error: Throwable) {
                    val unavailable = fixture.math && errorChain(error).any { it.message == "mathEngineUnavailable" }
                    val accepted = !requireMath && unavailable
                    recognition.put("status", if (accepted) "unsupported" else "failed")
                        .put("error", errorJson(error)).put("acceptedBaselineLimitation", accepted)
                        .put("exactTruthMatch", JSONObject.NULL)
                        .put("exactCanonicalTruthMatch", JSONObject.NULL)
                        .put("whitespaceInsensitiveTruthMatch", JSONObject.NULL)
                    if (!accepted) failed(stage, error)
                } finally {
                    if (!recognition.has("elapsedMs")) recognition.put("elapsedMs", elapsedMs(recognitionStarted))
                    val unchanged = canonical(JSONArray(input)) == sourceBefore
                    recognition.put("sourceUnchanged", unchanged)
                    if (!unchanged) failed(stage, IllegalStateException("recognitionModifiedSourceInk"))
                    record.put("recognition", recognition)
                }
                stage = "${fixture.id}:modelProbe"
                record.put("modelProbe", probe(active, fixture) { scope, error -> failed(scope, error) })
                record.put("elapsedMs", elapsedMs(caseStarted))
                records.put(record)
            }
            if (requireMath) {
                val failuresBefore = errors.length()
                OcrQualityFixtures.largeMathVariants(fixtures).forEach { variant ->
                    stage = "${variant.id}:largeMath"
                    largeMathRecords.put(largeMathProbe(active, variant) { scope, error -> failed(scope, error) })
                }
                largeMath.put("status", if (errors.length() == failuresBefore) "complete" else "failed")
            }
        } catch (error: Throwable) {
            failed(stage, error)
            if (requireMath) largeMath.put("status", "failed").put("interruptedStage", stage)
        } finally {
            try {
                service?.close()
                report.put("serviceCloseRequested", service != null)
            } catch (error: Throwable) {
                report.put("serviceCloseRequested", false)
                failed("serviceClose", error)
            }
            report.put("status", if (errors.length() == 0) "complete" else "failed")
                .put("caseCount", records.length()).put("failureCount", errors.length())
                .put("largeMathCaseCount", largeMathRecords.length())
                .put("elapsedMs", elapsedMs(started))
            save(context, receiptPath, report)
        }
        if (errors.length() != 0) throw Failure(report, receiptPath, firstError)
        return report
    }

    private fun calculateRecognized(output: String, failed: (String, Throwable) -> Unit): JSONObject {
        val started = SystemClock.elapsedRealtimeNanos()
        val rows = JSONArray()
        var review = false
        var broken = false
        output.lineSequence().filter(String::isNotBlank).forEach { expression ->
            val row = json("expression" to expression)
            val rowStarted = SystemClock.elapsedRealtimeNanos()
            try {
                val result = MathEngine().calculate(expression)
                row.put("status", "complete").put("actualValue", result.value).put("display", result.display())
            } catch (error: Throwable) {
                val infrastructure = errorChain(error).any { it is java.util.regex.PatternSyntaxException || it is LinkageError } ||
                    error !is IllegalArgumentException && error !is IllegalStateException
                if (infrastructure) { broken = true; failed("calculator", error) } else review = true
                row.put("status", if (infrastructure) "failed" else "requiresReview")
                    .put("error", errorJson(error))
            }
            row.put("elapsedMs", elapsedMs(rowStarted))
            rows.put(row)
        }
        return json("api" to "MathEngine.calculate", "status" to if (broken) "failed" else if (review) "requiresReview" else "complete",
            "policy" to "Actual recognized expressions only; unknown variables or invalid expressions require user review, never character substitution.",
            "lines" to rows, "elapsedMs" to elapsedMs(started))
    }

    /** Receipt evidence uses the same production preparer as the real math service call. */
    private fun recognizeMathProbe(service: RecognitionService, region: PageOcrLayout.Region,
                                   record: JSONObject): List<RecognitionService.ModelResult> {
        val prepared = OcrInkGeometry.mathInput(region)
        val withinBudget = prepared.pointCount <= OcrInput.SOFT_REGION_POINTS &&
            prepared.strokes.size <= OcrInput.SOFT_REGION_STROKES &&
            prepared.byteUpperBound <= OcrInput.SOFT_REGION_BYTES
        val originalReferences = prepared.strokes.size == region.strokes.size &&
            prepared.strokes.indices.all { prepared.strokes[it].source.source === region.strokes[it].source }
        record.put("api", "RecognitionService.recognizeMathRegion")
            .put("modelInput", json(
                "preparer" to "OcrInkGeometry.mathInput",
                "evidenceScope" to "Production preparer evaluated on the original callback region immediately before recognizeMathRegion; service prepares the same immutable input again internally, not an SDK interception.",
                "sourcePointCount" to region.strokes.sumOf { it.pointCount.toLong() },
                "sourceStrokeCount" to region.strokes.size,
                "pointCount" to prepared.pointCount, "strokeCount" to prepared.strokes.size,
                "byteUpperBound" to prepared.byteUpperBound,
                "byteScope" to "Production conservative SDK payload upper bound, not source JSON size or a measured SDK allocation.",
                "resampled" to prepared.resampled, "withinSoftBudget" to withinBudget,
                "originalStrokeReferencesRetained" to originalReferences,
                "softBudget" to json("pointCount" to OcrInput.SOFT_REGION_POINTS,
                    "strokeCount" to OcrInput.SOFT_REGION_STROKES, "bytes" to OcrInput.SOFT_REGION_BYTES),
                "writingArea" to json("width" to prepared.space.width, "height" to prepared.space.height,
                    "scale" to prepared.space.scale),
                "strokes" to JSONArray(prepared.strokes.map { stroke ->
                    json("id" to stroke.source.id, "sourcePointCount" to stroke.source.pointCount,
                        "pointCount" to stroke.pointCount, "selectedIndices" to (stroke.indices != null))
                })))
        check(withinBudget) { "qualityMathModelInputExceededSoftBudget" }
        check(originalReferences) { "qualityMathPreparerReplacedOriginalStrokes" }
        check(prepared.pointCount == prepared.strokes.sumOf { it.pointCount }) {
            "qualityMathModelPointCountMismatch"
        }
        record.put("modelServiceCallStarted", true)
        val models = service.recognizeMathRegion(region)
        record.put("modelServiceCallReturned", true)
        check(models.map { it.languageTag } == listOf("en-US")) { "qualityMathEnglishModelResultsMissing" }
        return models
    }

    private fun largeMathProbe(service: RecognitionService, variant: OcrQualityFixtures.MathVariant,
                               failed: (String, Throwable) -> Unit): JSONObject {
        val started = SystemClock.elapsedRealtimeNanos()
        val baseBefore = sha256(canonical(variant.base.original()))
        val input = variant.copyObjects()
        val sourceBefore = sha256(canonical(JSONArray(input)))
        val inputById = input.associateBy { it.getString("id") }
        val callbacks = JSONArray()
        val linesReport = JSONArray()
        val sourcePoints = input.sumOf { it.getJSONArray("points").length().toLong() }
        val output = json("id" to variant.id, "truth" to variant.truth,
            "canonicalTruth" to variant.canonicalTruth, "recipe" to variant.recipe(),
            "baseFixtureSha256" to baseBefore, "sourceSha256Before" to sourceBefore,
            "width" to variant.width, "height" to variant.height,
            "sourcePointCount" to sourcePoints, "sourceStrokeCount" to input.size,
            "sourceExceedsSoftPointBudget" to (sourcePoints > OcrInput.SOFT_REGION_POINTS),
            "sourceStrokes" to JSONArray(input.map { stroke ->
                json("id" to stroke.getString("id"), "pointCount" to stroke.getJSONArray("points").length())
            }), "status" to "running", "structureReviewStatus" to "notCompleted",
            "accuracyAssertion" to "none; exact-label comparisons and every candidate are diagnostic only",
            "modelCallbacks" to callbacks, "lines" to linesReport, "actualOutput" to JSONObject.NULL,
            "error" to JSONObject.NULL)
        var callbackFailures = 0
        var compactedCallbacks = 0
        var lastCallbackError: Throwable? = null
        try {
            check(sourcePoints > OcrInput.SOFT_REGION_POINTS) { "qualityDenseFixtureDidNotExceedSoftBudget" }
            val lines = OcrMathLayout.recognize(input, variant.width, variant.height, recognize = { region ->
                val callbackStarted = SystemClock.elapsedRealtimeNanos()
                val record = json("id" to region.id, "order" to region.order,
                    "strokeIds" to JSONArray(region.strokes.map { it.id }),
                    "timeMode" to region.timeMode, "timeOrigin" to region.timeOrigin,
                    "models" to JSONArray(), "error" to JSONObject.NULL)
                try {
                    val originalReferences = region.strokes.all { inputById[it.id] === it.source }
                    record.put("callbackUsesOriginalSource", originalReferences)
                    check(originalReferences) { "qualityMathLayoutReplacedOriginalSource" }
                    val models = recognizeMathProbe(service, region, record)
                    record.put("models", JSONArray(models.map(::modelReport))).put("status", "complete")
                    if (record.getJSONObject("modelInput").getBoolean("resampled")) compactedCallbacks++
                    models.first().candidates
                } catch (error: Throwable) {
                    callbackFailures++
                    lastCallbackError = error
                    record.put("status", "failed").put("error", errorJson(error))
                    failed("${variant.id}:mathCallback:${region.id}", error)
                    throw error
                } finally {
                    record.put("elapsedMs", elapsedMs(callbackStarted))
                    callbacks.put(record)
                }
            })
            lines.forEach { line ->
                linesReport.put(json("id" to line.region.id, "order" to line.region.order,
                    "strokeIds" to JSONArray(line.region.strokes.map { it.id }),
                    "sourcePointCount" to line.region.strokes.sumOf { it.pointCount.toLong() },
                    "candidates" to JSONArray(line.candidates), "reviewReasons" to JSONArray(line.reviewReasons),
                    "errorCode" to line.errorCode,
                    "structureReviewStatus" to if (line.errorCode != null) "failed" else "requiresReview"))
            }
            check(callbacks.length() > 0) { "qualityDenseInkDidNotReachMathModel" }
            if (callbackFailures == 0) check(compactedCallbacks > 0) { "qualityDenseMathInputWasNotCompacted" }
            val actual = lines.joinToString("\n") { it.candidates.firstOrNull().orEmpty() }
            output.put("status", if (callbackFailures == 0) "complete" else "failed")
                .put("structureReviewStatus", if (callbackFailures == 0) "requiresReview" else "failed")
                .put("actualOutput", actual).put("exactTruthMatch", actual == variant.truth)
                .put("exactCanonicalTruthMatch", actual == variant.canonicalTruth)
                .put("reviewReasons", JSONArray(lines.flatMap { it.reviewReasons }.distinct()))
                .put("linesWithoutCandidates", lines.count { it.candidates.isEmpty() })
        } catch (error: Throwable) {
            output.put("status", "failed").put("structureReviewStatus", "notCompleted")
                .put("error", errorJson(error))
            if (error !== lastCallbackError) failed("${variant.id}:largeMath", error)
        } finally {
            val sourceAfter = sha256(canonical(JSONArray(input)))
            val sourceUnchanged = sourceAfter == sourceBefore
            val baseUnchanged = sha256(canonical(variant.base.original())) == baseBefore
            output.put("sourceSha256After", sourceAfter).put("sourceUnchanged", sourceUnchanged)
                .put("baseFixtureUnchanged", baseUnchanged).put("modelCallbackCount", callbacks.length())
                .put("compactedModelCallbackCount", compactedCallbacks).put("elapsedMs", elapsedMs(started))
            if (!sourceUnchanged || !baseUnchanged) {
                output.put("status", "failed")
                failed("${variant.id}:source", IllegalStateException("recognitionModifiedSourceInk"))
            }
        }
        return output
    }

    private fun modelReport(model: RecognitionService.ModelResult): JSONObject {
        return json("languageTag" to model.languageTag, "candidateKind" to model.candidateKind,
            "reviewReasons" to JSONArray(model.reviewReasons),
            "topCandidate" to (model.candidates.firstOrNull() ?: JSONObject.NULL),
            "candidates" to JSONArray(model.candidates.mapIndexed { rank, text ->
                json("rank" to rank, "nativeRank" to if (model.candidateKind == "native") rank else JSONObject.NULL,
                    "text" to text, "confidence" to JSONObject.NULL)
            }), "wordRegions" to JSONArray(model.words.map { word ->
                val region = word.region
                json("id" to region.id, "strokeIds" to JSONArray(region.strokes.map { it.id }),
                    "timeMode" to region.timeMode, "timeOrigin" to region.timeOrigin,
                    "bounds" to json("left" to region.bounds.left, "top" to region.bounds.top,
                        "right" to region.bounds.right, "bottom" to region.bounds.bottom),
                    "models" to JSONArray(word.models.map(::modelReport)))
            }))
    }

    private fun probe(service: RecognitionService, fixture: OcrQualityFixtures.Fixture,
                      failed: (String, Throwable) -> Unit): JSONObject {
        val started = SystemClock.elapsedRealtimeNanos()
        val input = copyStrokes(fixture)
        val sourceBefore = canonical(JSONArray(input))
        val records = JSONArray()
        val output = json("api" to "RecognitionService.recognizeRegion",
            "status" to "running", "policy" to fixture.policy, "regions" to records,
            "error" to JSONObject.NULL,
            "candidateCoverage" to "all service candidates; native candidates retain SDK ranks, explicit word compositions have no native rank and include each word's raw per-language candidates; no fabricated confidence",
            "scope" to if (fixture.math) "separate per-policy model recognition of complete fixed math ink; NOT structural math alternatives; bounded production-math regression is reported separately in largeMathRegression"
                else "separate recognition passes on inferred text regions; production output above is not reconstructed from these passes")
        try {
            val layout = PageOcrLayout.analyze(input, OcrQualityFixtures.WIDTH, OcrQualityFixtures.HEIGHT)
            output.put("layout", json("invalidInput" to JSONArray(layout.invalidInput.map {
                json("id" to it.id, "reason" to it.reason)
            }), "unresolvedStrokeIds" to JSONArray(layout.unresolved.map { it.id }),
                "nonTextStrokeIds" to JSONArray(layout.nonTextPreserved.map { it.id })))
            check(layout.invalidInput.isEmpty()) { "qualityFixtureRejectedAsInvalidInput" }
            val regions = if (fixture.math) {
                val strokes = (layout.regions.flatMap { it.strokes } + layout.unresolved + layout.nonTextPreserved)
                    .sortedBy { it.originalIndex }
                check(strokes.size == fixture.objects.size) { "qualityFixtureProbeLostStrokes" }
                listOf(PageOcrLayout.knownRegion("raw:${fixture.id}", "text", strokes, 0))
            } else layout.regions
            check(regions.isNotEmpty()) { "qualityFixtureHasNoProbeRegion" }
            var regionErrors = 0
            regions.forEach { region ->
                val regionStarted = SystemClock.elapsedRealtimeNanos()
                val record = json("id" to region.id, "order" to region.order,
                    "strokeIds" to JSONArray(region.strokes.map { it.id }),
                    "timeMode" to region.timeMode, "timeOrigin" to region.timeOrigin,
                    "bounds" to json("left" to region.bounds.left, "top" to region.bounds.top,
                        "right" to region.bounds.right, "bottom" to region.bounds.bottom),
                    "models" to JSONArray(), "error" to JSONObject.NULL)
                try {
                    val models = service.recognizeRegion(region, fixture.policy)
                    val reports = models.map(::modelReport)
                    record.put("models", JSONArray(reports))
                    val words = reports.flatMap { it.array("wordRegions").objects() }
                    val expected = service.modelTags(fixture.policy)
                    if (words.isEmpty()) check(models.map { it.languageTag } == expected) {
                        "requiredModelResultsMissing:${fixture.policy}"
                    } else words.forEach { word ->
                        check(word.array("models").objects().map { it.getString("languageTag") } == expected) {
                            "requiredWordModelResultsMissing:${fixture.policy}:${word.optString("id")}"
                        }
                    }
                    record.put("status", "complete")
                } catch (error: Throwable) {
                    regionErrors++
                    record.put("status", "failed").put("error", errorJson(error))
                    failed("${fixture.id}:modelProbe:${region.id}", error)
                } finally {
                    record.put("elapsedMs", elapsedMs(regionStarted))
                    records.put(record)
                }
            }
            output.put("status", if (regionErrors == 0) "complete" else "failed")
        } catch (error: Throwable) {
            output.put("status", "failed").put("error", errorJson(error))
            failed("${fixture.id}:modelProbe", error)
        } finally {
            val unchanged = canonical(JSONArray(input)) == sourceBefore
            output.put("sourceUnchanged", unchanged).put("elapsedMs", elapsedMs(started))
            if (!unchanged) {
                output.put("status", "failed")
                failed("${fixture.id}:modelProbe", IllegalStateException("recognitionModifiedSourceInk"))
            }
        }
        return output
    }

    private fun copyStrokes(fixture: OcrQualityFixtures.Fixture): List<JSONObject> =
        fixture.objects.map { JSONObject(it.toString()) }

    private fun mathLayoutPresent(context: Context): Boolean = try {
        Class.forName("com.inkforge.notesstudio.OcrMathLayout", false, context.classLoader)
        true
    } catch (_: ClassNotFoundException) { false }

    private fun errorChain(error: Throwable): List<Throwable> =
        generateSequence(error) { it.cause }.take(12).toList()

    private fun errorJson(error: Throwable): JSONObject = json(
        "type" to error.javaClass.name, "message" to error.message,
        "causes" to JSONArray(errorChain(error).map { json("type" to it.javaClass.name, "message" to it.message) }),
        "stackTrace" to error.stackTraceToString()
    )

    private fun elapsedMs(started: Long): Double = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0

    private fun save(context: Context, relativePath: String, value: JSONObject) {
        val destination = File(context.filesDir, relativePath)
        val directory = requireNotNull(destination.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "ocrQualityOutputDirectoryUnavailable" }
        FileOutputStream(destination).use { stream ->
            stream.write(value.toString(2).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
    }

    /** Independent of OcrInput/pipeline versions, so beta2 and upgraded receipts can be compared. */
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.opt(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is Number -> JSONObject.numberToString(value)
        is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun sha256(source: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val number = byte.toInt() and 255
                append(digits[number ushr 4]); append(digits[number and 15])
            }
        }
    }
}
