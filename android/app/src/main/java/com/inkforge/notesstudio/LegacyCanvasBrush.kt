package com.inkforge.notesstudio

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RenderNode
import android.graphics.RectF
import android.graphics.PorterDuffXfermode
import android.os.Build
import androidx.annotation.RequiresApi
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.roundToInt

/** Executes the same ordered 3.x brush commands on screen, active ink, PNG and PDF canvases. */
class LegacyCanvasBrush {
    private val sink = CanvasSink()
    private val smoothing = LegacyBrush.LiveSmoothing()
    private val paths = LivePaths(smoothing)

    fun draw(canvas: Canvas, stroke: JSONObject, renderScale: Double = 1.0) {
        execute(canvas) { LegacyBrush.renderTyped(stroke, renderScale, sink) }
    }
    fun draw(canvas: Canvas, stroke: JSONObject, points: List<InkPoint>, renderScale: Double = 1.0) {
        val cachedPaths = if (points is InkPointSnapshot && stroke.optString("brush") != "pencil") paths else null
        execute(canvas, livePaths = cachedPaths) { LegacyBrush.renderTyped(stroke, points, renderScale, sink, smoothing) }
    }
    /** Measures the paths already being recorded, including brush scatter, taper and pressure. */
    internal fun drawMeasured(canvas: Canvas, stroke: JSONObject, renderScale: Double): InkBounds {
        val bounds = RectF()
        execute(canvas, bounds) { LegacyBrush.renderTyped(stroke, renderScale, sink) }
        return InkBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }
    private inline fun execute(canvas: Canvas, bounds: RectF? = null, livePaths: LivePaths? = null,
                               render: () -> Unit) {
        val baseline = canvas.save()
        sink.begin(canvas, bounds, livePaths)
        try { render(); check(sink.depth == 0) { "unbalancedBrushCommands" } }
        finally { canvas.restoreToCount(baseline); sink.end() }
    }

    /** Immutable segment paths also let the hardware renderer retain tessellation. */
    private class LivePaths(private val smoothing: LegacyBrush.LiveSmoothing) {
        private val paths = ArrayList<Path>()
        private var version = -1
        private var bodies: BodyCache? = null
        var building = false
            private set
        fun path(segment: Int): Path {
            synchronize()
            building = segment >= paths.size
            if (building) {
                check(segment == paths.size) { "nonSequentialLiveSegment" }
                paths.add(Path())
            }
            return paths[segment]
        }
        private fun synchronize() {
            if (version != smoothing.geometryVersion) {
                paths.clear()
                if (Build.VERSION.SDK_INT >= 29) bodies?.clear()
                version = smoothing.geometryVersion
            }
        }
        @RequiresApi(29)
        fun body(pass: Int, context: LegacyBrush.WidthContext, style: Style, scale: Double): BodyPass {
            synchronize()
            val cache = bodies ?: BodyCache().also { bodies = it }
            return cache.pass(pass).apply { prepare(context, style, scale) }
        }
    }

    private class Style {
        var alpha = 1.0
        var strokeText = "#111827"
        var fillText = "#111827"
        var strokeColor = 0xff111827.toInt()
        var fillColor = 0xff111827.toInt()
        var butt = false
        var width = 1.0
        var multiply = false
        var saveCount = 0
        fun set(other: Style) {
            alpha = other.alpha; strokeText = other.strokeText; fillText = other.fillText
            strokeColor = other.strokeColor; fillColor = other.fillColor
            butt = other.butt; width = other.width; multiply = other.multiply
        }
        fun reset() {
            alpha = 1.0; strokeText = "#111827"; fillText = "#111827"
            strokeColor = 0xff111827.toInt(); fillColor = strokeColor
            butt = false; width = 1.0; multiply = false
        }
    }

    @RequiresApi(29)
    private class BodyCache {
        private val passes = ArrayList<BodyPass>()
        fun pass(index: Int): BodyPass {
            while (passes.size <= index) passes.add(BodyPass())
            return passes[index]
        }
        fun clear() { passes.forEach { it.clear() } }
    }

