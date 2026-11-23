package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.tan

/** Converts the documented Goodnotes TPL ink layouts to editable BADNOTE strokes. */
internal object GoodnotesInk {
    private const val MAX_POINTS = 250_000
    private const val MAX_ARRAY_ITEMS = 1_000_000
    private const val MAX_COORDINATE = 100_000f
    private const val QUADRATIC_STEPS = 6
    private const val UNIFORM_WIDTH_SCALE = 11f / 12f

    private data class Sample(
        val x: Float,
        val y: Float,
        val width: Float,
        val tilt: Float = 0f,
        val azimuth: Float? = null,
        val force: Float? = null
    )

    private data class Ink(val kind: Kind, val paths: List<List<Sample>>)
    private enum class Kind { PRESSURE, UNIFORM, PENCIL }
    private data class Template(val signature: String, val values: List<Any>)
    private data class Style(val color: String, val opacity: Double, val highlighter: Boolean)
    private object Unsupported : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /**
     * Returns null only when the object cannot be converted without guessing. An empty list is a
     * valid, known ink template with no visible subpath (for example a fully erased stroke).
     */
    fun decode(body: GoodnotesMessage, dx: Float, dy: Float): List<JSONObject>? = try {
        if (!dx.isFinite() || !dy.isFinite()) throw Unsupported
        // Field 9 is exact recognised-shape geometry, not one of the editable freehand layouts.
        if (body.any { it.number == 9 && it.data?.isNotEmpty() == true }) throw Unsupported
        val compressed = body.asSequence().filter { it.number == 2 }.mapNotNull { it.data }
            .firstOrNull(::isAppleLz4) ?: throw Unsupported
        val frame = GoodnotesWire.decompressFrame(compressed)
        val trailer = if (frame.endOffset < compressed.size) {
            GoodnotesWire.read(compressed.copyOfRange(frame.endOffset, compressed.size))
        } else emptyList()
        val metadata = body + trailer
        // Goodnotes Tape uses a different visual model and must not silently become a pen stroke.
        if (metadata.any { it.number == 20 && it.data?.isNotEmpty() == true }) throw Unsupported
        val template = parseTemplate(frame.bytes)
        val ink = when {
            template.signature.startsWith("vA(v)A(u)A(u)") -> pressure(template.values, newer = false)
            template.signature.startsWith("vuA(v)A(u)A(u)") -> pressure(template.values, newer = true)
            template.signature.startsWith("vuA(v)A(S(uu))A(S(uuuu))") -> uniform(template.values)
            template.signature.startsWith("vuA(v)A(S(uuuuu))A(S(uuuuuuuuuuu))") -> pencil(template.values)
            else -> throw Unsupported
        }
        val style = style(metadata)
        val (localDx, localDy) = moveOffset(metadata)
        ink.paths.map { path -> stroke(path, ink.kind, style, dx + localDx, dy + localDy) }
    } catch (_: Unsupported) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun pressure(values: List<Any>, newer: Boolean): Ink {
        val flags = ints(values.at(if (newer) 2 else 1))
        require(flags.all { it in 0..7 })
        if (flags.any { it and 4 != 0 }) throw Unsupported // extra tape payload
        val starts = floats(values.at(if (newer) 3 else 2))
        val segments = floats(values.at(if (newer) 4 else 3))
        val defaultRadius = if (newer) number(values.at(1)) else 0f
        val moves = ArrayList<Sample>()
        val curves = ArrayList<Pair<Sample, Sample>>()
        var startIndex = 0
        var segmentIndex = 0
        for (flag in flags) {
            val ownWidth = !newer || flag and 2 != 0
            if (flag and 1 == 0) {
                val need = if (ownWidth) 3 else 2
                require(startIndex <= starts.size - need)
                val radius = if (ownWidth) starts[startIndex + 2] else defaultRadius
                moves += sample(starts[startIndex], starts[startIndex + 1], radius * 2f)
                startIndex += need
            } else {
                val need = if (ownWidth) 6 else 4
                require(segmentIndex <= segments.size - need)
                val controlRadius = if (!newer) segments[segmentIndex + 2]
                    else if (ownWidth) segments[segmentIndex + 4] else defaultRadius
                val endRadius = if (ownWidth) segments[segmentIndex + 5] else defaultRadius
                val endOffset = if (newer) 2 else 3
                curves += sample(segments[segmentIndex], segments[segmentIndex + 1], controlRadius * 2f) to
                    sample(segments[segmentIndex + endOffset], segments[segmentIndex + endOffset + 1], endRadius * 2f)
                segmentIndex += need
            }
        }
        require(startIndex == starts.size && segmentIndex == segments.size)
        return Ink(Kind.PRESSURE, assemble(flags, moves, curves))
    }

