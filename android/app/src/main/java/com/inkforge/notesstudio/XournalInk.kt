package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.xml.parsers.SAXParserFactory
import kotlin.math.max
import kotlin.math.roundToInt

/** Xournal and Xournal++ store page coordinates and pen widths in points. */
internal object XournalInk {
    const val MIME = "application/x-xopp"
    private const val MAX_XML = 128L * 1024 * 1024
    private const val MAX_ELEMENT_TEXT = 20 * 1024 * 1024
    private const val MAX_STROKE_NUMBERS = 200_000
    private val whitespaceToken = Regex("\\S+")
    private val colors = mapOf("black" to "#000000", "white" to "#ffffff", "red" to "#ff0000",
        "green" to "#008000", "blue" to "#0000ff", "gray" to "#808080", "grey" to "#808080",
        "lightblue" to "#00c0ff", "lightgreen" to "#00ff00", "magenta" to "#ff00ff",
        "orange" to "#ff8000", "yellow" to "#ffff00", "pink" to "#ffc0cb", "cyan" to "#00ffff")
    private val paperColors = mapOf("blue" to "#afeeee", "pink" to "#ffc0cb", "green" to "#7fffd4",
        "orange" to "#ffa07a", "yellow" to "#f0e68c")
    private val templates = mapOf("plain" to "blank", "lined" to "lined", "ruled" to "lined",
        "graph" to "grid", "dotted" to "dotted")

    fun read(input: InputStream, saveImage: (String) -> String, emit: (NotePage) -> Unit) {
        val source = input.buffered()
        source.mark(2)
        val first = source.read()
        val second = source.read()
        source.reset()
        val decoded = if (first == 0x1f && second == 0x8b) GZIPInputStream(source) else source
        val limit = object : FilterInputStream(decoded) {
            private var remaining = MAX_XML
            override fun read(): Int = super.read().also { if (it >= 0) consume(1) }
            override fun read(buffer: ByteArray, offset: Int, size: Int): Int =
                super.read(buffer, offset, size).also { if (it > 0) consume(it) }
            private fun consume(bytes: Int) {
                remaining -= bytes
                require(remaining >= 0) { "Xournal 문서의 압축 해제 크기가 너무 큽니다." }
            }
        }
        val handler = Pages(saveImage, emit)
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.entityResolver = handler
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        limit.use { reader.parse(InputSource(it)) }
    }

    private class Pages(private val saveImage: (String) -> String, private val emit: (NotePage) -> Unit) :
        DefaultHandler2() {
        private val tags = ArrayList<String>(4)
        private var page: NotePage? = null
        private var scale = 1.0
        private var objects: Attributes? = null
        private var body: StringBuilder? = null
        private var pageCount = 0

        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            throw SAXException("Xournal 문서에 DTD를 사용할 수 없습니다.")
        }

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
            throw SAXException("외부 XML 자원은 가져올 수 없습니다.")

        override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource =
            throw SAXException("외부 XML 자원은 가져올 수 없습니다.")

        override fun startElement(uri: String, name: String, qName: String, attrs: Attributes) {
            require(uri.isEmpty()) { "지원하지 않는 Xournal 문서입니다." }
            when (tags.lastOrNull()) {
                null -> require(name == "xournal") { "Xournal 문서가 아닙니다." }
                "xournal" -> when (name) {
                    "title", "preview" -> Unit
                    "page" -> {
                        require(++pageCount <= 5000) { "Xournal 문서의 페이지가 너무 많습니다." }
                        val width = number(attrs.getValue("width"))
                        val height = number(attrs.getValue("height"))
                        require(width > 0 && height > 0) { "Xournal 페이지의 크기가 올바르지 않습니다." }
                        scale = 1000 / width
                        require(height * scale in 32.0..30000.0) { "지원하지 않는 Xournal 페이지 비율입니다." }
                        page = NotePage.blank("blank", 1000f, (height * scale).toFloat()).apply {
                            meta.put("xournalPageWidth", width)
                        }
                    }
                    else -> unsupported()
                }
                "page" -> when (name) {
                    "background" -> {
                        require(attrs.getValue("type") == "solid") {
                            "외부 PDF 또는 이미지 배경이 있는 Xournal 문서는 Xournal에서 PDF로 내보낸 뒤 가져와 주세요."
                        }
                        val meta = checkNotNull(page).meta
                        meta.put("template", templates[attrs.getValue("style")] ?: "blank")
                        meta.put("paperColor", rgb(attrs.getValue("color"), "#ffffff", paperColors))
                    }
                    "layer" -> Unit
                    else -> unsupported()
                }
                "layer" -> {
                    if (name !in setOf("stroke", "text", "image")) unsupported()
                    if (attrs.getValue("matrix") != null || attrs.getValue("audioFilename") != null) unsupported()
                    objects = org.xml.sax.helpers.AttributesImpl(attrs)
                    body = StringBuilder()
                }
                else -> unsupported()
            }
            tags.add(name)
        }

