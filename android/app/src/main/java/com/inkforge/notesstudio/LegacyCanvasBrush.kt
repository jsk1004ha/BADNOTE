package com.inkforge.notesstudio

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.roundToInt

/** Executes the same ordered 3.x brush commands on screen, active ink, PNG and PDF canvases. */
class LegacyCanvasBrush {
    private data class Style(val alpha: Double = 1.0, val strokeColor: String = "#111827",
                             val fillColor: String = "#111827", val cap: String = "round",
                             val join: String = "round", val width: Double = 1.0,
                             val composite: String = "source-over")
    fun draw(canvas: Canvas, stroke: JSONObject, renderScale: Double = 1.0) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val saves = ArrayDeque<Pair<Style, Int>>()
        var style = Style()
        var path = Path()
        fun prepare(fill: Boolean) {
            paint.reset();paint.isAntiAlias = true
            paint.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
            paint.color = try { Color.parseColor(if (fill) style.fillColor else style.strokeColor) }
                catch (_: IllegalArgumentException) { Color.BLACK }
            paint.alpha = (Color.alpha(paint.color) * style.alpha).roundToInt().coerceIn(0, 255)
            paint.strokeWidth = style.width.toFloat()
            paint.strokeCap = if (style.cap == "butt") Paint.Cap.BUTT else Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            if (style.composite == "multiply") {
                if (Build.VERSION.SDK_INT >= 29) paint.blendMode = BlendMode.MULTIPLY
                else paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
        }
        val baseline = canvas.save()
        try { LegacyBrush.render(stroke, renderScale) { operation ->
            val args = operation.args
            fun n(index: Int) = (args[index] as Number).toDouble()
            when (operation.name) {
                "save" -> saves.addLast(style to canvas.save())
                "restore" -> { val (previous, count) = saves.removeLast()
                    canvas.restoreToCount(count);style = previous }
                "set" -> { val key = args[0] as String; val value = args[1]
                    style = when (key) {
                        "globalAlpha" -> style.copy(alpha = (value as Number).toDouble())
                        "strokeStyle" -> style.copy(strokeColor = value as String)
                        "fillStyle" -> style.copy(fillColor = value as String)
                        "lineCap" -> style.copy(cap = value as String)
                        "lineJoin" -> style.copy(join = value as String)
                        "lineWidth" -> style.copy(width = (value as Number).toDouble())
                        "globalCompositeOperation" -> style.copy(composite = value as String)
                        else -> style
                    }
                }
                "beginPath" -> path = Path()
                "moveTo" -> path.moveTo(n(0).toFloat(), n(1).toFloat())
                "lineTo" -> path.lineTo(n(0).toFloat(), n(1).toFloat())
                "quadraticCurveTo" -> path.quadTo(n(0).toFloat(), n(1).toFloat(), n(2).toFloat(), n(3).toFloat())
                "arc" -> {
                    val start = n(3);val end = n(4)
                    if (end - start >= PI * 2 - 1e-9) {
                        path.addCircle(n(0).toFloat(), n(1).toFloat(), n(2).toFloat(), Path.Direction.CW)
                    } else {
                        path.addArc((n(0) - n(2)).toFloat(), (n(1) - n(2)).toFloat(),
                            (n(0) + n(2)).toFloat(), (n(1) + n(2)).toFloat(),
                            Math.toDegrees(start).toFloat(), Math.toDegrees(end - start).toFloat())
                    }
                }
                "stroke" -> { prepare(false);canvas.drawPath(path, paint) }
                "fill" -> { prepare(true);canvas.drawPath(path, paint) }
            }
        }
            check(saves.isEmpty()) { "unbalancedBrushCommands" }
        } finally { canvas.restoreToCount(baseline) }
    }
}
