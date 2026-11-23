package com.inkforge.notesstudio

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Verifies that legacy note dates survive and real content edits drive both card dates and date sorting. */
internal object LibraryDateChecks {
    private const val OLDER_TITLE = "날짜 검사 A 오래된 노트"
    private const val NEWER_TITLE = "날짜 검사 Z ISO 노트"
    private const val OLDER_MILLIS = 1_579_089_600_000L
    private const val NEWER_MILLIS = 1_624_192_496_789L
    private const val ISO_CREATED = "2021-06-20T12:34:56.789Z"
    private const val ISO_UPDATED = "2021-06-20T14:34:56.789+02:00"
    private const val EDITED_MILLIS_SETTING = "instrumentation-library-date-edited-millis"

    fun run(instrumentation: Instrumentation, output: Bundle, reopen: Boolean) {
        val context = instrumentation.targetContext
        val repository = (context.applicationContext as NoteApplication).repository
        val documentIds = repository.executor.submit<Pair<String, String>> {
            val existing = repository.documents()
            if (!reopen && existing.isEmpty()) {
                // Write the ISO-newer note first and the numerically older note last. The legacy
                // documents.updated storage order is therefore deliberately opposite to date order.
                val newer = repository.create(NEWER_TITLE, "root", "blank").let { created ->
                    DocumentInfo(created.id, created.data.copyJson()
                        .put("createdAt", ISO_CREATED).put("updatedAt", ISO_UPDATED))
                }
                repository.putDocument(newer)
                SystemClock.sleep(25)
                val older = repository.create(OLDER_TITLE, "root", "blank").let { created ->
                    DocumentInfo(created.id, created.data.copyJson()
                        .put("createdAt", OLDER_MILLIS).put("updatedAt", OLDER_MILLIS))
                }
                repository.putDocument(older)
            } else {
                check(existing.size == 2 && existing.map { it.title }.toSet() == setOf(OLDER_TITLE, NEWER_TITLE)) {
                    "Library date check requires a blank install or exactly its two seeded notes"
                }
                val seeded = existing.associateBy { it.title }
                checkOriginalDates(checkNotNull(seeded[OLDER_TITLE]), checkNotNull(seeded[NEWER_TITLE]))
            }
            repository.setting("legacy-migration-complete", "true", true)
            repository.setting("last-open-document", "", true)
            val preferences = JSONObject(repository.setting("preferences", "{}"))
                .put("language", "ko").put("listMode", true).put("librarySort", "date")
                .put("autoOcr", false)
            repository.setting("preferences", preferences.toString(), true)
            val byTitle = repository.documents().associateBy { it.title }
            checkOriginalDates(checkNotNull(byTitle[OLDER_TITLE]), checkNotNull(byTitle[NEWER_TITLE]))
            if (!reopen) check(repository.documents().first().title == OLDER_TITLE) {
                "Fixture storage order must put the older metadata note first"
            }
            checkNotNull(byTitle[OLDER_TITLE]).id to checkNotNull(byTitle[NEWER_TITLE]).id
        }.get(30, TimeUnit.SECONDS)
        val olderId = documentIds.first
        val newerId = documentIds.second

        val activity = instrumentation.startActivitySync(
            Intent(Intent.ACTION_MAIN).setClassName(context.packageName, MainActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val automation = instrumentation.uiAutomation
        fun <T> ui(block: () -> T): T {
            val value = AtomicReference<T>()
            val failure = AtomicReference<Throwable?>()
            instrumentation.runOnMainSync {
                try { value.set(block()) } catch (error: Throwable) { failure.set(error) }
            }
            failure.get()?.let { throw it }
            return value.get()
        }
        fun locate(node: AccessibilityNodeInfo?, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && matches(node)) return node
            for (index in 0 until node.childCount) locate(node.getChild(index), matches)?.let { return it }
            return null
        }
        fun findText(text: String) = locate(automation.rootInActiveWindow) { node ->
            node.text?.toString() == text || node.contentDescription?.toString() == text
        }
        fun findCard(title: String) = locate(automation.rootInActiveWindow) { node ->
            node.contentDescription?.toString() == "$title 열기"
        }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                SystemClock.sleep(30)
            }
            error("Library date check timed out: $label")
        }
        fun top(title: String): Int {
            val bounds = Rect()
            checkNotNull(findCard(title)) { "Missing visible card: $title" }.getBoundsInScreen(bounds)
            check(!bounds.isEmpty) { "Card has no visible bounds: $title" }
            return bounds.top
        }
        fun assertAbove(first: String, second: String, label: String) {
            check(top(first) < top(second)) { "$label: expected '$first' above '$second'" }
        }
        fun record(id: String) = repository.executor.submit<LibraryDocumentRecord> {
            checkNotNull(repository.libraryDocuments().firstOrNull { it.document.id == id })
        }.get(10, TimeUnit.SECONDS)
        fun assertRawDatesUnchanged() {
            repository.executor.submit {
                checkOriginalDates(checkNotNull(repository.document(olderId)), checkNotNull(repository.document(newerId)))
            }.get(10, TimeUnit.SECONDS)
        }
        fun display(epochMillis: Long) = SimpleDateFormat("yyyy. M. d.", Locale.getDefault()).format(Date(epochMillis))
        fun cardDate(epochMillis: Long) = "1페이지 · ${display(epochMillis)}"
        fun back() = ui { activity.onBackPressedDispatcher.onBackPressed() }

