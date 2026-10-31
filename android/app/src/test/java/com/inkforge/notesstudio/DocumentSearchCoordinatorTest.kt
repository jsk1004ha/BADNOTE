package com.inkforge.notesstudio

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Delayed
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DocumentSearchCoordinatorTest {
    /** Advance debounce time without sleeps or device timing assumptions. */
    private class ClockWorker : ScheduledThreadPoolExecutor(1) {
        private var now = 0L
        private inner class Task(val due: Long, action: Runnable) : FutureTask<Unit>(action, Unit), ScheduledFuture<Unit> {
            override fun getDelay(unit: TimeUnit) = unit.convert(due - now, TimeUnit.MILLISECONDS)
            override fun compareTo(other: Delayed) = getDelay(TimeUnit.MILLISECONDS).compareTo(other.getDelay(TimeUnit.MILLISECONDS))
        }
        private val tasks = ArrayList<Task>()
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            Task(now + unit.toMillis(delay), command).also { tasks += it }
        fun advance(millis: Long) {
            now += millis
            tasks.filter { it.due <= now }.sortedBy { it.due }.forEach { tasks.remove(it); it.run() }
        }
    }

    @Test fun rapidTypingWaitsForQuietPeriodAndOnlySearchesTheNewestQuery() {
        val worker = ClockWorker()
        val scanned = ArrayList<String>()
        val delivered = ArrayList<String>()
        DocumentSearchCoordinator(search = { id: String, term: String, _: () -> Boolean ->
            "$id:$term".also { scanned += it }
        }, dispatch = { it() }, worker = worker).use { search ->
            search.request("doc", "p") { delivered += it.getOrThrow() }
            worker.advance(200)
            search.request("doc", "pd") { delivered += it.getOrThrow() }
            worker.advance(200)
            search.request("doc", "pdf") { delivered += it.getOrThrow() }
            worker.advance(249)
            assertTrue(scanned.isEmpty())
            worker.advance(1)
            assertEquals(listOf("doc:pdf"), scanned)
            assertEquals(scanned, delivered)
        }
    }

    @Test fun blankQueryAndDrawerCancellationPreventQueuedWork() {
        val worker = ClockWorker()
        var scans = 0
        DocumentSearchCoordinator(search = { _: String, _: String, _: () -> Boolean -> ++scans },
            dispatch = { it() }, worker = worker).use { search ->
            search.request("doc", "queued") { fail("Cancelled search was delivered") }
            search.request("doc", "  ") { fail("Blank search was delivered") }
            worker.advance(1000)
            assertEquals(0, scans)
            search.request("doc", "drawer") { fail("Closed drawer received a result") }
            search.cancel()
            worker.advance(1000)
            assertEquals(0, scans)
        }
    }

    @Test fun resultPostedBeforeDocumentSwitchOrCloseCannotUpdateTheNewDrawer() {
        val worker = ClockWorker()
        val ui = ArrayList<() -> Unit>()
        val delivered = ArrayList<String>()
        val search = DocumentSearchCoordinator(search = { id: String, _: String, _: () -> Boolean -> id },
            dispatch = { ui += it }, worker = worker)
        try {
            search.request("old", "text") { delivered += it.getOrThrow() }
            worker.advance(250)
            assertEquals(1, ui.size)
            search.request("new", "text") { delivered += it.getOrThrow() }
            ui.removeAt(0).invoke()
            assertTrue(delivered.isEmpty())
            worker.advance(250)
            ui.removeAt(0).invoke()
            assertEquals(listOf("new"), delivered)
            search.request("closed", "text") { delivered += it.getOrThrow() }
            worker.advance(250)
            search.close()
            ui.removeAt(0).invoke()
            search.request("destroyed", "text") { fail("Destroyed activity received a result") }
            worker.advance(1000)
            assertEquals(listOf("new"), delivered)
        } finally { search.close() }
    }

    @Test fun blockedNativeCallDoesNotQueueObsoleteScansOrBlockTheSaveExecutor() {
        val worker = ScheduledThreadPoolExecutor(1)
        val persistence = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val scans = Collections.synchronizedList(ArrayList<String>())
        val results = Collections.synchronizedList(ArrayList<String>())
        val cancellationObserved = AtomicBoolean()
        val search = DocumentSearchCoordinator(search = { _: String, term: String, cancelled: () -> Boolean ->
            scans += term
            if (term == "old") {
                entered.countDown()
                // Model a JNI call that cannot be interrupted. It still must not continue to
                // a second page once it returns, or occupy the persistence worker meanwhile.
                var interrupted = false
                while (true) {
                    try { release.await(); break } catch (_: InterruptedException) { interrupted = true }
                }
                if (interrupted) Thread.currentThread().interrupt()
                cancellationObserved.set(cancelled())
                checkSearchCancelled(cancelled)
                fail("Cancelled search continued to the next page")
            }
            term
        }, dispatch = { it() }, debounceMillis = 0, worker = worker)
        try {
            search.request("doc", "old") { results += it.getOrThrow() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(1000) { index -> search.request("doc", "new-$index") {
                results += it.getOrThrow(); delivered.countDown()
            } }
            assertEquals("Cancelled delayed searches accumulated behind JNI", 1, worker.queue.size)
            val saved = CountDownLatch(1)
            persistence.execute { saved.countDown() }
            assertTrue("Save waited for source-PDF search", saved.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("old"), scans.toList())
            release.countDown()
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertTrue(cancellationObserved.get())
            assertEquals(listOf("old", "new-999"), scans.toList())
            assertEquals(listOf("new-999"), results.toList())
        } finally {
            release.countDown(); search.close(); persistence.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun closeCancelsRunningTraversalWithoutReportingAnError() {
        val worker = ScheduledThreadPoolExecutor(1)
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val reported = AtomicBoolean()
        val search = DocumentSearchCoordinator(search = { _: String, _: String, token: () -> Boolean ->
            entered.countDown()
            try {
                while (true) { checkSearchCancelled(token); Thread.yield() }
            } catch (error: CancellationException) { cancelled.countDown(); throw error }
        }, dispatch = { it() }, debounceMillis = 0, worker = worker)
        try {
            search.request("doc", "running") { reported.set(true) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            search.close()
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            assertFalse(reported.get())
        } finally { search.close() }
    }

    @Test fun genuineCurrentSearchFailureIsDelivered() {
        val worker = ClockWorker()
        val failure = IllegalStateException("fixture read failure")
        var result: Throwable? = null
        DocumentSearchCoordinator<Unit>(search = { _, _, _ -> throw failure }, dispatch = { it() }, worker = worker).use { search ->
            search.request("doc", "text") { result = it.exceptionOrNull() }
            worker.advance(250)
            assertSame(failure, result)
        }
    }
}
