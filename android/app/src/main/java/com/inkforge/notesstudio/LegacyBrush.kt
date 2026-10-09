package com.inkforge.notesstudio

import org.json.JSONObject
import kotlin.math.*

/** The 3.x brush arithmetic. Coordinates stay Double until a Canvas command is drawn. */
object LegacyBrush {
    data class Operation(val name: String, val args: List<Any?> = emptyList())
    fun interface Sink { fun emit(operation: Operation) }

    private data class Point(val x: Double, val y: Double, val p: Double?,
                             val azimuth: Double?, val tx: Double?, val ty: Double?) {
        fun shifted(dx: Double, dy: Double) = copy(x = x + dx, y = y + dy)
    }

    private val defaults = mapOf(
        "fountain" to mapOf("pressure" to .76, "smoothing" to .35, "taper" to .42, "opacity" to 1.0),
        "ballpoint" to mapOf("pressure" to .08, "smoothing" to .48, "taper" to .12, "opacity" to 1.0),
        "gel" to mapOf("pressure" to .16, "smoothing" to .45, "taper" to .2, "opacity" to 1.0),
        "brush" to mapOf("pressure" to .95, "smoothing" to .28, "taper" to .72, "opacity" to .96),
        "pencil" to mapOf("pressure" to .7, "smoothing" to .26, "grain" to .66,
            "tiltShade" to .82, "opacity" to .78),
        "fineliner" to mapOf("pressure" to .02, "smoothing" to .6, "taper" to .06, "opacity" to .98))

    private fun number(value: Any?): Double? = when (value) {
        null -> null
        JSONObject.NULL -> null
        is Number -> value.toDouble()
        is Boolean -> if (value) 1.0 else 0.0
        else -> value.toString().toDoubleOrNull()
    }?.takeIf { it.isFinite() }

    private fun field(data: JSONObject, key: String) = if (data.has(key)) number(data.opt(key)) else null
    private fun value(data: JSONObject?, fallback: Map<String, Double>, key: String,
                      default: Double, truthy: Boolean = false): Double {
        val found = if (data == null) fallback[key] else field(data, key)
        return if (found == null || truthy && found == 0.0) default else found
    }
    private fun jsOr(value: Double?, fallback: Double) = if (value == null || value == 0.0) fallback else value
    private fun clamp(value: Double, min: Double, max: Double) = value.coerceIn(min, max)
    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
    private fun jsRound(value: Double) = floor(value + .5).toInt()
    private fun points(stroke: JSONObject): List<Point> {
        val input = stroke.optJSONArray("points") ?: return emptyList()
        return (0 until input.length()).map { index ->
            val item = input.getJSONObject(index)
            Point(item.getDouble("x"), item.getDouble("y"), field(item, "p"),
                field(item, "azimuth"), field(item, "tx"), field(item, "ty"))
        }
    }
    private fun brush(stroke: JSONObject) = stroke.optString("brush").ifBlank { "fountain" }
    private fun settings(stroke: JSONObject) = stroke.optJSONObject("settings")
    private fun defaultSettings(brush: String) = defaults[brush] ?: defaults.getValue("fountain")

    /** JS String.charCodeAt iterates UTF-16 code units; Int multiplication wraps like Math.imul. */
    class Random(seedText: String) {
        private var seed = 2166136261L.toInt()
        init { (seedText.ifEmpty { "ink" }).forEach { char ->
            seed = (seed xor char.code) * 16777619
        } }
        fun next(): Double {
            seed += 0x6D2B79F5
            var t = seed
            t = (t xor (t ushr 15)) * (t or 1)
            t = t xor (t + (t xor (t ushr 7)) * (t or 61))
            return (t xor (t ushr 14)).toUInt().toLong() / 4294967296.0
        }
    }

    fun smoothed(stroke: JSONObject): List<JSONObject> {
        val source = stroke.optJSONArray("points") ?: return emptyList()
        val amount = value(settings(stroke), defaultSettings(brush(stroke)), "smoothing", .3)
        if (source.length() < 3 || amount <= 0) return (0 until source.length()).map { source.getJSONObject(it).copyJson() }
        val factor = clamp(amount, 0.0, .92)
        val output = ArrayList<JSONObject>(source.length())
        output += source.getJSONObject(0).copyJson()
        for (index in 1 until source.length()) {
            val previous = output.last()
            val current = source.getJSONObject(index)
            output += current.copyJson().put("x", lerp(current.getDouble("x"),
                (previous.getDouble("x") + current.getDouble("x")) * .5, factor))
                .put("y", lerp(current.getDouble("y"),
                    (previous.getDouble("y") + current.getDouble("y")) * .5, factor))
                .put("p", lerp(field(current, "p") ?: .5, field(previous, "p") ?: .5, factor * .28))
        }
        return output
    }

