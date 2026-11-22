package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class DeferredHistorySizingTest {
    private fun fill(obj: JSONObject, id: String, payload: Int = 0): JSONObject = obj.apply {
        put("id", id)
        put("type", "stroke")
        put("points", JSONArray().put(json("x" to 12.25, "y" to 34.5, "pressure" to 0.75,
            "time" to 123456789L, "extension" to json("nullValue" to JSONObject.NULL, "tag" to "한글🌱"))))
        put("extension", json("payload" to "x".repeat(payload), "flag" to true))
    }

    private class ObservedJson(private val beforeSerialize: () -> Unit) : JSONObject() {
        var serializations = 0
            private set

        override fun toString(): String {
            beforeSerialize()
            serializations++
            return super.toString()
        }
    }

    private inner class Sizing(val limit: Int = Int.MAX_VALUE) {
        val workers = mutableListOf<() -> Unit>()
        val completions = mutableListOf<() -> Unit>()
        val notifications = mutableListOf<Set<String>>()
        val history = EditHistory(limit) { workers.add(it) }
        private var measuring = false

        fun observed(id: String, payload: Int = 0): ObservedJson =
            ObservedJson { assertTrue("JSON serialization escaped the sizing worker", measuring) }
                .also { fill(it, id, payload) }

        fun push(change: ObjectChange) {
            history.pushDeferred(change, { completions.add(it) }) { notifications.add(it) }
        }

        fun measure(index: Int = 0) {
            val task = workers.removeAt(index)
            measuring = true
            try { task() } finally { measuring = false }
        }

        fun complete(index: Int = 0) { completions.removeAt(index)() }
        fun finish() {
            while (workers.isNotEmpty()) measure()
            while (completions.isNotEmpty()) complete()
        }
    }

    private fun addition(id: String, payload: Int = 0) =
        ObjectChange(emptyList(), listOf(fill(JSONObject(), id, payload)))

    private fun undoIds(history: EditHistory): List<String> {
        val ids = mutableListOf<String>()
        while (history.canUndo) ids.add(requireNotNull(history.undo()).before.single().getString("id"))
        return ids
    }

    @Test fun pushUndoAndRedoNeverSerializeUntilTheWorkerRuns() {
        val sizing = Sizing()
        val before = sizing.observed("before")
        val after = sizing.observed("after")
        val beforePositions = mapOf("before" to 3)
        val afterPositions = mapOf("after" to 4)
        val change = ObjectChange(listOf(before), listOf(after), beforePositions, afterPositions)

        sizing.push(change)
        assertEquals(0, before.serializations)
        assertEquals(0, after.serializations)
        val reversed = requireNotNull(sizing.history.undo())
        assertSame(change.after, reversed.before)
        assertSame(change.before, reversed.after)
        assertSame(afterPositions, reversed.beforePositions)
        assertSame(beforePositions, reversed.afterPositions)
        assertSame(change, sizing.history.redo())
        assertEquals(setOf("before", "after"), sizing.history.retainedObjectIds)
        assertEquals(0, before.serializations)
        assertEquals(0, after.serializations)

        sizing.finish()
        repeat(4) {
            assertNotNull(sizing.history.undo())
            assertSame(change, sizing.history.redo())
        }
        assertEquals(1, before.serializations)
        assertEquals(1, after.serializations)
        assertTrue(sizing.notifications.isEmpty())
    }

    @Test fun repeatedPublicationMeasuresTheImmutableChangeOnlyOnce() {
        val sizing = Sizing(0)
        val obj = sizing.observed("same")
        val change = ObjectChange(emptyList(), listOf(obj))
        sizing.push(change)
        sizing.push(change)
        sizing.finish()
        assertEquals(1, obj.serializations)
        assertEquals(setOf("same"), sizing.history.retainedObjectIds)
        assertTrue(sizing.notifications.isEmpty())
        assertSame(change.after, requireNotNull(sizing.history.undo()).before)
        assertFalse(sizing.history.canUndo)
        assertSame(change, sizing.history.redo())
    }

    @Test fun stableHistoryMatchesExactSynchronousEvictionRegardlessOfCompletionOrder() {
        for (reverseWorker in listOf(false, true)) for (reverseCompletion in listOf(false, true)) {
            val changes = (0..3).map { addition("id$it", it * 40) }
            val limit = changes[2].bytes + changes[3].bytes
            val synchronous = EditHistory(limit)
            changes.forEach(synchronous::push)
            val expectedIds = synchronous.retainedObjectIds.toSet()
            val expectedDiscarded = synchronous.takeDiscardedObjectIds()
            val sizing = Sizing(limit)
            val observed = (0..3).map { sizing.observed("id$it", it * 40) }
            observed.forEach { sizing.push(ObjectChange(emptyList(), listOf(it))) }
            assertEquals((0..3).map { "id$it" }.toSet(), sizing.history.retainedObjectIds)

            while (sizing.workers.isNotEmpty()) sizing.measure(if (reverseWorker) sizing.workers.lastIndex else 0)
            while (sizing.completions.size > 1) {
                sizing.complete(if (reverseCompletion) sizing.completions.lastIndex else 0)
                assertEquals(4, sizing.history.retainedObjectIds.size)
            }
            sizing.complete()
            assertEquals(expectedIds, sizing.history.retainedObjectIds)
            assertEquals(expectedDiscarded, sizing.notifications.flatten().toSet())
            assertTrue(sizing.history.takeDiscardedObjectIds().isEmpty())
            assertEquals(undoIds(synchronous), undoIds(sizing.history))
            observed.forEach { assertEquals(1, it.serializations) }
        }
    }

    @Test fun exactLimitKeepsBothChangesAndOversizedNewestStillKeepsOne() {
        val first = addition("first")
        val second = addition("second", 50)
        for (limit in listOf(first.bytes + second.bytes, 0, -1)) {
            val sizing = Sizing(limit)
            sizing.push(first)
            sizing.push(second)
            sizing.finish()
            assertEquals(if (limit >= first.bytes + second.bytes) listOf("second", "first") else listOf("second"),
                undoIds(sizing.history))
        }
    }

    @Test fun clearSkipsUnstartedWorkAndInvalidatesAlreadyPostedCompletions() {
        val sizing = Sizing(0)
        val queued = sizing.observed("queued")
        val measured = sizing.observed("measured")
        sizing.push(ObjectChange(emptyList(), listOf(queued)))
        sizing.push(ObjectChange(emptyList(), listOf(measured)))
        sizing.measure(1)
        sizing.history.clear()
        assertEquals(setOf("queued", "measured"), sizing.history.takeDiscardedObjectIds())
        assertTrue(sizing.history.retainedObjectIds.isEmpty())
        assertFalse(sizing.history.canUndo)
        assertFalse(sizing.history.canRedo)

        val replacement = sizing.observed("replacement")
        sizing.push(ObjectChange(emptyList(), listOf(replacement)))
        sizing.complete()
        sizing.finish()
        assertEquals(0, queued.serializations)
        assertEquals(1, measured.serializations)
        assertEquals(1, replacement.serializations)
        assertEquals(setOf("replacement"), sizing.history.retainedObjectIds)
        assertEquals(listOf("replacement"), undoIds(sizing.history))
        assertTrue(sizing.notifications.isEmpty())
    }

    @Test fun newPushDiscardsPendingRedoBeforeItCanBeSerialized() {
        val sizing = Sizing()
        val shared = sizing.observed("shared")
        val abandoned = sizing.observed("abandoned")
        val replacement = sizing.observed("replacement")
        sizing.push(ObjectChange(emptyList(), listOf(shared)))
        sizing.push(ObjectChange(listOf(shared), listOf(abandoned)))
        assertNotNull(sizing.history.undo())
        sizing.push(ObjectChange(listOf(shared), listOf(replacement)))
        assertFalse(sizing.history.canRedo)
        assertEquals(setOf("abandoned"), sizing.history.takeDiscardedObjectIds())
        sizing.finish()
        assertEquals(0, abandoned.serializations)
        // The same object belongs to two distinct changes, so each change charges its own tree.
        assertEquals(2, shared.serializations)
        assertEquals(setOf("shared", "replacement"), sizing.history.retainedObjectIds)
        assertTrue(sizing.notifications.isEmpty())
    }

    @Test fun discardedRedoCompletionCannotReintroduceChargesOrDiscardReusedIds() {
        val sizing = Sizing(0)
        val abandoned = sizing.observed("reused")
        sizing.push(ObjectChange(emptyList(), listOf(abandoned)))
        sizing.measure()
        assertNotNull(sizing.history.undo())
        val replacement = sizing.observed("reused")
        val replacementChange = ObjectChange(emptyList(), listOf(replacement))
        sizing.push(replacementChange)
        assertTrue(sizing.history.takeDiscardedObjectIds().isEmpty())
        sizing.complete()
        sizing.finish()
        assertEquals(setOf("reused"), sizing.history.retainedObjectIds)
        assertTrue(sizing.notifications.isEmpty())
        assertNotNull(sizing.history.undo())
        assertFalse(sizing.history.canUndo)
        assertSame(replacementChange, sizing.history.redo())
    }

    @Test fun completionChargesTheEntryCurrentStackWithoutChangingContactOrder() {
        val first = addition("first")
        val second = addition("second")
        val third = addition("third")
        val sizing = Sizing(first.bytes + second.bytes + third.bytes)
        sizing.push(first)
        sizing.push(second)
        sizing.measure(1)
        assertEquals("second", requireNotNull(sizing.history.undo()).before.single().getString("id"))
        sizing.complete()
        assertSame(second, sizing.history.redo())
        sizing.measure()
        assertEquals("second", requireNotNull(sizing.history.undo()).before.single().getString("id"))
        assertEquals("first", requireNotNull(sizing.history.undo()).before.single().getString("id"))
        sizing.complete()
        assertSame(first, sizing.history.redo())
        assertSame(second, sizing.history.redo())
        sizing.push(third)
        sizing.finish()
        assertTrue(sizing.notifications.isEmpty())
        assertEquals(listOf("third", "second", "first"), undoIds(sizing.history))
    }

    @Test fun measuredRedoDoesNotCauseEvictionUntilAnotherPush() {
        val first = addition("first")
        val second = addition("second")
        val sizing = Sizing(first.bytes + second.bytes - 1)
        sizing.push(first)
        sizing.push(second)
        assertNotNull(sizing.history.undo())
        assertNotNull(sizing.history.undo())
        sizing.finish()
        assertSame(first, sizing.history.redo())
        assertSame(second, sizing.history.redo())
        assertEquals(setOf("first", "second"), sizing.history.retainedObjectIds)
        val third = addition("third")
        sizing.push(third)
        sizing.finish()
        val synchronous = EditHistory(sizing.limit)
        synchronous.push(first)
        synchronous.push(second)
        synchronous.push(third)
        assertEquals(synchronous.retainedObjectIds, sizing.history.retainedObjectIds)
        assertEquals(undoIds(synchronous), undoIds(sizing.history))
    }

    @Test fun synchronousPushCanClearUnmeasuredRedoWithoutSizingItOnTheCaller() {
        val first = addition("first")
        val sizing = Sizing(first.bytes)
        val abandoned = sizing.observed("abandoned")
        sizing.push(first)
        sizing.push(ObjectChange(emptyList(), listOf(abandoned)))
        assertNotNull(sizing.history.undo())
        val replacement = addition("replacement", 30)
        sizing.history.push(replacement)
        assertEquals(setOf("abandoned"), sizing.history.takeDiscardedObjectIds())
        sizing.finish()
        assertEquals(0, abandoned.serializations)
        assertEquals(setOf("replacement"), sizing.history.retainedObjectIds)
        assertEquals(listOf(setOf("first")), sizing.notifications)
    }

    @Test fun serializedChargeCountsBothTreesAndLeavesSamplesAndExtensionsUntouched() {
        val original = fill(JSONObject(), "stroke", 80)
        val fragment = fill(JSONObject(), "fragment", 40)
        val replacement = fill(JSONObject(), "stroke", 20)
        val beforeJson = original.toString()
        val afterJson = listOf(replacement.toString(), fragment.toString())
        val change = ObjectChange(listOf(original), listOf(replacement, fragment), mapOf("stroke" to 0),
            mapOf("stroke" to 0, "fragment" to 1))
        val expectedCharge = (beforeJson.length + afterJson.sumOf { it.length }) * 2
        val sizing = Sizing()
        val page = NotePage("page", JSONObject(), mutableListOf(original))
        change.applyOwnedAfter(page)
        sizing.push(change)
        requireNotNull(sizing.history.undo()).apply(page)
        assertEquals(listOf(beforeJson), page.objects.map { it.toString() })
        requireNotNull(sizing.history.redo()).apply(page)
        assertEquals(afterJson, page.objects.map { it.toString() })
        sizing.finish()
        assertEquals(expectedCharge, change.bytes)
        assertEquals(beforeJson, original.toString())
        assertEquals(afterJson, listOf(replacement.toString(), fragment.toString()))
        val extension = page.objects[0].array("points").getJSONObject(0).getJSONObject("extension")
        assertTrue(extension.isNull("nullValue"))
        assertEquals("한글🌱", extension.getString("tag"))
    }

    @Test fun defaultHistoriesShareOneDaemonBackgroundPrioritySizingWorker() {
        val caller = Thread.currentThread()
        val threads = LinkedBlockingQueue<Thread>()
        val completions = LinkedBlockingQueue<() -> Unit>()
        val objects = (0..1).map { index ->
            ObservedJson { threads.add(Thread.currentThread()) }.also { fill(it, "id$index") }
        }
        val histories = listOf(EditHistory(), EditHistory())
        histories.forEachIndexed { index, history ->
            history.pushDeferred(ObjectChange(emptyList(), listOf(objects[index])),
                { completions.put(it) }) { fail("Unexpected eviction") }
        }
        val firstCompletion = requireNotNull(completions.poll(5, TimeUnit.SECONDS))
        val secondCompletion = requireNotNull(completions.poll(5, TimeUnit.SECONDS))
        val firstThread = requireNotNull(threads.poll())
        val secondThread = requireNotNull(threads.poll())
        assertNotSame(caller, firstThread)
        assertSame(firstThread, secondThread)
        assertTrue(firstThread.isDaemon)
        assertEquals(Thread.MIN_PRIORITY, firstThread.priority)
        firstCompletion()
        secondCompletion()
        objects.forEach { assertEquals(1, it.serializations) }
        histories.forEach { assertTrue(it.canUndo) }
    }
}
