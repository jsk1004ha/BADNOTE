package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** Manual conversion and idle indexing use the same bounded page queue. */
class PageOcrCoordinator(private val repository: NoteRepository, private val recognition: RecognitionService) {
    data class Receipt(val applied: Boolean, val result: JSONObject?, val errorCode: String? = null)
    private val queue = OcrRequestQueue(recognition.executor, ::process)
    init { recognition.onModelInvalidated = { language -> repository.invalidateModelGeneration(language); Unit } }

    fun request(documentId: String, pageId: String, policy: String, automatic: Boolean,
                expectedDigest: String? = null,
                progress: (String, Int, Int) -> Unit = { _, _, _ -> },
                complete: (Receipt) -> Unit) = queue.request(documentId, pageId, policy, automatic, expectedDigest, progress, complete)
    fun cancel(pageId: String) = queue.cancel(pageId)
    fun setActive(value: Boolean) = queue.setActive(value)
    fun close() = queue.close()

    private fun process(request: OcrRequestQueue.Request): Receipt {
        if (!queue.current(request)) return Receipt(false, null, "cancelled")
        val page = repository.page(request.pageId) ?: return Receipt(false, null, "pageMissing")
        val imageSource = page.meta.has("backgroundImage") || page.meta.has("pdfSource") ||
            page.objects.any { it.optString("type") == "image" }
        if (page.objects.none { it.optString("type") == "stroke" } && !imageSource) return Receipt(false, null, "noInk")
        val generation = repository.generation(request.pageId)
        val digest = OcrInput.pageDigest(page, request.policy)
        if (request.expectedDigest != null && digest != request.expectedDigest)
            return Receipt(false, null, "stalePage")
        val started = System.nanoTime()
        val layout = PageOcrLayout.analyze(page.objects, page.width, page.height)
        if (layout.regions.isEmpty() && layout.unresolved.isEmpty() && layout.invalidInput.isEmpty() &&
            layout.nonTextPreserved.isEmpty() && !imageSource)
            return Receipt(false, null, "noInk")
        val layoutMs = (System.nanoTime() - started) / 1_000_000
        val recognitionRegions = layout.regions.flatMap { PageOcrLayout.splitForBudget(it) ?: listOf(it) }
        val modelTags = if (recognitionRegions.isEmpty()) emptyList() else recognition.modelTags(request.policy)
        if (modelTags.isNotEmpty()) recognition.ensureModels(request.policy)
        val modelGeneration = modelTags.joinToString("|") { "$it:${repository.modelGeneration(it)}" }
        if (request.automatic && repository.ocrResult(request.pageId)?.let {
                it.optString("policy") == request.policy && it.optString("modelGeneration") == modelGeneration
            } == true) return Receipt(false, null, "alreadyIndexed")
        val regions = JSONArray()
        val readingOrder = JSONArray()
        var failed = 0
        if (layout.regions.isEmpty() && layout.unresolved.isEmpty() && imageSource) {
            queue.progress(request, "image", 0, 1)
            try {
                val bitmap = StreamingPdf.render(repository, page, 1400)
                val imageLanguage = if (request.policy == "en-primary") "en" else if (request.policy == "mixed-review") "ko" else request.policy.substringBefore('-')
                val (imageDigest, text) = try {
                    OcrInput.imageKey(page, request.policy, bitmap) to recognition.image(bitmap, imageLanguage)
                } finally { bitmap.recycle() }
                regions.put(json("id" to "image", "strokeIds" to JSONArray(), "order" to 0,
                    "role" to "image", "status" to "complete", "rawText" to text, "selectedText" to text,
                    "inputDigest" to imageDigest,
                    "candidates" to JSONArray(listOf(json("rank" to 0, "text" to text, "confidence" to JSONObject.NULL)))))
                readingOrder.put("image")
            } catch (error: Exception) {
                failed++
                regions.put(json("id" to "image", "strokeIds" to JSONArray(), "order" to 0,
                    "role" to "image", "status" to "failed", "errorCode" to (error.message ?: "imageOcrFailed")))
            }
        } else {
            recognitionRegions.forEachIndexed { index, region ->
                if (!queue.current(request)) return Receipt(false, null, "cancelled")
                queue.progress(request, "recognizing", index, recognitionRegions.size)
                val ids = JSONArray(region.strokes.map { it.id })
                val bounds = json("left" to region.bounds.left, "top" to region.bounds.top,
                    "right" to region.bounds.right, "bottom" to region.bounds.bottom)
                val output = json("id" to region.id, "strokeIds" to ids, "order" to region.order,
                    "role" to region.role, "bounds" to bounds, "timeMode" to region.timeMode,
                    "reviewReasons" to JSONArray(region.reviewReasons))
                try {
                    val tag = when (request.policy) {
                        "en", "en-primary" -> "en-US"; "ja" -> "ja"; "zh" -> "zh-Hans"; "pt" -> "pt-BR"; else -> "ko"
                    }
                    val writingWidth = region.bounds.width.coerceAtLeast(1f)
                    val writingHeight = region.bounds.height.coerceAtLeast(1f)
                    val inputDigest = OcrInput.regionInputDigest(region, tag, "", writingWidth,
                        writingHeight, region.timeMode)
                    val key = OcrInput.regionKey(region, tag, "", writingWidth,
                        writingHeight, region.timeMode, repository.modelGeneration(tag))
                    output.put("inputDigest", inputDigest)
                    val cached = repository.cachedOcr(key)
                    val models = if (cached == null || request.policy == "mixed-review")
                        recognition.recognizeRegion(region, request.policy) else emptyList()
                    val primary = models.firstOrNull()
                    val texts = primary?.candidates ?: cached?.array("candidates")?.objects()?.map { it.optString("text") }.orEmpty()
                    val selected = texts.firstOrNull().orEmpty()
                    val candidates = JSONArray(texts.mapIndexed { rank, text ->
                        json("rank" to rank, "text" to text, "confidence" to JSONObject.NULL)
                    })
                    val alternate = models.getOrNull(1)
                    if (alternate?.candidates?.firstOrNull()?.let { it != selected } == true)
                        output.array("reviewReasons").put("languageDisagreement")
                    output.put("status", if (cached != null && primary == null) "cached" else "complete")
                        .put("rawText", selected).put("selectedText", selected).put("candidates", candidates)
                        .put("languageTag", primary?.languageTag ?: tag)
                    if (alternate != null) output.put("alternateLanguage", json("languageTag" to alternate.languageTag,
                        "candidates" to JSONArray(alternate.candidates.mapIndexed { rank, text ->
                            json("rank" to rank, "text" to text, "confidence" to JSONObject.NULL)
                        })))
                    if (cached == null && primary != null) repository.cacheOcr(key, json("candidates" to candidates))
                } catch (error: Exception) {
                    failed++
                    val tooLarge = error.message == "inputTooLarge"
                    if (tooLarge) output.array("reviewReasons").put("inputTooLarge")
                    output.put("status", if (tooLarge) "unresolved" else "failed")
                        .put("errorCode", error.message ?: "recognitionFailed")
                }
                regions.put(output); readingOrder.put(region.id)
            }
        }
        layout.unresolved.forEach { stroke ->
            regions.put(json("id" to ("unresolved:" + stroke.id), "strokeIds" to JSONArray(listOf(stroke.id)),
                "order" to regions.length(), "status" to "unresolved",
                "reviewReasons" to JSONArray(listOf("layoutAmbiguous"))))
        }
        val status = if (failed == 0 && layout.unresolved.isEmpty() && layout.invalidInput.isEmpty() &&
            (regions.length() > 0 || layout.nonTextPreserved.isEmpty())) "complete" else "partial"
        val result = json("schemaVersion" to 1, "documentId" to request.documentId, "pageId" to request.pageId,
            "pageDigest" to digest, "generation" to generation, "status" to status,
            "modelGeneration" to modelGeneration,
            "regions" to regions, "readingOrder" to readingOrder,
            "invalidInput" to JSONArray(layout.invalidInput.map { json("id" to it.id, "reason" to it.reason) }),
            "nonTextPreserved" to JSONArray(layout.nonTextPreserved.map { it.id }),
            "layoutAmbiguous" to layout.layoutAmbiguous,
            "measurements" to json("layoutMs" to layoutMs, "totalMs" to ((System.nanoTime() - started) / 1_000_000)))
        if (!queue.current(request)) return Receipt(false, result, "cancelled")
        queue.progress(request, "saving", regions.length(), regions.length())
        if (!queue.current(request)) return Receipt(false, result, "cancelled")
        // Hash the current page outside the queue lock. The transaction below checks its
        // monotonic generation while cancel and commit share one short linearization point.
        val currentPage = repository.page(request.pageId)
        if (currentPage == null || OcrInput.pageDigest(currentPage, request.policy) != digest)
            return Receipt(false, result, "stalePage")
        val applied = queue.applyIfCurrent(request) {
            repository.putOcrResult(request.documentId, request.pageId, generation, digest,
                request.policy, result, digestChecked = true)
        } ?: return Receipt(false, result, "cancelled")
        return Receipt(applied, if (applied) repository.ocrResult(request.pageId) ?: result else result,
            if (applied) null else "stalePage")
    }
}

