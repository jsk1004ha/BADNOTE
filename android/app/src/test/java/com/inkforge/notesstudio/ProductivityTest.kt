package com.inkforge.notesstudio

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest

class ProductivityTest {
    @Test fun shortcutsProtectTextDialogsModifiersAndMissingEditor() {
        fun action(key: String, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false,
                   meta: Boolean = false, text: Boolean = false, open: Boolean = true, blocked: Boolean = false) =
            EditorShortcuts.resolve(key, ctrl, shift, alt, meta, text, open, blocked)
        assertEquals(EditorShortcuts.Action.UNDO, action("Z", ctrl = true))
        assertEquals(EditorShortcuts.Action.REDO, action("Z", ctrl = true, shift = true))
        assertEquals(EditorShortcuts.Action.REDO, action("Y", ctrl = true))
        assertEquals(EditorShortcuts.Action.SAVE, action("S", ctrl = true))
        val tools = listOf("PEN", "HIGHLIGHTER", "ERASER", "LASSO", "HAND", "TEXT", "SHAPE", "TAPE", "LASER")
        tools.forEachIndexed { index, tool -> assertEquals(tool, action("${index + 1}")?.name) }
        for (key in listOf("Z", "1")) {
            assertNull(action(key, ctrl = key == "Z", text = true))
            assertNull(action(key, ctrl = key == "Z", open = false))
            assertNull(action(key, ctrl = key == "Z", blocked = true))
            assertNull(action(key, ctrl = key == "Z", alt = true))
            assertNull(action(key, ctrl = key == "Z", meta = true))
        }
        assertNull(action("1", ctrl = true)); assertNull(action("1", shift = true))
    }

    @Test fun windowsHaveIndependentDocumentsButSingleWriterPerDocument() {
        val claims = WindowClaims<Any>(); val first = Any(); val second = Any()
        assertNull(claims.claim("a", first)); assertNull(claims.claim("b", second))
        assertSame(first, claims.claim("a", second))
        assertNull(claims.claim("a", first))
        claims.release("a", second); assertSame(first, claims.owner("a"))
        claims.release("a", first); assertNull(claims.claim("a", second))
        claims.releaseAll(first); assertSame(second, claims.owner("a"))
        claims.releaseAll(second); assertNull(claims.owner("a")); assertNull(claims.owner("b"))
    }

