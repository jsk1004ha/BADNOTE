package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipException
import kotlin.math.abs
import kotlin.math.roundToInt

internal typealias GoodnotesImportResult = NoteInterchange.ImportResult

internal object GoodnotesImporter {
    private data class ImportState(val assets: MutableList<String> = mutableListOf(), var skipped: Int = 0)
    private data class Bounds(val x: Float, val y: Float, val width: Float, val height: Float)

    fun read(repository: NoteRepository, input: InputStream, title: String, folder: String,
             backup: Boolean = false): GoodnotesImportResult {
        val state = ImportState()
        try {
            return GoodnotesPackage.read(input, repository.workDirectory, backup) { archive ->
                if (backup) readBackup(repository, archive, folder, state) else {
                    val document = readDocument(repository, archive, title, folder, state)
                    GoodnotesImportResult(listOf(document), null, state.skipped)
                }
            }
        } catch (error: Throwable) {
            state.assets.forEach { repository.asset(it).delete() }
            if (error is ZipException) throw IllegalArgumentException("Goodnotes 압축 파일을 열 수 없습니다.", error)
            throw error
        }
    }

    private fun readBackup(repository: NoteRepository, archive: GoodnotesPackage, folder: String,
                           state: ImportState): GoodnotesImportResult {
        val notebooks = archive.names.filter { it.endsWith(".goodnotes", true) && !it.startsWith("__MACOSX/") }
        require(notebooks.size in 1..512) { "가져올 수 있는 Goodnotes 백업 문서가 없습니다." }
        val folders = JSONArray(repository.setting("folders", "[]").ifBlank { "[]" })
        val documents = ArrayList<DocumentInfo>(notebooks.size)
        val knownFolders = folders.objects().associateByTo(HashMap()) {
            it.optString("parentId", "root") to it.optString("title").lowercase(Locale.ROOT)
        }
        repository.transaction {
            for (member in notebooks) {
                val path = member.split('/')
                require(path.size <= 32) { "Goodnotes 백업의 폴더 깊이가 너무 큽니다." }
                val target = path.dropLast(1).fold(folder) { parent, name ->
                    val key = parent to name.lowercase(Locale.ROOT)
                    knownFolders.getOrPut(key) {
                        json("id" to uid("folder"), "title" to name, "parentId" to parent, "color" to "#2468a1")
                            .also(folders::put)
                    }.getString("id")
                }
                val note = archive.consume(member, GoodnotesPackage.NOTE_BYTES) { source ->
                    GoodnotesPackage.read(source, repository.workDirectory, backup = false) { notebook ->
                        readDocument(repository, notebook, NoteInterchange.title(path.last()), target, state)
                    }
                }
                documents.add(note)
            }
            repository.setting("folders", folders.toString(), true)
        }
        return GoodnotesImportResult(documents, folders, state.skipped)
    }