        override fun characters(chars: CharArray, start: Int, length: Int) {
            body?.let {
                require(it.length + length <= MAX_ELEMENT_TEXT) { "Xournal 객체의 크기가 너무 큽니다." }
                it.append(chars, start, length)
            }
        }

        override fun endElement(uri: String, name: String, qName: String) {
            tags.removeAt(tags.lastIndex)
            val target = page
            if (target != null && tags.lastOrNull() == "layer") {
                val attrs = checkNotNull(objects)
                val text = checkNotNull(body).toString()
                val obj = when (name) {
                    "stroke" -> stroke(attrs, text, scale)
                    "image" -> {
                        val x = number(attrs.getValue("left")) * scale
                        val y = number(attrs.getValue("top")) * scale
                        val w = number(attrs.getValue("right")) * scale - x
                        val h = number(attrs.getValue("bottom")) * scale - y
                        require(w > 0 && h > 0) { "Xournal 이미지의 크기가 올바르지 않습니다." }
                        json("id" to uid("image"), "type" to "image", "src" to saveImage(text),
                            "x" to x, "y" to y, "w" to w, "h" to h)
                    }
                    else -> {
                        val size = number(attrs.getValue("size")) * scale
                        val x = number(attrs.getValue("x")) * scale
                        val width = attrs.getValue("wrap")?.let { number(it) * scale } ?: (target.width - x)
                        require(size > 0) { "Xournal 글자 크기가 올바르지 않습니다." }
                        json("id" to uid("text"), "type" to "text", "text" to text, "fontSize" to size,
                            "fontFamily" to attrs.getValue("font").orEmpty().ifBlank { "sans-serif" },
                            "color" to rgb(attrs.getValue("color")), "textPadding" to 0, "x" to x,
                            "alignment" to when (attrs.getValue("align")) { "center" -> "center"; "right" -> "right"; else -> "left" },
                            "y" to number(attrs.getValue("y")) * scale, "w" to width.coerceAtLeast(12.0),
                            "h" to max(size * 1.4, size * 1.4 * (1 + text.count { it == '\n' })))
                    }
                }
                target.objects.add(obj)
                objects = null
                body = null
            }
            if (name == "page") {
                emit(checkNotNull(target))
                page = null
            }
        }
    }

    private fun stroke(attrs: Attributes, text: String, scale: Double): JSONObject {
        val tool = attrs.getValue("tool") ?: "pen"
        if (tool !in setOf("pen", "highlighter") || attrs.getValue("fill")?.let { it != "-1" } == true ||
            attrs.getValue("style") != null || attrs.getValue("capStyle") !in listOf(null, "round", "butt")) unsupported()
        val coordinates = numbers(text)
        val pointCount = coordinates.size / 2
        require(pointCount > 0 && coordinates.size % 2 == 0) { "Xournal 획 좌표가 올바르지 않습니다." }
        val widths = numbers(attrs.getValue("width").orEmpty())
        require((widths.size == 1 || widths.size == pointCount) && widths.all { it > 0 }) { "Xournal 획의 굵기가 올바르지 않습니다." }
        val base = widths.max()
        val points = JSONArray()
        for (i in 0 until pointCount) {
            val width = if (widths.size == 1) base else widths[(i + 1).coerceAtMost(widths.lastIndex)]
            points.put(json("x" to coordinates[2 * i] * scale, "y" to coordinates[2 * i + 1] * scale,
                "p" to width / base))
        }
        val highlighter = tool == "highlighter"
        val color = attrs.getValue("color")
        val opacity = color?.takeIf { it.startsWith('#') && it.length == 9 }?.takeLast(2)
            ?.toIntOrNull(16)?.div(255.0) ?: if (highlighter) .5 else 1.0
        return json("id" to uid("stroke"), "type" to "stroke", "brush" to if (highlighter) "highlighter" else "ballpoint",
            "color" to rgb(color), "opacity" to opacity, "width" to base * scale, "widthProfile" to "xournal",
            "strokeCap" to (attrs.getValue("capStyle") ?: "round"),
            "settings" to json("smoothing" to 0), "points" to points)
    }

    private fun numbers(text: String): List<Double> = whitespaceToken.findAll(text).take(MAX_STROKE_NUMBERS + 1)
        .map { number(it.value) }.toList().also {
            require(it.size <= MAX_STROKE_NUMBERS) { "Xournal 획의 점이 너무 많습니다." }
        }

    private fun number(text: String?): Double = requireNotNull(text?.toDoubleOrNull()?.takeIf { it in -1e6..1e6 }) {
        "Xournal 문서의 수치가 올바르지 않습니다."
    }

    private fun rgb(value: String?, fallback: String = "#000000", overrides: Map<String, String> = emptyMap()): String {
        val text = value?.lowercase(Locale.ROOT) ?: return fallback
        if (text.startsWith('#') && text.length in listOf(7, 9) && text.substring(1).all { it in '0'..'9' || it in 'a'..'f' })
            return text.take(7)
        return overrides[text] ?: colors[text] ?: fallback
    }

    private fun unsupported(): Nothing =
        error("지원 범위를 벗어나는 Xournal 요소가 있습니다. 원본 화면을 보존하려면 Xournal에서 PDF로 내보내 주세요.")

    fun write(output: OutputStream, title: String, pages: Sequence<NotePage>, image: (NotePage) -> String,
              checkCancelled: () -> Unit = {}) {
        GZIPOutputStream(output).bufferedWriter(Charsets.UTF_8).use { out ->
            val safeTitle = title.filter { it >= ' ' || it == '\r' || it == '\n' || it == '\t' }
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><xournal creator=\"BADNOTE\" fileversion=\"4\"><title>")
            out.write(escape(safeTitle))
            out.write("</title>\n")
            for (page in pages) {
                checkCancelled()
                val width = page.meta.optDouble("xournalPageWidth", 595.0).takeIf { it in 1.0..1e6 } ?: 595.0
                val scale = width / page.width
                val height = page.height * scale
                val style = when (page.meta.optString("template", "blank")) {
                    "blank" -> "plain"
                    "grid" -> "graph"
                    "lined" -> "lined"
                    "dotted" -> "dotted"
                    else -> null
                }
                val objects = page.objects.filter { !it.optBoolean("hidden") && it.optString("type") != "ocrIndex" }
                val flatten = style == null || page.meta.has("pdfSource") || page.meta.has("backgroundImage") ||
                    objects.any { it.optString("type") != "stroke" || it.has(ObjectEraseMask.KEY) ||
                        it.array("points").length() > MAX_STROKE_NUMBERS / 2 ||
                        GraphiteBrush.isGraphite(it) || it.optString("brush") in setOf("pencil", "gel") }
                out.write("<page width=\"$width\" height=\"$height\"><background type=\"solid\" " +
                    "color=\"${rgb(page.meta.optString("paperColor"), "#ffffff")}ff\" style=\"${style ?: "plain"}\"/><layer>\n")
                if (flatten) {
                    out.write("<image left=\"0\" top=\"0\" right=\"$width\" bottom=\"$height\">")
                    out.write(image(page))
                    out.write("</image>")
                } else {
                    for (obj in objects) {
                        checkCancelled()
                        writeStroke(out, obj, scale)
                    }
                }
                out.write("</layer></page>\n")
            }
            out.write("</xournal>")
        }
    }

    private fun writeStroke(out: Writer, obj: JSONObject, scale: Double) {
        val points = InkGeometry.pagePoints(obj)
        if (points.isEmpty()) return
        val widths = LegacyBrush.widths(obj)
        val external = obj.optString("widthProfile") == "xournal"
        val base = obj.f("width", 4f) * scale
        val highlighter = obj.optString("brush") == "highlighter"
        val color = rgb(obj.optString("color"))
        val defaultOpacity = when (obj.optString("brush")) {
            "highlighter" -> .28
            "marker", "fineliner" -> .98
            else -> obj.optJSONObject("settings")?.optDouble("opacity", 1.0)?.takeIf { it.isFinite() } ?: 1.0
        }
        val opacity = obj.optDouble("opacity", defaultOpacity).takeIf { it.isFinite() } ?: defaultOpacity
        val alpha = (opacity.coerceIn(0.0, 1.0) * 255).roundToInt().toString(16).padStart(2, '0')
        val cap = if (external && obj.optString("strokeCap") == "butt" || !external && highlighter) "butt" else "round"
        out.write("<stroke tool=\"${if (highlighter) "highlighter" else "pen"}\" " +
            "color=\"$color$alpha\" capStyle=\"$cap\" width=\"$base")
        if (points.size > 1 && !highlighter) for (i in 0 until widths.lastIndex) {
            val width = if (external) widths[i] else (widths[i] + widths[i + 1]) / 2
            out.write(" " + (width * scale).coerceAtLeast(.01))
        }
        out.write("\">")
        // A repeated point is Xournal's interoperable representation of a pen dot.
        if (points.size == 1) out.write("${points[0].x * scale} ${points[0].y * scale} ")
        for (p in points) out.write("${p.x * scale} ${p.y * scale} ")
        out.write("</stroke>\n")
    }

    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
