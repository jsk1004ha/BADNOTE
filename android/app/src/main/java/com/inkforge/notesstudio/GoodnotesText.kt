package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import kotlin.math.max
import kotlin.math.roundToInt

/** Converts the two editable text encodings observed in Goodnotes documents. */
internal object GoodnotesText {
    private const val MAX_TEXT_BYTES = 2 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 1_000_000
    private const val DEFAULT_SIZE = 24f
    private const val DEFAULT_WEIGHT = -60L

    fun decode(kind: Int, body: GoodnotesMessage, dx: Float, dy: Float): JSONObject? =
        runCatching {
            when (kind) {
                8 -> decodeRtf(body, dx, dy)
                21 -> decodeRich(body, dx, dy)
                else -> null
            }
        }.getOrNull()

    /** Converts Goodnotes' geometry-free "start typing" paragraphs into one editable page text box. */
    internal fun decodeFlow(
        paragraphs: List<GoodnotesMessage>, pageWidth: Float, pageHeight: Float
    ): JSONObject? = runCatching {
        if (paragraphs.isEmpty() || !pageWidth.isFinite() || !pageHeight.isFinite() ||
            pageWidth <= FLOW_MARGIN * 2 || pageHeight <= FLOW_FIRST_BASELINE) return@runCatching null
        require(paragraphs.sumOf { it.bytes(3)?.size?.toLong() ?: 0L } <= MAX_TEXT_BYTES)
        val ordered = paragraphs.withIndex().sortedWith(
            compareBy<IndexedValue<GoodnotesMessage>> { it.value.flowIndex() }.thenBy { it.index }
        )
        val text = StringBuilder()
        val spans = ArrayList<Span>()
        val counters = HashMap<Int, Int>()
        var firstStyle: Style? = null

        for ((_, paragraph) in ordered) {
            val rtf = paragraph.bytes(3) ?: return@runCatching null
            if (rtf.size > MAX_TEXT_BYTES || rtf.indexOfRtf() < 0) return@runCatching null
            val document = RtfDocument.parse(rtf) ?: return@runCatching null
            val level = paragraph.flowLevel().coerceIn(0, 32)
            val marker = FLOW_LIST_MARKER.find(document.text)
            val listKind = when {
                marker == null -> null
                marker.value.startsWith("\u2022") -> ListKind.BULLET
                else -> ListKind.NUMBERED
            }
            for (counterLevel in counters.keys.filter { it > level || (it == level && listKind != ListKind.NUMBERED) })
                counters.remove(counterLevel)
            val visibleText = if (marker != null) document.text.removeRange(marker.range) else document.text
            val style = document.spans.firstOrNull()?.style ?: Style(size = 18f)
            if (firstStyle == null) firstStyle = style
            if (text.isNotEmpty()) appendBounded(text, "\n")
            val prefix = buildString {
                repeat(level) { append('\t') }
                when (listKind) {
                    ListKind.BULLET -> append(FLOW_BULLETS[level % FLOW_BULLETS.size]).append('\t')
                    ListKind.NUMBERED -> {
                        val number = (counters[level] ?: 0) + 1
                        counters[level] = number
                        append(number).append('.').append('\t')
                    }
                    null -> Unit
                }
            }
            val prefixStart = text.length
            appendBounded(text, prefix)
            if (text.length > prefixStart) spans.addMerged(Span(prefixStart, text.length, style))
            val bodyStart = text.length
            appendBounded(text, visibleText)
            val removed = marker?.value?.length ?: 0
            for (span in document.spans) {
                val start = (span.start - removed).coerceAtLeast(0)
                val end = (span.end - removed).coerceIn(start, visibleText.length)
                if (end > start) spans.addMerged(Span(bodyStart + start, bodyStart + end, span.style))
            }
        }
        if (text.isEmpty()) return@runCatching null
        val base = firstStyle ?: Style(size = 18f)
        val top = (FLOW_FIRST_BASELINE - base.size).coerceAtLeast(0f)
        textObject(
            text.toString(), FLOW_MARGIN, top, pageWidth - FLOW_MARGIN * 2,
            pageHeight - top, base, "left", spans
        ).put("lineSpacing", 1.2).put("fullPageText", true)
    }.getOrNull()

