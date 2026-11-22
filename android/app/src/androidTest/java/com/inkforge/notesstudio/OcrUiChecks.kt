package com.inkforge.notesstudio

import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Model-free UI consumer checks. Fixture strings are not recognition-quality evidence. */
internal object OcrUiChecks {
    private const val DISPLAY = "hello world\n둘째 줄"
    private const val EDITED = "  corrected  words\n\n둘째 줄 \n"

    fun run(activity: MainActivity, instrument: Instrumentation, result: Bundle) {
        check(activity.packageName.endsWith(".debug")) { "ocrUiRequiresDebugApp" }
        val repository = activity.repository
        val settings = field(activity, "settings") as JSONObject
        val previousAutomatic = settings.opt("autoOcr")
        ui(instrument) { settings.put("autoOcr", false) }
        val doc = repository.create("OCR UI consumer fixture", "root", "blank")
        val first = requireNotNull(repository.page(repository.pageIds(doc.id).single()))
        val second = NotePage.blank("blank")
        first.objects += json("id" to "ocr-ui-invalid-first", "type" to "stroke", "points" to JSONArray())
        second.objects += json("id" to "ocr-ui-invalid-second", "type" to "stroke", "points" to JSONArray())
        second.objects += json("id" to "ocr-ui-search-target", "type" to "text",
            "text" to "untouched other page", "x" to 300, "y" to 400, "w" to 360, "h" to 60)
        repository.transaction { repository.putPage(doc.id, first, 0); repository.putPage(doc.id, second, 1) }
        val secondSnapshot = requireNotNull(repository.readOcrSnapshot(doc.id, second.id))
        val sentinel = json("documentId" to doc.id, "pageId" to second.id,
            "pageDigest" to OcrInput.pageDigest(secondSnapshot.page, "ko-primary"), "status" to "complete",
            "regions" to JSONArray(listOf(json("id" to "other-page", "status" to "complete",
                "selectedText" to "untouched other page"))))
        check(repository.putOcrResult(doc.id, second.id, secondSnapshot.generation,
            sentinel.getString("pageDigest"), "ko-primary", sentinel))
        val originalOtherPage = requireNotNull(repository.ocrResult(second.id)).toString()
        try {
            ui(instrument) { activity.openDocument(doc.id, 0) }
            await("ocrUiEditor") { ui(instrument) { activity.inkView?.currentPage?.id == first.id &&
                activity.inkView!!.width > 0 } }
            ui(instrument) {
                val navbar = field(activity, "editorNavbar") as ViewGroup
                listOf("문서 검색", "현재 페이지 손글씨 인식").forEach { label ->
                    val button = findDescription(navbar, translated(activity, label))
                    val bounds = Rect()
                    check(button.getGlobalVisibleRect(bounds) && bounds.width() >= button.width - 1) {
                        "ocrUiDirectButtonClipped:$label"
                    }
                }
                invoke(activity, "editorMenu")
                val modal = requireNotNull(field(activity, "modal") as? ViewGroup)
                check(descendants(modal).none { it.contentDescription?.toString() in
                    setOf(translated(activity, "문서 검색"), translated(activity, "손글씨 OCR"),
                        translated(activity, "현재 페이지 손글씨 인식")) }) { "ocrUiDuplicateDocumentOptions" }
                invoke(activity, "closeSheet")
                findDescription(navbar, translated(activity, "현재 페이지 손글씨 인식")).performClick()
            }
            // Invalid-coordinate input completes without downloading or invoking a model.
            await("ocrUiCurrentPageRequest") { repository.ocrResult(first.id) != null &&
                ui(instrument) { field(activity, "manualOcrPageId") == null && field(activity, "modal") != null } }
            check(requireNotNull(repository.ocrResult(first.id)).optString("pageId") == first.id)
            check(repository.ocrResult(second.id)?.toString() == originalOtherPage) { "ocrUiRecognizedOtherPage" }

            showFixture(activity)
            ui(instrument) {
                val modal = requireNotNull(field(activity, "modal") as? ViewGroup)
                val editors = descendants(modal).filterIsInstance<EditText>()
                check(editors.size == 1 && editors.single().text.toString() == DISPLAY) { "ocrUiFragmentedText" }
                check(descendants(modal).filterIsInstance<TextView>().none { view ->
                    listOf("fixture-internal-region", "fixtureErrorCode", "nativeRank", "reviewReasons", "JSON")
                        .any { view.text.contains(it) }
                }) { "ocrUiInternalDiagnosticsVisible" }
                editors.single().setText(EDITED)
                findDescription(modal, translated(activity, "전체 텍스트 복사")).performClick()
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                check(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == EDITED) { "ocrUiCopyWhitespaceMismatch" }
            }

            showFixture(activity)
            val before = ui(instrument) { activity.inkView!!.currentPage!!.objects.map { it.toString() } }
            ui(instrument) {
                val modal = requireNotNull(field(activity, "modal") as? ViewGroup)
                descendants(modal).filterIsInstance<EditText>().single().setText(EDITED)
                findDescription(modal, translated(activity, "텍스트로 추가")).performClick()
                val inserted = activity.inkView!!.currentPage!!.objects.filter { it.optString("type") == "text" }
                check(inserted.size == 1 && inserted.single().optString("text") == EDITED) { "ocrUiInsertTextMismatch" }
                check(activity.inkView!!.currentPage!!.objects.size == before.size + 1) { "ocrUiMultipleInsertedObjects" }
                activity.inkView!!.undo()
                check(activity.inkView!!.currentPage!!.objects.map { it.toString() } == before) { "ocrUiOriginalInkChanged" }
            }
            awaitSaved(activity, instrument)

            showFixture(activity)
            ui(instrument) {
                val modal = requireNotNull(field(activity, "modal") as? ViewGroup)
                descendants(modal).filterIsInstance<EditText>().single().setText(EDITED)
                findDescription(modal, translated(activity, "교정 저장")).performClick()
            }
            await("ocrUiCorrectionSaved") { repository.ocrText(first.id) == EDITED &&
                ui(instrument) { (field(activity, "modal") as? ViewGroup)?.let {
                    descendants(it).filterIsInstance<EditText>().singleOrNull()?.text?.toString() == EDITED
                } == true } }
            check(repository.searchDocumentPages(doc.id, "corrected  words").any { it.pageId == first.id })
            check(repository.searchDocumentPages(doc.id, "hello").none { it.pageId == first.id })
            check(repository.ocrResult(first.id)?.array("regions")?.objects()?.any {
                it.optString("selectedText") == "hello" } == true) { "ocrUiCorrectionDestroyedRawResult" }

            ui(instrument) {
                invoke(activity, "closeSheet")
                val navbar = field(activity, "editorNavbar") as ViewGroup
                findDescription(navbar, translated(activity, "문서 검색")).performClick()
                val drawer = requireNotNull(field(activity, "searchDrawer") as? ViewGroup)
                descendants(drawer).filterIsInstance<EditText>().single().setText("untouched other page")
            }
            await("ocrUiSearchResult") { ui(instrument) { (field(activity, "searchDrawer") as? ViewGroup)?.let {
                descendants(it).any { view -> view.isClickable && view.contentDescription?.toString() == "2페이지" }
            } == true } }
            ui(instrument) {
                val drawer = requireNotNull(field(activity, "searchDrawer") as? ViewGroup)
                findDescription(drawer, "2페이지").performClick()
            }
            await("ocrUiSearchNavigation") { ui(instrument) { activity.inkView?.currentPage?.id == second.id } }
            ui(instrument) {
                val view = requireNotNull(activity.inkView)
                check(canvasField(view, "searchPageId") == second.id &&
                    (canvasField(view, "searchBounds") as List<*>).isNotEmpty()) { "ocrUiSearchHighlightMissing" }
            }
            ui(instrument) {
                val drawer = requireNotNull(field(activity, "searchDrawer") as? ViewGroup)
                descendants(drawer).filterIsInstance<EditText>().single().setText("")
                check(canvasField(requireNotNull(activity.inkView), "searchPageId") == null &&
                    (canvasField(requireNotNull(activity.inkView), "searchBounds") as List<*>).isEmpty()) {
                    "ocrUiQueryChangeKeptHighlight"
                }
                findDescription(drawer, translated(activity, "검색 닫기")).performClick()
                check(field(activity, "searchDrawer") == null)
                check(canvasField(requireNotNull(activity.inkView), "searchPageId") == null)
                activity.inkView!!.goTo(0)
            }
            await("ocrUiReturnToFirstPage") { ui(instrument) { activity.inkView?.currentPage?.id == first.id } }
            showFixture(activity)
            ui(instrument) {
                val modal = requireNotNull(field(activity, "modal") as? ViewGroup)
                val insert = findDescription(modal, translated(activity, "텍스트로 추가"))
                val view = activity.inkView!!
                view.add(json("id" to "ocr-ui-stale-edit", "type" to "text", "text" to "new edit",
                    "x" to 100, "y" to 600, "w" to 300, "h" to 50))
                val count = view.currentPage!!.objects.size
                insert.performClick()
                check(view.currentPage!!.objects.size == count) { "ocrUiStaleResultInserted" }
                view.undo()
            }
            awaitSaved(activity, instrument)
            ui(instrument) {
                val math = json("contentKind" to "MATH", "ephemeral" to true, "status" to "complete",
                    "regions" to JSONArray(listOf(
                        json("id" to "math-first", "order" to 0, "status" to "complete", "selectedText" to "(2)^(3)"),
                        json("id" to "math-second", "order" to 1, "status" to "complete", "selectedText" to "((1)/(2))"))))
                MainActivity::class.java.getDeclaredMethod("showOcrResult", String::class.java, JSONObject::class.java)
                    .apply { isAccessible = true }.invoke(activity, first.id, math)
                val modal = requireNotNull(field(activity, "modal") as? ViewGroup)
                val input = descendants(modal).filterIsInstance<EditText>().single()
                check(input.text.toString() == "(2)^(3)\n((1)/(2))")
                input.setSelection(0, "(2)^(3)".length)
                findDescription(modal, translated(activity, "수식 확인 및 계산")).performClick()
                val confirmation = requireNotNull(field(activity, "modal") as? ViewGroup)
                check(descendants(confirmation).filterIsInstance<EditText>().single().text.toString() == "(2)^(3)")
                findDescription(confirmation, translated(activity, "확인")).performClick()
                val calculated = activity.inkView!!.currentPage!!.objects.single { it.optString("type") == "math" }
                check(calculated.optString("expression") == "(2)^(3)" && calculated.optString("result") == "8") {
                    "ocrUiEditedMathCalculationLost"
                }
                activity.inkView!!.undo()
            }
            awaitSaved(activity, instrument)
            result.putString("ocrUi", "direct_buttons_current_page_single_edit_copy_insert_correction_search_highlight_stale_math")
            result.putInt("ocrUiScreenWidthDp", activity.resources.configuration.screenWidthDp)
        } finally {
            ui(instrument) {
                invoke(activity, "closeSheet")
                invoke(activity, "closeSearchDrawer")
                if (previousAutomatic == null) settings.remove("autoOcr") else settings.put("autoOcr", previousAutomatic)
            }
            awaitSaved(activity, instrument)
            repository.deleteDocument(doc.id)
        }
    }