    private fun uniform(values: List<Any>): Ink {
        val width = checkedWidth(number(values.at(1)) * UNIFORM_WIDTH_SCALE)
        val flags = ints(values.at(2))
        require(flags.all { it in 0..1 })
        val moves = structs(values.at(3)).map {
            require(it.size == 2)
            sample(number(it[0]), number(it[1]), width)
        }
        val curves = structs(values.at(4)).map {
            require(it.size == 4)
            sample(number(it[0]), number(it[1]), width) to
                sample(number(it[2]), number(it[3]), width)
        }
        return Ink(Kind.UNIFORM, assemble(flags, moves, curves))
    }

    private fun pencil(values: List<Any>): Ink {
        val nominalWidth = checkedWidth(number(values.at(1)) * UNIFORM_WIDTH_SCALE)
        val flags = ints(values.at(2))
        require(flags.all { it in 0..1 })
        fun point(parts: List<Any>, offset: Int): Sample {
            require(parts.size >= offset + 5)
            val altitude = number(parts[offset + 3]).coerceIn(.51f, (PI / 2).toFloat())
            val cotangent = 1f / tan(altitude)
            val width = checkedWidth(nominalWidth *
                (1f + 1.27f * (cotangent * cotangent - 1f / 3f)).coerceIn(.35f, 8f))
            val azimuth = number(parts[offset + 2]).takeIf { it in -10f..10f } ?: 0f
            val force = number(parts[offset + 4]).takeIf { it in 0f..5f } ?: 0f
            return sample(parts = floatArrayOf(number(parts[offset]), number(parts[offset + 1])),
                width = width, tilt = (PI.toFloat() / 2f - altitude), azimuth = azimuth, force = force)
        }
        val moves = structs(values.at(3)).map { require(it.size == 5); point(it, 0) }
        val curves = structs(values.at(4)).map {
            require(it.size == 11)
            point(it, 1) to point(it, 6)
        }
        return Ink(Kind.PENCIL, assemble(flags, moves, curves))
    }

    private fun assemble(
        flags: List<Int>, moves: List<Sample>, curves: List<Pair<Sample, Sample>>
    ): List<List<Sample>> {
        val paths = ArrayList<MutableList<Sample>>()
        var moveIndex = 0
        var curveIndex = 0
        var pointCount = 0
        for (flag in flags) {
            if (flag and 1 == 0) {
                require(moveIndex < moves.size)
                paths += arrayListOf(moves[moveIndex++])
                pointCount++
            } else {
                require(paths.isNotEmpty() && curveIndex < curves.size)
                val path = paths.last()
                val start = path.last()
                val (control, end) = curves[curveIndex++]
                repeat(QUADRATIC_STEPS) { index ->
                    val t = (index + 1f) / QUADRATIC_STEPS
                    val a = (1f - t) * (1f - t)
                    val b = 2f * (1f - t) * t
                    val c = t * t
                    path += interpolate(start, control, end, a, b, c)
                }
                pointCount += QUADRATIC_STEPS
            }
            require(pointCount <= MAX_POINTS)
        }
        require(moveIndex == moves.size && curveIndex == curves.size)
        return paths
    }

    private fun interpolate(a: Sample, b: Sample, c: Sample, wa: Float, wb: Float, wc: Float) =
        sample(
            floatArrayOf(a.x * wa + b.x * wb + c.x * wc, a.y * wa + b.y * wb + c.y * wc),
            checkedWidth(a.width * wa + b.width * wb + c.width * wc),
            a.tilt * wa + b.tilt * wb + c.tilt * wc,
            interpolateAngle(a.azimuth, b.azimuth, c.azimuth, wa, wb, wc),
            interpolateOptional(a.force, b.force, c.force, wa, wb, wc)
        )

    private fun interpolateAngle(a: Float?, b: Float?, c: Float?, wa: Float, wb: Float, wc: Float): Float? {
        if (a == null || b == null || c == null) return null
        return a * wa + b * wb + c * wc
    }

    private fun interpolateOptional(a: Float?, b: Float?, c: Float?, wa: Float, wb: Float, wc: Float): Float? {
        if (a == null || b == null || c == null) return null
        return a * wa + b * wb + c * wc
    }

