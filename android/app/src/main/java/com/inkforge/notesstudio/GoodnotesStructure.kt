package com.inkforge.notesstudio

import java.util.Locale

internal fun GoodnotesMessage.gnValue(number: Int): Long? =
    firstOrNull { it.number == number && it.wire == 0 }?.value

internal fun GoodnotesMessage.gnData(number: Int): ByteArray? =
    firstOrNull { it.number == number && it.wire == 2 }?.data

internal fun GoodnotesMessage.gnFields(number: Int): GoodnotesMessage? = gnData(number)?.let(GoodnotesWire::read)

internal fun GoodnotesMessage.gnString(number: Int): String? = gnData(number)?.toString(Charsets.UTF_8)

internal fun GoodnotesMessage.gnFloat(number: Int): Float? = firstOrNull { it.number == number }?.let { field ->
    when (field.wire) {
        5 -> Float.fromBits(field.value.toInt())
        1 -> Double.fromBits(field.value).toFloat()
        else -> null
    }?.takeIf(Float::isFinite)
}

private val goodnotesUuid = Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
internal fun GoodnotesMessage.gnId(number: Int): String? =
    gnString(number)?.takeIf(goodnotesUuid::matches)?.uppercase(Locale.ROOT)

internal data class GoodnotesPaper(val attachment: String?, val pageNumber: Int, val width: Float?, val height: Float?)
internal data class GoodnotesPageSpec(val id: String, val member: String, val paper: GoodnotesPaper?)
internal data class GoodnotesObject(val id: String, val kind: Int, val header: GoodnotesMessage, val body: GoodnotesMessage)

/** Goodnotes uses indexed ZIP members and versioned page registers.
 * Observed format: https://github.com/Kaih1825/parser-for-goodnotes/tree/baec907c2aa2d222502fe3e95f0e669acd78e0cd/wiki
 */
internal class GoodnotesStructure(private val archive: GoodnotesPackage) {
    private data class IndexEntry(val id: String, val path: String)
    private data class Register(val value: String, val version: Long)

    private fun index(name: String, directory: String): List<IndexEntry> {
        return archive.records(name).mapNotNull { record ->
            if (record.isEmpty()) return@mapNotNull null
            val id = requireNotNull(record.gnId(1)) { "지원하지 않는 Goodnotes 목록 형식입니다." }
            val path = requireNotNull(record.gnString(2)) { "Goodnotes 항목 경로가 없습니다." }
            require(path.startsWith(directory) && path in archive.names) { "Goodnotes 항목이 누락되었습니다." }
            IndexEntry(id, path)
        }.also { list ->
            require(list.map { it.id }.toSet().size == list.size) { "Goodnotes 목록에 중복 항목이 있습니다." }
        }
    }

    private val attachments = if ("index.attachments.pb" in archive.names) {
        index("index.attachments.pb", "attachments/").associate { it.id to it.path }
    } else emptyMap()

    fun attachment(id: String): String? = attachments[id] ?: "attachments/$id".takeIf { it in archive.names }

    private val events by lazy {
        if ("index.events.pb" in archive.names) archive.records("index.events.pb") else emptyList()
    }

    fun audioCount(): Int {
        val deleted = events.mapNotNull { it.gnFields(163)?.gnId(1) }.toHashSet()
        return events.mapNotNull { it.gnFields(160)?.gnId(1) }.toSet().count { it !in deleted }
    }

