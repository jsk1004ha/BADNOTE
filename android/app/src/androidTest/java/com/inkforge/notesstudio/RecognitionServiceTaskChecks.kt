package com.inkforge.notesstudio

import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object RecognitionServiceTaskChecks {
    fun run() {
        timedOutTaskKeepsPermitThenRecoversAfterActualCompletion()
        failedTaskReleasesPermitForNextRealTask()
        inactiveServiceRejectsNewTaskWithoutReleasingRunningTask()
        renderedImageDigestTracksPixelsAndPolicy()
    }
    private fun timedOutTaskKeepsPermitThenRecoversAfterActualCompletion() {
        val service = RecognitionService()
        val workers = Executors.newFixedThreadPool(2)
        val running = TaskCompletionSource<String>()
        val started = CountDownLatch(1)
        val secondStarted = AtomicBoolean(false)
        try {
            val first = workers.submit<Throwable?> {
                try { service.infer(1, { started.countDown(); running.task }); null }
                catch (error: Throwable) { error }
            }
            check(started.await(2, TimeUnit.SECONDS))
            val second = workers.submit<Throwable?> {
                try { service.infer(3, { secondStarted.set(true); Tasks.forResult("second") }); null }
                catch (error: Throwable) { error }
            }
            check(first.get(3, TimeUnit.SECONDS) is IllegalStateException)
            check(!secondStarted.get()) { "second recognizer started while original Task remained running" }
            val early = try { service.infer(1, { Tasks.forResult("too early") }); null }
                catch (error: Throwable) { error }
            check(early is IllegalStateException)
            running.setResult("late")
            check(second.get(3, TimeUnit.SECONDS) == null)
            check(secondStarted.get()) { "recognizer did not recover after actual Task completion" }
        } finally { service.close(); workers.shutdownNow() }
    }

    private fun failedTaskReleasesPermitForNextRealTask() {
        val service = RecognitionService()
        val failed = TaskCompletionSource<String>()
        val workers = Executors.newSingleThreadExecutor()
        try {
            val first = workers.submit<Throwable?> {
                try { service.infer(3, { failed.task }); null } catch (error: Throwable) { error }
            }
            failed.setException(IllegalStateException("model failure"))
            check(first.get(3, TimeUnit.SECONDS) != null)
            check(service.infer(3, { Tasks.forResult("next") }) == "next")
        } finally { service.close(); workers.shutdownNow() }
    }
    private fun inactiveServiceRejectsNewTaskWithoutReleasingRunningTask() {
        val service = RecognitionService()
        val workers = Executors.newSingleThreadExecutor()
        val running = TaskCompletionSource<String>()
        val started = CountDownLatch(1)
        try {
            val first = workers.submit<String> { service.infer(3, { started.countDown(); running.task }) }
            check(started.await(2, TimeUnit.SECONDS))
            service.setActive(false)
            var newTaskStarted = false
            val rejected = runCatching { service.infer(1, { newTaskStarted = true; Tasks.forResult("wrong") }) }
            check(rejected.exceptionOrNull()?.message == "recognizerInactive" && !newTaskStarted)
            running.setResult("original")
            check(first.get(3, TimeUnit.SECONDS) == "original")
            service.setActive(true)
            check(service.infer(3, { Tasks.forResult("resumed") }) == "resumed")
        } finally { service.close(); workers.shutdownNow() }
    }
    private fun renderedImageDigestTracksPixelsAndPolicy() {
        val page = NotePage.blank("blank")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val first = OcrInput.imageKey(page, "ko-primary", bitmap)
            check(first == OcrInput.imageKey(page, "ko-primary", bitmap))
            check(first != OcrInput.imageKey(page, "en-primary", bitmap))
            bitmap.setPixel(1, 1, Color.BLACK)
            check(first != OcrInput.imageKey(page, "ko-primary", bitmap))
        } finally { bitmap.recycle() }
    }
}
