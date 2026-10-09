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
    val executor = Executors.newSingleThreadExecutor()
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

    fun modelTags(policy: String): List<String> = when (policy) {
        "en", "en-primary" -> listOf("en-US")
        "mixed-review" -> listOf("ko", "en-US")
        "ja" -> listOf("ja")
        "zh" -> listOf("zh-Hans")
        "pt" -> listOf("pt-BR")
        else -> listOf("ko")
    }

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
    fun ensureModels(policy: String) = synchronized(operationLock) {
        modelTags(policy).forEach { language -> ensureDownloaded(language, model(language)) }
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

    data class ModelResult(val languageTag: String, val candidates: List<String>)

    fun recognizeRegion(region: PageOcrLayout.Region, policy: String, preContext: String = ""): List<ModelResult> = synchronized(operationLock) {
        modelLimit = if (lowMemory || policy != "mixed-review") 1 else 2
        val pointCount = region.strokes.sumOf { it.pointCount.toLong() }
        require(region.strokes.size <= OcrInput.HARD_REGION_STROKES && pointCount <= OcrInput.HARD_REGION_POINTS) { "inputTooLarge" }
        var bytes = 0L
        for (source in region.strokes) {
            bytes += source.source.toString().toByteArray(Charsets.UTF_8).size
            require(bytes <= OcrInput.HARD_REGION_BYTES) { "inputTooLarge" }
        }
        require(region.strokes.size <= OcrInput.SOFT_REGION_STROKES && pointCount <= OcrInput.SOFT_REGION_POINTS && bytes <= OcrInput.SOFT_REGION_BYTES) { "inputTooLarge" }
        val ink = Ink.builder()
        for (source in region.strokes) {
            val stroke = Ink.Stroke.builder()
            val points = source.source.optJSONArray("points") ?: throw IllegalArgumentException("invalidInput")
            for (i in 0 until points.length()) {
                val point = requireNotNull(points.optJSONObject(i)) { "invalidInput" }
                val x = OcrInput.number(point, "x").toFloat() - region.bounds.left
                val y = OcrInput.number(point, "y").toFloat() - region.bounds.top
                if (region.timeMode == "relative") stroke.addPoint(Ink.Point.create(x, y, point.getLong("t") - region.timeOrigin))
                else stroke.addPoint(Ink.Point.create(x, y))
            }
            ink.addStroke(stroke.build())
        }
        val context = RecognitionContext.builder().setWritingArea(
            WritingArea(region.bounds.width.coerceAtLeast(1f), region.bounds.height.coerceAtLeast(1f)))
            .setPreContext(preContext).build()
        val tags = modelTags(policy)
        tags.map { tag ->
            val client = recognizer(tag)
            ModelResult(tag, infer(25, { client.recognize(ink.build(), context) }).candidates.take(5).map { it.text })
        }
    }

    /** Existing equation action uses the same ink preparation without a competing recognizer. */
    fun handwriting(objects: List<JSONObject>, width: Float, height: Float, language: String = "ko", math: Boolean = false): String {
        val layout = PageOcrLayout.analyze(objects, width, height)
        require(layout.regions.isNotEmpty()) { "인식할 필기가 없습니다." }
        return layout.regions.joinToString("\n") { region ->
            recognizeRegion(region, if (math) "en-primary" else language).first().candidates.firstOrNull().orEmpty()
        }
    }

    fun image(bitmap: Bitmap, language: String): String {
        val client = if (language == "ko") TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            else TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return infer(30, { client.process(InputImage.fromBitmap(bitmap, 0)) }, { client.close() }).text
    }

    override fun close() {
        closing.set(true)
        releaseRequested.set(true)
        queueMaintenance()
        executor.shutdown()
    }
}