    fun pages(): List<GoodnotesPageSpec> {
        require("index.notes.pb" in archive.names) { "지원하지 않는 Goodnotes 문서입니다." }
        val indexed = index("index.notes.pb", "notes/")
        require(indexed.size in 1..10_000) { "가져올 수 있는 Goodnotes 페이지가 없습니다." }

        val knownIds = indexed.mapTo(HashSet()) { it.id }
        // Some events refer to an earlier revision of a page ID. Use the unique
        // shared prefix only when the ID is absent from the current index.
        val byPrefix = indexed.groupBy { it.id.take(32) }
        fun resolve(id: String?): String? =
            if (id in knownIds) id else id?.let { byPrefix[it.take(32)]?.singleOrNull()?.id }

        val paper = HashMap<String, GoodnotesPaper>()
        val pagePaper = HashMap<String, Register>()
        val pageOrder = HashMap<String, Register>()
        val deleted = HashSet<String>()
        fun assign(registers: MutableMap<String, Register>, id: String, value: GoodnotesMessage?) {
            val data = value?.gnString(1)?.takeIf(String::isNotBlank) ?: return
            val version = value.gnFields(2)?.gnValue(1) ?: 0L
            if (version >= (registers[id]?.version ?: -1L)) registers[id] = Register(data, version)
        }

        for (event in events) {
            event.gnFields(2)?.let { layer ->
                val id = layer.gnId(2) ?: return@let
                val size = layer.gnFields(8)
                val width = size?.gnFloat(1)?.takeIf { it in 32f..30_000f }
                val height = size?.gnFloat(2)?.takeIf { it in 32f..30_000f }
                val pdfPage = layer.gnValue(5)?.takeIf { it in 1L..100_000L }?.toInt() ?: 1
                paper[id] = GoodnotesPaper(layer.gnId(4), pdfPage, width, height)
            }
            event.gnFields(54)?.let { create ->
                val id = resolve(create.gnId(2)) ?: return@let
                assign(pagePaper, id, create.gnFields(3))
                assign(pageOrder, id, create.gnFields(4))
            }
            event.gnFields(3)?.let { change ->
                val id = resolve(change.gnId(2)) ?: return@let
                assign(pagePaper, id, change.gnFields(3))
            }
            event.gnFields(55)?.let { move ->
                val id = resolve(move.gnId(2)) ?: return@let
                assign(pageOrder, id, move.gnFields(3))
            }
            event.gnFields(56)?.let { remove -> resolve(remove.gnId(2))?.let(deleted::add) }
        }
        return indexed.asSequence().filterNot { it.id in deleted }
            .sortedWith(compareBy<IndexEntry> { it.id !in pageOrder }.thenBy { pageOrder[it.id]?.value })
            .map { GoodnotesPageSpec(it.id, it.path, pagePaper[it.id]?.value?.uppercase(Locale.ROOT)?.let(paper::get)) }
            .toList().also { require(it.isNotEmpty()) { "가져올 수 있는 Goodnotes 페이지가 없습니다." } }
    }

    fun objects(page: GoodnotesPageSpec, skipped: () -> Unit): List<GoodnotesObject> {
        data class ElementVersion(val version: Long, val position: Long, val element: GoodnotesObject?)
        val versions = LinkedHashMap<String, ElementVersion>()
        var header: GoodnotesMessage = emptyList()
        var index = 0L
        for (record in archive.records(page.member)) {
            if (record.isEmpty()) continue
            if (record.gnId(1) != null) {
                header = record
                continue
            }
            val bodyField = record.singleOrNull()?.takeIf { it.data != null }
            if (bodyField == null) {
                skipped()
                header = emptyList()
                continue
            }
            val body = GoodnotesWire.read(checkNotNull(bodyField.data))
            val id = body.gnId(1)
            val elementHeader = header.takeIf { it.gnId(1) == id }.orEmpty()
            header = emptyList()
            if (id == null) {
                skipped()
                continue
            }
            val version = (elementHeader.gnFields(2) ?: body.gnFields(15))?.gnValue(1) ?: 0L
            if (version < (versions[id]?.version ?: -1L)) continue
            val deleted = elementHeader.gnValue(3) == 1L || body.gnValue(14) == 1L
            val element = if (deleted) null else GoodnotesObject(id, bodyField.number, elementHeader, body)
            versions[id] = ElementVersion(version, elementHeader.gnValue(9) ?: index, element)
            index++
        }
        return versions.values.sortedBy { it.position }.mapNotNull { it.element }
    }
}
