package com.inkforge.notesstudio

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.digitalink.recognition.*
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** One actual ML Kit Task at a time, including image OCR after an ink timeout. */
class RecognitionService(private val lowMemory: Boolean = false) : Closeable {
    val executor = Executors.newSingleThreadExecutor { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "badnote-ocr")
    }
    private val permit = Semaphore(1)
    private val blocked = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val inactive = AtomicBoolean(false)
    private val releaseRequested = AtomicBoolean(false)
    private val operationLock = Any()
    private val recognizers = LinkedHashMap<String, DigitalInkRecognizer>(2, .75f, true)
    @Volatile private var modelLimit = 1
    var onModelState: (String, String) -> Unit = { _, _ -> }
    var onModelInvalidated: (String) -> Unit = {}

    fun modelTags(policy: String): List<String> = OcrRecognitionPlan.languageTags(policy)

    private fun model(language: String): DigitalInkRecognitionModel {
        val identifier = requireNotNull(DigitalInkRecognitionModelIdentifier.fromLanguageTag(language)) { "unsupportedLanguage" }
        return DigitalInkRecognitionModel.builder(identifier).build()
    }

    private fun ensureDownloaded(language: String, model: DigitalInkRecognitionModel) {
        val manager = RemoteModelManager.getInstance()
        onModelState(language, "checking")
        val downloaded = try { infer(60, { manager.isModelDownloaded(model) }) }
            catch (error: Exception) { onModelState(language, "failed"); throw error }
        if (!downloaded) {
            onModelState(language, "notDownloaded")
            onModelInvalidated(language) // Persist before the download; a crash cannot reuse the old cache.
            synchronized(recognizers) { recognizers.remove(language)?.close() }
            onModelState(language, "downloading")
            try { infer(120, { manager.download(model, DownloadConditions.Builder().build()) }) }
            catch (error: Exception) { onModelState(language, "failed"); throw error }
        }
        onModelState(language, "ready")
    }

    /** Even a cache hit must first verify that its remote model is still installed. */
    fun ensureModels(policy: String, checkCurrent: () -> Unit = {}) = synchronized(operationLock) {
        modelTags(policy).forEach { language -> checkCurrent(); ensureDownloaded(language, model(language)) }
    }

    private fun recognizer(language: String): DigitalInkRecognizer {
        if (inactive.get()) error("recognizerInactive")
        synchronized(recognizers) { recognizers[language]?.let { return it } }
        val model = model(language)
        ensureDownloaded(language, model)
        if (closing.get() || inactive.get()) error(if (closing.get()) "recognizerClosed" else "recognizerInactive")
        val client = DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(model).setMaxResultCount(5).build())
        synchronized(recognizers) {
            if (closing.get() || inactive.get()) {
                client.close(); error(if (closing.get()) "recognizerClosed" else "recognizerInactive")
            }
            recognizers[language]?.let { client.close(); return it }
            recognizers[language] = client
            if (permit.availablePermits() > 0) trimRecognizersLocked(modelLimit)
        }
        return client
    }

    private fun trimRecognizersLocked(limit: Int) {
        while (recognizers.size > limit) {
            val oldest = recognizers.entries.iterator().next()
            oldest.value.close(); recognizers.remove(oldest.key)
        }
    }

    private fun clearRecognizersLocked() {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }

    private fun maintainRecognizers() {
        synchronized(operationLock) {
            if (permit.availablePermits() == 0) return
            synchronized(recognizers) {
                if (closing.get() || inactive.get() || releaseRequested.getAndSet(false)) clearRecognizersLocked()
                else trimRecognizersLocked(modelLimit)
            }
        }
    }

    private fun queueMaintenance() {
        try { executor.execute(::maintainRecognizers) }
        catch (_: RejectedExecutionException) {
            if (closing.get() && permit.availablePermits() > 0) synchronized(recognizers) { clearRecognizersLocked() }
        }
    }

    fun setActive(value: Boolean) {
        inactive.set(!value)
        if (!value) releaseIdle()
    }

    fun releaseIdle() {
        releaseRequested.set(true)
        queueMaintenance()
    }

    /** Check before allocating a raster fallback while a timed-out SDK Task still owns its input. */
    internal fun requireAvailable() {
        if (closing.get() || inactive.get() || blocked.get())
            error(if (closing.get()) "recognizerClosed" else if (inactive.get()) "recognizerInactive" else "recognizerCircuitOpen")
    }

    internal fun <T> infer(timeoutSeconds: Long, start: () -> Task<T>, finished: () -> Unit = {}): T {
        if (closing.get() || inactive.get() || blocked.get()) {
            finished()
            error(if (closing.get()) "recognizerClosed" else if (inactive.get()) "recognizerInactive" else "recognizerCircuitOpen")
        }
        try { permit.acquire() } catch (error: InterruptedException) { finished(); throw error }
        if (closing.get() || inactive.get() || blocked.get()) {
            permit.release(); finished()
            error(if (closing.get()) "recognizerClosed" else if (inactive.get()) "recognizerInactive" else "recognizerCircuitOpen")
        }
        val task = try { start() } catch (error: Exception) { permit.release(); finished(); throw error }
        val timedOut = AtomicBoolean(false)
        task.addOnCompleteListener({ command -> command.run() }) {
            try { finished() } finally {
                if (timedOut.get()) blocked.set(false)
                permit.release()
                queueMaintenance()
            }
        }
        return try { Tasks.await(task, timeoutSeconds, TimeUnit.SECONDS) }
        catch (error: TimeoutException) {
            blocked.set(true); timedOut.set(true)
            if (task.isComplete) blocked.set(false)
            throw IllegalStateException("recognizerTimeout", error)
        }
    }

    data class ModelResult(val languageTag: String, val candidates: List<String>,
                           val candidateKind: String = "native", val words: List<WordResult> = emptyList(),
                           val reviewReasons: List<String> = emptyList())
    data class WordResult(val region: PageOcrLayout.Region, val models: List<ModelResult>)

    fun recognizeRegion(region: PageOcrLayout.Region, policy: String, preContext: String = "",
                        checkCurrent: () -> Unit = {}): List<ModelResult> =
        recognizePlan(OcrRecognitionPlan.create(region, policy, preContext), checkCurrent)

    internal fun recognizePlan(plan: OcrRecognitionPlan, checkCurrent: () -> Unit = {}): List<ModelResult> =
        synchronized(operationLock) {
        val region = plan.source
        val tags = plan.languageTags
        modelLimit = if (lowMemory) 1 else tags.size.coerceAtMost(2)
        val pointCount = region.strokes.sumOf { it.pointCount.toLong() }
        require(region.strokes.size <= OcrInput.HARD_REGION_STROKES && pointCount <= OcrInput.HARD_REGION_POINTS) { "inputTooLarge" }
        var bytes = 0L
        for (source in region.strokes) {
            bytes += source.source.toString().toByteArray(Charsets.UTF_8).size
            require(bytes <= OcrInput.HARD_REGION_BYTES) { "inputTooLarge" }
        }
        require(region.strokes.size <= OcrInput.SOFT_REGION_STROKES && pointCount <= OcrInput.SOFT_REGION_POINTS && bytes <= OcrInput.SOFT_REGION_BYTES) { "inputTooLarge" }
        checkCurrent()
        // Use the very plan that was hashed for the cache, including per-word timing modes.
        val words = plan.regions
        if (words.size > 1) {
            val results = words.mapIndexed { index, word ->
                checkCurrent()
                WordResult(word, recognizeNativeRegion(word, tags, if (index == 0) plan.preContext else "", checkCurrent))
            }
            return@synchronized listOf(OcrTextComposition.compose(results, plan.policy))
        }
        recognizeNativeRegion(words.single(), tags, plan.preContext, checkCurrent)
    }

    /** Math keeps source JSON intact; only the bounded, geometry-preserving payload reaches ML Kit. */
    internal fun recognizeMathRegion(region: PageOcrLayout.Region,
                                     checkCurrent: () -> Unit = {}): List<ModelResult> =
        synchronized(operationLock) {
            val input = OcrInkGeometry.mathInput(region, checkCurrent)
            modelLimit = 1
            recognizeNativeRegion(region, listOf("en-US"), "", checkCurrent, input)
        }

    private fun recognizeNativeRegion(region: PageOcrLayout.Region, tags: List<String>, preContext: String,
                                      checkCurrent: () -> Unit,
                                      prepared: OcrInkGeometry.ModelInput? = null): List<ModelResult> {
        val space = prepared?.space ?: OcrInkGeometry.space(region)
        val ink = Ink.builder()
        region.strokes.forEachIndexed { strokeIndex, source ->
            checkCurrent()
            val stroke = Ink.Stroke.builder()
            val points = source.source.optJSONArray("points") ?: throw IllegalArgumentException("invalidInput")
            val selected = prepared?.strokes?.get(strokeIndex)
            val count = selected?.pointCount ?: points.length()
            for (i in 0 until count) {
                if (i and 255 == 0) checkCurrent()
                val index = selected?.indices?.get(i) ?: i
                val point = requireNotNull(points.optJSONObject(index)) { "invalidInput" }
                val x = space.x(OcrInput.number(point, "x").toFloat())
                val y = space.y(OcrInput.number(point, "y").toFloat())
                if (region.timeMode == "relative") stroke.addPoint(Ink.Point.create(x, y, point.getLong("t") - region.timeOrigin))
                else stroke.addPoint(Ink.Point.create(x, y))
            }
            ink.addStroke(stroke.build())
        }
        val context = RecognitionContext.builder().setWritingArea(WritingArea(space.width, space.height))
            .setPreContext(preContext).build()
        val input = ink.build()
        return tags.map { tag ->
            checkCurrent()
            val client = recognizer(tag)
            ModelResult(tag, infer(25, { client.recognize(input, context) }).candidates.take(5).map { it.text },
                reviewReasons = region.reviewReasons)
        }
    }

    /** Mathematics is an explicitly reviewed spatial subset over real English model text. */
    fun handwriting(objects: List<JSONObject>, width: Float, height: Float, language: String = "ko", math: Boolean = false): String {
        if (math) {
            val lines = OcrMathLayout.recognize(objects, width, height,
                recognize = { recognizeMathRegion(it).first().candidates })
            require(lines.isNotEmpty()) { "noInk" }
            require(lines.all { it.candidates.isNotEmpty() }) {
                lines.firstOrNull { it.errorCode != null }?.errorCode ?: "mathStructureUnresolved"
            }
            return lines.joinToString("\n") { it.candidates.first() }
        }
        val layout = PageOcrLayout.analyze(objects, width, height)
        require(layout.regions.isNotEmpty()) { "noInk" }
        return layout.regions.joinToString("\n") { region ->
            val models = recognizeRegion(region, language)
            OcrCandidatePolicy.preferred(models, language).candidates.first()
        }
    }

    fun image(bitmap: Bitmap, language: String, actualCompletion: () -> Unit = {}): String {
        val client = try {
            if (language == "ko") TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            else TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        } catch (error: Exception) { actualCompletion(); throw error }
        return infer(30, { client.process(InputImage.fromBitmap(bitmap, 0)) }, {
            try { client.close() } finally { actualCompletion() }
        }).text
    }

    override fun close() {
        closing.set(true)
        releaseRequested.set(true)
        queueMaintenance()
        executor.shutdown()
    }
}