    private fun smoothedPoints(stroke: JSONObject) = smoothed(stroke).map { item ->
        Point(item.getDouble("x"), item.getDouble("y"), field(item, "p"),
            field(item, "azimuth"), field(item, "tx"), field(item, "ty"))
    }

    fun pencilRenderPoints(stroke: JSONObject): List<JSONObject> {
        val smooth = smoothed(stroke)
        if (smooth.size <= 2) return smooth
        val minDistance = clamp(jsOr(field(stroke, "width"), 4.0) * .2, 1.2, 3.4)
        val output = ArrayList<JSONObject>()
        output += smooth.first()
        var last = smooth.first()
        for (index in 1 until smooth.lastIndex) {
            val point = smooth[index]
            val dx = point.getDouble("x") - last.getDouble("x")
            val dy = point.getDouble("y") - last.getDouble("y")
            if (dx * dx + dy * dy >= minDistance * minDistance) { output += point; last = point }
        }
        if (output.last() !== smooth.last()) output += smooth.last()
        if (output.size <= 260) return output
        val stride = ceil(output.size / 260.0).toInt()
        return buildList { for (index in 0 until output.lastIndex step stride) add(output[index]); add(output.last()) }
    }

    private fun pointPressure(point: Point, fallback: Double): Double {
        val pressure = point.p ?: Double.NaN
        return if (!pressure.isFinite() || pressure <= 0) fallback else clamp(pressure, .02, 1.0)
    }
    private fun segmentWidth(stroke: JSONObject, point: Point, next: Point?, index: Int,
                             total: Int): Double {
        val base = max(.35, jsOr(field(stroke, "width"), 4.0))
        val brush = brush(stroke)
        val settings = settings(stroke)
        val fallback = defaultSettings(brush)
        val pressure = pointPressure(point, if (brush == "ballpoint" || brush == "fineliner") .54 else .42)
        val progress = if (total > 1) index.toDouble() / (total - 1) else 0.0
        val startTaper = clamp(progress / .045, 0.0, 1.0)
        val endTaper = clamp((1 - progress) / .07, 0.0, 1.0)
        val taper = lerp(1.0, min(startTaper, endTaper), value(settings, fallback, "taper", 0.0, true))
        val direction = if (next == null) 0.0 else atan2(next.y - point.y, next.x - point.x)
        val stylusAngle = point.azimuth ?: direction
        val nib = .72 + .28 * abs(sin(direction - stylusAngle))
        return when (brush) {
            "ballpoint" -> base * lerp(.94, 1.08, pressure * value(settings, fallback, "pressure", .08, true)) * taper
            "fineliner" -> base * (.98 + pressure * .025) * taper
            "gel" -> base * (.88 + pressure * .24) * taper
            "brush" -> base * (.18 + pressure * 1.32 * value(settings, fallback, "pressure", .95, true)) * taper
            "pencil" -> {
                val tilt = hypot(point.tx ?: 0.0, point.ty ?: 0.0) / 90
                base * (.45 + pressure * .65 + tilt * value(settings, fallback, "tiltShade", .82, true) * 1.35)
            }
            "highlighter" -> base
            else -> base * (.28 + pressure * (1.04 + value(settings, fallback, "pressure", .76, true) * .48)) * nib * taper
        }
    }

    fun widths(stroke: JSONObject): List<Double> {
        val rendered = if (brush(stroke) == "pencil") pencilRenderPoints(stroke).map { item ->
            Point(item.getDouble("x"), item.getDouble("y"), field(item, "p"),
                field(item, "azimuth"), field(item, "tx"), field(item, "ty")) }
            else smoothedPoints(stroke)
        return rendered.mapIndexed { index, point -> segmentWidth(stroke, point,
            rendered.getOrNull(index + 1), index, rendered.size) }
    }

    /** screenWidth is CSS dp; baseCssWidth excludes zoom, matching the original tool contract. */
    fun screenToolWidthToPage(cssWidth: Double, width: Double) = max(.25,
        max(.5, jsOr(width, 1.0)) / cssWidth * 1000.0)
    fun toolStrokeWidthToPage(baseCssWidth: Double, width: Double) = max(.25,
        max(.5, jsOr(width, 1.0)) / max(1.0, baseCssWidth) * 1000.0)