    /** Non-isolated display lists retain the original per-segment alpha/blend order. */
    @RequiresApi(29)
    private class BodyPass {
        private val nodes = ArrayList<RenderNode?>()
        private val previousStyle = Style()
        private var previousContext: LegacyBrush.WidthContext? = null
        private var previousScale = Double.NaN
        fun prepare(context: LegacyBrush.WidthContext, style: Style, scale: Double) {
            if (previousContext?.matches(context) != true || previousScale != scale ||
                previousStyle.alpha != style.alpha || previousStyle.strokeColor != style.strokeColor ||
                previousStyle.butt != style.butt || previousStyle.multiply != style.multiply) clear()
            previousContext = context; previousScale = scale; previousStyle.set(style)
        }
        fun replay(index: Int, target: Canvas): Boolean {
            val node = nodes.getOrNull(index) ?: return false
            // A retained node can outlive its native list. Returning false re-records
            // this block using the existing smoothed points and immutable Paths.
            if (!node.hasDisplayList()) return false
            target.drawRenderNode(node)
            return true
        }
        fun store(index: Int, node: RenderNode) {
            while (nodes.size <= index) nodes.add(null)
            nodes[index]?.discardDisplayList()
            nodes[index] = node
        }
        fun clear() {
            nodes.forEach { it?.discardDisplayList() }
            nodes.clear()
        }
    }

    /** Keep API-29 native types out of the ordinary legacy Canvas executor's state. */
    @RequiresApi(29)
    private class BodyRecording(private val owner: BodyPass, private val block: Int,
                                val last: Int, val target: Canvas, clip: Rect) {
        private val node = RenderNode("ink-stable-body").apply {
            target.getClipBounds(clip)
            setPosition(0, 0, clip.right.coerceAtLeast(1), clip.bottom.coerceAtLeast(1))
            setClipToBounds(false)
            setUseCompositingLayer(false, null)
        }
        val canvas: Canvas = node.beginRecording()
        fun finish() {
            node.endRecording()
            owner.store(block, node)
            target.drawRenderNode(node)
        }
        fun abort() { node.endRecording(); node.discardDisplayList() }
    }

    private class CanvasSink : LegacyBrush.DrawingSink {
        private var canvas: Canvas? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val scratch = Path()
        private var path = scratch
        private var livePaths: LivePaths? = null
        private var building = true
        private val pathBounds = RectF()
        private val multiplyMode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        private val styles = ArrayList<Style>().apply { repeat(3) { add(Style()) } }
        private var bounds: RectF? = null
        private var measured = false
        private var pass = -1
        private var bodyPass: BodyPass? = null
        private var recording: BodyRecording? = null
        private var segment = -1
        private val nodeClip = Rect()
        var depth = 0
            private set
        private val style: Style get() = styles[depth]

