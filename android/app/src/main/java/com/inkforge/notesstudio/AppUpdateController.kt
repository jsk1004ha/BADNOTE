package com.inkforge.notesstudio

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File

internal enum class UpdateStage {
    CHECKING, CURRENT, CHECK_FAILED, AVAILABLE, DOWNLOADING, DOWNLOAD_FAILED, READY, INSTALL_PERMISSION, INSTALL_FAILED
}

internal data class UpdateScreenState(
    val stage: UpdateStage,
    val release: AppUpdater.Release? = null,
    val percent: Int? = null
)

/** Activity-owned UI state. The network and APK inspection run off the main thread. */
internal class AppUpdateController(
    private val activity: Activity,
    private val beforeInstall: (() -> Unit) -> Unit
) {
    private val updater = AppUpdater(activity.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var downloadGeneration = 0
    private var downloadedApk: File? = null
    private var installPending = false
    private var lastCheck: Long? = null
    private var dismissedVersion: Pair<String, Int?>? = null

    var state by mutableStateOf(UpdateScreenState(UpdateStage.CHECKING))
        private set
    var notice by mutableStateOf<AppUpdater.Release?>(null)
        private set
    var visible by mutableStateOf(false)
        private set

    fun onResume(isMainWindow: Boolean) {
        if (state.stage == UpdateStage.INSTALL_PERMISSION && updater.canInstall()) {
            state = state.copy(stage = UpdateStage.READY)
        }
        if (!isMainWindow || !updater.isPublicInstall || visible || downloadedApk != null) return
        val checkedAt = lastCheck
        if (checkedAt == null || SystemClock.elapsedRealtime() - checkedAt >= AUTO_CHECK_INTERVAL_MS) {
            checkRelease()
        }
    }

    fun open() {
        visible = true
        if (state.stage !in setOf(UpdateStage.DOWNLOADING, UpdateStage.READY, UpdateStage.INSTALL_PERMISSION, UpdateStage.INSTALL_FAILED)) {
            checkRelease()
        }
    }

    fun openNotice() {
        val release = notice ?: return
        dismissNotice()
        state = UpdateScreenState(UpdateStage.AVAILABLE, release)
        visible = true
    }

    fun dismissNotice() {
        dismissedVersion = notice?.let { it.version to it.build }
        notice = null
    }

    fun dismiss() {
        visible = false
        cancelDownload()
    }

    fun primaryAction() {
        when (state.stage) {
            UpdateStage.CURRENT -> dismiss()
            UpdateStage.CHECK_FAILED -> checkRelease()
            UpdateStage.AVAILABLE, UpdateStage.DOWNLOAD_FAILED -> state.release?.let(::download)
            UpdateStage.READY, UpdateStage.INSTALL_FAILED -> install()
            UpdateStage.INSTALL_PERMISSION -> {
                if (updater.canInstall()) {
                    state = state.copy(stage = UpdateStage.READY)
                } else {
                    try {
                        activity.startActivity(updater.installSettings())
                    } catch (error: Exception) {
                        failed(UpdateStage.INSTALL_FAILED, error)
                    }
                }
            }
            UpdateStage.CHECKING, UpdateStage.DOWNLOADING -> Unit
        }
    }

    fun cancelDownload() {
        if (state.stage != UpdateStage.DOWNLOADING) return
        downloadGeneration++
        downloadJob?.cancel()
        downloadJob = null
        state = state.copy(stage = UpdateStage.AVAILABLE, percent = null)
    }

    fun dispose() = scope.cancel()

    private fun checkRelease() {
        if (checkJob?.isActive == true) return
        lastCheck = SystemClock.elapsedRealtime()
        state = UpdateScreenState(UpdateStage.CHECKING)
        notice = null
        checkJob = scope.launch {
            try {
                val release = runInterruptible(Dispatchers.IO) { updater.latest() }
                state = if (release == null) UpdateScreenState(UpdateStage.CURRENT)
                else UpdateScreenState(UpdateStage.AVAILABLE, release)
                notice = release?.takeUnless { (it.version to it.build) == dismissedVersion }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed(UpdateStage.CHECK_FAILED, error)
            }
        }
    }

    private fun download(release: AppUpdater.Release) {
        if (downloadJob?.isActive == true) return
        dismissNotice()
        state = UpdateScreenState(UpdateStage.DOWNLOADING, release)
        val generation = ++downloadGeneration
        downloadJob = scope.launch {
            try {
                downloadedApk = runInterruptible(Dispatchers.IO) {
                    updater.download(release) { percent ->
                        scope.launch {
                            if (generation == downloadGeneration && state.stage == UpdateStage.DOWNLOADING) {
                                state = state.copy(percent = percent)
                            }
                        }
                    }
                }
                state = UpdateScreenState(UpdateStage.READY, release)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == downloadGeneration) failed(UpdateStage.DOWNLOAD_FAILED, error)
            }
        }
    }

    private fun install() {
        if (!updater.canInstall()) {
            state = state.copy(stage = UpdateStage.INSTALL_PERMISSION)
            return
        }
        val file = downloadedApk
        if (file == null || !file.isFile) {
            downloadedApk = null
            state = state.copy(stage = UpdateStage.DOWNLOAD_FAILED)
            return
        }
        if (installPending) return
        installPending = true
        beforeInstall {
            installPending = false
            if (visible && !activity.isDestroyed && !activity.isFinishing) {
                try {
                    activity.startActivity(updater.installIntent(file))
                    state = state.copy(stage = UpdateStage.READY)
                } catch (error: Exception) {
                    failed(UpdateStage.INSTALL_FAILED, error)
                }
            }
        }
    }

    private fun failed(stage: UpdateStage, error: Exception) {
        Log.w("AppUpdate", stage.name, error)
        state = state.copy(stage = stage, percent = null)
    }

    private companion object {
        const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