    private fun readDocument(repository: NoteRepository, archive: GoodnotesPackage, title: String, folder: String,
                             state: ImportState): DocumentInfo {
        val structure = GoodnotesStructure(archive)
        val pages = structure.pages()
        state.skipped += structure.audioCount()
        return repository.createFromPages(title, folder) { append ->
            Assets(repository, archive, state).use { assets ->
                for (sourcePage in pages) {
                    val paper = sourcePage.paper
                    val background = paper?.attachment?.let(structure::attachment)?.let { assets.store(it, forPaper = true) }
                    if (paper?.attachment != null && background == null) state.skipped++
                    val pdfSize = if (background?.extension == "pdf") {
                        assets.pdfSize(background, checkNotNull(paper).pageNumber)
                    } else null
                    val width = paper?.width ?: pdfSize?.first?.toFloat() ?: background?.width?.toFloat() ?: 1000f
                    val height = paper?.height ?: pdfSize?.second?.toFloat() ?: background?.height?.toFloat() ?: 1414f
                    val page = NotePage.blank("blank", width, height)
                    page.meta.put("goodnotesPageId", sourcePage.id)
                    if (background != null && pdfSize != null) {
                        page.meta.put("pdfSource", "asset:${background.id}")
                            .put("pdfSourceName", title).put("pdfPageNumber", checkNotNull(paper).pageNumber)
                            .put("pdfPointWidth", pdfSize.first).put("pdfPointHeight", pdfSize.second)
                    } else if (background != null) {
                        page.meta.put("backgroundImage", "asset:${background.id}")
                    }

                    val elements = structure.objects(sourcePage) { state.skipped++ }
                    val flowText = elements.filter { it.kind == 12 }.map { it.body }
                    if (flowText.isNotEmpty()) {
                        val text = GoodnotesText.decodeFlow(flowText, width, height)
                        if (text == null) state.skipped += flowText.size else page.objects.add(text)
                    }
                    val outlines = HashMap<String, JSONObject>()
                    for (element in elements) {
                        if (element.kind == 9 || element.kind == 12) continue
                        // Some newer versions store a reference in header field 6.
                        // A movement in the element body is handled by its own decoder.
                        val delta = element.header.takeIf { it.gnId(6) == null }?.gnFields(6)
                        val dx = delta?.gnFloat(1) ?: 0f
                        val dy = delta?.gnFloat(2) ?: 0f
                        val imported = when (element.kind) {
                            7 -> if (element.body.gnData(9)?.isNotEmpty() == true) {
                                val movement = element.body.gnFields(6)
                                GoodnotesShape.decode(element.body, dx + (movement?.gnFloat(1) ?: 0f),
                                    dy + (movement?.gnFloat(2) ?: 0f))
                            } else GoodnotesInk.decode(element.body, dx, dy)
                            8, 21 -> GoodnotesText.decode(element.kind, element.body, dx, dy)?.let(::listOf)
                            1 -> image(element, structure, assets, dx, dy)?.let(::listOf)
                            else -> null
                        }
                        if (imported == null) {
                            state.skipped++
                        } else {
                            page.objects.addAll(imported)
                            imported.singleOrNull()?.takeIf {
                                it.optString("shape") in setOf("rectangle", "ellipse") ||
                                    it.optString("goodnotesShape") == "polygon"
                            }
                                ?.let { outlines[element.id] = it }
                        }
                    }
                    for (fill in elements.filter { it.kind == 9 }) {
                        val outline = fill.body.gnId(5)?.let(outlines::get)
                        val color = fill.body.gnFields(7)
                        if (outline == null || color == null) state.skipped++
                        else outline.put("fillColor", argb(color))
                    }
                    append(page)
                }
            }
        }
    }

    private fun argb(color: GoodnotesMessage): String {
        fun channel(number: Int, default: Float) =
            ((color.gnFloat(number) ?: default).coerceIn(0f, 1f) * 255).roundToInt()
        val packed = (channel(4, 0f) shl 24) or (channel(1, 0f) shl 16) or
            (channel(2, 0f) shl 8) or channel(3, 0f)
        return "#${packed.toUInt().toString(16).padStart(8, '0')}"
    }

    private fun image(element: GoodnotesObject, structure: GoodnotesStructure, assets: Assets,
                      dx: Float, dy: Float): JSONObject? {
        val member = sequenceOf(element.header.gnId(7), element.header.gnId(4), element.body.gnId(4))
            .filterNotNull().firstNotNullOfOrNull(structure::attachment) ?: return null
        val canvas = element.body.gnFields(2) ?: return null
        val original = bounds(canvas) ?: return null
        val crop = element.body.gnFields(3)
        val cropSize = crop?.gnFields(2)
        val cropWidth = cropSize?.gnFloat(1)?.takeIf { it in .1f..30_000f } ?: original.width
        val cropHeight = cropSize?.gnFloat(2)?.takeIf { it in .1f..30_000f } ?: original.height
        val cut = abs(cropWidth - original.width) > .1f || abs(cropHeight - original.height) > .1f
        val center = crop?.gnFields(1)
        val x = center?.gnFloat(1)?.minus(cropWidth / 2) ?: original.x
        val y = center?.gnFloat(2)?.minus(cropHeight / 2) ?: original.y
        val rotation = crop?.gnFloat(3) ?: 0f
        if (listOf(x + dx, y + dy, original.x, original.y).any { abs(it) > 100_000f }) return null
        val asset = assets.store(member, forPaper = false) ?: return null
        return json("id" to uid("image"), "type" to "image", "src" to "asset:${asset.id}",
            "x" to x + dx, "y" to y + dy, "w" to cropWidth, "h" to cropHeight, "rotation" to rotation).apply {
            if (cut) put("imageCrop", json(
                "offsetX" to (original.x - x) / cropWidth, "offsetY" to (original.y - y) / cropHeight,
                "width" to original.width / cropWidth, "height" to original.height / cropHeight
            ))
        }
    }

