package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Base64OutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

/** Explicit 3.x JSON export. Assets are encoded in bounded chunks, one page at a time. */
class LegacyJsonWriter(private val repository: NoteRepository, private val cancelled: () -> Boolean) {
    private lateinit var output: Writer
    private val temporaryAssets = HashMap<String, File>()

    fun write(documentId: String, target: File) {
        val document = requireNotNull(repository.document(documentId)) { "내보낼 노트가 없습니다." }
        val ids = repository.pageIds(documentId)
        require(ids.isNotEmpty()) { "내보낼 페이지가 없습니다." }
        val generations = ids.associateWith(repository::generation)
        val originalDocument = document.data.toString()
        try {
            FileOutputStream(target).use { stream ->
                output = BufferedWriter(OutputStreamWriter(stream, StandardCharsets.UTF_8), 64 * 1024)
                output.write('{'.code)
                val metadata = document.data.copyJson().put("version", 3)
                metadata.remove("pages")
                var comma = false
                metadata.keys().asSequence().toList().forEach { key ->
                    checkCancelled()
                    if (comma) output.write(','.code)
                    fieldName(key); value(metadata.opt(key), key); comma = true
                }
                if (comma) output.write(','.code)
                fieldName("pages"); output.write('['.code)
                ids.forEachIndexed { index, pageId ->
                    checkCancelled()
                    if (index > 0) output.write(','.code)
                    val page = requireNotNull(repository.page(pageId)) { "내보내는 중 페이지가 삭제되었습니다." }
                    val body = page.json()
                    var spool: File? = null
                    try {
                        if (page.meta.optString("pdfSource").startsWith("asset:")) {
                            spool = renderPdfBackground(page)
                            val token = "__pdf_background_${page.id}__"
                            temporaryAssets[token] = spool
                            body.put("backgroundImage", "asset:$token")
                            body.remove("pdfSource")
                            body.remove("backgroundAssetId")
                        }
                        repository.exportOcrSidecar(pageId)?.let { body.put("_nativeOcrSidecar", it) }
                        value(body)
                    } finally {
                        spool?.delete()
                        temporaryAssets.clear()
                    }
                }
                output.write("]}")
                output.flush()
                require(repository.document(documentId)?.data?.toString() == originalDocument &&
                    repository.pageIds(documentId) == ids &&
                    ids.all { repository.generation(it) == generations[it] }) {
                    "내보내는 동안 노트가 변경되었습니다. 다시 시도해 주세요."
                }
                checkCancelled()
                stream.fd.sync()
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private fun fieldName(key: String) { output.write(JSONObject.quote(key)); output.write(':'.code) }

    private fun value(item: Any?, field: String = "") {
        checkCancelled()
        when (item) {
            null, JSONObject.NULL -> output.write("null")
            is JSONObject -> {
                output.write('{'.code)
                item.keys().asSequence().toList().forEachIndexed { index, key ->
                    if (index > 0) output.write(','.code)
                    fieldName(key); value(item.opt(key), key)
                }
                output.write('}'.code)
            }
            is JSONArray -> {
                output.write('['.code)
                for (index in 0 until item.length()) {
                    if (index > 0) output.write(','.code)
                    value(item.opt(index))
                }
                output.write(']'.code)
            }
            is String -> if (isAssetField(field) && item.startsWith("asset:")) asset(item.removePrefix("asset:"))
                else output.write(JSONObject.quote(item))
            is Number, is Boolean -> output.write(item.toString())
            else -> output.write(JSONObject.quote(item.toString()))
        }
    }

    private fun asset(assetId: String) {
        val file = temporaryAssets[assetId] ?: repository.asset(assetId)
        require(file.isFile) { "노트 자산이 없습니다: $assetId" }
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"
            "pdf" -> "application/pdf"; "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"
            "webm" -> "audio/webm"; "ogg" -> "audio/ogg"; "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
        output.write('"'.code); output.write("data:$mime;base64,")
        val bridge = object : OutputStream() {
            override fun write(byte: Int) { output.write(byte) }
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                output.write(String(bytes, offset, count, StandardCharsets.US_ASCII))
            }
            override fun close() { output.flush() }
        }
        Base64OutputStream(bridge, Base64.NO_WRAP).use { encoded ->
            FileInputStream(file).use { input ->
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled()
                    val count = input.read(chunk)
                    if (count < 0) break
                    encoded.write(chunk, 0, count)
                }
            }
        }
        output.write('"'.code)
    }

    private fun renderPdfBackground(page: NotePage): File {
        val file = repository.asset(page.meta.getString("pdfSource").removePrefix("asset:"))
        val outputFile = File.createTempFile("legacy-pdf-background-", ".jpg", repository.workDirectory)
        try {
            val width = minOf(1600, (3200f * page.width / page.height).roundToInt()).coerceAtLeast(1)
            val height = (width * page.height / page.width).roundToInt().coerceIn(1, 3200)
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try { PdfRenderer(descriptor) } catch (error: Exception) { descriptor.close(); throw error }
            renderer.use { pdf ->
                pdf.openPage(page.meta.optInt("pdfPageNumber", 1) - 1).use { source ->
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        source.render(bitmap, null, Matrix().apply {
                            setScale(width.toFloat() / source.width, height.toFloat() / source.height)
                        }, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        FileOutputStream(outputFile).use { stream ->
                            require(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)) { "PDF 배경을 변환하지 못했습니다." }
                        }
                    } finally { bitmap.recycle() }
                }
            }
            return outputFile
        } catch (error: Exception) {
            outputFile.delete()
            throw error
        }
    }

    private fun checkCancelled() { if (cancelled()) throw CancellationException("내보내기를 취소했습니다.") }
}