        try {
            await("two date fixture cards") {
                findText("2개") != null && findCard(OLDER_TITLE) != null && findCard(NEWER_TITLE) != null
            }

            if (reopen) {
                val expectedEdited = repository.executor.submit<Long> {
                    repository.setting(EDITED_MILLIS_SETTING, "").toLongOrNull()
                        ?: error("Missing first-phase edited timestamp")
                }.get(10, TimeUnit.SECONDS)
                val older = record(olderId)
                val newer = record(newerId)
                check(older.modifiedAtMillis == expectedEdited) {
                    "Cold reopen changed edited timestamp: ${older.modifiedAtMillis} != $expectedEdited"
                }
                check(newer.modifiedAtMillis == NEWER_MILLIS) { "Unedited ISO fixture changed date on reopen" }
                assertRawDatesUnchanged()
                await("cold-reopen edited card date") { findText(cardDate(expectedEdited)) != null }
                assertAbove(OLDER_TITLE, NEWER_TITLE, "cold-reopen modified-date order")
                output.putString("libraryDateColdReopen", "persisted_edit_date_card_and_order_verified")
                output.putLong("libraryDateEditedMillis", expectedEdited)
                return
            }

            val initialOlder = record(olderId)
            val initialNewer = record(newerId)
            check(initialOlder.modifiedAtMillis == OLDER_MILLIS && initialNewer.modifiedAtMillis == NEWER_MILLIS) {
                "Legacy fixture dates were not read exactly"
            }
            await("legacy numeric and ISO card dates") {
                findText(cardDate(OLDER_MILLIS)) != null && findText(cardDate(NEWER_MILLIS)) != null
            }
            assertAbove(NEWER_TITLE, OLDER_TITLE, "initial legacy date order")

            instrumentation.tapTestNode(checkNotNull(findText("날짜")))
            await("title sort selected") { findText("이름") != null }
            assertAbove(OLDER_TITLE, NEWER_TITLE, "title order")
            instrumentation.tapTestNode(checkNotNull(findText("이름")))
            await("date sort restored") { findText("날짜") != null }
            assertAbove(NEWER_TITLE, OLDER_TITLE, "restored legacy date order")

            instrumentation.tapTestNode(checkNotNull(findCard(OLDER_TITLE)))
            await("older note opened without editing") { ui { activity.inkView != null } }
            back()
            await("library restored after passive open") { findCard(OLDER_TITLE) != null }
            check(record(olderId).modifiedAtMillis == OLDER_MILLIS) { "Passive open changed the date" }
            assertRawDatesUnchanged()
            assertAbove(NEWER_TITLE, OLDER_TITLE, "order after passive open")

            instrumentation.tapTestNode(checkNotNull(findCard(OLDER_TITLE)))
            await("older note reopened for real edit") { ui { activity.inkView?.currentPage != null } }
            val editStarted = System.currentTimeMillis()
            ui {
                checkNotNull(activity.inkView).add(json(
                    "id" to "instrumentation-date-edit",
                    "type" to "stroke",
                    "brush" to "ballpoint",
                    "color" to "#344ca3",
                    "width" to 5,
                    "opacity" to 1,
                    "points" to JSONArray(listOf(
                        json("x" to 160, "y" to 220, "p" to .7, "t" to editStarted),
                        json("x" to 260, "y" to 260, "p" to .7, "t" to editStarted + 1)
                    ))
                ))
            }
            var editedMillis = 0L
            await("content edit persisted with modified timestamp") {
                record(olderId).modifiedAtMillis?.takeIf { it >= editStarted }?.let {
                    editedMillis = it
                    true
                } ?: false
            }
            back()
            await("library refreshed after content edit") { findCard(OLDER_TITLE) != null }
            assertRawDatesUnchanged()
            check(record(olderId).modifiedAtMillis == editedMillis)
            await("edited card uses persisted timestamp") { findText(cardDate(editedMillis)) != null }
            assertAbove(OLDER_TITLE, NEWER_TITLE, "modified-date order after real edit")
            repository.executor.submit {
                repository.setting(EDITED_MILLIS_SETTING, editedMillis.toString(), true)
            }.get(10, TimeUnit.SECONDS)

            output.putString("libraryDatePreservation", "numeric_and_iso_json_dates_unchanged")
            output.putString("libraryDatePassiveOpen", "timestamp_and_order_unchanged")
            output.putString("libraryDateRealEdit", "card_and_sort_share_persisted_content_timestamp")
            output.putLong("libraryDateEditedMillis", editedMillis)
        } finally {
            ui { if (!activity.isFinishing) activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun checkOriginalDates(older: DocumentInfo, newer: DocumentInfo) {
        check(older.data.opt("createdAt") is Number && older.data.getLong("createdAt") == OLDER_MILLIS)
        check(older.data.opt("updatedAt") is Number && older.data.getLong("updatedAt") == OLDER_MILLIS)
        check(newer.data.getString("createdAt") == ISO_CREATED)
        check(newer.data.getString("updatedAt") == ISO_UPDATED)
    }
}
