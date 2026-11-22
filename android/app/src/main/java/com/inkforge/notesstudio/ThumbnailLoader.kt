package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.max
import kotlin.math.roundToInt

/** Only live previews own requests. Database snapshots never perform bitmap work on the save queue. */
internal class ThumbnailLoader(
    private val repository: NoteRepository,
    private val afterSaved: (() -> Unit) -> Unit,
    private val render: ((NotePage) -> Bitmap)? = null
) : Closeable {
    private class Binding(val view: NotePreview, val key: String, val documentId: String?, val pageId: String?) {
        var bitmap: Bitmap? = null
    }
    private class Job(val binding: Binding, val generation: Long, val lifetime: Long) {
        @Volatile var cancelled = false
        var rendering = false
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "note-thumbnails")
    }
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        // Published bitmaps can still be held by a View or a display list after eviction.
    }
    private val listeners = LinkedHashMap<String, LinkedHashSet<Binding>>()
    private val generations = HashMap<String, Long>()
    private val failed = HashMap<String, Long>()
    private var lifetime = 0L
    private var active: Job? = null
    private var closed = false

    internal val pendingCount: Int
        get() = listeners.entries.count { (key, views) -> views.all { it.bitmap == null } && cache.get(key) == null && failed[key] != generation(key) } +
            if (active?.cancelled == true) 1 else 0

    fun bind(view: NotePreview, documentId: String?, pageId: String?) {
        require(pageId != null || documentId != null)
        view.onPreviewDetached?.invoke()
        val binding = Binding(view, pageId ?: "doc:$documentId", documentId, pageId)
        view.onPreviewAttached = { attach(binding) }
        view.onPreviewDetached = { detach(binding) }
        view.loadBitmap = {
            if (!closed && view.isAttachedToWindow) attach(binding)
            binding.bitmap ?: cache.get(binding.key)
        }
        if (view.isAttachedToWindow) attach(binding)
    }

    private fun attach(binding: Binding) {
        if (closed) return
        val views = listeners.getOrPut(binding.key) { LinkedHashSet() }
        if (binding.bitmap == null) binding.bitmap = views.firstNotNullOfOrNull { it.bitmap } ?: cache.get(binding.key)
        views.add(binding)
        if (binding.bitmap == null && failed[binding.key] != generation(binding.key)) pump()
    }

    private fun detach(binding: Binding) {
        listeners[binding.key]?.let { views -> if (views.remove(binding) && views.isEmpty()) listeners.remove(binding.key) }
        binding.bitmap = null
        active?.takeIf { it.binding.key == binding.key && !listeners.containsKey(binding.key) }?.let(::cancel)
        pump()
    }

    private fun generation(key: String) = generations[key] ?: 0L
    private fun current(job: Job) = !closed && !job.cancelled && job.lifetime == lifetime &&
        job.generation == generation(job.binding.key) && listeners[job.binding.key]?.isNotEmpty() == true

    private fun pump() {
        if (closed || active != null) return
        val binding = listeners.entries.firstOrNull { (key, views) ->
            views.all { it.bitmap == null } && cache.get(key) == null && failed[key] != generation(key)
        }
            ?.value?.firstOrNull() ?: return
        val job = Job(binding, generation(binding.key), lifetime)
        active = job
        afterSaved {
            if (!current(job)) { finish(job, null); return@afterSaved }
            repository.requestThumbnail(job, binding.documentId, binding.pageId) { snapshot ->
                if (!current(job) || snapshot == null) { finish(job, null); return@requestThumbnail }
                job.rendering = true
                try {
                    worker.execute {
                        val bitmap = if (job.cancelled) null else runCatching {
                            val page = snapshot.page { job.cancelled }
                            if (job.cancelled) null else {
                                render?.invoke(page) ?: StreamingPdf.render(repository, page, 220,
                                    max(220, (220 * page.height / page.width).roundToInt().coerceIn(1, 5000)), { job.cancelled })
                            }
                        }.getOrNull()
                        main.post { finish(job, bitmap) }
                    }
                } catch (_: RejectedExecutionException) { finish(job, null) }
            }
        }
    }

    private fun finish(job: Job, bitmap: Bitmap?) {
        if (active !== job) { bitmap?.recycle(); return }
        active = null
        if (current(job)) {
            if (bitmap == null) failed[job.binding.key] = job.generation
            else {
                cache.put(job.binding.key, bitmap)
                listeners[job.binding.key]?.forEach { it.bitmap = bitmap; it.view.invalidate() }
            }
        } else bitmap?.recycle()
        pump()
    }

    private fun cancel(job: Job) {
        job.cancelled = true
        repository.cancelPageRequests(job)
        // A running renderer retains its slot until it releases its private temporary bitmaps.
        if (!job.rendering && active === job) active = null
    }

    private fun invalidate(key: String) {
        generations[key] = generation(key) + 1
        cache.remove(key); failed.remove(key)
        active?.takeIf { it.binding.key == key }?.let(::cancel)
        listeners[key]?.forEach { it.bitmap = null; it.view.invalidate() }
    }

    fun invalidatePage(documentId: String, pageId: String) {
        invalidate(pageId); invalidate("doc:$documentId"); pump()
    }

    fun invalidateDocument(documentId: String) {
        invalidate("doc:$documentId"); pump()
    }

    fun cancelPending() {
        lifetime++
        listeners.values.forEach { views -> views.forEach { it.bitmap = null } }
        listeners.clear(); failed.clear()
        active?.let(::cancel)
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelPending(); cache.evictAll(); generations.clear()
        worker.shutdown()
    }
}
