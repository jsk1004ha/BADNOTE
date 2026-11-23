package com.inkforge.notesstudio

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Measures real Compose library actions against a seeded database in an isolated debug install. */
internal object LibraryPresentationChecks {
    private const val DOCUMENT_COUNT = 1200
    private const val MEASURED_ACTIONS = 10
    private const val VIEW_LABEL = "보기 방식 전환"

    fun run(instrumentation: Instrumentation, result: Bundle, expectStable: Boolean) {
        val context = instrumentation.targetContext
        val repository = (context.applicationContext as NoteApplication).repository
        repository.executor.submit {
            val seeded = repository.documents()
            check(seeded.isEmpty() || seeded.size == DOCUMENT_COUNT && seeded.all { it.title.startsWith("측정 문서 ") }) {
                "Library workload requires an isolated debug install"
            }
            if (seeded.isEmpty()) {
                repository.transaction {
                    repeat(DOCUMENT_COUNT) { index ->
                        repository.create("측정 문서 ${index.toString().padStart(4, '0')}", "root", "blank")
                    }
                }
            }
            repository.setting("legacy-migration-complete", "true", true)
            repository.setting("last-open-document", "", true)
            repository.setting("preferences", json("language" to "ko", "listMode" to false,
                "autoOcr" to false).toString(), true)
        }.get(45, TimeUnit.SECONDS)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val generation = MainActivity::class.java.getDeclaredField("libraryGeneration").apply { isAccessible = true }
        val settings = MainActivity::class.java.getDeclaredField("settings").apply { isAccessible = true }
        fun <T> ui(block: () -> T): T {
            val value = AtomicReference<T>()
            instrumentation.runOnMainSync { value.set(block()) }
            return value.get()
        }
        fun nodeWithText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (n in 0 until node.childCount) {
                nodeWithText(node.getChild(n), text)?.let { return it }
            }
            return null
        }
        val listCardHeight = (96 * context.resources.displayMetrics.density).toInt()
        fun firstDocumentHeight(node: AccessibilityNodeInfo?): Int? {
            if (node == null) return null
            if (node.contentDescription?.startsWith("측정 문서 ") == true) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                return bounds.height()
            }
            for (n in 0 until node.childCount) {
                firstDocumentHeight(node.getChild(n))?.let { return it }
            }
            return null
        }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 12000
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                SystemClock.sleep(16)
            }
            error("Library workload timed out: $label")
        }
        fun tree() = instrumentation.uiAutomation.rootInActiveWindow
        fun listMode() = ui { (settings.get(activity) as JSONObject).optBoolean("listMode") }
        fun switchView(): Long {
            val before = listMode()
            await(VIEW_LABEL) { nodeWithText(tree(), VIEW_LABEL) != null }
            val control = checkNotNull(nodeWithText(tree(), VIEW_LABEL))
            val time = SystemClock.elapsedRealtimeNanos()
            instrumentation.tapTestNode(control)
            await("view changed") { listMode() != before }
            await("document count and new card geometry visible") {
                val root = tree()
                val cardHeight = firstDocumentHeight(root) ?: return@await false
                val expectedHeight = if (before) cardHeight > listCardHeight + 8
                    else kotlin.math.abs(cardHeight - listCardHeight) < 8
                expectedHeight && nodeWithText(root, "${DOCUMENT_COUNT}개") != null
            }
            return (SystemClock.elapsedRealtimeNanos() - time) / 1_000_000
        }
        try {
            await("library ready for warmup") { nodeWithText(tree(), "${DOCUMENT_COUNT}개") != null }
            repeat(2) { switchView() }
            val startGeneration = ui { generation.getInt(activity) }
            val samples = List(MEASURED_ACTIONS) { switchView() }.sorted()
            val rebuilds = ui { generation.getInt(activity) } - startGeneration
            if (expectStable) check(rebuilds == 0) { "Presentation toggles recreated library $rebuilds times" }
            if (expectStable) {
                val extra = repository.executor.submit<String> {
                    repository.create("측정 문서 다른 창", "root", "blank").id
                }.get(10, TimeUnit.SECONDS)
                try {
                    ui { activity.onWindowFocusChanged(true) }
                    await("library refresh after another window saved") {
                        nodeWithText(tree(), "${DOCUMENT_COUNT + 1}개") != null
                    }
                    result.putString("libraryFocusRefresh", "updated_count_visible")
                } finally {
                    repository.executor.submit { repository.deleteDocument(extra) }.get(10, TimeUnit.SECONDS)
                }
            }
            result.putInt("libraryDocuments", DOCUMENT_COUNT)
            result.putInt("libraryActions", MEASURED_ACTIONS)
            result.putInt("libraryRebuilds", rebuilds)
            result.putString("libraryViewModeMs", samples.joinToString(","))
            result.putString("libraryPresentation", "compose_view_toggle_and_document_count_verified")
        } finally {
            ui { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }
}

internal fun Instrumentation.tapTestNode(node: AccessibilityNodeInfo) {
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    check(!bounds.isEmpty) { "Control has no visible bounds" }
    val downTime = SystemClock.uptimeMillis()
    fun send(action: Int) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
            bounds.exactCenterX(), bounds.exactCenterY(), 0)
        try {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }
    send(MotionEvent.ACTION_DOWN)
    send(MotionEvent.ACTION_UP)
}
