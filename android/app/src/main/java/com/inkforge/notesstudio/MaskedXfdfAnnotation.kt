package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.Base64
import android.util.Base64OutputStream
import org.json.JSONObject
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/** Masked objects must travel as their visible appearance, not recoverable original geometry/text. */
internal object MaskedXfdfAnnotation {
    internal fun rasterSize(width: Float, height: Float): Pair<Int, Int> {
        require(width.isFinite() && height.isFinite() && width > 0f && height > 0f) {
            "페이지 크기가 올바르지 않습니다."
        }
        val scale = minOf(2.0, 5000.0 / width, 5000.0 / height,
            sqrt(4_000_000.0 / (width.toDouble() * height)))
        return floor(width * scale).toInt().coerceAtLeast(1) to
            floor(height * scale).toInt().coerceAtLeast(1)
    }

    fun write(repository: NoteRepository, page: NotePage, obj: JSONObject, pageIndex: Int,
              out: Writer, checkCancelled: () -> Unit) {
        val multiply = obj.optString("type") == "stroke" && obj.optString("brush") in setOf("highlighter", "pencil")
        // The pre-Q renderer uses PorterDuff MULTIPLY, whose transparent-backdrop
        // semantics differ from PDF/BlendMode MULTIPLY. Never silently drop that ink.
        require(!multiply || Build.VERSION.SDK_INT >= 29) {
            "이 Android 버전에서는 지운 형광펜·연필을 PDF 주석으로 내보낼 수 없습니다. PDF 내보내기를 사용해 주세요."
        }
        val (width, height) = rasterSize(page.width, page.height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var image: Bitmap? = null
        try {
            checkCancelled()
            val canvas = Canvas(bitmap)
            canvas.scale(width / page.width, height / page.height)
            val renderer = NoteRenderer { reference ->
                require(reference.startsWith("asset:")) { "이미지 자산을 읽을 수 없습니다." }
                image ?: (BackgroundLoader.decodeImage(repository.asset(reference.removePrefix("asset:")), 2000)
                    ?: error("이미지 자산을 읽을 수 없습니다.")).also { image = it }
            }
            // Render the full page before finding the alpha bounds: text can overflow its
            // nominal box, thick brushes have caps, and rotations move both ink and masks.
            renderer.draw(canvas, obj)
            checkCancelled()
            val row = IntArray(width)
            var left = width; var top = height; var right = 0; var bottom = 0
            for (y in 0 until height) {
                checkCancelled()
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                for (x in 0 until width) if (row[x] ushr 24 != 0) {
                    left = min(left, x); top = min(top, y)
                    right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
                }
            }
            // A fully erased object contributes no annotation and no hidden source content.
            if (right <= left || bottom <= top) return
            val croppedWidth = right - left; val croppedHeight = bottom - top
            XfdfStampAppearance.create(croppedWidth, croppedHeight, repository.workDirectory,
                checkCancelled, multiply) { y, pixels ->
                bitmap.getPixels(pixels, 0, croppedWidth, left, top + y, croppedWidth, 1)
            }.use { appearance ->
                val pw = page.meta.f("pdfPointWidth", 595f)
                val ph = page.meta.f("pdfPointHeight", 842f)
                require(pw.isFinite() && ph.isFinite() && pw > 0 && ph > 0) { "PDF 페이지 크기가 올바르지 않습니다." }
                val rect = "${left.toFloat() / width * pw},${ph - bottom.toFloat() / height * ph}," +
                    "${right.toFloat() / width * pw},${ph - top.toFloat() / height * ph}"
                // Alpha (including highlighter opacity) is already baked into SMask.
                out.write("<stamp page=\"$pageIndex\" name=\"${XfdfExport.xml(obj.optString("id"))}\" " +
                    "rect=\"$rect\" opacity=\"1\" flags=\"print\"><appearance>")
                val bridge = object : OutputStream() {
                    override fun write(value: Int) { out.write(value) }
                    override fun write(bytes: ByteArray, offset: Int, count: Int) {
                        out.write(String(bytes, offset, count, Charsets.US_ASCII))
                    }
                    override fun close() { out.flush() }
                }
                OutputStreamWriter(Base64OutputStream(bridge, Base64.NO_WRAP), Charsets.UTF_8).use {
                    appearance.writeXml(it, checkCancelled)
                }
                out.write("</appearance></stamp>")
            }
        } finally {
            image?.recycle()
            bitmap.recycle()
        }
    }
}