        fun begin(target: Canvas, outputBounds: RectF?, cachedPaths: LivePaths?) {
            canvas = target; bounds = outputBounds; measured = false; depth = 0
            styles[0].reset()
            paint.reset(); paint.isAntiAlias = true; paint.strokeJoin = Paint.Join.ROUND
            livePaths = cachedPaths; path = scratch; building = true; scratch.rewind()
            pass = -1; bodyPass = null; segment = -1
        }
        fun end() {
            if (Build.VERSION.SDK_INT >= 29) recording?.abort()
            recording = null; bodyPass = null
            canvas = null; bounds = null; livePaths = null; path = scratch
        }
        override fun save() {
            val previous = style
            depth++
            if (depth == styles.size) styles.add(Style())
            style.set(previous)
            style.saveCount = checkNotNull(canvas).save()
        }
        override fun restore() {
            check(depth > 0) { "unbalancedBrushCommands" }
            checkNotNull(canvas).restoreToCount(style.saveCount)
            depth--
        }
        override fun alpha(value: Double) { style.alpha = value }
        private fun color(value: String) = try { Color.parseColor(value) }
            catch (_: IllegalArgumentException) { Color.BLACK }
        override fun strokeColor(value: String) {
            if (style.strokeText != value) { style.strokeText = value; style.strokeColor = color(value) }
        }
        override fun fillColor(value: String) {
            if (style.fillText != value) { style.fillText = value; style.fillColor = color(value) }
        }
        override fun cap(butt: Boolean) { style.butt = butt }
        override fun roundJoin() = Unit
        override fun width(value: Double) { style.width = value }
        override fun multiply() { style.multiply = true }
        override fun beginSegmentBlock(first: Int, count: Int, stable: Boolean,
                                       context: LegacyBrush.WidthContext, widthScale: Double): Boolean {
            val cache = livePaths ?: return false
            val target = checkNotNull(canvas)
            if (Build.VERSION.SDK_INT < 29 || !target.isHardwareAccelerated) return false
            if (first == 0) bodyPass = cache.body(++pass, context, style, widthScale)
            if (!stable || count != LegacyBrush.STABLE_BLOCK_SEGMENTS || first >= 8192) return false
            val blocks = checkNotNull(bodyPass)
            val block = first / LegacyBrush.STABLE_BLOCK_SEGMENTS
            if (blocks.replay(block, target)) return true
            val next = BodyRecording(blocks, block, first + count - 1, target, nodeClip)
            recording = next; canvas = next.canvas
            return false
        }
        override fun beginPath(segment: Int) {
            this.segment = segment
            val cache = livePaths
            // Bound retained native geometry, never raw samples or visible output.
            if (cache != null && segment in 0 until 8192) {
                path = cache.path(segment); building = cache.building
            } else { path = scratch; path.rewind(); building = true }
        }
        override fun moveTo(x: Double, y: Double) {
            if (building) path.moveTo(x.toFloat(), y.toFloat())
        }
        override fun lineTo(x: Double, y: Double) {
            if (building) path.lineTo(x.toFloat(), y.toFloat())
        }
        override fun quadraticTo(cx: Double, cy: Double, x: Double, y: Double) {
            if (building) path.quadTo(cx.toFloat(), cy.toFloat(), x.toFloat(), y.toFloat())
        }
        override fun arc(x: Double, y: Double, radius: Double, start: Double, end: Double) {
            if (end - start >= PI * 2 - 1e-9)
                path.addCircle(x.toFloat(), y.toFloat(), radius.toFloat(), Path.Direction.CW)
            else path.addArc((x - radius).toFloat(), (y - radius).toFloat(),
                (x + radius).toFloat(), (y + radius).toFloat(),
                Math.toDegrees(start).toFloat(), Math.toDegrees(end - start).toFloat())
        }
        private fun prepare(fill: Boolean) {
            paint.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
            paint.color = if (fill) style.fillColor else style.strokeColor
            paint.alpha = (Color.alpha(paint.color) * style.alpha).roundToInt().coerceIn(0, 255)
            paint.strokeWidth = style.width.toFloat()
            paint.strokeCap = if (style.butt) Paint.Cap.BUTT else Paint.Cap.ROUND
            if (Build.VERSION.SDK_INT >= 29)
                paint.blendMode = if (style.multiply) BlendMode.MULTIPLY else BlendMode.SRC_OVER
            else paint.xfermode = if (style.multiply) multiplyMode else null
        }
        private fun measure(stroked: Boolean) {
            val target = bounds ?: return
            path.computeBounds(pathBounds, true)
            if (stroked) pathBounds.inset(-paint.strokeWidth.coerceAtLeast(0f) / 2,
                -paint.strokeWidth.coerceAtLeast(0f) / 2)
            if (!measured) { target.set(pathBounds); measured = true } else target.union(pathBounds)
        }
        override fun stroke() {
            prepare(false); measure(true); checkNotNull(canvas).drawPath(path, paint)
            val current = recording
            if (Build.VERSION.SDK_INT >= 29 && current != null && segment == current.last) {
                canvas = current.target; recording = null
                current.finish()
            }
        }
        override fun fill() {
            prepare(true); measure(false); checkNotNull(canvas).drawPath(path, paint)
        }
    }
}
