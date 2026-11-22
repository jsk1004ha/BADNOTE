package com.inkforge.notesstudio

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/** Debug-only SAF provider protected by MANAGE_DOCUMENTS and absent from release/chooser listings. */
class BackupTestProvider : DocumentsProvider() {
    companion object { @Volatile var deny = false }
    private val directory get() = File(requireNotNull(context).cacheDir, "backup-provider-checks").apply { mkdirs() }
    override fun onCreate() = true
    private fun file(id: String): File {
        if (deny) throw SecurityException("test permission revoked")
        if (id == "root") return directory
        if (id.contains('/') || id == "." || id == "..") throw FileNotFoundException(id)
        return File(directory, id).also { if (!it.exists()) throw FileNotFoundException(id) }
    }
    private fun columns(projection: Array<out String>?) = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE)
    private fun add(cursor: MatrixCursor, file: File, id: String) {
        val flags = if (id == "root") Document.FLAG_DIR_SUPPORTS_CREATE else
            Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        val values: Map<String, Any> = mapOf(Document.COLUMN_DOCUMENT_ID to id, Document.COLUMN_DISPLAY_NAME to file.name,
            Document.COLUMN_MIME_TYPE to if (id == "root") Document.MIME_TYPE_DIR else "application/octet-stream",
            Document.COLUMN_FLAGS to flags, Document.COLUMN_SIZE to file.length())
        cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
    }
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val names = projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)
        val values: Map<String, Any> = mapOf(Root.COLUMN_ROOT_ID to "root", Root.COLUMN_DOCUMENT_ID to "root",
            Root.COLUMN_TITLE to "BADNOTE backup test", Root.COLUMN_FLAGS to Root.FLAG_SUPPORTS_CREATE)
        return MatrixCursor(names).apply { addRow(names.map { values[it] }.toTypedArray()) }
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(columns(projection)).apply { add(this, file(documentId), documentId) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        require(parentDocumentId == "root")
        return MatrixCursor(columns(projection)).apply { file("root").listFiles().orEmpty().forEach { add(this, it, it.name) } }
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(parentDocumentId == "root"); file("root")
        require(!displayName.contains('/')); check(File(directory, displayName).createNewFile()); return displayName
    }
    override fun renameDocument(documentId: String, displayName: String): String {
        require(!displayName.contains('/')); check(file(documentId).renameTo(File(directory, displayName))); return displayName
    }
    override fun deleteDocument(documentId: String) { require(documentId != "root"); check(file(documentId).delete()) }
    override fun isChildDocument(parentDocumentId: String, documentId: String) = parentDocumentId == "root" && documentId != "root"
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
}
