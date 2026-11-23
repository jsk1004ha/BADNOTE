
package com.inkforge.notesstudio

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

internal object SettingsSheetChecks {
    fun run(instrumentation: Instrumentation, result: Bundle) {
        val context = instrumentation.targetContext
        val repository = (context.applicationContext as NoteApplication).repository
        repository.executor.submit {
            repository.setting("legacy-migration-complete", "true", true)
            repository.setting("last-open-document", "", true)
            val preferences = JSONObject(repository.setting("preferences", "{}")).put("language", "ko")
            repository.setting("preferences", preferences.toString(), true)
        }.get(30, TimeUnit.SECONDS)
        val activity = instrumentation.startActivitySync(
            Intent(Intent.ACTION_MAIN).setClassName(context.packageName, MainActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val automation = instrumentation.uiAutomation
        fun locate(node: AccessibilityNodeInfo?, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (node == null || matches(node)) return node
            for (index in 0 until node.childCount) {
                locate(node.getChild(index), matches)?.let { return it }
            }
            return null
        }
        fun find(label: String) = locate(automation.rootInActiveWindow) { node ->
            node.isVisibleToUser &&
                (node.contentDescription?.toString() == label || node.text?.toString() == label)
        }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 12_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                SystemClock.sleep(30)
            }
            error("Settings check timed out: $label")
        }
        fun click(label: String) {
            await(label) { find(label) != null }
            instrumentation.tapTestNode(checkNotNull(find(label)))
        }
        fun reveal(label: String) {
            repeat(10) {
                if (find(label) != null) return
                val screen = activity.resources.displayMetrics
                val x = screen.widthPixels / 2
                val fromY = (screen.heightPixels * .8).toInt()
                val toY = (screen.heightPixels * .4).toInt()
                val swipe = automation.executeShellCommand("input swipe $x $fromY $x $toY 300")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(swipe).use { it.readBytes() }
                SystemClock.sleep(300)
            }
            await(label) { find(label) != null }
        }
        fun screenshot(name: String): String {
            automation.waitForIdle(350, 5000)
            val bitmap = checkNotNull(automation.takeScreenshot())
            val file = File(context.filesDir, "settings-checks/$name")
            file.parentFile?.mkdirs()
            try {
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            return file.relativeTo(context.filesDir).invariantSeparatorsPath
        }
        fun back() = instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
        fun storedLanguage() = JSONObject(repository.setting("preferences", "{}")).optString("language")
        val ptLabels = context.assets.open("locales/pt.json").bufferedReader()
            .use { JSONObject(it.readText()) }

        try {
            click("설정")
            await("settings overview") { find("언어") != null }
            result.putString("settingsOverviewScreenshot", screenshot("settings-overview.png"))
            click("언어")
            click("Selecionar português")
            await("Portuguese confirmation and saved language") { find("Alterado") != null && storedLanguage() == "pt" }
            result.putString("settingsPortugueseScreenshot", screenshot("settings-portuguese.png"))
            click(ptLabels.optString("언어", "언어"))
            click(ptLabels.optString("한국어", "한국어"))
            await("Korean confirmation") { find("변경되었습니다") != null && storedLanguage() == "ko" }
            click("손글씨 OCR 언어")
            await("OCR options open directly") { find("한국어 우선") != null }
            check(find("Selecionar português") == null)
            back()
            await("back from OCR") { find("언어") != null }

            reveal("자동 백업")
            click("자동 백업")
            await("backup detail") { find("백업 폴더 선택") != null && find("설정으로 돌아가기") != null }
            result.putString("settingsBackupScreenshot", screenshot("settings-backup.png"))
            back()
            await("backup overview restored at its original position") { find("자동 백업") != null }
            back()
            await("library visible after closing settings") { find("노트 검색") != null }
            result.putString("settingsSheet", "language_confirmation_ocr_backup_back_verified")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