    /** Opens the real editor result sheet with explicit model-free text, preserving current ink. */
    fun showFixture(activity: MainActivity) {
        onActivity(activity) {
            val page = requireNotNull(activity.inkView?.currentPage) { "ocrUiFixtureNeedsOpenEditor" }
            val doc = requireNotNull(field(activity, "document") as? DocumentInfo)
            val snapshot = requireNotNull(activity.repository.readOcrSnapshot(doc.id, page.id))
            val digest = OcrInput.pageDigest(snapshot.page, "ko-primary")
            fun region(id: String, order: Int, value: String, left: Int, top: Int) =
                json("id" to id, "order" to order, "role" to "line", "status" to "complete",
                    "sourceKind" to "DIGITAL_INK", "contentKind" to "TEXT", "selectedText" to value,
                    "rawText" to value, "inputDigest" to "fixture-input-$id",
                    "sourceIdentityDigest" to "fixture-source-$id", "strokeIds" to JSONArray(),
                    "bounds" to json("left" to left, "top" to top, "right" to left + 180, "bottom" to top + 50),
                    "wordRegions" to JSONArray(listOf(json("selectedText" to value,
                        "bounds" to json("left" to left, "top" to top, "right" to left + 180, "bottom" to top + 50)))))
            val fixture = json("documentId" to doc.id, "pageId" to page.id, "pageDigest" to digest,
                "generation" to snapshot.generation, "status" to "partial",
                "readingOrder" to JSONArray(listOf("first/part1", "first/part2", "second")),
                "regions" to JSONArray(listOf(region("second", 1, "둘째 줄", 200, 400),
                    region("first/part2", 0, "world", 420, 300), region("first/part1", 0, "hello", 200, 300),
                    json("id" to "fixture-internal-region", "status" to "failed", "errorCode" to "fixtureErrorCode",
                        "selectedText" to "must never display", "reviewReasons" to JSONArray(listOf("fixtureInternalReason"))))))
            check(activity.repository.putOcrResult(doc.id, page.id, snapshot.generation,
                digest, "ko-primary", fixture))
            MainActivity::class.java.getDeclaredMethod("showOcrResult", String::class.java, JSONObject::class.java)
                .apply { isAccessible = true }.invoke(activity, page.id, fixture)
        }
    }

