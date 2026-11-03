package com.inkforge.notesstudio

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import java.io.File

/** Production SQLite/filesystem checks using a separate database and asset directory. */
object AssetCleanupChecks {
    fun run(context: Context) {
        val isolated = IsolatedContext(context)
        try {
            NoteRepository(isolated).use { repository ->
                sharedAndTransientAssets(repository)
                rollbackDoesNotRemoveAssets(repository)
                failedCleanupRetries(repository, isolated)
            }
            // The candidate journal survives repository/process recreation.
            NoteRepository(isolated).use { repository ->
                val pending = repository.setting("cleanup-test-pending")
                check(repository.asset(pending).isFile, "Clipboard protection did not survive the original deletion")
                repository.reclaimPendingAssets()
                check(!repository.asset(pending).exists(), "Unpinned candidate was not reclaimed after reopening")
            }
        } finally {
            context.deleteDatabase(isolated.databaseName)
            isolated.filesDir.deleteRecursively()
            isolated.cacheDir.deleteRecursively()
        }
    }

    private fun sharedAndTransientAssets(repository: NoteRepository) {
        val original = repository.create("delete source", "root", "blank")
        val shared = repository.create("shared copy", "root", "blank")
        val trashed = repository.create("recoverable trash", "root", "blank")
        val image = asset(repository, "image")
        val pdf = asset(repository, "pdf")
        val audio = asset(repository, "audio")
        val clipboard = asset(repository, "clipboard")
        val recordingInProgress = asset(repository, "not yet committed recording")
        val undoOnly = asset(repository, "unreferenced current-session undo asset")

        attach(repository, original.id, image)
        attach(repository, original.id, clipboard)
        attach(repository, shared.id, image)
        for (document in listOf(original, trashed)) {
            val page = requireNotNull(repository.page(repository.pageIds(document.id).first()))
            page.meta.put("pdfSource", "asset:$pdf")
            repository.transaction { repository.putPage(document.id, page, 0) }
        }
        original.data.put("audio", JSONArray().put(json("src" to "asset:$audio")))
        repository.putDocument(original)
        trashed.data.put("trashed", true)
        repository.putDocument(trashed)

        repository.deleteDocument(original.id, setOf(clipboard))
        check(repository.document(original.id) == null, "Document metadata was not deleted")
        check(!repository.asset(audio).exists(), "An exclusively owned recording was not reclaimed")
        check(repository.asset(image).readText() == "image", "Shared image was removed")
        check(repository.asset(pdf).readText() == "pdf", "A recoverable trashed document lost its PDF")
        check(repository.asset(clipboard).readText() == "clipboard", "Clipboard content was removed before paste")
        check(repository.asset(recordingInProgress).isFile, "An in-flight asset was swept")
        check(repository.asset(undoOnly).isFile, "An unrelated undo asset was swept")

        // Pasting into a retained document makes its reference durable.
        attach(repository, shared.id, clipboard)
        repository.reclaimPendingAssets()
        check(repository.asset(clipboard).isFile, "Pasted image was removed when the clipboard pin expired")
        repository.deleteDocument(shared.id)
        check(!repository.asset(image).exists(), "Last shared-image owner was deleted without reclamation")
        check(!repository.asset(clipboard).exists(), "Last pasted-image owner was deleted without reclamation")
        repository.deleteDocument(trashed.id)
        check(!repository.asset(pdf).exists(), "Permanently deleting the last trashed owner did not reclaim its PDF")
        check(repository.asset(recordingInProgress).isFile && repository.asset(undoOnly).isFile,
            "Cleanup touched unrelated temporary/undo files")

        val copiedSource = repository.create("clipboard restart", "root", "blank")
        val pinned = asset(repository, "pinned until the session ends")
        attach(repository, copiedSource.id, pinned)
        repository.deleteDocument(copiedSource.id, setOf(pinned))
        repository.setting("cleanup-test-pending", pinned, true)
    }

    private fun rollbackDoesNotRemoveAssets(repository: NoteRepository) {
        val document = repository.create("rollback", "root", "blank")
        val id = asset(repository, "rollback original")
        attach(repository, document.id, id)
        val failure = runCatching {
            repository.transaction {
                repository.deleteDocument(document.id)
                throw IllegalStateException("simulate outer transaction rollback")
            }
        }
        check(failure.isFailure, "Rollback fixture unexpectedly committed")
        check(repository.document(document.id) != null, "Outer rollback did not restore the document")
        check(repository.asset(id).readText() == "rollback original", "Files were removed before the outer transaction committed")
    }

    private fun failedCleanupRetries(repository: NoteRepository, context: IsolatedContext) {
        val document = repository.create("retry", "root", "blank")
        val id = asset(repository, "preserve until reference scan succeeds")
        attach(repository, document.id, id)
        val malformed = repository.create("unreadable metadata", "root", "blank")
        context.openOrCreateDatabase("badnote-native.db", Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("UPDATE documents SET body=? WHERE id=?", arrayOf("{", malformed.id))
        }
        // Unknown references in another document must defer all candidate deletion.
        val pinned = repository.setting("cleanup-test-pending")
        repository.deleteDocument(document.id, setOf(pinned))
        check(repository.asset(id).isFile, "Cleanup removed a file despite an incomplete reference scan")
        context.openOrCreateDatabase("badnote-native.db", Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("UPDATE documents SET body=? WHERE id=?", arrayOf(malformed.data.toString(), malformed.id))
        }
        repository.reclaimPendingAssets(setOf(pinned))
        check(!repository.asset(id).exists(), "A deferred deletion was not retried after recovery")
    }

    private fun asset(repository: NoteRepository, text: String): String =
        text.byteInputStream().use { repository.storeAsset(it, "bin") }

    private fun attach(repository: NoteRepository, documentId: String, assetId: String) {
        val page = requireNotNull(repository.page(repository.pageIds(documentId).first()))
        page.objects += json("id" to uid("image"), "type" to "image", "src" to "asset:$assetId")
        repository.transaction { repository.putPage(documentId, page, 0) }
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    private class IsolatedContext(context: Context) : ContextWrapper(context) {
        private val prefix = "asset-cleanup-${java.util.UUID.randomUUID()}-"
        val databaseName = prefix + "badnote-native.db"
        override fun getFilesDir() = File(baseContext.filesDir, prefix).apply { mkdirs() }
        override fun getCacheDir() = File(baseContext.cacheDir, prefix).apply { mkdirs() }
        override fun getDatabasePath(name: String) = baseContext.getDatabasePath(prefix + name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            baseContext.openOrCreateDatabase(prefix + name, mode, factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            baseContext.openOrCreateDatabase(prefix + name, mode, factory, errorHandler)
    }
}