    private fun decodeRich(body: GoodnotesMessage, dx: Float, dy: Float): JSONObject? {
        val origin = body.message(20)?.message(1)?.point() ?: return null
        val box = body.message(32) ?: return null
        val content = box.message(1)?.bytes(2) ?: return null
        require(content.size <= MAX_TEXT_BYTES)
        val defaults = Style().apply(box.message(5)?.message(1))
        val inset = box.message(10)
        val top = inset?.float(1) ?: 0f
        val left = inset?.float(2) ?: 0f
        val size = box.message(2)?.point()

        val raw = if (content.isAppleLz4()) GoodnotesWire.decompress(content, MAX_TEXT_BYTES) else content
        require(raw.size <= MAX_TEXT_BYTES)
        val rich = GoodnotesWire.read(raw)
        val text = StringBuilder()
        val spans = ArrayList<Span>()
        var firstAlignment: String? = null
        var paragraphStarted = false
        var numbered = 0

        for (field in rich) {
            if (field.number != 1 || field.data == null) continue
            val entry = GoodnotesWire.read(field.data)
            val value = entry.bytes(1)?.toString(Charsets.UTF_8) ?: continue
            val style = defaults.apply(entry.message(2))
            val paragraph = paragraph(entry.message(3))
            val pieces = value.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            for ((index, original) in pieces.withIndex()) {
                if (index > 0) {
                    appendBounded(text, "\n")
                    paragraphStarted = false
                }
                if (original.isEmpty()) continue
                var piece = original
                if (!paragraphStarted) {
                    paragraphStarted = true
                    if (firstAlignment == null) firstAlignment = paragraph.first
                    numbered = if (paragraph.second == ListKind.NUMBERED) numbered + 1 else 0
                    piece = when (paragraph.second) {
                        ListKind.NUMBERED -> "$numbered. $piece"
                        ListKind.BULLET -> "\u2022 $piece"
                        null -> piece
                    }
                }
                val start = text.length
                appendBounded(text, piece)
                if (text.length > start) spans += Span(start, text.length, style)
            }
        }
        if (text.isEmpty()) return null
        val width = saneDimension(size?.first, 360f)
        val height = saneDimension(size?.second, estimatedHeight(text, defaults.size))
        return textObject(
            text.toString(), origin.first + left + dx, origin.second + top + dy,
            width, height, defaults, firstAlignment ?: "left", spans
        )
    }

    private fun decodeRtf(body: GoodnotesMessage, dx: Float, dy: Float): JSONObject? {
        val transform = body.message(2) ?: return null
        val origin = transform.message(1)?.point() ?: return null
        val size = transform.message(2)?.point() ?: return null
        val rtf = body.asSequence().mapNotNull { it.data }
            .firstOrNull { it.size <= MAX_TEXT_BYTES && it.indexOfRtf() >= 0 } ?: return null
        val document = RtfDocument.parse(rtf) ?: return null
        if (document.text.isEmpty()) return null
        val base = document.spans.firstOrNull()?.style ?: Style()
        val background = body.message(9)?.rgba(defaultAlpha = 0f)
        return textObject(
            document.text, origin.first + dx, origin.second + dy,
            saneDimension(size.first, 360f), saneDimension(size.second, estimatedHeight(document.text, base.size)),
            base, "left", document.spans
        ).apply {
            if (background != null && background.alpha > 0f) put("textBackground", background.hex())
        }
    }

    private fun textObject(
        text: String, x: Float, y: Float, width: Float, height: Float, base: Style,
        alignment: String, spans: List<Span>
    ) = json(
        "id" to uid("text"), "type" to "text", "text" to text, "textLayoutVersion" to 1,
        "x" to x, "y" to y, "w" to width, "h" to height, "fontSize" to base.size,
        "fontFamily" to (base.family ?: "sans-serif"), "fontWeight" to if (base.bold) 700 else 400,
        "italic" to base.italic, "underline" to base.underline, "strike" to base.strike,
        "color" to base.color.hex(), "alignment" to alignment, "textPadding" to 0,
        "textRuns" to JSONArray().apply {
            for (span in spans) addStyleRuns(this, span, base)
        }
    )