    private fun bounds(geometry: GoodnotesMessage): Bounds? {
        val pos = geometry.gnFields(1)
        val size = geometry.gnFields(2) ?: return null
        val width = size.gnFloat(1)?.takeIf { it in .1f..30_000f } ?: return null
        val height = size.gnFloat(2)?.takeIf { it in .1f..30_000f } ?: return null
        return Bounds(pos?.gnFloat(1) ?: 0f, pos?.gnFloat(2) ?: 0f, width, height)
    }

    private data class Asset(val id: String, val extension: String, val width: Int? = null, val height: Int? = null)

    private class Assets(private val repository: NoteRepository, private val archive: GoodnotesPackage,
                         private val state: ImportState) : Closeable {
        private val imported = HashMap<String, Asset>()
        private val pdfImages = HashMap<String, Asset>()
        private var openPdf: Pair<String, PdfRenderer>? = null

        fun store(member: String, forPaper: Boolean): Asset? {
            if (!forPaper) pdfImages[member]?.let { return it }
            imported[member]?.takeIf { forPaper || it.extension != "pdf" }?.let { return it }
            val signature = archive.prefix(member, 12)
            val extension = when {
                signature.size >= 5 && signature.toString(Charsets.ISO_8859_1).startsWith("%PDF-") -> "pdf"
                signature.size >= 3 && signature[0] == 0xff.toByte() && signature[1] == 0xd8.toByte() &&
                    signature[2] == 0xff.toByte() -> "jpg"
                signature.size >= 8 && signature.copyOf(8).contentEquals(
                    byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                ) -> "png"
                signature.size == 12 && signature.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                    signature.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "webp"
                else -> return null
            }
            if (!forPaper && extension == "pdf") {
                return pdfImage(member)?.also { pdfImages[member] = it }
            }
            var size: Pair<Int, Int>? = null
            val id = archive.consume(member, GoodnotesPackage.NOTE_BYTES) { input ->
                repository.storeAsset(input, extension) { file ->
                    if (extension != "pdf") size = NoteRepository.checkedImageSize(file)
                }.also(state.assets::add)
            }
            return Asset(id, extension, size?.first, size?.second).also { imported[member] = it }
        }

        private fun pdfImage(member: String): Asset? {
            val pdf = File.createTempFile("goodnotes-sticker-", ".pdf", repository.workDirectory)
            val png = File(pdf.parentFile, "${pdf.name}.png")
            try {
                archive.consume(member, GoodnotesPackage.NOTE_BYTES) { input ->
                    pdf.outputStream().use { ArchiveCodec.copy(input, it) }
                }
                return PdfImporter.openRenderer(pdf).use renderer@ { renderer ->
                    if (renderer.pageCount != 1) return@renderer null
                    renderer.openPage(0).use page@ { page ->
                        if (page.width <= 0 || page.height <= 0) return@page null
                        val scale = 1536f / maxOf(page.width, page.height)
                        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            png.outputStream().use {
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                                    "Goodnotes 스티커 이미지를 만들 수 없습니다."
                                }
                            }
                        } finally {
                            bitmap.recycle()
                        }
                        val id = png.inputStream().use { repository.storeAsset(it, "png") }
                        state.assets.add(id)
                        Asset(id, "png", width, height)
                    }
                }
            } finally {
                png.delete()
                pdf.delete()
            }
        }

        fun pdfSize(asset: Asset, pageNumber: Int): Pair<Int, Int> {
            if (openPdf?.first != asset.id) {
                close()
                openPdf = asset.id to PdfImporter.openRenderer(repository.asset(asset.id))
            }
            val renderer = checkNotNull(openPdf).second
            require(pageNumber in 1..renderer.pageCount) { "Goodnotes에서 참조한 PDF 페이지가 없습니다." }
            return renderer.openPage(pageNumber - 1).use { it.width to it.height }
        }

        override fun close() {
            openPdf?.second?.close()
            openPdf = null
        }
    }
}