    private fun awaitSaved(activity: MainActivity, instrument: Instrumentation) = await("ocrUiSaves") {
        ui(instrument) { field(activity, "saving") == false && (field(activity, "saves") as Collection<*>).isEmpty() }
    }

    private fun translated(activity: MainActivity, key: String): String =
        (field(activity, "dictionary") as JSONObject).optString(key, key)

    private fun findDescription(root: ViewGroup, label: String): View =
        descendants(root).firstOrNull { it.isClickable && it.contentDescription?.toString() == label }
            ?: error("ocrUiMissingView:$label")

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }

    private fun field(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun canvasField(view: InkCanvasView, name: String): Any? =
        InkCanvasView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(view)

    private fun invoke(activity: MainActivity, name: String) =
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)

    private fun <T> ui(instrument: Instrumentation, block: () -> T): T {
        val value = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        instrument.runOnMainSync { try { value.set(block()) } catch (error: Throwable) { failure.set(error) } }
        failure.get()?.let { throw it }
        return value.get()
    }

    private fun onActivity(activity: MainActivity, block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        activity.runOnUiThread { try { block() } catch (error: Throwable) { failure.set(error) } finally { done.countDown() } }
        check(done.await(15, TimeUnit.SECONDS)) { "ocrUiMainThreadTimeout" }
        failure.get()?.let { throw it }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(40)
        }
        error("Timeout:$label")
    }
}
