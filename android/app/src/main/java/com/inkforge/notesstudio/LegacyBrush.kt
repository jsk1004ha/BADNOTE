package com.inkforge.notesstudio

import org.json.JSONObject
import kotlin.math.*

/** The 3.x brush arithmetic. Coordinates stay Double until a Canvas command is drawn. */
object LegacyBrush {
    internal const val STABLE_BLOCK_SEGMENTS = 64
    /** Primitive drawing boundary shared by committed and live ink. */
    internal interface DrawingSink {
        fun save()
        fun restore()
        fun alpha(value: Double)
        fun strokeColor(value: String)
        fun fillColor(value: String)
        fun cap(butt: Boolean)
        fun roundJoin()
        fun width(value: Double)
        fun multiply()
        fun beginSegmentBlock(first: Int, count: Int, stable: Boolean, context: WidthContext,
                              widthScale: Double): Boolean
        fun beginPath(segment: Int = -1)
        fun moveTo(x: Double, y: Double)
        fun lineTo(x: Double, y: Double)
        fun quadraticTo(cx: Double, cy: Double, x: Double, y: Double)
        fun arc(x: Double, y: Double, radius: Double, start: Double, end: Double)
        fun stroke()
        fun fill()
    }

    internal data class Point(val x: Double, val y: Double, val p: Double?,
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
    private fun copiedNumber(value: Any?): Double? = when (value) {
        null -> null
        JSONObject.NULL -> null
        is Number -> JSONObject.numberToString(value).toDoubleOrNull()
        is Boolean -> if (value) 1.0 else 0.0
        else -> value.toString().toDoubleOrNull()
    }?.takeIf { it.isFinite() }

    private fun field(data: JSONObject, key: String) = if (data.has(key)) number(data.opt(key)) else null
    private fun copiedField(data: JSONObject, key: String) =
        if (data.has(key)) copiedNumber(data.opt(key)) else null
    private fun value(data: JSONObject?, fallback: Map<String, Double>, key: String,
                      default: Double, truthy: Boolean = false): Double {
        val found = if (data == null) fallback[key] else field(data, key)
        return if (found == null || truthy && found == 0.0) default else found
    }
    private fun jsOr(value: Double?, fallback: Double) = if (value == null || value == 0.0) fallback else value
    private fun clamp(value: Double, min: Double, max: Double) = value.coerceIn(min, max)
    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
    private fun jsRound(value: Double) = floor(value + .5).toInt()
    private fun point(item: InkPoint, copiedCoordinatesAndPressure: Boolean): Point {
        val original = item.original
        val x = if (copiedCoordinatesAndPressure) copiedNumber(item.x) else number(item.x)
        val y = if (copiedCoordinatesAndPressure) copiedNumber(item.y) else number(item.y)
        val pressure = if (copiedCoordinatesAndPressure) copiedNumber(item.pressure) else number(item.pressure)
        return Point(requireNotNull(x), requireNotNull(y),
            if (original == null) pressure else copiedField(original, "p"),
            if (original == null) copiedNumber(item.azimuth) else copiedField(original, "azimuth"),
            if (original == null) copiedNumber(item.tx) else copiedField(original, "tx"),
            if (original == null) copiedNumber(item.ty) else copiedField(original, "ty"))
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

    private fun smoothedPoints(stroke: JSONObject):List<Point>{
        val input=stroke.optJSONArray("points")?:return emptyList()
        val amount=value(settings(stroke),defaultSettings(brush(stroke)),"smoothing",.3)
        fun coordinate(item:JSONObject,key:String):Double{
            val raw=item.get(key)
            return if(raw is Number)requireNotNull(copiedNumber(raw)) else item.getDouble(key)
        }
        fun copiedPoint(item:JSONObject)=Point(coordinate(item,"x"),coordinate(item,"y"),
            copiedField(item,"p"),copiedField(item,"azimuth"),copiedField(item,"tx"),copiedField(item,"ty"))
        if(input.length()<3||amount<=0)return (0 until input.length()).map{copiedPoint(input.getJSONObject(it))}
        val factor=clamp(amount,0.0,.92)
        val output=ArrayList<Point>(input.length())
        output+=copiedPoint(input.getJSONObject(0))
        for(index in 1 until input.length()){
            val previous=output.last();val current=input.getJSONObject(index)
            // Match smoothed() arithmetic and JSON number normalization without copying extension trees.
            val x=current.getDouble("x");val y=current.getDouble("y")
            output+=smoothPoint(previous, Point(x,y,field(current,"p"),
                copiedField(current,"azimuth"),copiedField(current,"tx"),copiedField(current,"ty")),factor)
        }
        return output
    }

    private fun smoothPoint(previous: Point, current: Point, factor: Double) = current.copy(
        x = lerp(current.x, (previous.x + current.x) * .5, factor),
        y = lerp(current.y, (previous.y + current.y) * .5, factor),
        p = lerp(current.p ?: .5, previous.p ?: .5, factor * .28))

    /** Smoothing is causal, except for the original one/two-point special case. */
    internal class LiveSmoothing {
        private var source: Any? = null
        private var amount = Double.NaN
        private var smoothing = false
        private val output = ArrayList<Point>()
        internal var geometryVersion = 0
            private set
        private var widthContext: WidthContext? = null
        private var bodyWidths = DoubleArray(64) { Double.NaN }

        internal fun width(context: WidthContext, a: Point, b: Point, index: Int, total: Int): Double {
            if (widthContext !== context) {
                if (widthContext?.matches(context) != true) java.util.Arrays.fill(bodyWidths, Double.NaN)
                widthContext = context
            }
            // Never freeze a fixed tail: both endpoint tapers must currently equal one.
            val stable = stable(context, index, total)
            if (stable && index < bodyWidths.size && !bodyWidths[index].isNaN()) return bodyWidths[index]
            val width = (segmentWidth(context, a, b, index, total) +
                segmentWidth(context, b, a, index + 1, total)) / 2
            if (stable) {
                if (index >= bodyWidths.size) {
                    val oldSize = bodyWidths.size
                    bodyWidths = bodyWidths.copyOf(max(index + 1, oldSize * 2))
                    java.util.Arrays.fill(bodyWidths, oldSize, bodyWidths.size, Double.NaN)
                }
                bodyWidths[index] = width
            }
            return width
        }

        internal fun stable(context: WidthContext, index: Int, total: Int): Boolean {
            if (context.taper == 0.0 || context.brush == "highlighter") return true
            val first = index.toDouble() / (total - 1)
            val second = (index + 1).toDouble() / (total - 1)
            return first / .045 >= 1 && (1 - first) / .07 >= 1 &&
                second / .045 >= 1 && (1 - second) / .07 >= 1
        }

        internal fun points(stroke: JSONObject, input: InkPointSnapshot): List<Point> {
            val nextAmount = value(settings(stroke), defaultSettings(brush(stroke)), "smoothing", .3)
            val nextSmoothing = input.size >= 3 && nextAmount > 0
            if (source !== input.source || amount != nextAmount || smoothing != nextSmoothing ||
                output.size > input.size) {
                output.clear(); source = input.source; amount = nextAmount; smoothing = nextSmoothing
                geometryVersion++
                widthContext = null
                java.util.Arrays.fill(bodyWidths, Double.NaN)
            }
            val factor = clamp(amount, 0.0, .92)
            for (index in output.size until input.size) {
                val current = point(input[index], !smoothing || index == 0)
                output += if (!smoothing || index == 0) current else smoothPoint(output.last(), current, factor)
            }
            return output
        }
    }

    private fun smoothedPoints(stroke: JSONObject, input: List<InkPoint>): List<Point> {
        val amount = value(settings(stroke), defaultSettings(brush(stroke)), "smoothing", .3)
        if (input.size < 3 || amount <= 0) return input.map { point(it, true) }
        val factor = clamp(amount, 0.0, .92)
        val output = ArrayList<Point>(input.size)
        output += point(input.first(), true)
        for (index in 1 until input.size) output += smoothPoint(output.last(), point(input[index], false), factor)
        return output
    }

    private fun pencilRenderPoints(stroke: JSONObject, smooth: List<Point>): List<Point> {
        if (smooth.size <= 2) return smooth
        val minDistance = clamp(jsOr(field(stroke, "width"), 4.0) * .2, 1.2, 3.4)
        val output = ArrayList<Point>()
        output += smooth.first()
        var last = smooth.first()
        for (index in 1 until smooth.lastIndex) {
            val point = smooth[index]
            val dx = point.x - last.x
            val dy = point.y - last.y
            if (dx * dx + dy * dy >= minDistance * minDistance) { output += point; last = point }
        }
        if (output.last() !== smooth.last()) output += smooth.last()
        if (output.size <= 260) return output
        val stride = ceil(output.size / 260.0).toInt()
        return buildList { for (index in 0 until output.lastIndex step stride) add(output[index]); add(output.last()) }
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
    internal class WidthContext(stroke: JSONObject) {
        val rawWidth = jsOr(field(stroke, "width"), 4.0)
        val base = max(.35, rawWidth)
        val brush = brush(stroke)
        private val settings = settings(stroke)
        private val fallback = defaultSettings(brush)
        val taper = value(settings, fallback, "taper", 0.0, true)
        val pressure = value(settings, fallback, "pressure",
            when (brush) { "ballpoint" -> .08; "brush" -> .95; else -> .76 }, true)
        val tiltShade = value(settings, fallback, "tiltShade", .82, true)
        fun matches(other: WidthContext) = brush == other.brush && rawWidth == other.rawWidth &&
            taper == other.taper && pressure == other.pressure && tiltShade == other.tiltShade
    }
    private fun segmentWidth(context: WidthContext, point: Point, next: Point?, index: Int,
                             total: Int): Double {
        val base = context.base
        val brush = context.brush
        val pressure = pointPressure(point, if (brush == "ballpoint" || brush == "fineliner") .54 else .42)
        val progress = if (total > 1) index.toDouble() / (total - 1) else 0.0
        val startTaper = clamp(progress / .045, 0.0, 1.0)
        val endTaper = clamp((1 - progress) / .07, 0.0, 1.0)
        val taper = lerp(1.0, min(startTaper, endTaper), context.taper)
        val direction = if (next == null) 0.0 else atan2(next.y - point.y, next.x - point.x)
        val stylusAngle = point.azimuth ?: direction
        val nib = .72 + .28 * abs(sin(direction - stylusAngle))
        return when (brush) {
            "ballpoint" -> base * lerp(.94, 1.08, pressure * context.pressure) * taper
            "fineliner" -> base * (.98 + pressure * .025) * taper
            "gel" -> base * (.88 + pressure * .24) * taper
            "brush" -> base * (.18 + pressure * 1.32 * context.pressure) * taper
            "pencil" -> {
                val tilt = hypot(point.tx ?: 0.0, point.ty ?: 0.0) / 90
                base * (.45 + pressure * .65 + tilt * context.tiltShade * 1.35)
            }
            "highlighter" -> base
            else -> base * (.28 + pressure * (1.04 + context.pressure * .48)) * nib * taper
        }
    }

    /**
     * Conservative transient-ink padding, evaluated through the same width arithmetic.
     * Smoothing is convex in position. Pressure and taper extrema bound every segment;
     * pencil additionally needs its complete scatter/particle envelope.
     */
    internal fun frontPadding(stroke: JSONObject, points: InkPointSnapshot): Double? {
        if (points.hasOriginal) return null
        val context = WidthContext(stroke)
        var width = .25
        for (pressureIndex in 0..1) {
            val pressure = if (pressureIndex == 0) .02 else 1.0
            val point = Point(0.0, 0.0, pressure, PI / 2, points.maxTilt, 0.0)
            val next = point.copy(x = 1.0)
            for (index in 0..2) width = max(width, abs(segmentWidth(context, point, next, index, 3)))
        }
        val padding = if (context.brush == "pencil")
            width * 2 + abs(context.rawWidth) * 2 + points.maxTilt * .05 + 4
        else width * 1.2 + 3
        return padding.takeIf { it.isFinite() }
    }

    fun widths(stroke: JSONObject): List<Double> {
        val rendered = if (brush(stroke) == "pencil") pencilRenderPoints(stroke).map { item ->
            Point(item.getDouble("x"), item.getDouble("y"), field(item, "p"),
                field(item, "azimuth"), field(item, "tx"), field(item, "ty")) }
            else smoothedPoints(stroke)
        val context = WidthContext(stroke)
        return rendered.mapIndexed { index, point -> segmentWidth(context, point,
            rendered.getOrNull(index + 1), index, rendered.size) }
    }

    /** screenWidth is CSS dp; baseCssWidth excludes zoom, matching the original tool contract. */
    fun screenToolWidthToPage(cssWidth: Double, width: Double) = max(.25,
        max(.5, jsOr(width, 1.0)) / cssWidth * 1000.0)
    fun toolStrokeWidthToPage(baseCssWidth: Double, width: Double) = max(.25,
        max(.5, jsOr(width, 1.0)) / max(1.0, baseCssWidth) * 1000.0)

    private fun strokePath(sink: DrawingSink, points: List<Point>, context: WidthContext,
                           opacity: Double, color: String, butt: Boolean = false, widthScale: Double = 1.0,
                           live: LiveSmoothing? = null) {
        if (points.isEmpty()) return
        sink.save(); sink.alpha(opacity)
        sink.strokeColor(color); sink.fillColor(color)
        sink.cap(butt); sink.roundJoin()
        if (points.size == 1) {
            sink.beginPath(); sink.arc(points[0].x, points[0].y,
                segmentWidth(context, points[0], null, 0, 1) / 2, 0.0, PI * 2)
            sink.fill(); sink.restore(); return
        }
        var index = 1
        while (index < points.size) {
            val first = index - 1
            if (first % STABLE_BLOCK_SEGMENTS == 0) {
                val count = min(STABLE_BLOCK_SEGMENTS, points.size - 1 - first)
                val stable = count == STABLE_BLOCK_SEGMENTS && live != null &&
                    live.stable(context, first, points.size) &&
                    live.stable(context, first + count - 1, points.size)
                if (sink.beginSegmentBlock(first, count, stable, context, widthScale)) {
                    index += count
                    continue
                }
            }
            val a = points[index - 1]; val b = points[index]
            val width = live?.width(context, a, b, index - 1, points.size) ?:
                ((segmentWidth(context, a, b, index - 1, points.size) +
                    segmentWidth(context, b, a, index, points.size)) / 2)
            sink.width(max(.25, width * jsOr(widthScale, 1.0)))
            sink.beginPath(index - 1); sink.moveTo(a.x, a.y)
            sink.quadraticTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
            sink.lineTo(b.x, b.y); sink.stroke()
            index++
        }
        sink.restore()
    }

    internal fun renderTyped(stroke: JSONObject, renderScale: Double, sink: DrawingSink) {
        val smooth = smoothedPoints(stroke)
        renderPoints(stroke, smooth, renderScale, sink, null) { pencilRenderPoints(stroke, smooth) }
    }

    internal fun renderTyped(stroke: JSONObject, points: List<InkPoint>, renderScale: Double,
                             sink: DrawingSink, live: LiveSmoothing? = null) {
        val smooth = if (points is InkPointSnapshot && live != null) live.points(stroke, points)
            else smoothedPoints(stroke, points)
        renderPoints(stroke, smooth, renderScale, sink, if (points is InkPointSnapshot) live else null) {
            pencilRenderPoints(stroke, smooth)
        }
    }

    private inline fun renderPoints(stroke: JSONObject, smooth: List<Point>, renderScale: Double,
                                    sink: DrawingSink, live: LiveSmoothing?, pencilPoints: () -> List<Point>) {
        if (smooth.isEmpty()) return
        val brush = brush(stroke); val settings = settings(stroke); val fallback = defaultSettings(brush)
        val context = WidthContext(stroke)
        val color = stroke.optString("color").ifBlank { "#111827" }
        val opacity = field(stroke, "opacity") ?: value(settings, fallback, "opacity", 1.0)
        when (brush) {
            "pencil" -> {
                val pencilOpacity = field(stroke, "opacity") ?:
                    (if (settings == null) fallback["opacity"] else field(settings, "opacity")) ?: .78
                val source = pencilPoints()
                val random = Random(stroke.optString("id"))
                val pencilColor = stroke.optString("color").ifBlank { "#30343a" }
                sink.save(); sink.multiply()
                strokePath(sink, source, context, pencilOpacity * .56, pencilColor, widthScale = .7)
                val grain = clamp(value(settings, fallback, "grain", .66), 0.0, 1.0)
                val layers = 2 + jsRound(grain * 2)
                val highCost = max(1.0, abs(renderScale)) > 2.6 || source.size > 180
                for (layer in 0 until layers) {
                    if (highCost && layer > 1) break
                    val scatter = (.18 + grain * 1.08) * jsOr(field(stroke, "width"), 4.0) / 4
                    val offset = source.map { point -> point.shifted((random.next() - .5) * scatter +
                        (point.tx ?: 0.0) / 90 * (layer - layers / 2.0) * .18,
                        (random.next() - .5) * scatter + (point.ty ?: 0.0) / 90 * (layer - layers / 2.0) * .18) }
                    strokePath(sink, offset, context, (.05 + random.next() * .05) * pencilOpacity,
                        pencilColor, widthScale = .28 + random.next() * .24)
                }
                val particleLimit = if (highCost) 28 else 48
                val step = max(1, ceil(source.size.toDouble() / particleLimit).toInt())
                for (index in source.indices step step) {
                    val point = source[index]
                    val width = segmentWidth(context, point, source.getOrNull(index + 1), index, source.size)
                    val particles = if (highCost) 1 else 1 + jsRound(grain * 2)
                    repeat(particles) {
                        sink.alpha((.035 + random.next() * .08) * pencilOpacity)
                        sink.fillColor(pencilColor)
                        sink.beginPath()
                        sink.arc(point.x + (random.next() - .5) * width,
                            point.y + (random.next() - .5) * width, .28 + random.next() * .55, 0.0, PI * 2)
                        sink.fill()
                    }
                }
                sink.restore()
            }
            "highlighter" -> {
                sink.save(); sink.multiply()
                strokePath(sink, smooth, context, field(stroke, "opacity") ?: .28, color, true, live = live)
                sink.restore()
            }
            "gel" -> {
                strokePath(sink, smooth, context, field(stroke, "opacity") ?: .98, color, widthScale = 1.12, live = live)
                strokePath(sink, smooth, context, .38, "#ffffff", widthScale = .28, live = live)
            }
            "fineliner" -> strokePath(sink, smooth, context, field(stroke, "opacity") ?: .98, color, live = live)
            else -> strokePath(sink, smooth, context, opacity, color, live = live)
        }
    }
}
