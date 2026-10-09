package com.inkforge.notesstudio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PageOcrQueueTest {
    @Test fun activeManualKeepsResultWhileNewerAutomaticWaits() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val order = CopyOnWriteArrayList<String>()
        val queue = OcrRequestQueue(worker) { request ->
            order += if (request.automatic) "auto" else "manual"
            if (!request.automatic) { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
            PageOcrCoordinator.Receipt(true, null)
        }
        try {
            queue.request("d", "p", "ko-primary", false, { _, _, _ -> }) {
                assertTrue(it.applied); completed.countDown()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            queue.request("d", "p", "ko-primary", true, { _, _, _ -> }) {
                assertTrue(it.applied); completed.countDown()
            }
            release.countDown()
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("manual", "auto"), order)
        } finally { queue.close(); worker.shutdownNow() }
    }

    @Test fun pendingManualCannotBeReplacedByAutomaticAndCancellationFinishesCallback() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(3)
        val order = CopyOnWriteArrayList<String>()
        val status = CopyOnWriteArrayList<String>()
        val queue = OcrRequestQueue(worker) { request ->
            order += request.pageId
            if (request.pageId == "first") { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
            PageOcrCoordinator.Receipt(true, null)
        }
        try {
            queue.request("d", "first", "ko-primary", true, { _, _, _ -> }) { completed.countDown() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            queue.request("d", "second", "ko-primary", false, { _, _, _ -> }) {
                status += if (it.applied) "manualApplied" else it.errorCode.orEmpty(); completed.countDown()
            }
            queue.request("d", "second", "ko-primary", true, { _, _, _ -> }) {
                status += it.errorCode.orEmpty(); completed.countDown()
            }
            release.countDown()
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("first", "second"), order)
            assertTrue(status.containsAll(listOf("cancelled", "manualApplied")))
        } finally { queue.close(); worker.shutdownNow() }
    }

    @Test fun cancellationInvalidatesActiveApplyButWaitsForProcessorReturn() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var receipt: PageOcrCoordinator.Receipt? = null
        val queue = OcrRequestQueue(worker) {
            entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS))
            PageOcrCoordinator.Receipt(true, null)
        }
        try {
            queue.request("d", "p", "ko-primary", false, { _, _, _ -> }) { receipt = it; completed.countDown() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            queue.cancel("p")
            assertEquals(1L, completed.count)
            release.countDown()
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertEquals("cancelled", receipt?.errorCode)
            assertFalse(receipt!!.applied)
        } finally { queue.close(); worker.shutdownNow() }
    }

    @Test fun cancellationAfterCommittedApplyRetainsActualSuccess() {
        val worker = Executors.newSingleThreadExecutor()
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var receipt: PageOcrCoordinator.Receipt? = null
        lateinit var queue: OcrRequestQueue
        queue = OcrRequestQueue(worker) { request ->
            assertEquals(true, queue.applyIfCurrent(request) { true })
            committed.countDown()
            assertTrue(release.await(3, TimeUnit.SECONDS))
            PageOcrCoordinator.Receipt(true, null)
        }
        try {
            queue.request("d", "p", "ko-primary", false, { _, _, _ -> }) { receipt = it; completed.countDown() }
            assertTrue(committed.await(2, TimeUnit.SECONDS))
            queue.cancel("p")
            assertEquals(1L, completed.count)
            release.countDown()
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertTrue(receipt!!.applied)
            assertNull(receipt!!.errorCode)
        } finally { queue.close(); worker.shutdownNow() }
    }

    @Test fun throwingProgressAndCancelledCallbackDoNotStrandOtherPages() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(2)
        lateinit var queue: OcrRequestQueue
        queue = OcrRequestQueue(worker) { request ->
            queue.progress(request, "recognizing", 0, 1)
            entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
            PageOcrCoordinator.Receipt(true, null)
        }
        try {
            queue.request("d", "active", "ko-primary", false, { _, _, _ -> throw IllegalStateException("progress") }) {
                delivered.countDown()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            queue.request("d", "bad", "ko-primary", false, { _, _, _ -> }) { throw IllegalStateException("callback") }
            queue.request("d", "good", "ko-primary", false, { _, _, _ -> }) { delivered.countDown() }
            queue.setActive(false)
            release.countDown()
            assertTrue(delivered.await(3, TimeUnit.SECONDS))
        } finally { queue.close(); worker.shutdownNow() }
    }

}