    private fun addStyleRuns(out: JSONArray, span: Span, base: Style) {
        if (span.end <= span.start) return
        fun add(kind: String, value: Any?) {
            out.put(json("start" to span.start, "end" to span.end, "kind" to kind).apply {
                if (value != null) put("value", value)
            })
        }
        val style = span.style
        if (style.bold != base.bold) add("bold", style.bold)
        if (style.italic != base.italic) add("italic", style.italic)
        if (style.underline != base.underline) add("underline", style.underline)
        if (style.strike != base.strike) add("strike", style.strike)
        if (style.color != base.color) add("color", style.color.hex())
        if (style.family != base.family && style.family != null) add("font", style.family)
        if (style.size != base.size) add("size", style.size)
    }

    private data class Span(val start: Int, val end: Int, val style: Style)

    private fun MutableList<Span>.addMerged(span: Span) {
        val last = lastOrNull()
        if (last != null && last.end == span.start && last.style == span.style) {
            this[lastIndex] = last.copy(end = span.end)
        } else add(span)
    }

    private data class Style(
        val color: Rgba = Rgba(0f, 0f, 0f, 1f),
        val family: String? = null,
        val size: Float = DEFAULT_SIZE,
        val weight: Long = DEFAULT_WEIGHT,
        val underline: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false
    ) {
        val bold get() = weight > DEFAULT_WEIGHT

        fun apply(message: GoodnotesMessage?): Style {
            if (message == null) return this
            val nextSize = message.float(40)?.takeIf { it.isFinite() && it > 0f } ?: size
            val nextWeight = message.varint(60)?.signed()?.takeUnless { it == -404L } ?: weight
            return copy(
                color = message.message(3)?.rgba() ?: color,
                family = message.bytes(30)?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() } ?: family,
                size = nextSize.coerceIn(4f, 360f), weight = nextWeight,
                underline = message.varint(2)?.let { it != 0L } ?: underline,
                italic = message.varint(50)?.let { it != 0L } ?: italic,
                strike = message.varint(1)?.let { it != 0L } ?: strike
            )
        }
    }

    private data class Rgba(val red: Float, val green: Float, val blue: Float, val alpha: Float) {
        fun hex(): String {
            fun channel(value: Float) = (value.coerceIn(0f, 1f) * 255f).roundToInt()
            return String.format("#%02X%02X%02X%02X", channel(alpha), channel(red), channel(green), channel(blue))
        }
    }

    private enum class ListKind { BULLET, NUMBERED }

    private fun paragraph(message: GoodnotesMessage?): Pair<String, ListKind?> {
        if (message == null) return "left" to null
        val alignment = when (message.varint(4)?.toInt()) {
            2 -> "center"
            3 -> "right"
            else -> "left"
        }
        val list = message.message(3)?.let { listMessage ->
            val marker = listMessage.firstOrNull { it.number == 1 }
            when {
                marker == null -> ListKind.BULLET
                marker.wire == 0 && marker.value.signed() == 1L -> ListKind.NUMBERED
                else -> null
            }
        }
        return alignment to list
    }

    private data class RtfState(
        val bold: Boolean = false, val italic: Boolean = false,
        val underline: Boolean = false, val strike: Boolean = false,
        val color: Int = 2, val font: Int = 0, val size: Float = DEFAULT_SIZE,
        val unicodeFallback: Int = 1, val ignored: Boolean = false
    )

    private data class RtfDocument(val text: String, val spans: List<Span>) {
        companion object {
            fun parse(bytes: ByteArray): RtfDocument? {
                val start = bytes.indexOfRtf()
                if (start < 0) return null
                val source = bytes.copyOfRange(start, bytes.size).toString(Charset.forName("windows-1252"))
                val colors = rtfColors(source)
                val fonts = rtfFonts(source)
                val text = StringBuilder()
                val spans = ArrayList<Span>()
                val stack = ArrayList<RtfState>()
                var state = RtfState()
                var i = 0
                var fallback = 0

                fun style() = Style(
                    color = colors.getOrElse(state.color - 1) { Rgba(0f, 0f, 0f, 1f) },
                    family = fonts[state.font], size = state.size.coerceIn(4f, 360f),
                    weight = if (state.bold) 0 else DEFAULT_WEIGHT, underline = state.underline,
                    italic = state.italic, strike = state.strike
                )
                fun append(value: String) {
                    if (state.ignored || value.isEmpty()) return
                    val allowed = minOf(value.length, MAX_TEXT_CHARS - text.length)
                    require(allowed >= value.length) { "Goodnotes text exceeds limit" }
                    val begin = text.length
                    text.append(value)
                    val current = style()
                    if (spans.lastOrNull()?.let { it.end == begin && it.style == current } == true) {
                        val last = spans.removeAt(spans.lastIndex)
                        spans += last.copy(end = text.length)
                    } else spans += Span(begin, text.length, current)
                }

                while (i < source.length) {
                    when (val ch = source[i]) {
                        '{' -> { stack += state; i++ }
                        '}' -> { if (stack.isNotEmpty()) state = stack.removeAt(stack.lastIndex); i++ }
                        '\\' -> {
                            if (++i >= source.length) break
                            val escaped = source[i]
                            if (escaped in "{}\\") { if (fallback > 0) fallback-- else append(escaped.toString()); i++; continue }
                            if (escaped == '\'') {
                                if (i + 2 < source.length) {
                                    val value = source.substring(i + 1, i + 3).toIntOrNull(16)
                                    if (value != null && fallback == 0) append(byteArrayOf(value.toByte()).toString(Charset.forName("windows-1252")))
                                    else if (fallback > 0) fallback--
                                    i += 3
                                } else i++
                                continue
                            }
                            if (!escaped.isLetter()) {
                                if (fallback > 0) fallback-- else when (escaped) {
                                    '*' -> state = state.copy(ignored = true)
                                    '~' -> append("\u00a0")
                                    '_' -> append("\u2011")
                                }
                                i++
                                continue
                            }
                            val wordStart = i
                            while (i < source.length && source[i].isLetter()) i++
                            val word = source.substring(wordStart, i)
                            val numberStart = i
                            if (i < source.length && source[i] == '-') i++
                            while (i < source.length && source[i].isDigit()) i++
                            val number = source.substring(numberStart, i).toIntOrNull()
                            if (i < source.length && source[i] == ' ') i++
                            state = when (word) {
                                "fonttbl", "colortbl", "stylesheet", "listtable", "listoverridetable",
                                "listtext", "info", "pict" -> state.copy(ignored = true)
                                "b" -> state.copy(bold = number != 0)
                                "i" -> state.copy(italic = number != 0)
                                "ul" -> state.copy(underline = number != 0)
                                "ulnone" -> state.copy(underline = false)
                                "strike", "strikeout", "strikethrough" -> state.copy(strike = number != 0)
                                "cf" -> state.copy(color = number ?: state.color)
                                "f" -> state.copy(font = number ?: state.font)
                                "fs" -> state.copy(size = (number ?: (state.size * 2).toInt()) / 2f)
                                "uc" -> state.copy(unicodeFallback = max(0, number ?: 1))
                                "plain" -> RtfState(ignored = state.ignored, unicodeFallback = state.unicodeFallback)
                                "par", "line", "n" -> { append("\n"); state }
                                "tab" -> { append("\t"); state }
                                "u" -> {
                                    if (number != null) append((number and 0xffff).toChar().toString())
                                    fallback = state.unicodeFallback
                                    state
                                }
                                else -> state
                            }
                        }
                        '\r', '\n' -> i++
                        else -> { if (fallback > 0) fallback-- else append(ch.toString()); i++ }
                    }
                }
                val first = text.indexOfFirst { !it.isWhitespace() }
                val last = text.indexOfLast { !it.isWhitespace() }
                if (first < 0 || last < first) return null
                val trimmed = text.substring(first, last + 1)
                val adjusted = spans.mapNotNull {
                    val begin = max(it.start, first) - first
                    val end = minOf(it.end, last + 1) - first
                    if (end > begin) it.copy(start = begin, end = end) else null
                }
                return RtfDocument(trimmed, adjusted)
            }
        }
    }

    private fun rtfColors(source: String): List<Rgba> {
        val table = source.destination("colortbl") ?: return emptyList()
        return Regex("""\\red(\d+)\\green(\d+)\\blue(\d+)""").findAll(table).map {
            val values = it.groupValues.drop(1).map(String::toInt)
            Rgba(values[0] / 255f, values[1] / 255f, values[2] / 255f, 1f)
        }.toList()
    }

    private fun rtfFonts(source: String): Map<Int, String> {
        val table = source.destination("fonttbl") ?: return emptyMap()
        return Regex("""\\f(\d+)[^;{}]*?\s([^;{}\\]+);""").findAll(table).associate {
            it.groupValues[1].toInt() to it.groupValues[2].trim()
        }
    }

    private fun String.destination(name: String): String? {
        val marker = indexOf("\\$name")
        if (marker < 0) return null
        val start = lastIndexOf('{', marker)
        if (start < 0) return null
        var depth = 0
        var escaped = false
        for (index in start until length) {
            val char = this[index]
            if (escaped) { escaped = false; continue }
            if (char == '\\') { escaped = true; continue }
            if (char == '{') depth++
            if (char == '}' && --depth == 0) return substring(start, index + 1)
        }
        return null
    }

    private fun GoodnotesMessage.message(number: Int): GoodnotesMessage? =
        bytes(number)?.let { GoodnotesWire.read(it) }

    private fun GoodnotesMessage.bytes(number: Int): ByteArray? =
        firstOrNull { it.number == number && it.wire == 2 }?.data

    private fun GoodnotesMessage.varint(number: Int): Long? =
        firstOrNull { it.number == number && it.wire == 0 }?.value

    private fun GoodnotesMessage.float(number: Int): Float? =
        firstOrNull { it.number == number && it.wire in setOf(1, 5) }?.let {
            if (it.wire == 5) Float.fromBits(it.value.toInt()) else Double.fromBits(it.value).toFloat()
        }

    private fun GoodnotesMessage.point(): Pair<Float, Float>? {
        val x = float(1) ?: return null
        val y = float(2) ?: return null
        return if (x.isFinite() && y.isFinite()) x to y else null
    }

    private fun GoodnotesMessage.rgba(defaultAlpha: Float = 1f): Rgba? {
        val red = float(1) ?: 0f
        val green = float(2) ?: 0f
        val blue = float(3) ?: 0f
        val alpha = float(4) ?: defaultAlpha
        return if (listOf(red, green, blue, alpha).all(Float::isFinite)) Rgba(red, green, blue, alpha) else null
    }

    private fun Long.signed() = this

    private fun GoodnotesMessage.flowIndex() = message(13)?.message(1)?.varint(1) ?: 0L

    private fun GoodnotesMessage.flowLevel() = (message(27)?.varint(1) ?: 0L).signed().toInt()

    private fun ByteArray.isAppleLz4() = size >= 4 && this[0] == 'b'.code.toByte() &&
        this[1] == 'v'.code.toByte() && this[2] == '4'.code.toByte() && (this[3] == '1'.code.toByte() || this[3] == '-'.code.toByte())

    private fun ByteArray.indexOfRtf(): Int {
        val marker = byteArrayOf('{'.code.toByte(), '\\'.code.toByte(), 'r'.code.toByte(), 't'.code.toByte(), 'f'.code.toByte())
        for (start in 0..size - marker.size) if (marker.indices.all { this[start + it] == marker[it] }) return start
        return -1
    }

    private fun appendBounded(builder: StringBuilder, value: String) {
        require(value.length <= MAX_TEXT_CHARS - builder.length) { "Goodnotes text exceeds limit" }
        builder.append(value)
    }

    private fun saneDimension(value: Float?, fallback: Float) =
        value?.takeIf { it.isFinite() && it in 1f..100_000f } ?: fallback

    private fun estimatedHeight(text: CharSequence, size: Float) =
        max(size * 1.4f, size * 1.4f * (1 + text.count { it == '\n' }))

    private const val FLOW_MARGIN = 44f
    private const val FLOW_FIRST_BASELINE = 91.63f
    private val FLOW_LIST_MARKER = Regex("""^(?:\u2022|\d+\.)\t""")
    private val FLOW_BULLETS = charArrayOf('\u25cf', '\u25cb', '\u25c6', '\u25c7', '\u25a0', '\u25a1', '\u25b2', '\u25b3')
}
