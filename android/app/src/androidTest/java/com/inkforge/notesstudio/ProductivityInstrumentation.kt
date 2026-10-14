package com.inkforge.notesstudio

import android.app.Instrumentation
import android.app.job.JobScheduler
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.KeyEvent
import android.widget.EditText
import android.widget.FrameLayout
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Small minimum-API runner: no dependency on the general runner's PixelCopy callbacks. */
class ProductivityInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun phase(label: String) { sendStatus(1, Bundle().apply { putString("productivityPhase", label) }) }
    private fun <T> ui(block: () -> T): T {
        val value = AtomicReference<T>(); val error = AtomicReference<Throwable>()
        runOnMainSync { try { value.set(block()) } catch (failure: Throwable) { error.set(failure) } }
        error.get()?.let { throw it }; return value.get()
    }
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < end) { if (condition()) return; SystemClock.sleep(80) }
        error("Timeout: $label")
    }
    private fun <T> ordered(repository: NoteRepository, block: () -> T) = repository.executor.submit<T> { block() }.get()

    override fun onStart() {
        val result = Bundle()
        try {
            check(targetContext.packageName.endsWith(".debug"))
            val repository = (targetContext.applicationContext as NoteApplication).repository
            ordered(repository) {
                repository.setting("legacy-migration-complete", "true", true)
                repository.setting("preferences", json("autoOcr" to false, "autoMath" to false).toString(), true)
                repository.setting("last-open-document", "", true)
            }
            phase("windows-and-shortcuts")
            checkWindows(repository)
            phase("pdf-background-colors")
            checkPdfColors(repository)
            phase("saf-snapshot-and-restore")
            checkBackup(repository)
            result.putString("passed", "true")
            result.putString("productivity", "windows_shortcuts_pdf_colors_saf_restore_schedule_verified")
        } catch (error: Throwable) {
            result.putString("passed", "false"); result.putString("error", error.stackTraceToString())
        }
        finish(0, result)
    }

    private fun checkWindows(repository: NoteRepository) {
        val firstDoc = ordered(repository) { repository.create("첫 창", "root", "blank") }
        val secondDoc = ordered(repository) { repository.create("둘째 창", "root", "blank") }
        ordered(repository) { repository.addPage(secondDoc.id, NotePage.blank("grid"), 1) }
        var first: MainActivity? = null; var second: MainActivity? = null
        try {
            first = startActivitySync(DocumentWindows.intent(targetContext, firstDoc.id)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val a = requireNotNull(first)
            await("first document") { ui { a.inkView?.currentPage != null } }
            second = startActivitySync(DocumentWindows.intent(targetContext, secondDoc.id)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val b = requireNotNull(second)
            await("second document") { ui { b.inkView?.currentPage != null } }
            check(a.taskId != b.taskId) { "Documents share a task" }
            check(a.repository === b.repository) { "Windows have separate writers" }
            val app = targetContext.applicationContext as NoteApplication
            check(app.windows.owner(firstDoc.id) === a && app.windows.owner(secondDoc.id) === b)
            ui {
                a.inkView!!.add(json("id" to "first-text", "type" to "text", "text" to "창 A", "x" to 100, "y" to 100))
                b.inkView!!.add(json("id" to "second-text", "type" to "text", "text" to "창 B", "x" to 100, "y" to 100))
            }
            await("two independent saves") { ordered(repository) {
                repository.page(repository.pageIds(firstDoc.id).first())!!.objects.any { it.optString("id") == "first-text" } &&
                repository.page(repository.pageIds(secondDoc.id).first())!!.objects.any { it.optString("id") == "second-text" }
            } }
            ui { a.openDocumentWindow(secondDoc.id) }
            waitForIdleSync()
            check(app.windows.owner(secondDoc.id) === b) { "Same document acquired a second editor" }
            ui {
                val field = MainActivity::class.java.getDeclaredField("settings").apply { isAccessible = true }
                val save = MainActivity::class.java.getDeclaredMethod("saveSettings").apply { isAccessible = true }
                (field.get(a) as org.json.JSONObject).put("pdfDarkMode", true); save.invoke(a)
                (field.get(b) as org.json.JSONObject).put("stylusOnly", false); save.invoke(b)
            }
            await("independent preference changes") { ordered(repository) {
                val preferences = org.json.JSONObject(repository.setting("preferences"))
                preferences.optBoolean("pdfDarkMode") && !preferences.optBoolean("stylusOnly", true)
            } }
            ui {
                b.inkView!!.requestFocus()
                press(b, KeyEvent.KEYCODE_2)
                check(b.inkView!!.tool == InkCanvasView.Tool.HIGHLIGHTER)
                press(b, KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)
                check(b.inkView!!.currentPage!!.objects.none { it.optString("id") == "second-text" })
                press(b, KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
                check(b.inkView!!.currentPage!!.objects.any { it.optString("id") == "second-text" })
                val content = b.findViewById<FrameLayout>(android.R.id.content)
                val input = EditText(b).apply { setText(""); isFocusableInTouchMode = true }
                content.addView(input, FrameLayout.LayoutParams(300, 100)); input.requestFocus()
                press(b, KeyEvent.KEYCODE_1)
                check(input.text.toString() == "1") { "Digit was intercepted in text field" }
                check(b.inkView!!.tool == InkCanvasView.Tool.HIGHLIGHTER)
                content.removeView(input); b.inkView!!.requestFocus()
                MainActivity::class.java.getDeclaredMethod("shortcutGuide").apply { isAccessible = true }.invoke(b)
                press(b, KeyEvent.KEYCODE_3)
                check(b.inkView!!.tool == InkCanvasView.Tool.HIGHLIGHTER) { "Dialog did not block shortcut" }
                MainActivity::class.java.getDeclaredMethod("closeSheet").apply { isAccessible = true }.invoke(b)
            }
            phase("window-recreation")
            ui { b.inkView!!.goTo(1) }
            await("second page") { ui { b.inkView?.currentPage != null && b.inkView?.currentIndex == 1 } }
            val states = Bundle()
            ui { callActivityOnSaveInstanceState(b, states) }
            check(states.getString("documentId") == secondDoc.id)
            check(states.getString("activeTool") == "HIGHLIGHTER")
            check(states.getInt("pageIndex") == 1)
            ui { b.recreate() }
            await("recreated window claims") { app.windows.owner(secondDoc.id)?.let { it !== b && ui { it.inkView?.currentPage != null } } == true }
            second = app.windows.owner(secondDoc.id)
            check(ui { second!!.inkView!!.tool } == InkCanvasView.Tool.HIGHLIGHTER)
            check(ui { second!!.inkView!!.currentIndex } == 1)
        } finally {
            ui { first?.finishAndRemoveTask(); second?.finishAndRemoveTask() }
            await("claims released") {
                val app = targetContext.applicationContext as NoteApplication
                app.windows.owner(firstDoc.id) == null && app.windows.owner(secondDoc.id) == null
            }
            ordered(repository) { repository.deleteDocument(firstDoc.id); repository.deleteDocument(secondDoc.id) }
        }
    }

    private fun press(activity: MainActivity, key: Int, modifiers: Int = 0) {
        val time = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, key, 0, modifiers))
        activity.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, key, 0, modifiers))
    }

    private fun checkPdfColors(repository: NoteRepository) {
        val doc = ordered(repository) { repository.create("PDF 색상", "root", "blank") }
        val source = File(targetContext.cacheDir, "productivity-source.pdf")
        var asset: File? = null; var view: InkCanvasView? = null; var host: MainActivity? = null
        try {
            val pdf = PdfDocument()
            try {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(1000, 1414, 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawRect(100f, 100f, 300f, 260f, Paint().apply { color = Color.BLACK })
                pdf.finishPage(page); source.outputStream().use { pdf.writeTo(it) }
            } finally { pdf.close() }
            val page = ordered(repository) {
                val id = source.inputStream().use { repository.storeAsset(it, "pdf") }; asset = repository.asset(id)
                val page = repository.page(repository.pageIds(doc.id).first())!!
                page.meta.put("pdfSource", "asset:$id").put("pdfPageNumber", 1)
                page.objects += json("id" to "blue-line", "type" to "shape", "shape" to "line", "color" to "#2379dd",
                    "x1" to 300, "y1" to 400, "x2" to 700, "y2" to 400, "width" to 24)
                repository.transaction { repository.putPage(doc.id, page, 0) }; page
            }
            ordered(repository) { repository.setting("last-open-document", "", true) }
            host = startActivitySync(android.content.Intent(targetContext, MainActivity::class.java)
                .setAction(android.content.Intent.ACTION_MAIN).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val activity = requireNotNull(host)
            view = ui { InkCanvasView(activity, repository).apply {
                activity.findViewById<FrameLayout>(android.R.id.content).addView(this, FrameLayout.LayoutParams(1000, 1414))
                layout(0, 0, 1000, 1414); open(listOf(page.id), listOf(page.meta))
            } }
            val canvas = requireNotNull(view)
            await("page loaded") { ui { canvas.currentPage != null } }
            fun pixel(x: Float, y: Float) = ui {
                val bitmap = Bitmap.createBitmap(1000, 1414, Bitmap.Config.ARGB_8888)
                try { canvas.draw(Canvas(bitmap)); val point = canvas.screenPoint(x, y); bitmap.getPixel(point.x.toInt(), point.y.toInt()) }
                finally { bitmap.recycle() }
            }
            await("PDF black tile") { Color.red(pixel(200f, 180f)) < 10 }
            check(Color.red(pixel(800f, 900f)) > 245)
            val originalInk = pixel(500f, 400f)
            ui { canvas.pdfDarkMode = true }
            await("PDF inversion") { Color.red(pixel(200f, 180f)) > 225 }
            check(Color.red(pixel(800f, 900f)) in 20..32) { "PDF paper did not darken" }
            val darkInk = pixel(500f, 400f)
            check(abs(Color.red(originalInk) - Color.red(darkInk)) <= 2 &&
                abs(Color.blue(originalInk) - Color.blue(darkInk)) <= 2) { "Annotations were inverted" }
            val exported = StreamingPdf.render(repository, page, 1000)
            try { check(Color.red(exported.getPixel(800, 900)) > 245) { "Display mode changed export colors" } }
            finally { exported.recycle() }
            ui { canvas.pdfDarkMode = false }
            check(Color.red(pixel(800f, 900f)) > 245) { "Cache pixels were mutated" }
        } finally {
            ui { view?.close(); host?.finishAndRemoveTask() }
            ordered(repository) { repository.deleteDocument(doc.id) }; source.delete(); asset?.delete()
        }
    }

    private fun checkBackup(repository: NoteRepository) {
        val prefs = BackupPreferences(targetContext)
        val originalFolder = prefs.folder; val originalEnabled = prefs.enabled
        val doc = ordered(repository) { repository.create("SAF 복원 검사", "root", "blank") }
        val providerDirectory = File(targetContext.cacheDir, "backup-provider-checks").apply { mkdirs() }
        val tree = DocumentsContract.buildTreeDocumentUri("${targetContext.packageName}.backup-test", "root")
        var imported: String? = null
        try {
            prefs.enabled = false; prefs.folder = tree.toString()
            val pageId = ordered(repository) { repository.pageIds(doc.id).first() }
            val beforeNavigation = ordered(repository) { repository.backupStamp(doc.id) }
            ordered(repository) { val metadata = repository.document(doc.id)!!; metadata.data.put("lastPageIndex", 0).put("lastPageId", pageId); repository.putDocument(metadata) }
            check(beforeNavigation == ordered(repository) { repository.backupStamp(doc.id) }) { "Navigation triggered backup" }
            val first = SafBackup.run(targetContext, repository) { false }
            check(first.written == 1)
            val journal = prefs.journal(tree.toString()); val old = journal.previous(doc.id)!!
            check(!Uri.parse(old.uri).lastPathSegment.orEmpty().endsWith(".pending"))
            val digest = MessageDigest.getInstance("SHA-256")
            targetContext.contentResolver.openInputStream(Uri.parse(old.uri))!!.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            check(old.sha256 == digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
            check(SafBackup.run(targetContext, repository) { false }.written == 0)
            ordered(repository) { repository.setPageBookmark(pageId, true) }
            check(SafBackup.run(targetContext, repository) { false }.written == 1)
            val newer = journal.previous(doc.id)!!
            check(newer.uri != old.uri && SafBackupDestination(targetContext, tree).exists(old.uri))
            targetContext.contentResolver.openInputStream(Uri.parse(newer.uri))!!.use { input ->
                imported = ordered(repository) { repository.importArchive(input).id }
            }
            check(ordered(repository) { repository.pageMeta(repository.pageIds(imported!!).first())!!.optBoolean("bookmarked") })
            ordered(repository) { repository.deleteDocument(imported!!); imported = null }
            SafBackupDestination(targetContext, tree).delete(newer.uri)
            check(SafBackup.run(targetContext, repository) { false }.written == 1) { "Deleted backup was not repaired" }
            val repaired = journal.previous(doc.id)!!
            ordered(repository) {
                val page = repository.page(pageId)!!
                val policy = "ko-primary"; val input = OcrInput.pageDigest(page, policy)
                val region = json("id" to "backup-region", "inputDigest" to "backup-input", "text" to "원문",
                    "strokeIds" to org.json.JSONArray())
                check(repository.putOcrResult(doc.id, pageId, repository.generation(pageId), input, policy,
                    json("status" to "ready", "pageDigest" to input, "regions" to org.json.JSONArray().put(region))))
            }
            check(SafBackup.run(targetContext, repository) { false }.written == 1) { "OCR-only change was skipped" }
            val beforeCorrection = ordered(repository) { repository.backupStamp(doc.id) }
            ordered(repository) { check(repository.correctOcrRegion(pageId, "backup-region", "교정")) }
            check(beforeCorrection != ordered(repository) { repository.backupStamp(doc.id) })
            check(SafBackup.run(targetContext, repository) { false }.written == 1)
            val beforeTimestamp = ordered(repository) { repository.backupStamp(doc.id) }
            SystemClock.sleep(8)
            ordered(repository) { check(repository.correctOcrRegion(pageId, "backup-region", "교정")) }
            check(beforeTimestamp != ordered(repository) { repository.backupStamp(doc.id) }) { "Correction timestamp was excluded" }
            check(SafBackup.run(targetContext, repository) { false }.written == 1)
            val withOcr = journal.previous(doc.id)!!
            BackupTestProvider.deny = true
            try { SafBackup.run(targetContext, repository) { false }; error("Revoked provider access was ignored") }
            catch (_: SecurityException) { }
            finally { BackupTestProvider.deny = false }
            check(journal.previous(doc.id) == withOcr)
            try {
                BackupSchedule.chooseFolder(targetContext, tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                error("Read-only permission was accepted")
            } catch (_: IllegalArgumentException) { }
            check(providerDirectory.listFiles().orEmpty().none { it.name.endsWith(".pending") })
            prefs.enabled = true; BackupSchedule.restore(targetContext)
            val scheduler = targetContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val job = scheduler.allPendingJobs.single { it.id == BackupSchedule.JOB_ID }
            check(job.isPeriodic && job.isPersisted && job.isRequireCharging && job.isRequireDeviceIdle)
            prefs.enabled = false; BackupSchedule.restore(targetContext)
            check(scheduler.allPendingJobs.none { it.id == BackupSchedule.JOB_ID })
        } finally {
            BackupTestProvider.deny = false; prefs.enabled = originalEnabled; prefs.folder = originalFolder
            BackupSchedule.restore(targetContext)
            ordered(repository) { imported?.let(repository::deleteDocument); repository.deleteDocument(doc.id) }
            providerDirectory.listFiles().orEmpty().forEach(File::delete)
        }
    }
}
