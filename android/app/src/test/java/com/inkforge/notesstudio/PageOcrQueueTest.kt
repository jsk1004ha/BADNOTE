package com.inkforge.notesstudio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PageOcrQueueTest {
    private fun writing(queue: OcrRequestQueue, value: Boolean) {
        OcrRequestQueue::class.java.getMethod("setWriting", Boolean::class.javaPrimitiveType).invoke(queue, value)
    }

    @Test fun writingRejectsAutomaticButKeepsManualAndResumesAfterContact() {
        val worker = Executors.newSingleThreadExecutor()
        val processed = CopyOnWriteArrayList<Boolean>()
        val receipts = CopyOnWriteArrayList<PageOcrCoordinator.Receipt>()
        val completed = CountDownLatch(3)
        val queue = OcrRequestQueue(worker) { request ->
            processed += request.automatic
            PageOcrCoordinator.Receipt(true, null)
        }
        try {
            writing(queue, true)
            queue.request("d", "auto", "ko-primary", true, { _, _, _ -> }) { receipts += it; completed.countDown() }
            queue.request("d", "manual", "ko-primary", false, { _, _, _ -> }) { receipts += it; completed.countDown() }
            writing(queue, false)
            queue.request("d", "resumed", "ko-primary", true, { _, _, _ -> }) { receipts += it; completed.countDown() }
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(false, true), processed)
            assertEquals(1, receipts.count { it.errorCode == "cancelled" })
            assertEquals(2, receipts.count { it.applied })
        } finally { queue.close(); worker.shutdownNow() }
    }

    @Test fun contactInvalidatesRunningAutomaticEvenAfterPenLift() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var saved = false
        var receipt: PageOcrCoordinator.Receipt? = null
        lateinit var queue: OcrRequestQueue
        queue = OcrRequestQueue(worker) { request ->
            entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
            val applied = queue.applyIfCurrent(request) { saved = true; true }
            PageOcrCoordinator.Receipt(applied == true, null)
        }
        try {
            queue.request("d", "p", "ko-primary", true, { _, _, _ -> }) { receipt = it; completed.countDown() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            writing(queue, true); writing(queue, false)
            release.countDown()
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertFalse(saved)
            assertEquals("cancelled", receipt?.errorCode)
        } finally { release.countDown(); queue.close(); worker.shutdownNow() }
    }

    @Test fun contactStartDoesNotWaitForAnAlreadyCommittingResult() {
        val worker = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var receipt: PageOcrCoordinator.Receipt? = null
        lateinit var queue: OcrRequestQueue
        queue = OcrRequestQueue(worker) { request ->
            val applied = queue.applyIfCurrent(request) {
                entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); true
            }
            PageOcrCoordinator.Receipt(applied == true, null)
        }
        try {
            queue.request("d", "p", "ko-primary", true, { _, _, _ -> }) { receipt = it; completed.countDown() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            caller.submit { writing(queue, true) }.get(1, TimeUnit.SECONDS)
            release.countDown()
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertTrue(receipt!!.applied) // The commit crossed its boundary before the contact started.
        } finally { release.countDown(); queue.close(); worker.shutdownNow(); caller.shutdownNow() }
    }

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

    @Test fun scopedMathCarriesImmutableSelectionThroughManualWritingGate() {
        val worker = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var actual: OcrRequestQueue.Request? = null
        val queue = OcrRequestQueue(worker) { request ->
            actual = request
            PageOcrCoordinator.Receipt(true, null)
        }
        try {
            worker.execute { release.await(3, TimeUnit.SECONDS) }
            val selected = linkedSetOf("original")
            queue.setWriting(true)
            queue.request("d", "p", "en", false, expectedDigest = null,
                progress = { _, _, _ -> }, complete = { completed.countDown() },
                math = true, selectedIds = selected)
            selected.clear(); selected += "changed"
            release.countDown()
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertEquals(setOf("original"), actual?.selectedIds)
            assertEquals(true, actual?.math)
            assertFalse(actual!!.automatic)
        } finally { release.countDown(); queue.close(); worker.shutdownNow() }
    }

}
