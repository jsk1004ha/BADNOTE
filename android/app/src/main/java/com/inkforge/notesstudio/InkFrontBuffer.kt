package com.inkforge.notesstudio

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Picture
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.hardware.HardwareBuffer
import android.os.Build
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import androidx.graphics.surface.SurfaceControlCompat
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executor
import org.json.JSONObject

/** Editor-owned append buffer. Published roots and partial chunks are never mutated. */
internal class InkPointPublication {
    internal class Node(val children: Array<Node?>? = null, val points: Array<InkPoint?>? = null)
    private var source = Any()
    private var root: Node? = null
    private var depth = 0
    private var chunks = 0
    private var tail = arrayOfNulls<InkPoint>(64)
    private var used = 0
    private var published: InkPointSnapshot? = null
    private var left = Float.POSITIVE_INFINITY
    private var top = Float.POSITIVE_INFINITY
    private var right = Float.NEGATIVE_INFINITY
    private var bottom = Float.NEGATIVE_INFINITY
    private var tilt = 0.0
    private var hasOriginal = false

    fun append(point: InkPoint) {
        if (used == tail.size) {
            if (chunks.toLong() >= (1L shl ((depth + 1) * 5))) {
                root = Node(arrayOfNulls<Node>(32).also { it[0] = root })
                depth++
            }
            root = insert(root, depth, chunks++, Node(points = tail))
            tail = arrayOfNulls(64)
            used = 0
        }
        tail[used++] = point
        left = minOf(left, point.x); top = minOf(top, point.y)
        right = maxOf(right, point.x); bottom = maxOf(bottom, point.y)
        // Live points have no original JSON; imported extension values do not use this bound.
        tilt = maxOf(tilt, kotlin.math.hypot((point.tx ?: 0f).toDouble(), (point.ty ?: 0f).toDouble()))
        hasOriginal = hasOriginal || point.original != null
        published = null
    }

    private fun insert(node: Node?, level: Int, index: Int, leaf: Node): Node {
        val children = node?.children?.copyOf() ?: arrayOfNulls(32)
        val slot = (index ushr (level * 5)) and 31
        children[slot] = if (level == 0) leaf else insert(children[slot], level - 1, index, leaf)
        return Node(children)
    }

    fun snapshot(): List<InkPoint> = published ?: InkPointSnapshot(source, root, depth,
        chunks * 64, tail.copyOf(used), left, top, right, bottom, tilt, hasOriginal).also { published = it }

    fun clear() {
        source = Any(); root = null; depth = 0; chunks = 0
        tail = arrayOfNulls(64); used = 0; published = null
        left = Float.POSITIVE_INFINITY; top = Float.POSITIVE_INFINITY
        right = Float.NEGATIVE_INFINITY; bottom = Float.NEGATIVE_INFINITY; tilt = 0.0
        hasOriginal = false
    }
}

internal class InkPointSnapshot internal constructor(
    internal val source: Any, private val root: InkPointPublication.Node?, private val depth: Int,
    private val prefixSize: Int, private val tail: Array<InkPoint?>,
    internal val left: Float, internal val top: Float, internal val right: Float,
    internal val bottom: Float, internal val maxTilt: Double, internal val hasOriginal: Boolean
) : AbstractList<InkPoint>(), java.util.RandomAccess {
    override val size: Int = prefixSize + tail.size
    override fun get(index: Int): InkPoint {
        if (index !in 0 until size) throw IndexOutOfBoundsException("$index / $size")
        if (index >= prefixSize) return checkNotNull(tail[index - prefixSize])
        val chunk = index / 64
        var node = checkNotNull(root)
        for (level in depth downTo 0) node = checkNotNull(node.children?.get((chunk ushr (level * 5)) and 31))
        return checkNotNull(node.points?.get(index and 63))
    }
}

/** API-neutral editor contract. Only API 31+ constructs the SurfaceView implementation. */
internal data class InkFrontFrame(val stroke: JSONObject, val x: Float, val y: Float, val scale: Float,
                                  val pageWidth: Float, val pageHeight: Float,
                                  val points: List<InkPoint>? = null) {
    constructor(stroke: String, x: Float, y: Float, scale: Float, pageWidth: Float, pageHeight: Float) :
        this(JSONObject(stroke), x, y, scale, pageWidth, pageHeight)
}

internal interface InkFrontSurface {
    val presented: Boolean
    fun hideForLegacy()
    fun renderScene(picture: Picture, width: Int, height: Int, commitFront: Boolean)
    fun renderFront(frame: InkFrontFrame): Boolean
    fun cancelFront()
}

