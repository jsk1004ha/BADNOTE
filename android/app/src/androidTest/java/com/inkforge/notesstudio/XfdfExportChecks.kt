package com.inkforge.notesstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.File
import java.io.InterruptedIOException
import java.util.UUID
import java.util.zip.InflaterInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.roundToInt

/** Persist real masked objects, export through the user path and compare its alpha/color pixels. */
internal object XfdfExportChecks {
    fun run(context: Context, result: Bundle) {
        val database = "xfdf-export-${UUID.randomUUID()}.db"
        val output = File.createTempFile("xfdf-export-", ".xfdf", context.cacheDir)
        var fixtures = 0
        try {
            NoteRepository(context, database).use { repository ->
                val document = repository.create("XFDF eraser regression", "root", "blank")
                val page = requireNotNull(repository.page(repository.pageIds(document.id).single()))
                page.meta.put("width", 512).put("height", 360)
                    .put("pdfPointWidth", 256).put("pdfPointHeight", 180)
                val sources = listOf(
                    json("id" to "highlight", "type" to "stroke", "brush" to "highlighter", "width" to 100,
                        "opacity" to .4f, "color" to "#ffe066", "points" to JSONArray(listOf(
                            InkPoint(60f, 120f).json(), InkPoint(400f, 120f).json()))),
                    json("id" to "shape", "type" to "shape", "shape" to "rectangle", "x1" to 60,
                        "y1" to 70, "x2" to 400, "y2" to 230, "width" to 30, "color" to "#238648"),
                    json("id" to "text", "type" to "text", "text" to "Goodnotes 한글", "fontSize" to 64,
                        "x" to 60, "y" to 70, "w" to 400, "h" to 180, "textLayoutVersion" to 1),
                    // Legacy text can paint below its nominal height. Export must not crop to bounds().
                    json("id" to "overflow", "type" to "text", "text" to "first\nsecond\nthird", "fontSize" to 44,
                        "x" to 60, "y" to 70, "w" to 300, "h" to 40),
                )
                if (Build.VERSION.SDK_INT < 29) {
                    for (brush in listOf("highlighter", "pencil")) {
                        page.objects.clear()
                        page.objects += ObjectEraseMask.erase(sources.first().copyJson().put("brush", brush),
                            listOf(InkPoint(230f, 120f)), 12f)
                        repository.putPage(document.id, page, 0)
                        val failure = runCatching { XfdfExport.write(repository, document.id, output) }.exceptionOrNull()
                        check(failure is IllegalArgumentException && failure.message.orEmpty().contains("PDF 내보내기")) {
                            "Pre-Q multiply must reject explicitly instead of losing or restoring erased ink"
                        }
                    }
                }
                for (source in sources) for (angle in listOf(0f, .32f)) {
                    if (source.optString("id") == "highlight" && Build.VERSION.SDK_INT < 29) continue
                    val original = source.copyJson().put("rotation", angle)
                    val before = render(page, original)
                    try {
                        val center = paintedCenter(before)
                        // Overlap must remain erased (union), not reappear as an even-odd hole.
                        val erased = ObjectEraseMask.erase(original,
                            listOf(center, InkPoint(center.x + 8f, center.y)), 12f)
                        check(erased !== original && erased.array(ObjectEraseMask.KEY).length() >= 1)
                        val variants = if (angle == 0f) listOf(erased,
                            InkGeometry.transform(erased, 12f, 8f, .85f)) else listOf(erased)
                        for (obj in variants) {
                            page.objects.clear(); page.objects += obj
                            repository.putPage(document.id, page, 0)
                            val persisted = requireNotNull(repository.page(page.id)).objects.single()
                            val snapshot = persisted.toString()
                            XfdfExport.write(repository, document.id, output)
                            val exported = parse(output.readText())
                            check(exported.getElementsByTagName("stamp").length == 1) { "Masked object was not an appearance stamp" }
                            check(exported.getElementsByTagName("ink").length == 0 &&
                                exported.getElementsByTagName("freetext").length == 0 &&
                                exported.getElementsByTagName("contents").length == 0) { "Erased source geometry/text was exported" }
                            val stamp = exported.getElementsByTagName("stamp").item(0) as Element
                            check(stamp.getAttribute("opacity") == "1") { "Appearance alpha would be applied twice" }
                            if (source.optString("id") == "highlight") {
                                val appearance = String(Base64.decode(stamp.getElementsByTagName("appearance").item(0).textContent,
                                    Base64.DEFAULT), Charsets.UTF_8)
                                check(appearance.contains("KEY=\"BM\" VAL=\"Multiply\"")) { "Highlighter blend mode lost" }
                            }
                            val expected = render(page, persisted)
                            val actual = decode(stamp, page)
                            try {
                                assertSamePixels(expected, actual)
                                var visible = false; var removed = false
                                if (obj === erased) {
                                    val centerX = (center.x * 2).toInt(); val centerY = (center.y * 2).toInt()
                                    check(Color.alpha(actual.getPixel(centerX, centerY)) == 0) { "Erased center restored" }
                                    val layered = Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888)
                                    try {
                                        Canvas(layered).apply { drawColor(Color.MAGENTA); drawBitmap(actual, 0f, 0f, null) }
                                        check(layered.getPixel(centerX, centerY) == Color.MAGENTA) { "Cutout covered underlying content" }
                                    } finally { layered.recycle() }
                                    val oldRow = IntArray(before.width); val newRow = IntArray(actual.width)
                                    for (y in 0 until actual.height) {
                                        before.getPixels(oldRow, 0, before.width, 0, y, before.width, 1)
                                        actual.getPixels(newRow, 0, actual.width, 0, y, actual.width, 1)
                                        for (x in oldRow.indices) {
                                            if (oldRow[x] ushr 24 > 20 && newRow[x] ushr 24 == 0) removed = true
                                            if (newRow[x] ushr 24 > 20) visible = true
                                        }
                                    }
                                    check(visible && removed) { "Cutout or surviving ink missing" }
                                }
                            } finally { expected.recycle(); actual.recycle() }
                            check(requireNotNull(repository.page(page.id)).objects.single().toString() == snapshot) {
                                "Export mutated source data"
                            }
                            fixtures++
                        }
                    } finally { before.recycle() }
                }

                // All pixels erased means no recoverable annotation, not an empty default stamp icon.
                page.objects.clear()
                page.objects += ObjectEraseMask.erase(sources[1], listOf(InkPoint(60f, 120f)), 2000f)
                repository.putPage(document.id, page, 0)
                XfdfExport.write(repository, document.id, output)
                check(parse(output.readText()).getElementsByTagName("stamp").length == 0)

                // Native annotation types and escaping are unchanged for unmasked/empty-mask objects.
                page.objects.clear(); page.objects.addAll(sources.take(3).map { it.copyJson() })
                page.objects.last().put("text", "visible <text> & 한글").put(ObjectEraseMask.KEY, JSONArray())
                page.objects += sources.first().copyJson().put("id", "hidden").put("hidden", true)
                repository.putPage(document.id, page, 0)
                XfdfExport.write(repository, document.id, output)
                val unmasked = parse(output.readText())
                check(unmasked.getElementsByTagName("ink").length == 1)
                check(unmasked.getElementsByTagName("square").length == 1)
                check(unmasked.getElementsByTagName("freetext").length == 1)
                check(unmasked.getElementsByTagName("stamp").length == 0)
                check(unmasked.getElementsByTagName("contents").item(0).textContent == "visible <text> & 한글")
                check(runCatching { XfdfExport.write(repository, document.id, output) { true } }
                    .exceptionOrNull() is InterruptedIOException)
                check(repository.workDirectory.listFiles().orEmpty().none { it.name.startsWith("xfdf-rgb-") ||
                    it.name.startsWith("xfdf-alpha-") }) { "Appearance spools leaked" }
                for ((w, h) in listOf(1000f to 1414f, 1f to 100000f, 100000f to 1f, 100000f to 100000f)) {
                    val (rw, rh) = MaskedXfdfAnnotation.rasterSize(w, h)
                    check(rw > 0 && rh > 0 && rw <= 5000 && rh <= 5000 && rw.toLong() * rh <= 4_000_000L)
                }
                repository.deleteDocument(document.id)
            }
        } finally {
            output.delete()
            context.deleteDatabase(database)
        }
        result.putInt("xfdfMaskedFixtures", fixtures)
        result.putString("xfdfCutouts", "highlighter_shape_text_overflow_rotation_move_scale_alpha_union_verified")
        result.putString("xfdfCompatibility", "unmasked_native_types_fully_erased_empty_hidden_cancellation_bounded_spools_verified")
    }

    private fun render(page: NotePage, obj: JSONObject): Bitmap {
        val (width, height) = MaskedXfdfAnnotation.rasterSize(page.width, page.height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply { scale(width / page.width, height / page.height); NoteRenderer().draw(this, obj) }
        }
    }

    private fun paintedCenter(bitmap: Bitmap): InkPoint {
        // Choose an interior painted sample in note coordinates, including rotated text glyphs.
        for (y in 90 until 230) for (x in 90 until 350) {
            if (Color.alpha(bitmap.getPixel(x * 2, y * 2)) > 20) return InkPoint(x.toFloat(), y.toFloat())
        }
        error("Fixture contains no painted center")
    }

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(xml.byteInputStream())

    private fun direct(parent: Element, tag: String, key: String? = null): Element {
        for (i in 0 until parent.childNodes.length) {
            val child = parent.childNodes.item(i)
            if (child is Element && child.tagName == tag && (key == null || child.getAttribute("KEY") == key)) return child
        }
        error("Missing $tag/$key")
    }

    private fun decode(stamp: Element, page: NotePage): Bitmap {
        val ap = parse(String(Base64.decode(stamp.getElementsByTagName("appearance").item(0).textContent,
            Base64.DEFAULT), Charsets.UTF_8)).documentElement
        val normal = direct(ap, "STREAM", "N")
        val image = direct(direct(direct(normal, "DICT", "Resources"), "DICT", "XObject"), "STREAM", "Im0")
        val mask = direct(image, "STREAM", "SMask")
        val width = direct(image, "INT", "Width").getAttribute("VAL").toInt()
        val height = direct(image, "INT", "Height").getAttribute("VAL").toInt()
        check(direct(mask, "INT", "Width").getAttribute("VAL").toInt() == width &&
            direct(mask, "INT", "Height").getAttribute("VAL").toInt() == height)
        fun bytes(stream: Element): ByteArray {
            val hex = direct(stream, "DATA").textContent
            val compressed = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            return InflaterInputStream(compressed.inputStream()).use { it.readBytes() }
        }
        val rgb = bytes(image); val alpha = bytes(mask)
        check(rgb.size == width * height * 3 && alpha.size == width * height)
        val (pageWidth, pageHeight) = MaskedXfdfAnnotation.rasterSize(page.width, page.height)
        val rect = stamp.getAttribute("rect").split(',').map(String::toFloat)
        val x = (rect[0] / page.meta.f("pdfPointWidth") * pageWidth).roundToInt()
        val y = ((page.meta.f("pdfPointHeight") - rect[3]) / page.meta.f("pdfPointHeight") * pageHeight).roundToInt()
        check(abs((rect[2] - rect[0]) / page.meta.f("pdfPointWidth") * pageWidth - width) < .01f)
        check(abs((rect[3] - rect[1]) / page.meta.f("pdfPointHeight") * pageHeight - height) < .01f)
        val pixels = IntArray(width * height) { i ->
            val a = alpha[i].toInt() and 255
            val red = rgb[i * 3].toInt() and 255; val green = rgb[i * 3 + 1].toInt() and 255; val blue = rgb[i * 3 + 2].toInt() and 255
            if (a == 0) check(red == 0 && green == 0 && blue == 0) { "Erased RGB leaked" }
            Color.argb(a, red, green, blue)
        }
        return Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, x, y, width, height)
        }
    }

    private fun assertSamePixels(expected: Bitmap, actual: Bitmap) {
        check(expected.width == actual.width && expected.height == actual.height)
        val first = IntArray(expected.width); val second = IntArray(actual.width)
        var surviving = 0
        for (y in 0 until expected.height) {
            expected.getPixels(first, 0, first.size, 0, y, first.size, 1)
            actual.getPixels(second, 0, second.size, 0, y, second.size, 1)
            for (x in first.indices) {
                val a = first[x] ushr 24
                check(a == (second[x] ushr 24)) { "Export alpha mismatch at $x,$y" }
                if (a > 0) surviving++
                var shift = 0
                while (shift <= 16) {
                    val oldChannel = 255 - a + ((first[x] ushr shift) and 255) * a / 255
                    val newChannel = 255 - a + ((second[x] ushr shift) and 255) * a / 255
                    check(abs(oldChannel - newChannel) <= 1) { "Export color mismatch at $x,$y" }
                    shift += 8
                }
            }
        }
        check(surviving > 0)
    }
}
