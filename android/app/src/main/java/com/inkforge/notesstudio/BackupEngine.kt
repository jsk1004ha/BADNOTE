package com.inkforge.notesstudio

import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

/** A complete per-document archive, never an in-memory copy of a whole notebook. */
internal object BackupEngine {
    data class Candidate(val id: String, val title: String)
    data class Checkpoint(val stamp: String, val uri: String, val sha256: String)
    data class Result(val written: Int, val skipped: Int)
    interface Source {
        fun documents(): List<Candidate>
        /** Reads the stamp and exports under the same repository executor turn. */
        fun prepare(id: String, previousStamp: String?, file: File, cancelled: () -> Boolean): String?
    }
    interface Destination {
        fun exists(uri: String): Boolean
        fun create(name: String): String
        fun output(uri: String): OutputStream
        fun finish(uri: String, name: String): String
        fun delete(uri: String)
    }
    interface Journal {
        fun previous(id: String): Checkpoint?
        fun commit(id: String, checkpoint: Checkpoint)
    }

    fun run(source: Source, destination: Destination, journal: Journal, directory: File,
            cancelled: () -> Boolean = { false }): Result {
        var written = 0; var skipped = 0
        for (doc in source.documents()) {
            checkCancelled(cancelled)
            val old = journal.previous(doc.id)?.takeIf { destination.exists(it.uri) }
            val staging = File.createTempFile("backup-", ".ifnote", directory)
            var pending: String? = null
            try {
                val stamp = source.prepare(doc.id, old?.stamp, staging, cancelled)
                if (stamp == null) { skipped++; continue }
                checkCancelled(cancelled)
                val title = doc.title.replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_").take(64).ifBlank { "노트" }
                val name = "$title-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.ifnote"
                pending = destination.create("$name.pending")
                val digest = MessageDigest.getInstance("SHA-256")
                destination.output(pending).use { output ->
                    staging.inputStream().use { input -> copy(input, DigestOutputStream(output, digest), cancelled) }
                    output.flush()
                }
                checkCancelled(cancelled)
                // Renaming is the publish boundary. Failed writes remain identifiable .pending files.
                pending = destination.finish(pending, name)
                journal.commit(doc.id, Checkpoint(stamp, pending,
                    digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }))
                pending = null
                written++
            } finally {
                pending?.let { runCatching { destination.delete(it) } }
                staging.delete()
            }
        }
        return Result(written, skipped)
    }

    fun copy(input: InputStream, output: OutputStream, cancelled: () -> Boolean) {
        val bytes = ByteArray(64 * 1024)
        while (true) {
            checkCancelled(cancelled)
            val count = input.read(bytes)
            if (count < 0) break
            output.write(bytes, 0, count)
        }
        checkCancelled(cancelled)
    }
    fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("백업을 취소했습니다.")
    }
}
