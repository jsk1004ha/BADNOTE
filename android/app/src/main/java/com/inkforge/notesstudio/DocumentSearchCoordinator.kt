package com.inkforge.notesstudio

import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One debounced search, on its own worker: source-PDF scans never enter the save queue. */
internal class DocumentSearchCoordinator<T>(
    private val search: (String, String, () -> Boolean) -> T,
    private val dispatch: (() -> Unit) -> Unit,
    private val debounceMillis: Long = 250,
    private val worker: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "note-document-search").apply { isDaemon = true }
    }
) : Closeable {
    private val generation = AtomicLong()
    private val closed = AtomicBoolean()
    private var pending: Future<*>? = null

    init {
        require(debounceMillis >= 0)
        // Cancelling a delayed query must remove it, not leave thousands of cancelled tasks
        // behind an in-flight native call. At most the current call and newest query remain.
        worker.removeOnCancelPolicy = true
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        worker.setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
    }

    @Synchronized
    fun request(documentId: String, query: String, complete: (Result<T>) -> Unit) {
        cancel()
        if (closed.get() || query.isBlank()) return
        val request = generation.get()
        fun current() = !closed.get() && generation.get() == request
        pending = worker.schedule({
            val cancelled = { !current() || Thread.currentThread().isInterrupted }
            val result = try {
                checkSearchCancelled(cancelled)
                Result.success(search(documentId, query, cancelled)).also { checkSearchCancelled(cancelled) }
            } catch (_: CancellationException) {
                return@schedule
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@schedule
            } catch (error: Exception) {
                if (cancelled()) return@schedule
                Result.failure(error)
            }
            if (current()) dispatch {
                // A result may already be posted when the drawer is closed or query changes.
                synchronized(this) { if (current()) complete(result) }
            }
        }, debounceMillis, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun cancel() {
        generation.incrementAndGet()
        pending?.cancel(true)
        pending = null
    }

    @Synchronized
    override fun close() {
        if (closed.getAndSet(true)) return
        cancel()
        // PDFium may finish its current native call; the token is rechecked inside its lock
        // and before the next page. Never wait for that call on the activity's UI thread.
        worker.shutdownNow()
    }
}

internal fun checkSearchCancelled(cancelled: () -> Boolean) {
    if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("문서 검색을 취소했습니다.")
}
