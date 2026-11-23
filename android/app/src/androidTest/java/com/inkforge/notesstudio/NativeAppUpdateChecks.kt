package com.inkforge.notesstudio

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal object NativeAppUpdateChecks {
    fun run(instrumentation: Instrumentation, result: Bundle) {
        val context = instrumentation.targetContext
        val repository = (context.applicationContext as NoteApplication).repository
        repository.executor.submit {
            repository.setting("legacy-migration-complete", "true", true)
            repository.setting("last-open-document", "", true)
            repository.setting("preferences",
                JSONObject(repository.setting("preferences", "{}")).put("language", "ko").toString(), true)
        }.get(30, TimeUnit.SECONDS)
        val activity = instrumentation.startActivitySync(
            Intent(Intent.ACTION_MAIN).setClassName(context.packageName, MainActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val automation = instrumentation.uiAutomation
        val release = AppUpdater.Release(
            version = "4.2.3",
            build = 434,
            url = "https://github.com/jsk1004ha/BADNOTE/releases/download/v4.2.3/bad-note-Android-4.2.3-Update.apk",
            size = 83_517_679,
            notes = """
                # BADNOTE 4.2.3
                - 업데이트 알림과 화면을 새로 구성했습니다.
                - 다운로드 진행률과 설치 상태를 표시합니다.
            """.trimIndent(),
            sha256 = "a".repeat(64)
        )
        var notice by mutableStateOf<AppUpdater.Release?>(release)
        var visible by mutableStateOf(false)
        var screen by mutableStateOf(UpdateScreenState(UpdateStage.AVAILABLE, release))
        val noticeDismissed = AtomicInteger()
        val dialogDismissed = AtomicInteger()
        val primaryActions = AtomicInteger()
        val cancelled = AtomicInteger()
        lateinit var host: ComposeView

        fun locate(node: AccessibilityNodeInfo?, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (node == null || matches(node)) return node
            for (index in 0 until node.childCount) {
                locate(node.getChild(index), matches)?.let { return it }
            }
            return null
        }
        fun visibleText(node: AccessibilityNodeInfo, label: String, exact: Boolean): Boolean {
            if (!node.isVisibleToUser) return false
            val values = listOf(node.contentDescription?.toString(), node.text?.toString()).filterNotNull()
            return values.any { if (exact) it == label else it.contains(label) }
        }
        fun find(label: String, exact: Boolean = true) =
            locate(automation.rootInActiveWindow) { visibleText(it, label, exact) }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 12_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                SystemClock.sleep(30)
            }
            val labels = mutableListOf<String>()
            locate(automation.rootInActiveWindow) { node ->
                (node.text ?: node.contentDescription)?.toString()?.let(labels::add)
                false
            }
            checkNotNull(automation.takeScreenshot()).let { bitmap ->
                try {
                    File(context.filesDir, "app-update-checks/failure.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { bitmap.recycle() }
            }
            error("App update check timed out: $label; stage=${screen.stage}, " +
                "actions=${primaryActions.get()}, visible=${labels.take(18)}")
        }
        fun click(label: String) {
            await(label) { find(label) != null }
            automation.waitForIdle(120, 5_000)
            instrumentation.tapTestNode(checkNotNull(find(label)))
        }
        fun screenshot(name: String): String {
            automation.waitForIdle(350, 5_000)
            val bitmap = checkNotNull(automation.takeScreenshot())
            val file = File(context.filesDir, "app-update-checks/$name")
            file.parentFile?.mkdirs()
            try {
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            return file.relativeTo(context.filesDir).invariantSeparatorsPath
        }

        instrumentation.runOnMainSync {
            val icons = ClassicUi(activity)
            host = ComposeView(activity).apply {
                setContent {
                    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 68.dp)) {
                        NativeAppUpdateNotice(notice, { it }, icons::icon, onOpen = {
                            notice = null
                            screen = UpdateScreenState(UpdateStage.AVAILABLE, release)
                            visible = true
                        }, onDismiss = {
                            noticeDismissed.incrementAndGet()
                            notice = null
                        })
                        NativeAppUpdateDialog(visible, screen, "4.2.2 (433)", { it }, icons::icon,
                            onDismiss = {
                                dialogDismissed.incrementAndGet()
                                visible = false
                            },
                            onPrimary = {
                                primaryActions.incrementAndGet()
                                screen = when (screen.stage) {
                                    UpdateStage.AVAILABLE -> UpdateScreenState(UpdateStage.DOWNLOADING, release, 37)
                                    UpdateStage.DOWNLOAD_FAILED -> UpdateScreenState(UpdateStage.READY, release)
                                    UpdateStage.READY -> UpdateScreenState(UpdateStage.INSTALL_PERMISSION, release)
                                    UpdateStage.INSTALL_PERMISSION -> UpdateScreenState(UpdateStage.INSTALL_FAILED, release)
                                    UpdateStage.INSTALL_FAILED -> UpdateScreenState(UpdateStage.READY, release)
                                    else -> screen
                                }
                            },
                            onCancelDownload = {
                                cancelled.incrementAndGet()
                                screen = UpdateScreenState(UpdateStage.AVAILABLE, release)
                            })
                    }
                }
            }
            (activity.window.decorView as ViewGroup).addView(host,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        try {
            await("update notice") {
                find("새 업데이트", exact = false) != null && find("4.2.3 · 빌드 434", exact = false) != null && find("보기") != null
            }
            result.putString("appUpdateNoticeScreenshot", screenshot("notice.png"))
            click("업데이트 알림 닫기")
            await("notice dismissal") { noticeDismissed.get() == 1 && find("새 업데이트", exact = false) == null }

            instrumentation.runOnMainSync { notice = release }
            await("restored update notice") { find("보기") != null }
            click("보기")
            await("available update dialog") {
                find("새 버전이 있습니다") != null &&
                    find("4.2.2 (433)", exact = false) != null &&
                    find("4.2.3 · 빌드 434", exact = false) != null &&
                    find("업데이트 알림과 화면을 새로 구성했습니다.") != null &&
                    find("다운로드") != null
            }
            result.putString("appUpdateDialogScreenshot", screenshot("available.png"))

            click("다운로드")
            await("download progress") {
                primaryActions.get() == 1 && find("업데이트 다운로드 중") != null && find("37%") != null
            }
            click("다운로드 취소")
            await("cancelled download returns to offer") {
                cancelled.get() == 1 && find("새 버전이 있습니다") != null && find("다운로드") != null
            }

            instrumentation.runOnMainSync { screen = UpdateScreenState(UpdateStage.DOWNLOAD_FAILED, release) }
            await("retry download") { find("다운로드하지 못했습니다") != null && find("다시 다운로드") != null }
            click("다시 다운로드")
            await("ready to install") { find("설치할 준비가 됐습니다") != null && find("설치") != null }
            click("설치")
            await("install permission") { find("설치 권한이 필요합니다") != null && find("설정 열기") != null }
            click("설정 열기")
            await("install failure retry") { find("설치를 시작하지 못했습니다") != null && find("다시 시도") != null }

            instrumentation.runOnMainSync { screen = UpdateScreenState(UpdateStage.CURRENT) }
            await("current version state") { find("최신 버전입니다") != null && find("확인") != null }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("system back dismisses update dialog") {
                dialogDismissed.get() == 1 && find("최신 버전입니다") == null
            }
            result.putString("appUpdateScreen",
                "notice_dismiss_available_progress_cancel_retry_permission_failure_current_back_verified")
        } finally {
            instrumentation.runOnMainSync {
                (host.parent as? ViewGroup)?.removeView(host)
                activity.finish()
            }
            instrumentation.waitForIdleSync()
        }
    }
}
