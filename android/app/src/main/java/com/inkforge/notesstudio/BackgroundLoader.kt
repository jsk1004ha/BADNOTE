package com.inkforge.notesstudio

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.Closeable
import java.io.File
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** A single PDF renderer worker prioritizes the visible center and rejects obsolete viewport tiles. */
class BackgroundLoader(context: Context, private val repository: NoteRepository,
                       private val invalidate: () -> Unit, private val onError: (String) -> Unit = {}) : Closeable {
    private inner class TileTask(val pendingKey: String, val cacheKey: String, val pageId: String, val generation: Long,
                                 val priority: Int, val sequence: Long, val load: () -> Bitmap?) : Runnable, Comparable<TileTask> {
        override fun compareTo(other: TileTask): Int = compareValuesBy(this, other, TileTask::priority, TileTask::sequence)
        override fun run() {
            var error = false
            val bitmap = try { if (closed) null else load() } catch (_: Exception) { error = true; null }
            main.post {
                if (pending[pendingKey] === this) pending.remove(pendingKey)
                if (closed) { bitmap?.recycle(); return@post }
                val current = pageId in activePages && viewports[pageId]?.generation == generation
                if (current && bitmap != null) { cache.put(cacheKey, bitmap); invalidate() }
                else {
                    bitmap?.recycle()
                    if (pageId in activePages) invalidate()
                }
                if (current && error && failedSources.add(cacheKey.substringBefore(':'))) onError("PDF 또는 이미지 배경을 읽지 못했습니다.")
            }
        }
    }
    private data class Viewport(val signature: String, val generation: Long)
    private data class Tile(val key: String, val left: Float, val top: Float, val width: Float,
                            val height: Float, val priority: Int)

    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        PriorityBlockingQueue<Runnable>(11) { left, right ->
            when {
                left is TileTask && right is TileTask -> left.compareTo(right)
                left is TileTask -> -1
                right is TileTask -> 1
                else -> 0
            }
        })
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, TileTask>()
    private val failedSources = HashSet<String>()
    private val viewports = HashMap<String, Viewport>()
    private val framePages = HashSet<String>()
    private var activePages = emptySet<String>()
    private var generation = 0L
    private var sequence = 0L
    @Volatile private var closed = false
    private val budget = min(128L * 1024 * 1024,
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass.toLong() * 1024 * 1024 / 4).toInt()
    private val cache = object : LruCache<String, Bitmap>(budget) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    /** PdfRenderer instances and their ParcelFileDescriptors are owned only by the worker. */
    private val pdfs = LinkedHashMap<String, PdfRenderer>(4, .75f, true)

    fun beginFrame() { framePages.clear() }
    fun endFrame() {
        activePages = framePages.toSet()
        viewports.keys.retainAll(activePages)
        pending.values.toList().forEach { task ->
            if (task.pageId !in activePages || viewports[task.pageId]?.generation != task.generation) {
                if (worker.remove(task)) pending.remove(task.pendingKey)
            }
        }
    }

    fun image(reference: String, maxSide: Int = 1000, pageId: String): Bitmap? {
        if (!reference.startsWith("asset:")) return null
        val size = when { maxSide <= 256 -> 256; maxSide <= 512 -> 512; maxSide <= 1024 -> 1024; else -> 2000 }
        val key = "img:$reference:$size"
        cache.get(key)?.let { return it }
        val viewport = viewports[pageId] ?: Viewport("image:$reference", ++generation).also { viewports[pageId] = it }
        request(key, pageId, viewport.generation, Int.MAX_VALUE / 2) {
            decodeImage(repository.asset(reference.removePrefix("asset:")), size)
        }
        return null
    }

    private fun request(key: String, pageId: String, viewport: Long, priority: Int, load: () -> Bitmap?) {
        val pendingKey = "$pageId|$key"
        if (closed || pending.containsKey(pendingKey)) return
        val task = TileTask(pendingKey, key, pageId, viewport, priority, ++sequence, load)
        pending[pendingKey] = task
        worker.execute(task)
    }

    private fun renderer(reference: String): PdfRenderer {
        pdfs[reference]?.let { return it }
        while (pdfs.size >= 2) {
            val oldest = pdfs.keys.first()
            pdfs.remove(oldest)?.close()
        }
        val fd = ParcelFileDescriptor.open(repository.asset(reference.removePrefix("asset:")), ParcelFileDescriptor.MODE_READ_ONLY)
        val pdf = try { PdfRenderer(fd) } catch (error: Exception) { fd.close(); throw error }
        pdfs[reference] = pdf
        return pdf
    }

    fun draw(canvas: Canvas, page: NotePage, visible: RectF, scale: Float) {
        framePages += page.id
        val reference = page.meta.optString("pdfSource")
        if (!reference.startsWith("asset:")) {
            image(page.meta.optString("backgroundImage"), (max(page.width, page.height) * scale).roundToInt(), page.id)
                ?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, page.width, page.height), null) }
            return
        }
        val bucket = (ceil(scale * 2) / 2).coerceIn(.5f, 6f)
        val tileLogical = 512f / bucket
        val startX = floor(max(0f, visible.left) / tileLogical).toInt()
        val endX = floor(min(page.width, visible.right) / tileLogical).toInt()
        val startY = floor(max(0f, visible.top) / tileLogical).toInt()
        val endY = floor(min(page.height, visible.bottom) / tileLogical).toInt()
        val index = page.meta.optInt("pdfPageNumber", 1) - 1
        val signature = "$reference:$index:$bucket:${page.width}:${page.height}:$startX:$endX:$startY:$endY"
        val old = viewports[page.id]
        val viewport = if (old?.signature == signature) old else Viewport(signature, ++generation).also { viewports[page.id] = it }
        val centerX = (startX + endX) / 2f
        val centerY = (startY + endY) / 2f
        val missing = ArrayList<Tile>()
        for (y in startY..endY) for (x in startX..endX) {
            val left = x * tileLogical; val top = y * tileLogical
            if (left >= page.width || top >= page.height || x < 0 || y < 0) continue
            val width = min(tileLogical, page.width - left)
            val height = min(tileLogical, page.height - top)
            val key = "$reference:$index:$bucket:${page.width}:${page.height}:$x:$y"
            val bitmap = cache.get(key)
            if (bitmap != null) canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), null)
            else missing += Tile(key, left, top, width, height,
                (((x - centerX).pow(2) + (y - centerY).pow(2)) * 100).toInt())
        }
        missing.sortedBy(Tile::priority).take(64).forEach { tile ->
            val width = page.width; val height = page.height
            request(tile.key, page.id, viewport.generation, tile.priority) {
                renderer(reference).openPage(index).use { pdf ->
                    val bitmap = Bitmap.createBitmap(ceil(tile.width * bucket).toInt().coerceAtLeast(1),
                        ceil(tile.height * bucket).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        val matrix = Matrix().apply {
                            setScale(width / pdf.width * bucket, height / pdf.height * bucket)
                            postTranslate(-tile.left * bucket, -tile.top * bucket)
                        }
                        pdf.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    } catch (error: Exception) { bitmap.recycle(); throw error }
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.clear(); worker.queue.clear()
        worker.execute { pdfs.values.forEach { it.close() }; pdfs.clear() }
        worker.shutdown()
        cache.evictAll()
    }

    companion object {
        fun decodeImage(file: File, maxSide: Int): Bitmap? {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            var sample = 1
            while (max(options.outWidth, options.outHeight) / sample > maxSide) sample *= 2
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