    private fun stroke(path: List<Sample>, kind: Kind, style: Style, dx: Float, dy: Float): JSONObject {
        require(path.isNotEmpty())
        val baseWidth = path.maxOf { it.width }.coerceAtLeast(.01f)
        val points = JSONArray()
        for (point in path) {
            val x = point.x + dx
            val y = point.y + dy
            require(x.isFinite() && y.isFinite() && x in -MAX_COORDINATE..MAX_COORDINATE &&
                y in -MAX_COORDINATE..MAX_COORDINATE)
            val pressure = (point.width / baseWidth).coerceIn(.001f, 1f)
            val extensions = JSONObject().put("p", pressure).put("t", 0L).put("tilt", point.tilt)
                .put("orientation", point.azimuth ?: 0f).put("pointerId", -1)
            point.azimuth?.let { extensions.put("azimuth", it) }
            point.force?.let { extensions.put("goodnotesForce", it) }
            points.put(InkPoint(x, y, pressure, tilt = point.tilt,
                orientation = point.azimuth ?: 0f, azimuth = point.azimuth, original = extensions).json())
        }
        val brush = when {
            kind == Kind.PENCIL -> "pencil"
            style.highlighter -> "highlighter"
            kind == Kind.PRESSURE -> "fountain"
            else -> "ballpoint"
        }
        val settings = when (kind) {
            Kind.PENCIL -> json("smoothing" to 0, "pressure" to .7, "grain" to .66, "tiltShade" to .82)
            else -> json("smoothing" to 0)
        }
        return json(
            "id" to uid("stroke"), "type" to "stroke", "brush" to brush,
            "color" to style.color, "opacity" to style.opacity, "width" to baseWidth,
            "widthProfile" to "xournal", "settings" to settings, "points" to points
        )
    }

    private fun style(body: GoodnotesMessage): Style {
        var red = 0f
        var green = 0f
        var blue = 0f
        var alpha = 1f
        body.firstOrNull { it.number == 4 && it.data != null }?.data?.let { raw ->
            val color = GoodnotesWire.read(raw)
            fun component(number: Int, fallback: Float): Float = color.firstOrNull {
                it.number == number && it.wire == 5
            }?.let { Float.fromBits(it.value.toInt()) }?.takeIf(Float::isFinite) ?: fallback
            red = component(1, 0f).coerceIn(0f, 1f)
            green = component(2, 0f).coerceIn(0f, 1f)
            blue = component(3, 0f).coerceIn(0f, 1f)
            alpha = component(4, 1f).coerceIn(0f, 1f)
        }
        val color = "#%02x%02x%02x".format(
            (red * 255f).toInt().coerceIn(0, 255),
            (green * 255f).toInt().coerceIn(0, 255),
            (blue * 255f).toInt().coerceIn(0, 255)
        )
        val explicitHighlighter = body.any { it.number == 5 && it.wire == 0 && it.value == 1L }
        return Style(color, alpha.toDouble(), explicitHighlighter || alpha < .95f)
    }

    private fun moveOffset(body: GoodnotesMessage): Pair<Float, Float> {
        val raw = body.firstOrNull { it.number == 6 && it.data != null }?.data ?: return 0f to 0f
        val offset = GoodnotesWire.read(raw)
        fun component(number: Int): Float = offset.firstOrNull {
            it.number == number && it.wire == 5
        }?.let { Float.fromBits(it.value.toInt()) }?.takeIf(Float::isFinite) ?: 0f
        val x = component(1)
        val y = component(2)
        require(x in -MAX_COORDINATE..MAX_COORDINATE && y in -MAX_COORDINATE..MAX_COORDINATE)
        return x to y
    }

    private fun parseTemplate(raw: ByteArray): Template {
        require(raw.size >= 9 && raw[0] == 't'.code.toByte() && raw[1] == 'p'.code.toByte() &&
            raw[2] == 'l'.code.toByte() && raw[3] == 0.toByte())
        require(unsignedInt(raw, 4) == raw.size.toLong())
        val terminator = (8 until raw.size).firstOrNull { raw[it] == 0.toByte() } ?: throw Unsupported
        val signature = raw.copyOfRange(8, terminator).toString(Charsets.US_ASCII)
        require(signature.isNotEmpty() && signature.all { it.code in 32..126 })
        val (nodes, signatureEnd) = parseNodes(signature, 0, false)
        require(signatureEnd == signature.length)
        val cursor = BinaryCursor(raw, terminator + 1)
        val values = nodes.map { readNode(it, cursor) }
        require(cursor.position == raw.size)
        return Template(signature, values)
    }

    private sealed interface Node {
        data class Scalar(val kind: Char) : Node
        data class Group(val kind: Char, val children: List<Node>) : Node
    }

    private fun parseNodes(text: String, initial: Int, grouped: Boolean): Pair<List<Node>, Int> {
        val nodes = ArrayList<Node>()
        var index = initial
        while (index < text.length) {
            val token = text[index++]
            if (token == ')') {
                require(grouped)
                return nodes to index
            }
            if (token == 'A' || token == 'S') {
                require(index < text.length && text[index++] == '(')
                val (children, end) = parseNodes(text, index, true)
                require(children.isNotEmpty())
                nodes += Node.Group(token, children)
                index = end
            } else {
                require(token in "jviuIUcsfB")
                nodes += Node.Scalar(token)
            }
        }
        require(!grouped)
        return nodes to index
    }

