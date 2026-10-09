package com.inkforge.notesstudio

import android.content.Context
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit debug-only replay. Input and output remain in the debug app's private files. */
object OcrReplayChecks {
    private val safeName = Regex("[A-Za-z0-9_.-]{1,160}")
    private val paths = setOf("B0-manual", "B0-auto", "B1", "B2", "B3")
    private data class Measured(val result: JSONObject, val totalMs: Long,
                                val warmupPerformed: Boolean = false,
                                val warmupStatus: String = "notRequested", val warmupMs: Long = 0)

    fun run(context: Context, arguments: Bundle): String {
        require(context.packageName.endsWith(".debug")) { "replayRequiresDebugApp" }
        val name = requireNotNull(arguments.getString("ocrInput")) { "ocrInputRequired" }
        require(safeName.matches(name)) { "invalidReplayInputName" }
        val path = arguments.getString("ocrPath") ?: "B2"
        require(path in paths) { "invalidReplayPath" }
        val feature = arguments.getString("ocrFeature") ?: "policy"
        require(path != "B3" || feature in setOf("policy", "cache")) { "unsupportedReplayFeature" }
        val input = File(File(context.filesDir, "ocr-replay"), name)
        require(input.isFile && input.length() > 0 && input.length() <= 64L * 1024 * 1024) { "replayInputMissingOrTooLarge" }
        val started = SystemClock.elapsedRealtimeNanos()
        val source = JSONObject(input.readText(Charsets.UTF_8))
        val page = NotePage.fromBorrowed(source.optJSONObject("sourcePage") ?: source)
        val provenance = source.optJSONObject("provenance") ?: JSONObject()
        require(provenance.optString("kind") in setOf("real", "synthetic")) { "provenanceRequired" }
        val sampleId = source.optString("sampleId", page.id)
        val policy = when (path) {
            "B3" -> if (feature == "cache") arguments.getString("ocrPolicy") ?: "ko-primary"
                else arguments.getString("ocrPolicy") ?: "mixed-review"
            else -> arguments.getString("ocrPolicy") ?: "ko-primary"
        }
        val digest = OcrInput.pageDigest(page, policy)
        val snapshotMs = elapsedMs(started)
        val warmupRequested = arguments.getString("ocrWarmup") == "true" || path == "B3" && feature == "cache"
        val measured: Measured
        val execution = json("path" to path, "feature" to if (path == "B3") feature else "none",
            "policy" to policy, "context" to "none", "compression" to "none",
            "baseline" to if (path.startsWith("B0")) "historical-4.0.1-required" else "current-debug",
            "processId" to Process.myPid())
        when (path) {
            "B0-manual", "B0-auto" -> measured = Measured(json("status" to "failed", "errorCode" to "baselineUnavailable",
                "regions" to JSONArray(), "readingOrder" to JSONArray()), 0)
            "B3" -> measured = runCoordinator(context, page, policy, warmupRequested, feature == "cache")
            else -> {
                if (path == "B1" && (source.optJSONArray("truthRegions")?.length() ?: 0) == 0) {
                    measured = Measured(json("status" to "failed", "errorCode" to "truthRegionsRequired",
                        "regions" to JSONArray(), "readingOrder" to JSONArray()), 0)
                } else {
                    val recognition = RecognitionService()
                    try {
                        fun one(): Measured {
                            val passStart = SystemClock.elapsedRealtimeNanos()
                            val layout = PageOcrLayout.analyze(page.objects, page.width, page.height)
                            val layoutMs = elapsedMs(passStart)
                            val regions = if (path == "B1") truthRegions(source, layout)
                                else layout.regions.flatMap { PageOcrLayout.splitForBudget(it) ?: listOf(it) }
                            val result = runRegions(recognition, regions, layout, policy, digest, layoutMs,
                                if (path == "B1") source.optJSONArray("truthRegions") else null)
                            val totalMs = elapsedMs(passStart)
                            result.optJSONObject("measurements")?.put("totalMs", totalMs)
                            return Measured(result, totalMs)
                        }
                        if (warmupRequested) {
                            val warm = one()
                            val status = warm.result.optString("status", "failed")
                            measured = if (status == "complete") one().copy(warmupPerformed = true,
                                warmupStatus = status, warmupMs = warm.totalMs)
                            else Measured(json("status" to "failed", "errorCode" to "warmupFailed:$status",
                                "regions" to JSONArray(), "readingOrder" to JSONArray()), 0,
                                true, status, warm.totalMs)
                        } else measured = one()
                    } finally { recognition.close() }
                }
            }
        }
        val scope = when (path) { "B3" -> "coordinatorRequestIncludingPersistence"
            "B1", "B2" -> "layoutAndRecognition"; else -> "baselineUnavailable" }
        execution.put("warmupPerformed", measured.warmupPerformed).put("warmupStatus", measured.warmupStatus)
            .put("warmupMs", measured.warmupMs).put("measurementScope", scope)
            .put("cacheMode", if(path=="B3")if(feature=="cache")"retained" else if(warmupRequested)"modelOnly" else "cold" else "none")
            .put("regionCacheHits", measured.result.array("regions").objects().count{it.optString("status")=="cached"})
        val measurements = json("snapshotMs" to snapshotMs, "totalMs" to measured.totalMs,
            "replayTotalMs" to elapsedMs(started),
            "layoutMs" to measured.result.optJSONObject("measurements")?.optLong("layoutMs", -1)?.takeIf { it >= 0 },
            "recognizeMs" to measured.result.optJSONObject("measurements")?.optLong("recognizeMs", -1)?.takeIf { it >= 0 },
            "modelWaitMs" to JSONObject.NULL, "nativeParseMs" to JSONObject.NULL,
            "deviceKind" to "emulator-or-device-unverified")
        val report = json("schemaVersion" to 1, "sampleId" to sampleId,
            "documentId" to measured.result.optString("documentId"), "pageId" to page.id,
            "pageDigest" to digest, "provenance" to provenance,
            "truthRegions" to (source.optJSONArray("truthRegions") ?: JSONArray()),
            "result" to measured.result, "measurements" to measurements, "execution" to execution)
        val outputDir = File(context.filesDir, "ocr-replay-results").apply { mkdirs() }
        val output = File(outputDir, UUID.randomUUID().toString() + ".json")
        FileOutputStream(output).use { stream ->
            stream.write(report.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync()
        }
        return output.relativeTo(context.filesDir).invariantSeparatorsPath
    }

    private fun truthRegions(source: JSONObject, layout: PageOcrLayout.Result): List<PageOcrLayout.Region> {
        val truth = source.optJSONArray("truthRegions") ?: throw IllegalArgumentException("truthRegionsRequired")
        require(truth.length() > 0) { "truthRegionsRequired" }
        val strokes = (layout.regions.flatMap { it.strokes } + layout.unresolved + layout.nonTextPreserved).associateBy { it.id }
        val used = HashSet<String>()
        return (0 until truth.length()).mapNotNull { index ->
            val region = truth.getJSONObject(index)
            if (region.optString("role") != "text") return@mapNotNull null
            val ids = region.optJSONArray("strokeIds") ?: throw IllegalArgumentException("truthStrokeIdsRequired")
            require(ids.length() > 0) { "emptyTruthTextRegion" }
            val members = (0 until ids.length()).map { position ->
                val id = ids.getString(position)
                require(used.add(id)) { "duplicateTruthStroke" }
                requireNotNull(strokes[id]) { "missingTruthStroke" }
            }
            PageOcrLayout.knownRegion(region.optString("id", "truth:$index"), "text", members,
                region.optInt("order", index))
        }.sortedBy { it.order }
    }

    private fun runRegions(recognition: RecognitionService, regions: List<PageOcrLayout.Region>,
                           layout: PageOcrLayout.Result, policy: String, digest: String,
                           layoutMs: Long, truth: JSONArray?): JSONObject {
        val start = SystemClock.elapsedRealtimeNanos()
        val outputs = JSONArray()
        val readingOrder = JSONArray()
        var failed = 0
        regions.forEach { region ->
            val output = json("id" to region.id, "strokeIds" to JSONArray(region.strokes.map { it.id }),
                "order" to region.order, "role" to region.role, "timeMode" to region.timeMode)
            try {
                val candidates = recognition.recognizeRegion(region, policy).first().candidates
                val raw = candidates.firstOrNull().orEmpty()
                output.put("status", "complete").put("rawText", raw).put("selectedText", raw)
                    .put("candidates", JSONArray(candidates.mapIndexed { rank, text ->
                        json("rank" to rank, "text" to text, "confidence" to JSONObject.NULL)
                    }))
            } catch (error: Exception) {
                failed++
                output.put("status", if (error.message == "inputTooLarge") "unresolved" else "failed")
                    .put("errorCode", error.message ?: "recognitionFailed")
            }
            outputs.put(output); readingOrder.put(region.id)
        }
        val selectedIds = regions.flatMap { it.strokes.map(PageOcrLayout.Stroke::id) }.toSet()
        val unresolved = if (truth == null) layout.unresolved.filter { it.id !in selectedIds } else emptyList()
        unresolved.forEach { stroke -> outputs.put(json("id" to "unresolved:${stroke.id}",
            "strokeIds" to JSONArray(listOf(stroke.id)), "order" to outputs.length(), "status" to "unresolved")) }
        val nonText = if (truth == null) layout.nonTextPreserved.map { it.id } else buildList {
            for (i in 0 until truth.length()) { val entry = truth.getJSONObject(i)
                if (entry.optString("role") == "nonText") { val ids = entry.optJSONArray("strokeIds") ?: JSONArray()
                    for (j in 0 until ids.length()) add(ids.getString(j)) } }
        }
        var unreadableCount = 0
        if (truth != null) for (i in 0 until truth.length()) { val entry = truth.getJSONObject(i)
            if (entry.optString("role") == "unreadable") { val id = entry.optString("id", "unreadable:$i")
                outputs.put(json("id" to id, "strokeIds" to (entry.optJSONArray("strokeIds") ?: JSONArray()),
                    "order" to entry.optInt("order", i), "status" to "unresolved")); readingOrder.put(id); unreadableCount++ } }
        return json("status" to if (failed == 0 && unresolved.isEmpty() && unreadableCount == 0 && layout.invalidInput.isEmpty()) "complete" else "partial",
            "pageDigest" to digest, "regions" to outputs, "readingOrder" to readingOrder,
            "nonTextPreserved" to JSONArray(nonText),
            "invalidInput" to JSONArray(layout.invalidInput.map { json("id" to it.id, "reason" to it.reason) }),
            "measurements" to json("layoutMs" to layoutMs, "recognizeMs" to elapsedMs(start)))
    }

    private fun runCoordinator(context: Context, page: NotePage, policy: String,
                               warmup: Boolean, retainCache: Boolean): Measured {
        val databaseName = "ocr-replay-" + UUID.randomUUID() + ".db"
        try {
            NoteRepository(context, databaseName).use { repository ->
                val docId = "ocr-replay-" + UUID.randomUUID()
                val doc = DocumentInfo(docId, json("id" to docId, "title" to "OCR replay", "version" to 5))
                repository.transaction { repository.putDocument(doc); repository.putPage(docId, page, 0) }
                val recognition = RecognitionService()
                val coordinator = PageOcrCoordinator(repository, recognition)
                try {
                    fun one(): Pair<PageOcrCoordinator.Receipt, Long> {
                        val passStart = SystemClock.elapsedRealtimeNanos()
                        val done = CountDownLatch(1)
                        val receipt = AtomicReference<PageOcrCoordinator.Receipt>()
                        coordinator.request(docId, page.id, policy, false, complete = { receipt.set(it); done.countDown() })
                        require(done.await(240, TimeUnit.SECONDS)) { "replayTimeout" }
                        return requireNotNull(receipt.get()) to elapsedMs(passStart)
                    }
                    val warmStart = SystemClock.elapsedRealtimeNanos()
                    val warmStatus = when {
                        !warmup -> "notRequested"
                        retainCache -> { val receipt = one().first
                            if (receipt.applied) receipt.result?.optString("status") ?: "failed"
                            else receipt.errorCode ?: "failed" }
                        else -> try {
                            val layout = PageOcrLayout.analyze(page.objects, page.width, page.height)
                            val first = layout.regions.flatMap { PageOcrLayout.splitForBudget(it) ?: listOf(it) }.firstOrNull()
                                ?: throw IllegalArgumentException("noWarmupInk")
                            recognition.recognizeRegion(first, policy)
                            "complete"
                        } catch (error: Exception) { error.message ?: "warmupFailed" }
                    }
                    val warmMs = if (warmup) elapsedMs(warmStart) else 0
                    if (warmup && warmStatus != "complete")
                        return Measured(json("status" to "failed", "errorCode" to "warmupFailed:$warmStatus",
                            "regions" to JSONArray(), "readingOrder" to JSONArray()).put("documentId", docId),
                            0, true, warmStatus, warmMs)
                    val (receipt, totalMs) = one()
                    val result = receipt.result ?: json("status" to "failed", "regions" to JSONArray(),
                        "readingOrder" to JSONArray(), "errorCode" to (receipt.errorCode ?: "recognitionFailed"))
                    result.put("documentId", docId).put("cacheWarmupStatus", warmStatus)
                    val details = result.optJSONObject("measurements") ?: JSONObject().also { result.put("measurements", it) }
                    details.put("totalMs", totalMs)
                    return Measured(result, totalMs, warmup, warmStatus, warmMs)
                } finally { coordinator.close(); recognition.close() }
            }
        } finally { context.deleteDatabase(databaseName) }
    }

    private fun elapsedMs(startNanos: Long) = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000
}
