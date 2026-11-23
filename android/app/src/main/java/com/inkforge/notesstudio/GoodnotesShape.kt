package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Strict decoder for the four field-9 vector geometries observed in Goodnotes kind-7 objects. */
internal object GoodnotesShape {
    private const val MAX_COORDINATE = 100_000f
    private const val MAX_POLYGON_POINTS = 100_000

    private data class Point(val x: Float, val y: Float)
    private data class Style(val color: String, val opacity: Double, val width: Float)

    fun decode(body: GoodnotesMessage, dx: Float, dy: Float): List<JSONObject>? = try {
        require(dx.isFinite() && dy.isFinite())
        val raw = body.firstOrNull { it.number == 9 && it.data?.isNotEmpty() == true }?.data
            ?: return null
        val geometry = GoodnotesWire.read(raw)
        val populated = (1..4).mapNotNull { number ->
            geometry.firstOrNull { it.number == number && it.data?.isNotEmpty() == true }
                ?.data?.let { number to it }
        }
        require(populated.size == 1)
        val width = fixedFloat(geometry, 15)?.takeIf { it in .05f..200f } ?: return null
        val style = style(body, width)
        val result = when (val kind = populated.single().first) {
            1 -> polygon(populated.single().second, style, dx, dy)
            2 -> quadratic(populated.single().second, style, dx, dy)
            3 -> rectangle(populated.single().second, style, dx, dy)
            4 -> ellipse(populated.single().second, style, dx, dy)
            else -> null
        } ?: return null
        listOf(result)
    } catch (_: RuntimeException) {
        null
    }

    private fun polygon(raw: ByteArray, style: Style, dx: Float, dy: Float): JSONObject? {
        val message = GoodnotesWire.read(raw)
        val pointFields = message.filter { it.number == 1 && it.data != null }
        if (pointFields.size !in 2..MAX_POLYGON_POINTS ||
            message.any { it.number != 1 }) return null
        val points = pointFields.map { point(checkNotNull(it.data), dx, dy) ?: return null }
        if (points.size == 2) return nativeShape("line", points[0], points[1], style)

        val values = JSONArray()
        for (point in points) values.put(InkPoint(point.x, point.y, 1f).json())
        return json(
            "id" to uid("stroke"), "type" to "stroke", "brush" to "ballpoint",
            "color" to style.color, "opacity" to style.opacity, "width" to style.width,
            "widthProfile" to "xournal", "settings" to json("smoothing" to 0),
            "points" to values, "goodnotesShape" to "polygon"
        )
    }

    private fun quadratic(raw: ByteArray, style: Style, dx: Float, dy: Float): JSONObject? {
        val message = GoodnotesWire.read(raw)
        if (message.any { it.number !in 1..3 } || (1..3).any { number ->
                message.count { it.number == number && it.data != null } != 1
            }) return null
        val start = nestedPoint(message, 1, dx, dy) ?: return null
        val control = nestedPoint(message, 2, dx, dy) ?: return null
        val end = nestedPoint(message, 3, dx, dy) ?: return null
        return nativeShape("curve", start, end, style).put("cx", control.x).put("cy", control.y)
    }

    private fun rectangle(raw: ByteArray, style: Style, dx: Float, dy: Float): JSONObject? {
        val message = GoodnotesWire.read(raw)
        if (message.any { it.number !in 1..3 }) return null
        val center = nestedPoint(message, 1, dx, dy) ?: return null
        // The size vector is local, so a document translation must not be applied to it.
        val size = nestedPoint(message, 2, 0f, 0f) ?: return null
        if (size.x <= 0f || size.y <= 0f) return null
        val rotation = fixedFloat(message, 3) ?: 0f
        if (!rotation.isFinite()) return null
        return nativeShape(
            "rectangle", Point(center.x - size.x / 2f, center.y - size.y / 2f),
            Point(center.x + size.x / 2f, center.y + size.y / 2f), style
        ).put("rotation", rotation)
    }

    private fun ellipse(raw: ByteArray, style: Style, dx: Float, dy: Float): JSONObject? {
        val message = GoodnotesWire.read(raw)
        if (message.any { it.number !in 1..3 }) return null
        val center = nestedPoint(message, 1, dx, dy) ?: return null
        // Field 2 stores semi-axes rather than the full size used by rectangles.
        val axes = nestedPoint(message, 2, 0f, 0f) ?: return null
        if (axes.x <= 0f || axes.y <= 0f) return null
        val rotation = fixedFloat(message, 3) ?: 0f
        if (!rotation.isFinite()) return null
        return nativeShape(
            "ellipse", Point(center.x - axes.x, center.y - axes.y),
            Point(center.x + axes.x, center.y + axes.y), style
        ).put("rotation", rotation)
    }

    private fun nativeShape(name: String, start: Point, end: Point, style: Style) = json(
        "id" to uid("shape"), "type" to "shape", "shape" to name,
        "color" to style.color, "opacity" to style.opacity,
        "width" to style.width, "screenWidth" to style.width,
        "x1" to start.x, "y1" to start.y, "x2" to end.x, "y2" to end.y
    )

    private fun nestedPoint(message: GoodnotesMessage, number: Int, dx: Float, dy: Float): Point? {
        val fields = message.filter { it.number == number && it.data != null }
        if (fields.size != 1) return null
        return point(checkNotNull(fields[0].data), dx, dy)
    }

    private fun point(raw: ByteArray, dx: Float, dy: Float): Point? {
        val message = GoodnotesWire.read(raw)
        if (message.any { it.number !in 1..2 } || message.count { it.number == 1 } != 1 ||
            message.count { it.number == 2 } != 1) return null
        val x = fixedFloat(message, 1) ?: return null
        val y = fixedFloat(message, 2) ?: return null
        val translatedX = x + dx
        val translatedY = y + dy
        if (!translatedX.isFinite() || !translatedY.isFinite() ||
            abs(translatedX) > MAX_COORDINATE || abs(translatedY) > MAX_COORDINATE) return null
        return Point(translatedX, translatedY)
    }

    private fun style(body: GoodnotesMessage, width: Float): Style {
        val colorRaw = body.firstOrNull { it.number == 4 && it.data != null }?.data
        val color = colorRaw?.let(GoodnotesWire::read)
        fun component(number: Int, fallback: Float): Float = color?.let { fixedFloat(it, number) }
            ?.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: fallback
        val red = component(1, 0f)
        val green = component(2, 0f)
        val blue = component(3, 0f)
        val alpha = component(4, 1f)
        val hex = "#%02x%02x%02x".format(
            (red * 255f).roundToInt(), (green * 255f).roundToInt(), (blue * 255f).roundToInt()
        )
        return Style(hex, alpha.toDouble(), width)
    }

    private fun fixedFloat(message: GoodnotesMessage, number: Int): Float? {
        val fields = message.filter { it.number == number && it.wire == 5 }
        if (fields.size != 1) return null
        return Float.fromBits(fields[0].value.toInt()).takeIf(Float::isFinite)
    }
}
