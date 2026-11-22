package com.inkforge.notesstudio

import org.json.JSONObject
import kotlin.math.*
import kotlin.random.Random

/** Graphite deposited by an angled nib at regular distances along the stroke. */
internal object GraphiteBrush {
    const val CURRENT_VERSION = 2
    private const val MAX_DABS = 2048
    val opacityBands = doubleArrayOf(.17, .3, .47, .68)

    data class Style(
        val width: Double,
        val opacity: Double,
        val hardness: Double,
        val texture: Double,
        val sensitivity: Double,
        val tiltShade: Double,
        val seed: Int,
        val color: String,
        val version: Int
    )

    fun isGraphite(stroke: JSONObject) =
        stroke.optString("brush") == "pencil" && stroke.optString("pencilTexture") == "graphite"

    fun style(stroke: JSONObject): Style {
        val settings = stroke.optJSONObject("settings")
        fun setting(name: String, fallback: Double) = (settings?.optDouble(name, fallback) ?: fallback)
            .takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: fallback
        return Style(
            width = stroke.optDouble("width", 4.0).takeIf(Double::isFinite)?.coerceIn(.35, 240.0) ?: 4.0,
            opacity = stroke.optDouble("opacity", setting("opacity", .82)).takeIf(Double::isFinite)
                ?.coerceIn(0.0, 1.0) ?: .82,
            hardness = setting("hardness", .58),
            texture = setting("grain", .66),
            sensitivity = setting("pressure", .7),
            tiltShade = setting("tiltShade", .82),
            seed = stroke.optString("id").hashCode(),
            color = stroke.optString("color").ifBlank { "#34373a" },
            version = stroke.optInt("graphiteVersion", 1)
        )
    }

    fun interface GrainSink {
        fun circle(band: Int, x: Double, y: Double, radius: Double)
    }

    /** The active stroke appends to four paths. Committed ink uses the same sampler. */
    class PathBuilder(private val clear: () -> Unit, private val sink: GrainSink) {
        private var style: Style? = null
        private var generation: Int? = null
        private var scanned = 0
        private var length = 0.0
        private var spacing = 0.0
        private var sampler: Sampler? = null

        fun update(stroke: Style, points: List<LegacyBrush.Point>, liveGeneration: Int? = null) {
            val version = liveGeneration?.takeIf { stroke.version >= CURRENT_VERSION }
            if (version == null || generation != version || style != stroke || points.size < scanned) {
                generation = version
                style = stroke
                scanned = 0
                length = 0.0
                sampler = null
                clear()
            }
            for (i in max(1, scanned) until points.size) {
                val a = points[i - 1]
                val b = points[i]
                length += hypot(b.x - a.x, b.y - a.y)
            }
            scanned = points.size
            if (points.isEmpty() || !length.isFinite()) return
            val minimum = max(.46, stroke.width * .18)
            val pitch = if (stroke.version < CURRENT_VERSION) max(minimum, length / (MAX_DABS - 2)) else {
                // Changing the pitch changes every dab. Only rebuild at discrete length bounds.
                var step = minimum
                while (length > step * (MAX_DABS - 2)) step *= 2
                step
            }
            var current = sampler
            if (current == null || spacing != pitch) {
                if (current != null) clear()
                spacing = pitch
                current = Sampler(stroke, pitch, sink)
                sampler = current
            }
            current.append(points)
            if (stroke.version < CURRENT_VERSION) current.finishLegacy(points, length)
        }
    }

    private class Sampler(private val style: Style, private val spacing: Double, private val sink: GrainSink) {
        private val random = Random(style.seed)
        private var next = spacing
        private var consumed = 0

        fun append(points: List<LegacyBrush.Point>) {
            if (consumed == 0) {
                val first = points.first()
                deposit(first.x, first.y, pressure(first, .45), first.tx ?: 0.0, first.ty ?: 0.0)
            }
            for (i in max(1, consumed) until points.size) {
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
            consumed = points.size
        }

        fun finishLegacy(points: List<LegacyBrush.Point>, length: Double) {
            if (length >= spacing * .5 && next < spacing * .4) {
                val last = points.last()
                deposit(last.x, last.y, pressure(last, pressure(points[points.lastIndex - 1], .45)),
                    last.tx ?: 0.0, last.ty ?: 0.0)
            }
        }

        private fun pressure(point: LegacyBrush.Point, fallback: Double) =
            point.p?.takeIf { it > 0 }?.coerceIn(.02, 1.0) ?: fallback

        private fun deposit(x: Double, y: Double, pressure: Double, tx: Double, ty: Double) {
            val load = .5 + style.sensitivity * (pressure - .5)
            val tilt = (hypot(tx, ty) / 72).coerceIn(0.0, 1.0) * style.tiltShade
            val angle = atan2(ty, tx)
            val sideRadius = style.width * (.28 + .3 * load + .19 * tilt)
            val tiltRadius = sideRadius * (1 + 2.05 * tilt)
            val c = cos(angle)
            val s = sin(angle)
            val count = (12 + 13 * load + 7 * tilt - 5 * style.texture).roundToInt()
            repeat(count) {
                val phase = random.nextDouble() * 2 * PI
                val spread = sqrt(random.nextDouble())
                val along = cos(phase) * spread * tiltRadius
                val across = sin(phase) * spread * sideRadius
                val radius = (style.width * (.055 + random.nextDouble() * (.095 + .035 * style.texture)))
                    .coerceIn(.16, 3.0)
                val deposit = (1 - spread * (.28 + .26 * tilt)) *
                    (.22 + .67 * load + .13 * (1 - style.hardness)) *
                    (.62 + random.nextDouble() * .68)
                val band = (deposit * opacityBands.size).toInt().coerceIn(0, opacityBands.lastIndex)
                sink.circle(band, x + along * c - across * s, y + along * s + across * c, radius)
            }
        }
    }
}
