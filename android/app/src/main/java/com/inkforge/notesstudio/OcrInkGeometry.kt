package com.inkforge.notesstudio

import java.util.Arrays
import kotlin.math.min

/** A fitted single-line area; all SDK coordinates and WritingArea use the same units.
 * Source points, capture order, timestamps and extension fields remain untouched. */
internal object OcrInkGeometry {
    data class Space(val left: Float, val top: Float, val padding: Float, val scale: Float,
                     val width: Float, val height: Float) {
        fun x(value: Float) = (value - left + padding) * scale
        fun y(value: Float) = (value - top + padding) * scale
    }

    class InputLimitException(val reason: String) : IllegalArgumentException(reason)

    data class ModelStroke(val source: PageOcrLayout.Stroke, val indices: IntArray?) {
        val pointCount: Int get() = indices?.size ?: source.pointCount
    }

    data class ModelInput(val space: Space, val strokes: List<ModelStroke>, val pointCount: Int,
                          val byteUpperBound: Long, val resampled: Boolean)

    // Conservative accounting for float x/y, optional int64 time and enclosing SDK messages.
    // Source JSON pressure/extension fields never enter the SDK payload.
    private const val POINT_BYTES = 32L
    private const val STROKE_BYTES = 32L
    private const val CONTEXT_BYTES = 256L
    private const val MAX_NORMALIZED_ERROR = .35
    private class Work(var remaining: Long)

    /** Model-only simplification, bounded in the same normalized units as the WritingArea.
     * Retains original endpoints, pen-up boundaries, order and exact selected timestamps.
     * A stroke whose real detail cannot fit is rejected, never truncated or fabricated. */
    fun mathInput(region: PageOcrLayout.Region, checkCurrent: () -> Unit = {}): ModelInput {
        checkCurrent()
        val space = space(region)
        if (region.strokes.size > OcrInput.SOFT_REGION_STROKES)
            throw InputLimitException("mathStrokeBudgetExceeded")
        val overhead = CONTEXT_BYTES + STROKE_BYTES * region.strokes.size
        val limit = min(OcrInput.SOFT_REGION_POINTS,
            ((OcrInput.SOFT_REGION_BYTES - overhead) / POINT_BYTES).toInt())
        val originalCount = region.strokes.sumOf { it.pointCount.toLong() }
        if (originalCount <= limit) return ModelInput(space, region.strokes.map { ModelStroke(it, null) },
            originalCount.toInt(), overhead + POINT_BYTES * originalCount, false)
        val work = Work(min(originalCount * 32, 4_000_000L))
        val minimum = region.strokes.sumOf { min(it.pointCount, 2) }
        if (minimum > limit) throw InputLimitException("mathInputDetailExceeded")
        // Workspace is bounded by the output budget, not by dense source-point count.
        val retained = IntArray(limit)
        val stack = IntArray(limit * 2)
        var count = 0
        var reserved = minimum
        val strokes = region.strokes.map { source ->
            checkCurrent()
            reserved -= min(source.pointCount, 2)
            val start = count
            val end = simplify(source, space, retained, start, limit - reserved, stack, work, checkCurrent)
            count = end
            ModelStroke(source, retained.copyOfRange(start, end))
        }
        check(count <= limit)
        return ModelInput(space, strokes, count, overhead + POINT_BYTES * count, true)
    }

    /** Iterative RDP at fixed geometric error, stopping honestly when the output is full. */
    private fun simplify(source: PageOcrLayout.Stroke, space: Space, retained: IntArray,
                         offset: Int, limit: Int, stack: IntArray, work: Work, checkCurrent: () -> Unit): Int {
        val points = source.source.getJSONArray("points")
        var count = offset
        retained[count++] = 0
        if (source.pointCount == 1) return count
        retained[count++] = source.pointCount - 1
        var pending = 0
        stack[pending++] = 0
        stack[pending++] = source.pointCount - 1
        val toleranceSquared = MAX_NORMALIZED_ERROR * MAX_NORMALIZED_ERROR
        while (pending > 0) {
            checkCurrent()
            val last = stack[--pending]
            val first = stack[--pending]
            val a = points.getJSONObject(first)
            val b = points.getJSONObject(last)
            val ax = space.x(a.getDouble("x").toFloat()).toDouble()
            val ay = space.y(a.getDouble("y").toFloat()).toDouble()
            val dx = space.x(b.getDouble("x").toFloat()).toDouble() - ax
            val dy = space.y(b.getDouble("y").toFloat()).toDouble() - ay
            val lengthSquared = dx * dx + dy * dy
            var farthest = -1
            var error = toleranceSquared
            for (index in first + 1 until last) {
                // RDP has quadratic adversarial cases; unsupported detail must not monopolize OCR.
                if (--work.remaining < 0) throw InputLimitException("mathInputDetailExceeded")
                if (index and 255 == 0) checkCurrent()
                val point = points.getJSONObject(index)
                val px = space.x(point.getDouble("x").toFloat()).toDouble() - ax
                val py = space.y(point.getDouble("y").toFloat()).toDouble() - ay
                val fraction = if (lengthSquared == 0.0) 0.0
                    else ((px * dx + py * dy) / lengthSquared).coerceIn(0.0, 1.0)
                val ex = px - fraction * dx
                val ey = py - fraction * dy
                val distance = ex * ex + ey * ey
                if (distance > error) { error = distance; farthest = index }
            }
            if (farthest < 0) continue
            if (count >= limit) throw InputLimitException("mathInputDetailExceeded")
            retained[count++] = farthest
            if (farthest - first > 1) { stack[pending++] = first; stack[pending++] = farthest }
            if (last - farthest > 1) { stack[pending++] = farthest; stack[pending++] = last }
        }
        Arrays.sort(retained, offset, count)
        return count
    }

    fun space(region: PageOcrLayout.Region): Space {
        require(region.strokes.isNotEmpty()) { "noInk" }
        // Layout has already fitted one line, including its detached marks. Do not estimate a
        // second baseline from individual jamo or Latin dots. Degenerate horizontal ink still
        // needs a finite area, without inventing replacement coordinates.
        val lineHeight = if (region.bounds.height > .001f) region.bounds.height
            else region.bounds.width.coerceAtLeast(1f)
        val padding = lineHeight * .08f
        val scale = 128f / (lineHeight + padding * 2)
        return Space(region.bounds.left, region.bounds.top, padding, scale,
            ((region.bounds.width + padding * 2) * scale).coerceAtLeast(1f), 128f)
    }
}
