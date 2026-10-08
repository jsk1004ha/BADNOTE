package com.inkforge.notesstudio

import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Asset bytes are never represented as strings, byte arrays, or a whole-document snapshot. */
object ArchiveCodec {
    const val BUFFER_SIZE = 64 * 1024
    const val MAX_ARCHIVE_BYTES = 16L * 1024 * 1024 * 1024
    data class Entry(val name: String, val input: () -> InputStream)

    fun copy(input: InputStream, output: OutputStream, cancelled: () -> Boolean = { false },
             progress: (Long) -> Unit = {}): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            if (cancelled()) throw InterruptedIOException("작업을 취소했습니다.")
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            output.write(buffer, 0, n)
            total += n
            progress(total)
        }
        return total
    }

    fun write(output: OutputStream, entries: Sequence<Entry>, cancelled: () -> Boolean = { false },
              progress: (Int, Long) -> Unit = { _, _ -> }) {
        ZipOutputStream(BufferedOutputStream(output, BUFFER_SIZE)).use { zip ->
            zip.setLevel(1)
            val seen = HashSet<String>()
            var bytes = 0L
            var count = 0
            for (entry in entries) {
                require(validName(entry.name) && seen.add(entry.name)) { "잘못되거나 중복된 파일 이름" }
                zip.putNextEntry(ZipEntry(entry.name))
                entry.input().use { input ->
                    val base = bytes
                    bytes += copy(input, zip, cancelled) { progress(count, base + it) }
                }
                zip.closeEntry()
                progress(++count, bytes)
            }
        }
    }

    fun extract(input: InputStream, destination: File, cancelled: () -> Boolean = { false },
                limit: Long = MAX_ARCHIVE_BYTES): List<String> {
        require(destination.mkdirs() || destination.isDirectory)
        val root = destination.canonicalFile
        val seen = HashSet<String>()
        var total = 0L
        ZipInputStream(BufferedInputStream(input, BUFFER_SIZE)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && validName(entry.name) && seen.add(entry.name)) { "유효하지 않은 노트 파일 경로" }
                require(seen.size <= 200_000) { "노트 파일 항목이 너무 많습니다." }
                val target = File(root, entry.name).canonicalFile
                require(target.path.startsWith(root.path + File.separator)) { "노트 파일 경로 오류" }
                target.parentFile?.mkdirs()
                val base = total
                FileOutputStream(target).use { output ->
                    total += copy(zip, output, cancelled) {
                        require(base + it <= limit) { "노트 파일 크기 한도를 초과했습니다." }
                        if (entry.name.endsWith(".json")) require(it <= 64L * 1024 * 1024) { "페이지 데이터가 너무 큽니다." }
                    }
                }
                zip.closeEntry() // verifies CRC before the import is committed
            }
        }
        require("manifest.json" in seen) { "노트 목록 정보가 없습니다." }
        return seen.toList()
    }

    private fun validName(name: String): Boolean =
        name == "manifest.json" || Regex("(?:pages|assets)/[A-Za-z0-9_.-]{1,160}").matches(name)
}