/** Owns request priority and cancellation while inference retains its separate Task permit. */
internal class OcrRequestQueue(private val worker: Executor,
                               private val process: (Request) -> PageOcrCoordinator.Receipt) {
    data class Request(val documentId: String, val pageId: String, val policy: String,
                       val automatic: Boolean, val token: Long,
                       val expectedDigest: String?,
                       val progress: (String, Int, Int) -> Unit,
                       val complete: (PageOcrCoordinator.Receipt) -> Unit)
    private val lock = Any()
    private val pending = LinkedHashMap<String, Request>()
    private val epochs = HashMap<String, Long>()
    private val sequence = AtomicLong()
    private var draining = false
    private var activeRequest: Request? = null
    private var committedToken: Long? = null
    private var active = true
    private var closed = false
    private fun deliver(request: Request, receipt: PageOcrCoordinator.Receipt) {
        try { request.complete(receipt) } catch (_: Exception) { /* A UI callback cannot strand other requests. */ }
    }
    fun progress(request: Request, stage: String, done: Int, total: Int) {
        try { request.progress(stage, done, total) } catch (_: Exception) { /* Progress is advisory. */ }
    }

    fun request(documentId: String, pageId: String, policy: String, automatic: Boolean,
                progress: (String, Int, Int) -> Unit, complete: (PageOcrCoordinator.Receipt) -> Unit) =
        request(documentId, pageId, policy, automatic, null, progress, complete)

    fun request(documentId: String, pageId: String, policy: String, automatic: Boolean,
                expectedDigest: String?,
                progress: (String, Int, Int) -> Unit, complete: (PageOcrCoordinator.Receipt) -> Unit) {
        var cancelled: Request? = null
        var rejected = false
        synchronized(lock) {
            if (closed || !active) rejected = true
            else if (automatic && pending[pageId]?.automatic == false) rejected = true
            else {
                val token = sequence.incrementAndGet()
                if (!(automatic && activeRequest?.pageId == pageId && activeRequest?.automatic == false))
                    epochs[pageId] = token
                cancelled = pending.put(pageId, Request(documentId, pageId, policy, automatic, token, expectedDigest, progress, complete))
                if (!draining) { draining = true; worker.execute(::drain) }
            }
        }
        cancelled?.let { deliver(it, PageOcrCoordinator.Receipt(false, null, "cancelled")) }
        if (rejected) try { complete(PageOcrCoordinator.Receipt(false, null, if (closed || !active) "inactive" else "cancelled")) }
            catch (_: Exception) { /* Rejected request has no queue state to strand. */ }
    }
    fun cancel(pageId: String) {
        val removed = synchronized(lock) {
            epochs[pageId] = sequence.incrementAndGet()
            pending.remove(pageId)
        }
        removed?.let { deliver(it, PageOcrCoordinator.Receipt(false, null, "cancelled")) }
    }
    fun setActive(value: Boolean) {
        val removed = synchronized(lock) {
            active = value
            if (value) emptyList() else {
                val removed = pending.values.toList(); pending.clear()
                epochs.keys.toList().forEach { epochs[it] = sequence.incrementAndGet() }
                removed
            }
        }
        removed.forEach { deliver(it, PageOcrCoordinator.Receipt(false, null, "cancelled")) }
    }
    fun close() {
        val removed = synchronized(lock) {
            closed = true; active = false
            val removed = pending.values.toList(); pending.clear(); epochs.clear(); removed
        }
        removed.forEach { deliver(it, PageOcrCoordinator.Receipt(false, null, "cancelled")) }
    }
    fun current(request: Request) = synchronized(lock) {
        !closed && active && epochs[request.pageId] == request.token
    }
    /** A cancellation before this boundary prevents persistence; one after it is a later action. */
    fun applyIfCurrent(request: Request, apply: () -> Boolean): Boolean? = synchronized(lock) {
        if (!closed && active && epochs[request.pageId] == request.token) {
            apply().also { if (it) committedToken = request.token }
        } else null
    }
    private fun drain() {
        while (true) {
            val request = synchronized(lock) {
                val next = pending.values.firstOrNull { !it.automatic } ?: pending.values.firstOrNull()
                if (next == null) { draining = false; return }
                pending.remove(next.pageId); activeRequest = next; next
            }
            val receipt = try { process(request) }
                catch (error: Exception) { PageOcrCoordinator.Receipt(false, null, error.message ?: "ocrFailed") }
            val deliverable = synchronized(lock) {
                if (current(request) || (receipt.applied && committedToken == request.token)) receipt
                else PageOcrCoordinator.Receipt(false, null, "cancelled")
            }
            try { deliver(request, deliverable) }
            finally { synchronized(lock) {
                if (activeRequest == request) activeRequest = null
                if (committedToken == request.token) committedToken = null
                pending[request.pageId]?.let { epochs[it.pageId] = it.token }
            } }
        }
    }
}
