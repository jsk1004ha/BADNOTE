package com.inkforge.notesstudio

import java.io.Closeable
import java.io.File
import java.io.Writer
import java.util.zip.DeflaterOutputStream

/**
 * XFDF stamp appearances serialize the PDF AP dictionary as base64 XML. The caller
 * supplies that base64 layer; this writer streams the nested Form/Image/SMask objects.
 * RGB and alpha are lossless Flate streams, never a white rectangle over erased ink.
 */
internal class XfdfStampAppearance private constructor(
    val width: Int,
    val height: Int,
    private val rgb: File,
    private val alpha: File,
    private val multiply: Boolean,
) : Closeable {
    fun writeXml(out: Writer, checkCancelled: () -> Unit = {}) {
        out.write("<DICT KEY=\"AP\"><STREAM KEY=\"N\">")
        out.write("<NAME KEY=\"Type\" VAL=\"XObject\"/><NAME KEY=\"Subtype\" VAL=\"Form\"/>")
        out.write("<INT KEY=\"FormType\" VAL=\"1\"/>")
        out.write("<ARRAY KEY=\"BBox\"><INT VAL=\"0\"/><INT VAL=\"0\"/><INT VAL=\"$width\"/><INT VAL=\"$height\"/></ARRAY>")
        out.write("<ARRAY KEY=\"Matrix\"><INT VAL=\"1\"/><INT VAL=\"0\"/><INT VAL=\"0\"/><INT VAL=\"1\"/><INT VAL=\"0\"/><INT VAL=\"0\"/></ARRAY>")
        out.write("<DICT KEY=\"Resources\">")
        if (multiply) out.write("<DICT KEY=\"ExtGState\"><DICT KEY=\"GS0\"><NAME KEY=\"BM\" VAL=\"Multiply\"/></DICT></DICT>")
        out.write("<DICT KEY=\"XObject\"><STREAM KEY=\"Im0\">")
        imageDictionary(out, "DeviceRGB", rgb.length())
        out.write("<STREAM KEY=\"SMask\">")
        imageDictionary(out, "DeviceGray", alpha.length())
        data(out, alpha, checkCancelled)
        out.write("</STREAM>")
        data(out, rgb, checkCancelled)
        out.write("</STREAM></DICT></DICT>")
        // PDF image samples run from top to bottom; the positive image matrix places
        // the first scanline at the top of the Form, matching Android bitmap rows.
        val commands = "q ${if (multiply) "/GS0 gs " else ""}$width 0 0 $height 0 0 cm /Im0 Do Q\n"
        out.write("<INT KEY=\"Length\" VAL=\"${commands.length}\"/>")
        out.write("<DATA ENCODING=\"ASCII\" MODE=\"RAW\">$commands</DATA></STREAM></DICT>")
    }

    private fun imageDictionary(out: Writer, colorSpace: String, length: Long) {
        out.write("<NAME KEY=\"Type\" VAL=\"XObject\"/><NAME KEY=\"Subtype\" VAL=\"Image\"/>")
        out.write("<INT KEY=\"Width\" VAL=\"$width\"/><INT KEY=\"Height\" VAL=\"$height\"/>")
        out.write("<INT KEY=\"BitsPerComponent\" VAL=\"8\"/><NAME KEY=\"ColorSpace\" VAL=\"$colorSpace\"/>")
        out.write("<NAME KEY=\"Filter\" VAL=\"FlateDecode\"/><INT KEY=\"Length\" VAL=\"$length\"/>")
        out.write("<BOOL KEY=\"Interpolate\" VAL=\"false\"/>")
    }

    private fun data(out: Writer, file: File, checkCancelled: () -> Unit) {
        out.write("<DATA ENCODING=\"HEX\" MODE=\"RAW\">")
        val bytes = ByteArray(8192)
        val chars = CharArray(bytes.size * 2)
        val hex = "0123456789ABCDEF"
        file.inputStream().buffered().use { input ->
            while (true) {
                checkCancelled()
                val count = input.read(bytes)
                if (count < 0) break
                for (i in 0 until count) {
                    val value = bytes[i].toInt() and 255
                    chars[2 * i] = hex[value ushr 4]
                    chars[2 * i + 1] = hex[value and 15]
                }
                out.write(chars, 0, count * 2)
            }
        }
        out.write("</DATA>")
    }

    override fun close() {
        rgb.delete()
        alpha.delete()
    }

    companion object {
        /** Only one row and the two compressed disk spools are retained in addition to the bitmap. */
        fun create(width: Int, height: Int, directory: File,
                   checkCancelled: () -> Unit = {}, multiply: Boolean = false,
                   readRow: (Int, IntArray) -> Unit): XfdfStampAppearance {
            require(width > 0 && height > 0 && width.toLong() * height <= 4_000_000L)
            val rgb = File.createTempFile("xfdf-rgb-", ".flate", directory)
            var alpha: File? = null
            try {
                val alphaFile = File.createTempFile("xfdf-alpha-", ".flate", directory)
                alpha = alphaFile
                val pixels = IntArray(width)
                val colors = ByteArray(width * 3)
                val alphas = ByteArray(width)
                DeflaterOutputStream(rgb.outputStream().buffered()).use { colorOut ->
                    DeflaterOutputStream(alphaFile.outputStream().buffered()).use { alphaOut ->
                        for (y in 0 until height) {
                            checkCancelled()
                            readRow(y, pixels)
                            for (x in 0 until width) {
                                val pixel = pixels[x]
                                val opacity = pixel ushr 24
                                alphas[x] = opacity.toByte()
                                // Do not retain hidden RGB data for a fully erased pixel.
                                val visible = if (opacity == 0) 0 else pixel
                                colors[3 * x] = (visible ushr 16).toByte()
                                colors[3 * x + 1] = (visible ushr 8).toByte()
                                colors[3 * x + 2] = visible.toByte()
                            }
                            colorOut.write(colors)
                            alphaOut.write(alphas)
                        }
                    }
                }
                return XfdfStampAppearance(width, height, rgb, alphaFile, multiply)
            } catch (error: Throwable) {
                rgb.delete()
                alpha?.delete()
                throw error
            }
        }
    }
}
