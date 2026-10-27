package com.inkforge.notesstudio

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.File
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class BackupPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("saf-backup", Context.MODE_PRIVATE)
    var folder: String
        get() = prefs.getString("folder", "").orEmpty()
        set(value) { check(prefs.edit().putString("folder", value).commit()) }
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { check(prefs.edit().putBoolean("enabled", value).commit()) }
    val status get() = prefs.getString("status", "아직 백업하지 않았습니다.").orEmpty()
    fun status(value: String) { check(prefs.edit().putString("status", value).commit()) }
    fun journal(tree: String): BackupEngine.Journal {
        val prefix = "checkpoint:" + MessageDigest.getInstance("SHA-256")
            .digest(tree.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } + ":"
        return object : BackupEngine.Journal {
            override fun previous(id: String): BackupEngine.Checkpoint? = prefs.getString(prefix + id, null)?.let {
                runCatching { JSONObject(it).let { item ->
                    BackupEngine.Checkpoint(item.getString("stamp"), item.getString("uri"), item.getString("sha256"))
                } }.getOrNull()
            }
            override fun commit(id: String, checkpoint: BackupEngine.Checkpoint) {
                val body = json("stamp" to checkpoint.stamp, "uri" to checkpoint.uri, "sha256" to checkpoint.sha256)
                check(prefs.edit().putString(prefix + id, body.toString()).commit()) { "백업 기록을 저장하지 못했습니다." }
            }
        }
    }
}

internal class SafBackupDestination(context: Context, folder: Uri) : BackupEngine.Destination {
    private val resolver = context.contentResolver
    private val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
    override fun exists(uri: String): Boolean = try { resolver.query(Uri.parse(uri),
        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false }
        catch (_: java.io.FileNotFoundException) { false }
        catch (_: IllegalArgumentException) { false }
    override fun input(uri: String) = resolver.openInputStream(Uri.parse(uri))
        ?: throw java.io.IOException("백업 파일을 읽을 수 없습니다.")
    override fun create(name: String): String {
        val uri = requireNotNull(DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)) {
            "백업 파일을 만들 수 없습니다. 폴더 권한을 다시 지정해 주세요."
        }
        try {
            val flags = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            } ?: 0
            require(flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0) {
                "이 저장 위치는 파일 이름 변경을 지원하지 않습니다. 다른 백업 폴더를 선택해 주세요."
            }
            return uri.toString()
        } catch (error: Exception) { runCatching { DocumentsContract.deleteDocument(resolver, uri) }; throw error }
    }
    override fun output(uri: String) = requireNotNull(resolver.openOutputStream(Uri.parse(uri), "wt")) {
        "백업 파일을 열 수 없습니다."
    }
    override fun finish(uri: String, name: String) = requireNotNull(
        DocumentsContract.renameDocument(resolver, Uri.parse(uri), name)) { "백업 파일을 완료하지 못했습니다." }.toString()
    override fun delete(uri: String) { DocumentsContract.deleteDocument(resolver, Uri.parse(uri)) }
}

/** The same serial executor used by every editor owns each archive snapshot. Provider I/O runs elsewhere. */
internal object SafBackup {
    private val running = AtomicBoolean()
    fun run(context: Context, repository: NoteRepository, cancelled: () -> Boolean): BackupEngine.Result {
        check(running.compareAndSet(false, true)) { "백업이 이미 진행 중입니다." }
        val prefs = BackupPreferences(context)
        val tree = prefs.folder
        val interrupted = { cancelled() || prefs.folder != tree }
        try {
            require(tree.isNotBlank()) { "백업 폴더를 먼저 선택해 주세요." }
            val source = object : BackupEngine.Source {
                private fun <T> ordered(block: () -> T): T = try { repository.executor.submit<T> { block() }.get() }
                    catch (error: ExecutionException) { throw error.cause ?: error }
                override fun documents() = ordered {
                    repository.documents().filter { !it.trashed }.map { BackupEngine.Candidate(it.id, it.title) }
                }
                override fun prepare(id: String, previousStamp: String?, file: File, cancelled: () -> Boolean): String? = ordered {
                    repository.exportBackup(id, previousStamp, file, cancelled)
                }
            }
            val result = BackupEngine.run(source, SafBackupDestination(context, Uri.parse(tree)), prefs.journal(tree),
                repository.workDirectory, interrupted)
            val date = java.text.DateFormat.getDateTimeInstance().format(java.util.Date())
            prefs.status("$date · ${result.written}개 저장, ${result.skipped}개 변경 없음 또는 가져오기 진행 중")
            return result
        } catch (error: Exception) {
            prefs.status(if (error is InterruptedIOException) "백업 취소 · 완료된 이전 백업은 보관됩니다."
                else "백업 실패 · ${error.message ?: error.javaClass.simpleName}")
            throw error
        } finally { running.set(false) }
    }
}

internal object BackupSchedule {
    const val JOB_ID = 4190
    private const val PERIOD = 12L * 60 * 60 * 1000
    fun restore(context: Context) {
        val prefs = BackupPreferences(context)
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (!prefs.enabled || prefs.folder.isBlank()) { scheduler.cancel(JOB_ID); return }
        if (scheduler.allPendingJobs.any { it.id == JOB_ID }) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, BackupJobService::class.java))
            .setPeriodic(PERIOD).setRequiresCharging(true).setRequiresDeviceIdle(true).setPersisted(true).build()
        check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "자동 백업을 예약하지 못했습니다." }
    }
    fun chooseFolder(context: Context, uri: Uri, flags: Int) {
        val access = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(access == (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
            "백업에는 폴더 읽기·쓰기 권한이 필요합니다."
        }
        context.contentResolver.takePersistableUriPermission(uri, access)
        BackupPreferences(context).folder = uri.toString()
        restore(context)
    }
}

class BackupJobService : JobService() {
    private val worker = Executors.newSingleThreadExecutor()
    private var stopped = AtomicBoolean()
    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as NoteApplication
        val prefs = BackupPreferences(this)
        if (!prefs.enabled || prefs.folder.isBlank() || app.hasVisibleWindow || app.hasPendingSaves) return false
        val cancel = AtomicBoolean(); stopped = cancel
        worker.execute {
            runCatching { SafBackup.run(this, app.repository) { cancel.get() || app.hasVisibleWindow || app.hasPendingSaves ||
                !BackupPreferences(this).enabled } }
            mainExecutorCompat { if (!cancel.get()) jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped.set(true); return true }
    override fun onDestroy() { stopped.set(true); worker.shutdown(); super.onDestroy() }
    private fun mainExecutorCompat(block: () -> Unit) { android.os.Handler(mainLooper).post(block) }
}