/** One renderer per drawing thread; does not access SurfaceView or editor state. */
@RequiresApi(Build.VERSION_CODES.S)
internal class InkFrontFrameRenderer {
    private val brush = NoteRenderer()
    private val replacePaint = Paint().apply { blendMode = BlendMode.SRC }
    private val pending = AtomicReference<InkFrontFrame?>()
    private val generation = AtomicInteger()
    private var drawnGeneration = -1
    private var drawnWidth = 0
    private var drawnHeight = 0
    private val previousBounds = RectF()
    private val currentBounds = RectF()
    private val damage = RectF()
    private val pixelDamage = Rect()

    fun submit(frame: InkFrontFrame) { pending.set(frame) }
    fun clear() { pending.set(null); generation.incrementAndGet() }

    fun drawLatest(canvas: Canvas, bufferWidth: Int, bufferHeight: Int): Boolean {
        val frame = pending.getAndSet(null) ?: return false
        draw(canvas, bufferWidth, bufferHeight, frame)
        return true
    }
    fun addDamage(region: Region) {
        damage.roundOut(pixelDamage)
        region.op(pixelDamage, Region.Op.UNION)
    }


    private fun bounds(frame: InkFrontFrame, width: Int, height: Int): Boolean {
        val points = frame.points as? InkPointSnapshot ?: return false
        if (frame.scale <= 0 || !frame.scale.isFinite()) return false
        if (points.isEmpty()) { currentBounds.setEmpty(); return true }
        val padding = LegacyBrush.frontPadding(frame.stroke, points) ?: return false
        val left = maxOf(frame.x.toDouble(), frame.x + (points.left - padding) * frame.scale)
        val top = maxOf(frame.y.toDouble(), frame.y + (points.top - padding) * frame.scale)
        val right = minOf((frame.x + frame.pageWidth * frame.scale).toDouble(),
            frame.x + (points.right + padding) * frame.scale)
        val bottom = minOf((frame.y + frame.pageHeight * frame.scale).toDouble(),
            frame.y + (points.bottom + padding) * frame.scale)
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return false
        currentBounds.set(kotlin.math.floor(left - 2).toFloat().coerceIn(0f, width.toFloat()),
            kotlin.math.floor(top - 2).toFloat().coerceIn(0f, height.toFloat()),
            kotlin.math.ceil(right + 2).toFloat().coerceIn(0f, width.toFloat()),
            kotlin.math.ceil(bottom + 2).toFloat().coerceIn(0f, height.toFloat()))
        if (currentBounds.isEmpty) currentBounds.setEmpty()
        return true
    }

    fun draw(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, frame: InkFrontFrame) {
        // Replace transparent pixels too, without exposing CLEAR on the displayed front.
        // Geometry is fully replayed: total-count taper and pencil randomness are not frozen.
        val version = generation.get()
        val bounded = bounds(frame, bufferWidth, bufferHeight)
        if (!bounded) currentBounds.set(0f, 0f, bufferWidth.toFloat(), bufferHeight.toFloat())
        if (drawnGeneration != version || drawnWidth != bufferWidth || drawnHeight != bufferHeight)
            damage.set(0f, 0f, bufferWidth.toFloat(), bufferHeight.toFloat())
        else { damage.set(previousBounds); damage.union(currentBounds) }
        drawnGeneration = version; drawnWidth = bufferWidth; drawnHeight = bufferHeight
        previousBounds.set(currentBounds)
        if (damage.isEmpty) return
        // Integer screen-space layer bounds only; retain the original page transform and clip.
        val saved = canvas.saveLayer(damage, replacePaint)
        try {
            canvas.translate(frame.x, frame.y)
            canvas.scale(frame.scale, frame.scale)
            canvas.clipRect(0f, 0f, frame.pageWidth, frame.pageHeight)
            val points = frame.points
            if (points == null) brush.draw(canvas, frame.stroke, frame.scale.toDouble())
            else brush.stroke(canvas, frame.stroke, points, frame.scale.toDouble())
        } finally { canvas.restoreToCount(saved) }
    }
}