    private fun emit(sink: Sink, name: String, vararg args: Any?) = sink.emit(Operation(name, args.toList()))
    private fun strokePath(sink: Sink, points: List<Point>, stroke: JSONObject,
                           opacity: Double, color: String, cap: String = "round", widthScale: Double = 1.0) {
        if (points.isEmpty()) return
        emit(sink, "save");emit(sink, "set", "globalAlpha", opacity)
        emit(sink, "set", "strokeStyle", color);emit(sink, "set", "fillStyle", color)
        emit(sink, "set", "lineCap", cap);emit(sink, "set", "lineJoin", "round")
        if (points.size == 1) {
            emit(sink, "beginPath");emit(sink, "arc", points[0].x, points[0].y,
                segmentWidth(stroke, points[0], null, 0, 1) / 2, 0.0, PI * 2)
            emit(sink, "fill");emit(sink, "restore");return
        }
        for (index in 1 until points.size) {
            val a = points[index - 1]; val b = points[index]
            emit(sink, "set", "lineWidth", max(.25, (segmentWidth(stroke, a, b, index - 1, points.size) +
                segmentWidth(stroke, b, a, index, points.size)) / 2 * jsOr(widthScale, 1.0)))
            emit(sink, "beginPath");emit(sink, "moveTo", a.x, a.y)
            emit(sink, "quadraticCurveTo", a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
            emit(sink, "lineTo", b.x, b.y);emit(sink, "stroke")
        }
        emit(sink, "restore")
    }

    fun render(stroke: JSONObject, renderScale: Double = 1.0, sink: Sink) {
        val smooth = smoothedPoints(stroke)
        if (smooth.isEmpty()) return
        val brush = brush(stroke);val settings = settings(stroke);val fallback = defaultSettings(brush)
        val color = stroke.optString("color").ifBlank { "#111827" }
        val opacity = field(stroke, "opacity") ?: value(settings, fallback, "opacity", 1.0)
        when (brush) {
            "pencil" -> {
                val pencilOpacity = field(stroke, "opacity") ?:
                    (if (settings == null) fallback["opacity"] else field(settings, "opacity")) ?: .78
                val source = pencilRenderPoints(stroke).map { item -> Point(item.getDouble("x"), item.getDouble("y"),
                    field(item, "p"), field(item, "azimuth"), field(item, "tx"), field(item, "ty")) }
                val random = Random(stroke.optString("id"))
                val pencilColor = stroke.optString("color").ifBlank { "#30343a" }
                emit(sink, "save");emit(sink, "set", "globalCompositeOperation", "multiply")
                strokePath(sink, source, stroke, pencilOpacity * .56, pencilColor, widthScale = .7)
                val grain = clamp(value(settings, fallback, "grain", .66), 0.0, 1.0)
                val layers = 2 + jsRound(grain * 2)
                val highCost = max(1.0, abs(renderScale)) > 2.6 || source.size > 180
                for (layer in 0 until layers) {
                    if (highCost && layer > 1) break
                    val scatter = (.18 + grain * 1.08) * jsOr(field(stroke, "width"), 4.0) / 4
                    val offset = source.map { point -> point.shifted((random.next() - .5) * scatter +
                        (point.tx ?: 0.0) / 90 * (layer - layers / 2.0) * .18,
                        (random.next() - .5) * scatter + (point.ty ?: 0.0) / 90 * (layer - layers / 2.0) * .18) }
                    strokePath(sink, offset, stroke, (.05 + random.next() * .05) * pencilOpacity,
                        pencilColor, widthScale = .28 + random.next() * .24)
                }
                val particleLimit = if (highCost) 28 else 48
                val step = max(1, ceil(source.size.toDouble() / particleLimit).toInt())
                for (index in source.indices step step) {
                    val point = source[index]
                    val width = segmentWidth(stroke, point, source.getOrNull(index + 1), index, source.size)
                    val particles = if (highCost) 1 else 1 + jsRound(grain * 2)
                    repeat(particles) {
                        emit(sink, "set", "globalAlpha", (.035 + random.next() * .08) * pencilOpacity)
                        emit(sink, "set", "fillStyle", pencilColor)
                        emit(sink, "beginPath")
                        emit(sink, "arc", point.x + (random.next() - .5) * width,
                            point.y + (random.next() - .5) * width, .28 + random.next() * .55, 0.0, PI * 2)
                        emit(sink, "fill")
                    }
                }
                emit(sink, "restore")
            }
            "highlighter" -> {
                emit(sink, "save");emit(sink, "set", "globalCompositeOperation", "multiply")
                strokePath(sink, smooth, stroke, field(stroke, "opacity") ?: .28, color, "butt")
                emit(sink, "restore")
            }
            "gel" -> {
                strokePath(sink, smooth, stroke, field(stroke, "opacity") ?: .98, color, widthScale = 1.12)
                strokePath(sink, smooth, stroke, .38, "#ffffff", widthScale = .28)
            }
            "fineliner" -> strokePath(sink, smooth, stroke, field(stroke, "opacity") ?: .98, color)
            else -> strokePath(sink, smooth, stroke, opacity, color)
        }
    }
}
