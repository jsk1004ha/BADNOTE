package com.inkforge.notesstudio

import org.json.JSONObject
import kotlin.math.*
import kotlin.random.Random

/** Graphite deposited by an angled nib at regular distances along the stroke. */
internal object GraphiteBrush {
    private const val MAX_DABS = 2048
    private val opacityBands = doubleArrayOf(.17, .3, .47, .68)
    private data class Grain(val x: Double, val y: Double, val radius: Double)

    fun isGraphite(stroke: JSONObject) =
        stroke.optString("brush") == "pencil" && stroke.optString("pencilTexture") == "graphite"

    fun render(stroke: JSONObject, points: List<LegacyBrush.Point>, sink: LegacyBrush.DrawingSink) {
        val settings = stroke.optJSONObject("settings")
        fun setting(name: String, fallback: Double) = (settings?.optDouble(name, fallback) ?: fallback)
            .takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: fallback
        val baseWidth = stroke.optDouble("width", 4.0).coerceIn(.35, 240.0)
        val opacity = stroke.optDouble("opacity", setting("opacity", .82)).coerceIn(0.0, 1.0)
        val hardness = setting("hardness", .58)
        val texture = setting("grain", .66)
        val sensitivity = setting("pressure", .7)
        val tiltShade = setting("tiltShade", .82)
        val random = Random(stroke.optString("id").hashCode())
        val grains = Array(opacityBands.size) { ArrayList<Grain>() }

        fun deposit(x: Double, y: Double, pressure: Double, tx: Double, ty: Double) {
            val load = .5 + sensitivity * (pressure - .5)
            val tilt = (hypot(tx, ty) / 72).coerceIn(0.0, 1.0) * tiltShade
            val angle = atan2(ty, tx)
            val sideRadius = baseWidth * (.28 + .3 * load + .19 * tilt)
            val tiltRadius = sideRadius * (1 + 2.05 * tilt)
            val c = cos(angle)
            val s = sin(angle)
            val count = (12 + 13 * load + 7 * tilt - 5 * texture).roundToInt()
            repeat(count) {
                val phase = random.nextDouble() * 2 * PI
                val spread = sqrt(random.nextDouble())
                val along = cos(phase) * spread * tiltRadius
                val across = sin(phase) * spread * sideRadius
                val radius = (baseWidth * (.055 + random.nextDouble() * (.095 + .035 * texture)))
                    .coerceIn(.16, 3.0)
                val deposit = (1 - spread * (.28 + .26 * tilt)) *
                    (.22 + .67 * load + .13 * (1 - hardness)) *
                    (.62 + random.nextDouble() * .68)
                val band = (deposit * opacityBands.size).toInt().coerceIn(0, opacityBands.lastIndex)
                grains[band].add(Grain(x + along * c - across * s, y + along * s + across * c, radius))
            }
        }

        fun pressure(point: LegacyBrush.Point, fallback: Double) =
            point.p?.takeIf { it > 0 }?.coerceIn(.02, 1.0) ?: fallback
        var length = 0.0
        for (i in 1 until points.size) length += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        if (!length.isFinite()) return
        val spacing = max(max(.46, baseWidth * .18), length / (MAX_DABS - 2))
        val first = points.first()
        deposit(first.x, first.y, pressure(first, .45), first.tx ?: 0.0, first.ty ?: 0.0)
        var next = spacing
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val distance = hypot(dx, dy)
            if (distance == 0.0) continue
            val p = pressure(a, .45)
            val q = pressure(b, p)
            while (next <= distance) {
                val f = next / distance
                fun tilt(v: Double?, w: Double?) = (v ?: 0.0) * (1 - f) + (w ?: v ?: 0.0) * f
                deposit(a.x + dx * f, a.y + dy * f, p + (q - p) * f, tilt(a.tx, b.tx), tilt(a.ty, b.ty))
                next += spacing
            }
            next -= distance
        }
        if (length >= spacing * .5 && next < spacing * .4) {
            val last = points.last()
            deposit(last.x, last.y, pressure(last, pressure(points[points.lastIndex - 1], .45)),
                last.tx ?: 0.0, last.ty ?: 0.0)
        }

        val color = stroke.optString("color").ifBlank { "#34373a" }
        sink.save()
        sink.multiply()
        sink.fillColor(color)
        grains.forEachIndexed { index, band ->
            if (band.isNotEmpty()) {
                sink.alpha(opacity * opacityBands[index])
                sink.beginPath()
                for (grain in band) sink.arc(grain.x, grain.y, grain.radius, 0.0, 2 * PI)
                sink.fill()
            }
        }
        sink.restore()
    }
}