/** Owns the editor's final page image after its multi-buffer transaction is committed. */
@RequiresApi(Build.VERSION_CODES.S)
internal class InkFrontBuffer(context: Context, private val onPresented: (Boolean) -> Unit,
                              private val onRecreated: () -> Unit,
                              private val callbackExecutor: Executor = context.mainExecutor) : SurfaceView(context),
    CanvasFrontBufferedRenderer.Callback<InkFrontFrame>, InkFrontSurface {
    private data class Scene(val version: Long, val picture: Picture, val width: Int, val height: Int)

    private var renderer: CanvasFrontBufferedRenderer<InkFrontFrame>? = null
    @Volatile private var scene: Scene? = null
    private val renderedVersions = ArrayDeque<Long>() // Renderer callbacks share its worker thread.
    private val frontPainter = InkFrontFrameRenderer()
    private var nextVersion = 0L
    private val damageGeneration = AtomicInteger()
    private var drawnDamageGeneration = -1 // Renderer-worker-owned below.
    private val frontDamage = Region()
    private var frontDamageDrawn = false
    override var presented = false
        private set
    val frontFrames = AtomicInteger()
    val multiFrames = AtomicInteger()
    val multiCompletions = AtomicInteger()
    val cancelledFrames = AtomicInteger()
    val releasedRenderers = AtomicInteger()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (renderer == null) renderer = CanvasFrontBufferedRenderer(this, this, HardwareBuffer.RGBA_8888)
        onRecreated()
    }

    override fun onDetachedFromWindow() {
        hideForLegacy()
        renderer?.release(true)
        renderer = null
        scene = null
        synchronized(renderedVersions) { renderedVersions.clear() }
        releasedRenderers.incrementAndGet()
        super.onDetachedFromWindow()
    }

    override fun hideForLegacy() {
        // A previously rendered transaction may commit after a page or tool switch.
        damageGeneration.incrementAndGet()
        frontPainter.clear()
        ++nextVersion
        if (presented) { presented = false; onPresented(false) }
    }

    override fun renderScene(picture: Picture, width: Int, height: Int, commitFront: Boolean) {
        // A previous commit must never hide a newly admitted front stroke. The View owns
        // presentation until this scene's transaction has actually committed.
        damageGeneration.incrementAndGet()
        frontPainter.clear()
        if (presented) { presented = false; onPresented(false) }
        scene = Scene(++nextVersion, picture, width, height)
        val current = renderer ?: return
        if (commitFront) current.commit() else current.renderMultiBufferedLayer(emptyList())
    }

    override fun renderFront(frame: InkFrontFrame): Boolean {
        val current = renderer ?: return false
        if (!presented || !current.isValid()) return false
        frontPainter.submit(frame)
        current.renderFrontBufferedLayer(frame)
        return true
    }

    override fun cancelFront() {
        // Cancelling transient ink keeps the already committed scene reusable.
        damageGeneration.incrementAndGet()
        frontPainter.clear()
        cancelledFrames.incrementAndGet()
        renderer?.cancel()
    }

    override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: InkFrontFrame) {
        // A merged callback may carry an old parameter. Always draw the newest available
        // snapshot once, so continuous input cannot starve behind superseded callbacks.
        val version = damageGeneration.get()
        if (drawnDamageGeneration != version) {
            frontDamage.setEmpty(); frontDamageDrawn = false; drawnDamageGeneration = version
        }
        if (frontPainter.drawLatest(canvas, bufferWidth, bufferHeight)) {
            frontPainter.addDamage(frontDamage); frontDamageDrawn = true
            frontFrames.incrementAndGet()
        }
    }

    override fun onFrontBufferedLayerRenderComplete(
        frontBufferedLayerSurfaceControl: SurfaceControlCompat,
        transaction: SurfaceControlCompat.Transaction
    ) {
        // AndroidX invokes this synchronously after all merged draw callbacks and before
        // recording the next batch. Never annotate unknown library-only clears/cancels.
        if (drawnDamageGeneration == damageGeneration.get() && frontDamageDrawn && !frontDamage.isEmpty)
            transaction.setDamageRegion(frontBufferedLayerSurfaceControl, frontDamage)
        frontDamage.setEmpty(); frontDamageDrawn = false
    }

    override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int,
                                           params: Collection<InkFrontFrame>) {
        val snapshot = scene
        canvas.drawColor(Color.BLACK, BlendMode.CLEAR)
        val complete = snapshot != null && snapshot.width == bufferWidth && snapshot.height == bufferHeight
        if (complete) canvas.drawPicture(checkNotNull(snapshot).picture)
        // Resize callbacks may still use the previous buffer size. Never announce a
        // cleared, undrawn buffer as the new scene merely because its version matches.
        synchronized(renderedVersions) { renderedVersions.addLast(if (complete) checkNotNull(snapshot).version else 0L) }
        multiFrames.incrementAndGet()
    }

    override fun onMultiBufferedLayerRenderComplete(
        frontBufferedLayerSurfaceControl: SurfaceControlCompat,
        multiBufferedLayerSurfaceControl: SurfaceControlCompat,
        transaction: SurfaceControlCompat.Transaction
    ) {
        val version = synchronized(renderedVersions) { if (renderedVersions.isEmpty()) 0L else renderedVersions.removeFirst() }
        multiCompletions.incrementAndGet()
        transaction.addTransactionCommittedListener(callbackExecutor,
            object : SurfaceControlCompat.TransactionCommittedListener {
                override fun onTransactionCommitted() {
                    val latest = scene
                    if (isAttachedToWindow && version != 0L && version == nextVersion &&
                        version == latest?.version &&
                        width == latest.width && height == latest.height && !presented) {
                        presented = true
                        onPresented(true)
                    }
                }
            }
        )
    }
}