    private class Journal : BackupEngine.Journal {
        val rows = HashMap<String, BackupEngine.Checkpoint>()
        var fail = false
        override fun previous(id: String) = rows[id]
        override fun commit(id: String, checkpoint: BackupEngine.Checkpoint) {
            if (fail) throw IOException("journal failure")
            rows[id] = checkpoint
        }
    }
    private class Destination(val directory: File) : BackupEngine.Destination {
        var failWrite = false; var failClose = false; var failRename = false; var maxChunk = 0
        var failRead = false; var maxReadChunk = 0
        var onRead: () -> Unit = {}
        var onWrite: () -> Unit = {}
        override fun exists(uri: String) = File(uri).exists()
        override fun input(uri: String) = object : FilterInputStream(File(uri).inputStream()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                maxReadChunk = maxOf(maxReadChunk, length)
                if (failRead) throw IOException("read failure")
                return super.read(bytes, offset, length).also { onRead() }
            }
        }
        override fun create(name: String) = File(directory, name).apply { check(createNewFile()) }.path
        override fun output(uri: String): OutputStream {
            val output = File(uri).outputStream()
            return object : OutputStream() {
                override fun write(value: Int) { output.write(value) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    maxChunk = maxOf(maxChunk, length)
                    if (failWrite) throw IOException("write failure")
                    output.write(bytes, offset, length); onWrite()
                }
                override fun close() { output.close(); if (failClose) throw IOException("close failure") }
            }
        }
        override fun finish(uri: String, name: String): String {
            if (failRename) throw IOException("rename failure")
            val target = File(directory, name); check(File(uri).renameTo(target)); return target.path
        }
        override fun delete(uri: String) { File(uri).delete() }
    }
    private class Source(private val bytes: Long = 200L * 1024 * 1024) : BackupEngine.Source {
        var stamp = "v1"; var exports = 0
        override fun documents() = listOf(BackupEngine.Candidate("one", "bad/name"))
        override fun prepare(id: String, previousStamp: String?, file: File, cancelled: () -> Boolean): String? {
            if (previousStamp == stamp) return null
            exports++
            val chunk = ByteArray(64 * 1024) { (it * 31).toByte() }
            file.outputStream().use { output ->
                var left = bytes
                while (left > 0) {
                    BackupEngine.checkCancelled(cancelled)
                    val count = minOf(left, chunk.size.toLong()).toInt()
                    output.write(chunk, 0, count); left -= count
                }
            }
            return stamp
        }
    }
    @Test fun largeBackupStreamsAndSkipsUnchangedButRepairsMissingDestination() {
        val root = Files.createTempDirectory("backup-test").toFile()
        try {
            val work = File(root, "work").apply { mkdir() }; val destination = Destination(File(root, "remote").apply { mkdir() })
            val journal = Journal(); val source = Source()
            assertEquals(1, BackupEngine.run(source, destination, journal, work).written)
            val old = requireNotNull(journal.previous("one")); val file = File(old.uri)
            assertEquals(200L * 1024 * 1024, file.length()); assertTrue(destination.maxChunk <= 64 * 1024)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val chunk = ByteArray(64 * 1024)
                while (true) { val count = input.read(chunk); if (count < 0) break; digest.update(chunk, 0, count) }
            }
            assertEquals(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, old.sha256)
            assertEquals(1, BackupEngine.run(source, destination, journal, work).skipped); assertEquals(1, source.exports)
            assertTrue(destination.maxReadChunk in 1..64 * 1024)
            source.stamp = "v2"; assertEquals(1, BackupEngine.run(source, destination, journal, work).written)
            assertTrue(file.exists()); assertNotEquals(old.uri, journal.previous("one")?.uri)
            File(requireNotNull(journal.previous("one")).uri).delete()
            assertEquals(1, BackupEngine.run(source, destination, journal, work).written)
            assertTrue(work.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun unchangedDocumentRepairsTruncatedAndSameSizeCorruptBackups() {
        for (truncate in listOf(true, false)) {
            val root = Files.createTempDirectory("backup-corrupt").toFile()
            try {
                val work = File(root, "work").apply { mkdir() }
                val destination = Destination(File(root, "remote").apply { mkdir() })
                val journal = Journal(); val source = Source(1024 * 1024)
                BackupEngine.run(source, destination, journal, work)
                val old = requireNotNull(journal.previous("one"))
                val file = File(old.uri)
                if (truncate) file.writeBytes(byteArrayOf(1, 2, 3))
                else java.io.RandomAccessFile(file, "rw").use { it.writeByte(99) }
                assertEquals(1, BackupEngine.run(source, destination, journal, work).written)
                val repaired = requireNotNull(journal.previous("one"))
                assertEquals(2, source.exports)
                assertEquals(old.stamp, repaired.stamp)
                assertEquals(old.sha256, repaired.sha256)
                assertNotEquals(old.uri, repaired.uri)
                assertTrue(file.exists())
                assertEquals(1, BackupEngine.run(source, destination, journal, work).skipped)
                assertTrue(work.listFiles().orEmpty().isEmpty())
            } finally { root.deleteRecursively() }
        }
    }
    @Test fun verificationReadFailureAndCancellationPreserveCheckpointAndArchive() {
        for (cancel in listOf(false, true)) {
            val root = Files.createTempDirectory("backup-verify").toFile()
            try {
                val work = File(root, "work").apply { mkdir() }
                val remote = File(root, "remote").apply { mkdir() }
                val destination = Destination(remote); val journal = Journal(); val source = Source(1024 * 1024)
                BackupEngine.run(source, destination, journal, work)
                val old = requireNotNull(journal.previous("one"))
                var cancelled = false
                destination.failRead = !cancel
                if (cancel) destination.onRead = { cancelled = true }
                try { BackupEngine.run(source, destination, journal, work) { cancelled }; fail("verification succeeded") }
                catch (error: IOException) { if (cancel) assertTrue(error is InterruptedIOException) }
                assertEquals(old, journal.previous("one"))
                assertEquals(1, source.exports)
                assertTrue(File(old.uri).exists())
                assertEquals(1, remote.listFiles().orEmpty().size)
                assertTrue(work.listFiles().orEmpty().isEmpty())
            } finally { root.deleteRecursively() }
        }
    }
    @Test fun failedWriteCloseRenameJournalAndCancellationPreserveLastCompleteBackup() {
        for (failure in listOf("write", "close", "rename", "journal", "cancel", "snapshot")) {
            val root = Files.createTempDirectory("backup-failure").toFile()
            try {
                val work = File(root, "work").apply { mkdir() }; val remote = File(root, "remote").apply { mkdir() }
                val destination = Destination(remote); val journal = Journal(); val source = Source(1024 * 1024)
                BackupEngine.run(source, destination, journal, work)
                val old = requireNotNull(journal.previous("one")); source.stamp = "v2"
                destination.failWrite = failure == "write"; destination.failClose = failure == "close"
                destination.failRename = failure == "rename"; journal.fail = failure == "journal"
                var cancelled = false; if (failure == "cancel") destination.onWrite = { cancelled = true }
                if (failure == "snapshot") cancelled = true
                try { BackupEngine.run(source, destination, journal, work) { cancelled }; fail(failure) }
                catch (_: IOException) { }
                assertEquals(failure, old, journal.previous("one"))
                assertTrue(File(old.uri).exists()); assertEquals(1, remote.listFiles().orEmpty().size)
                assertTrue(work.listFiles().orEmpty().isEmpty())
            } finally { root.deleteRecursively() }
        }
    }
}
