package com.inkforge.notesstudio

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Reads Goodnotes members by name without extracting archive paths to the filesystem. */
internal class GoodnotesPackage private constructor(private val zip: ZipFile, expandedLimit: Long) {
    private val entries: Map<String, ZipEntry> = buildMap {
        var expanded = 0L
        var count = 0
        for (entry in zip.entries().asSequence()) {
            val path = entry.name.removeSuffix("/")
            require(path.length in 1..1024 && '\\' !in path && path.split('/').all {
                it.isNotEmpty() && it != "." && it != ".." && it.none(Char::isISOControl)
            }) { "Goodnotes 파일 경로가 올바르지 않습니다." }
            require(++count <= 40_000 && entry.size in 0..(expandedLimit - expanded)) {
                "Goodnotes 파일 크기 한도를 초과했습니다."
            }
            expanded += entry.size
            if (!entry.isDirectory) {
                require(entry.crc >= 0 && put(path, entry) == null) { "중복되거나 잘못된 Goodnotes 항목입니다." }
            }
        }
    }

    val names: Set<String> get() = entries.keys

    fun records(name: String): List<GoodnotesMessage> = consume(name, MAX_RECORD_FILE_BYTES) {
        GoodnotesWire.records(it.readBytes())
    }

    fun prefix(name: String, length: Int): ByteArray = zip.getInputStream(entries.getValue(name)).use { input ->
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        bytes.copyOf(offset)
    }

    fun <T> consume(name: String, limit: Long, read: (InputStream) -> T): T {
        val entry = requireNotNull(entries[name]) { "Goodnotes 파일에 필요한 데이터가 없습니다." }
        require(entry.size <= limit) { "Goodnotes 파일 항목이 너무 큽니다." }
        return CheckedMember(zip.getInputStream(entry), entry).use { input ->
            read(input).also { input.verifyEnd() }
        }
    }

    private class CheckedMember(source: InputStream, private val entry: ZipEntry) : FilterInputStream(source) {
        private val crc = CRC32()
        private var count = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) {
                advance(1)
                crc.update(value)
            }
            return value
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(bytes, offset, length)
            if (read > 0) {
                advance(read)
                crc.update(bytes, offset, read)
            }
            return read
        }

        private fun advance(bytes: Int) {
            count += bytes
            require(count <= entry.size) { "Goodnotes 항목 길이가 올바르지 않습니다." }
        }

        fun verifyEnd() {
            require(count == entry.size && crc.value == entry.crc) { "Goodnotes 파일 항목이 손상되었습니다." }
        }
    }

    companion object {
        const val NOTE_BYTES = 200L * 1024 * 1024
        const val BACKUP_BYTES = 1024L * 1024 * 1024
        private const val MAX_RECORD_FILE_BYTES = 16L * 1024 * 1024

        fun <T> read(input: InputStream, workDirectory: File, backup: Boolean, parse: (GoodnotesPackage) -> T): T {
            val limit = if (backup) BACKUP_BYTES else NOTE_BYTES
            val file = File.createTempFile("goodnotes-", ".zip", workDirectory)
            try {
                file.outputStream().use { output ->
                    ArchiveCodec.copy(input, output, progress = {
                        require(it <= limit) { "Goodnotes 파일 크기 한도를 초과했습니다." }
                    })
                }
                return ZipFile(file).use { parse(GoodnotesPackage(it, if (backup) limit else limit * 2)) }
            } finally {
                file.delete()
            }
        }
    }
}
