package com.inkforge.notesstudio

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Picture
import android.hardware.HardwareBuffer
import android.os.Build
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import androidx.graphics.surface.SurfaceControlCompat
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** API-neutral editor contract. Only API 31+ constructs the SurfaceView implementation. */
internal data class InkFrontFrame(val stroke: String, val x: Float, val y: Float, val scale: Float,
                                  val pageWidth: Float, val pageHeight: Float)

internal interface InkFrontSurface {
    val presented: Boolean
    fun hideForLegacy()
    fun renderScene(picture: Picture, width: Int, height: Int, commitFront: Boolean)
    fun renderFront(frame: InkFrontFrame): Boolean
    fun cancelFront()
}

/** Owns the editor's final page image after its multi-buffer transaction is committed. */
@RequiresApi(Build.VERSION_CODES.S)
internal class InkFrontBuffer(context: Context, private val onPresented: (Boolean) -> Unit,
                              private val onRecreated: () -> Unit) : SurfaceView(context),
    CanvasFrontBufferedRenderer.Callback<InkFrontFrame>, InkFrontSurface {
    private data class Scene(val version: Long, val picture: Picture, val width: Int, val height: Int)

    private val callbackExecutor = context.mainExecutor
    private var renderer: CanvasFrontBufferedRenderer<InkFrontFrame>? = null
    @Volatile private var scene: Scene? = null
    private val renderedVersions = ArrayDeque<Long>() // Renderer callbacks share its worker thread.
    private val frontBrush = NoteRenderer()
    private var nextVersion = 0L
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
        ++nextVersion
        if (presented) { presented = false; onPresented(false) }
    }

    override fun renderScene(picture: Picture, width: Int, height: Int, commitFront: Boolean) {
        scene = Scene(++nextVersion, picture, width, height)
        val current = renderer ?: return
        if (commitFront) current.commit() else current.renderMultiBufferedLayer(emptyList())
    }

    override fun renderFront(frame: InkFrontFrame): Boolean {
        val current = renderer ?: return false
        if (!presented || !current.isValid()) return false
        current.renderFrontBufferedLayer(frame)
        return true
    }

    override fun cancelFront() {
        cancelledFrames.incrementAndGet()
        renderer?.cancel()
    }

    override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: InkFrontFrame) {
        // This is a persisted buffer: replaying the whole stroke without CLEAR would darken alpha each MOVE.
        canvas.drawColor(Color.BLACK, BlendMode.CLEAR)
        canvas.save()
        try {
            canvas.translate(param.x, param.y)
            canvas.scale(param.scale, param.scale)
            canvas.clipRect(0f,0f,param.pageWidth,param.pageHeight)
            frontBrush.draw(canvas, JSONObject(param.stroke), param.scale.toDouble())
        } finally { canvas.restore() }
        frontFrames.incrementAndGet()
    }

    override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int,
                                           params: Collection<InkFrontFrame>) {
        val snapshot = scene
        canvas.drawColor(Color.BLACK, BlendMode.CLEAR)
        if (snapshot != null && snapshot.width == bufferWidth && snapshot.height == bufferHeight)
            canvas.drawPicture(snapshot.picture)
        synchronized(renderedVersions) { renderedVersions.addLast(snapshot?.version ?: 0L) }
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