    private fun readNode(node: Node, cursor: BinaryCursor): Any = when (node) {
        is Node.Group -> if (node.kind == 'S') {
            node.children.map { readNode(it, cursor) }
        } else {
            val count = cursor.u32().also { require(it <= MAX_ARRAY_ITEMS) }.toInt()
            List(count) {
                val values = node.children.map { readNode(it, cursor) }
                if (values.size == 1) values[0] else values
            }
        }
        is Node.Scalar -> when (node.kind) {
            'c' -> cursor.i8()
            'j' -> cursor.i16()
            'v' -> cursor.u16()
            'i' -> cursor.i32()
            'u' -> Float.fromBits(cursor.i32())
            'I' -> cursor.i64()
            'U' -> cursor.i64()
            'f' -> Double.fromBits(cursor.i64())
            's' -> cursor.string()
            'B' -> cursor.bytes(cursor.u32().also { require(it <= MAX_ARRAY_ITEMS) }.toInt())
            else -> throw Unsupported
        }
    }

    private class BinaryCursor(private val data: ByteArray, var position: Int) {
        private fun take(count: Int): ByteArray {
            require(count >= 0 && position <= data.size - count)
            return data.copyOfRange(position, position + count).also { position += count }
        }
        fun bytes(count: Int) = take(count)
        fun i8() = take(1)[0].toInt()
        fun u16() = (i8() and 255) or ((i8() and 255) shl 8)
        fun i16() = u16().toShort().toInt()
        fun i32() = u32().toInt()
        fun u32(): Long {
            val bytes = take(4)
            return (bytes[0].toLong() and 255) or ((bytes[1].toLong() and 255) shl 8) or
                ((bytes[2].toLong() and 255) shl 16) or ((bytes[3].toLong() and 255) shl 24)
        }
        fun i64(): Long {
            val bytes = take(8)
            var value = 0L
            repeat(8) { value = value or ((bytes[it].toLong() and 255) shl (it * 8)) }
            return value
        }
        fun string(): String {
            val size = u32().also { require(it in 1..MAX_ARRAY_ITEMS.toLong()) }.toInt()
            val raw = take(size)
            require(raw.last() == 0.toByte())
            return raw.copyOf(raw.lastIndex).toString(Charsets.UTF_8)
        }
    }

    private fun isAppleLz4(data: ByteArray) = data.size >= 4 && data[0] == 'b'.code.toByte() &&
        data[1] == 'v'.code.toByte() && data[2] == '4'.code.toByte() &&
        (data[3] == '1'.code.toByte() || data[3] == '-'.code.toByte())

    private fun sample(x: Float, y: Float, width: Float) = sample(floatArrayOf(x, y), width)
    private fun sample(
        parts: FloatArray, width: Float, tilt: Float = 0f, azimuth: Float? = null, force: Float? = null
    ): Sample {
        require(parts.size == 2 && parts.all(Float::isFinite))
        require(parts[0] in -MAX_COORDINATE..MAX_COORDINATE && parts[1] in -MAX_COORDINATE..MAX_COORDINATE)
        require(tilt.isFinite() && azimuth?.isFinite() != false && force?.isFinite() != false)
        return Sample(parts[0], parts[1], checkedWidth(width), tilt, azimuth, force)
    }

    private fun checkedWidth(value: Float): Float {
        require(value.isFinite() && value in .01f..10_000f)
        return value
    }

    private fun List<Any>.at(index: Int): Any = getOrElse(index) { throw Unsupported }
    private fun number(value: Any): Float = (value as? Number)?.toFloat()?.takeIf(Float::isFinite)
        ?: throw Unsupported
    private fun ints(value: Any): List<Int> = (value as? List<*>)?.map {
        (it as? Number)?.toInt() ?: throw Unsupported
    } ?: throw Unsupported
    private fun floats(value: Any): List<Float> = (value as? List<*>)?.map { number(it ?: throw Unsupported) }
        ?: throw Unsupported
    private fun structs(value: Any): List<List<Any>> = (value as? List<*>)?.map {
        @Suppress("UNCHECKED_CAST")
        (it as? List<Any>) ?: throw Unsupported
    } ?: throw Unsupported

    private fun unsignedInt(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 255) or ((data[offset + 1].toLong() and 255) shl 8) or
            ((data[offset + 2].toLong() and 255) shl 16) or ((data[offset + 3].toLong() and 255) shl 24)
}
