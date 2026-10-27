package com.inkforge.notesstudio

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
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

/** One PDF worker shares content-keyed requests across viewport changes and keeps bounded fallback tiles. */
class BackgroundLoader(context: Context, private val repository: NoteRepository,
                       private val invalidate: () -> Unit, private val onError: (String) -> Unit = {}) : Closeable {
    private data class PdfSource(val reference: String, val index: Int, val width: Float, val height: Float)
    private data class Tile(val key: String, val source: PdfSource, val bucket: Int,
                            val left: Float, val top: Float, val width: Float, val height: Float, val priority: Int)
    private data class CachedBitmap(val bitmap: Bitmap, val tile: Tile? = null)
    private inner class TileTask(val cacheKey: String, val source: String, val tile: Tile?,
                                 var priority: Int, val sequence: Long, val load: () -> Bitmap?) : Runnable, Comparable<TileTask> {
        override fun compareTo(other: TileTask): Int = compareValuesBy(this, other, TileTask::priority, TileTask::sequence)
        override fun run() {
            var error = false
            val bitmap = try { if (closed) null else load() } catch (_: Exception) { error = true; null }
            main.post {
                if (pending[cacheKey] === this) pending.remove(cacheKey)
                // Only unpublished results may be recycled: recorded/hardware canvases can still own cached bitmaps.
                if (closed) { bitmap?.recycle(); return@post }
                if (bitmap != null) {
                    val value = CachedBitmap(bitmap, tile)
                    if (tile != null) {
                        // Bucket zero is a bounded whole-page overview; 1..12 are exact half-step resolutions.
                        val buckets = pdfTiles.getOrPut(tile.source) { arrayOfNulls(13) }
                        val tiles = buckets[tile.bucket] ?: LinkedHashMap<String, CachedBitmap>().also {
                            buckets[tile.bucket] = it
                        }
                        tiles[cacheKey] = value
                    }
                    cache.put(cacheKey, value)
                    if (cacheKey in activeRequests) {
                        readyKeys += cacheKey
                        scheduleInvalidate()
                    }
                }
                if (cacheKey in activeRequests && error && failedSources.add(source)) onError("PDF 또는 이미지 배경을 읽지 못했습니다.")
            }
        }
    }

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
    private var frameRequests = HashSet<String>()
    private var activeRequests = HashSet<String>()
    private val readyKeys = HashSet<String>()
    private var invalidationScheduled = false
    private val notifyReady = Runnable {
        invalidationScheduled = false
        val visible = readyKeys.any { it in activeRequests }
        readyKeys.clear()
        if (!closed && visible) invalidate()
    }
    private var sequence = 0L
    @Volatile private var closed = false
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private val pdfTiles = HashMap<PdfSource, Array<LinkedHashMap<String, CachedBitmap>?>>()
    private val budget = min(128L * 1024 * 1024,
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass.toLong() * 1024 * 1024 / 4).toInt()
    private val cache = object : LruCache<String, CachedBitmap>(budget) {
        override fun sizeOf(key: String, value: CachedBitmap) = value.bitmap.allocationByteCount
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: CachedBitmap, newValue: CachedBitmap?) {
            val tile = oldValue.tile ?: return
            val buckets = pdfTiles[tile.source] ?: return
            val tiles = buckets[tile.bucket] ?: return
            if (tiles[key] === oldValue) tiles.remove(key)
            if (tiles.isEmpty()) buckets[tile.bucket] = null
            if (buckets.all { it == null }) pdfTiles.remove(tile.source)
            // No recycle here: a Picture/RenderNode may outlive its cache entry.
        }
    }
    /** PdfRenderer instances and their ParcelFileDescriptors are owned only by the worker. */
    private val pdfs = LinkedHashMap<String, PdfRenderer>(4, .75f, true)

    fun beginFrame(retainPrevious:Boolean=false) {
        frameRequests.clear()
        // Raster-cache hits still depend on their pending images/PDF tiles. Keep those requests
        // active until the screen-space cache is replaced by a new viewport/background scene.
        if(retainPrevious)frameRequests.addAll(activeRequests)
    }
    fun endFrame() {
        val previous = activeRequests
        activeRequests = frameRequests
        frameRequests = previous
        val tasks = pending.values.iterator()
        while (tasks.hasNext()) {
            val task = tasks.next()
            // A pan may change the viewport without changing this tile's pixels.
            if (task.cacheKey !in activeRequests && worker.remove(task)) tasks.remove()
        }
    }

    private fun scheduleInvalidate() {
        if (invalidationScheduled) return
        invalidationScheduled = true
        main.postDelayed(notifyReady, 16L)
    }

    fun image(reference: String, maxSide: Int = 1000): Bitmap? {
        if (closed || !reference.startsWith("asset:")) return null
        val size = when { maxSide <= 256 -> 256; maxSide <= 512 -> 512; maxSide <= 1024 -> 1024; else -> 2000 }
        val key = "img:$reference:$size"
        frameRequests += key
        cache.get(key)?.let { return it.bitmap }
        request(key, reference, null, Int.MAX_VALUE / 2) {
            decodeImage(repository.asset(reference.removePrefix("asset:")), size)
        }
        return null
    }

    private fun request(key: String, source: String, tile: Tile?, priority: Int, load: () -> Bitmap?) {
        if (closed) return
        pending[key]?.let { task ->
            // Reheap only queued work; an already rendering tile is shared by the new viewport.
            if (priority != task.priority && worker.remove(task)) {
                task.priority = priority
                worker.execute(task)
            }
            return
        }
        val task = TileTask(key, source, tile, priority, ++sequence, load)
        pending[key] = task
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
        if (closed) return
        val reference = page.meta.optString("pdfSource")
        if (!reference.startsWith("asset:")) {
            image(page.meta.optString("backgroundImage"), (max(page.width, page.height) * scale).roundToInt())
                ?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, page.width, page.height), null) }
            return
        }
        val bucket = (ceil(scale * 2).toInt()).coerceIn(1, 12)
        val resolution = bucket / 2f
        val tileLogical = 512f / resolution
        val left = max(0f, visible.left); val right = min(page.width, visible.right)
        val top = max(0f, visible.top); val bottom = min(page.height, visible.bottom)
        if (left >= right || top >= bottom) return
        val startX = floor(left / tileLogical).toInt()
        val endX = ceil(right / tileLogical).toInt() - 1
        val startY = floor(top / tileLogical).toInt()
        val endY = ceil(bottom / tileLogical).toInt() - 1
        val source = PdfSource(reference, page.meta.optInt("pdfPageNumber", 1) - 1, page.width, page.height)
        val centerX = (startX + endX) / 2f
        val centerY = (startY + endY) / 2f
        val missing = ArrayList<Tile>()
        for (y in startY..endY) for (x in startX..endX) {
            val tileLeft = x * tileLogical; val tileTop = y * tileLogical
            val key = "$reference:${source.index}:$resolution:${page.width}:${page.height}:$x:$y"
            frameRequests += key
            if (cache.get(key) == null) missing += Tile(key, source, bucket, tileLeft, tileTop,
                min(tileLogical, page.width - tileLeft), min(tileLogical, page.height - tileTop),
                (((x - centerX).pow(2) + (y - centerY).pow(2)) * 100).toInt())
        }
        val buckets = pdfTiles[source]
        if (buckets != null) {
            canvas.save()
            canvas.clipRect(left, top, right, bottom)
            if (missing.isNotEmpty()) {
                // The overview covers newly exposed areas; sharper cached buckets and exact tiles then win.
                for (other in buckets.indices) if (other != bucket)
                    drawTiles(canvas, buckets[other], left, top, right, bottom)
            }
            drawTiles(canvas, buckets[bucket], left, top, right, bottom)
            canvas.restore()
        }
        missing.sortBy(Tile::priority)
        for (i in 0 until min(64, missing.size)) requestPdf(missing[i], resolution)

        // Visible tiles alone cannot cover a pan into a previously unseen part of the page.
        // Keep one whole-page fallback in the same LRU, capped at a single tile's pixel budget.
        val overviewKey = "$reference:${source.index}:overview:${source.width}:${source.height}"
        frameRequests += overviewKey
        if (cache.get(overviewKey) == null) {
            val overview = Tile(overviewKey, source, 0, 0f, 0f, source.width, source.height, Int.MAX_VALUE / 4)
            requestPdf(overview, min(.5f, 512f / max(source.width, source.height)))
        }
    }

    private fun requestPdf(tile: Tile, resolution: Float) {
        val source = tile.source
        request(tile.key, source.reference, tile, tile.priority) {
            renderer(source.reference).openPage(source.index).use { pdf ->
                val bitmap = Bitmap.createBitmap(ceil(tile.width * resolution).toInt().coerceIn(1, 512),
                    ceil(tile.height * resolution).toInt().coerceIn(1, 512), Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    val matrix = Matrix().apply {
                        setScale(source.width / pdf.width * resolution, source.height / pdf.height * resolution)
                        postTranslate(-tile.left * resolution, -tile.top * resolution)
                    }
                    pdf.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } catch (error: Exception) { bitmap.recycle(); throw error }
            }
        }
    }

    private fun drawTiles(canvas: Canvas, tiles: Map<String, CachedBitmap>?,
                          left: Float, top: Float, right: Float, bottom: Float) {
        if (tiles == null) return
        for ((key, value) in tiles) {
            val tile = requireNotNull(value.tile)
            if (tile.left >= right || tile.top >= bottom || tile.left + tile.width <= left || tile.top + tile.height <= top) continue
            cache.get(key) // Keep the fallback currently being displayed inside the same bounded LRU.
            destination.set(tile.left, tile.top, tile.left + tile.width, tile.top + tile.height)
            canvas.drawBitmap(value.bitmap, null, destination, bitmapPaint)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(notifyReady)
        readyKeys.clear(); frameRequests.clear(); activeRequests.clear()
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
