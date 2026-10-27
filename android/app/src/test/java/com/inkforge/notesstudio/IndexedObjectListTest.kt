package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.ConcurrentModificationException
import java.util.Random

class IndexedObjectListTest {
    private fun obj(id: String): JSONObject = JSONObject().put("id", id)

    private fun assertOrder(expected: List<JSONObject>, actual: IndexedObjectList) {
        assertEquals(expected.size, actual.size)
        val forward = actual.iterator()
        expected.forEachIndexed { index, value ->
            assertTrue(forward.hasNext())
            assertSame(value, forward.next())
            assertSame(value, actual[index])
            assertEquals(index, actual.indexOfId(value.getString("id")))
        }
        assertFalse(forward.hasNext())
        assertEquals(-1, actual.indexOfId("absent"))
        val backward = actual.listIterator(actual.size)
        for (index in expected.indices.reversed()) {
            assertTrue(backward.hasPrevious())
            assertEquals(index, backward.previousIndex())
            assertSame(expected[index], backward.previous())
            assertEquals(index, backward.nextIndex())
        }
        assertFalse(backward.hasPrevious())
    }

    @Test fun constructorAndReplacementKeepOwnedJsonReferencesAndRawSamples() {
        val samples = JSONArray().put(JSONObject().put("x", 5).put("p", 0)
            .put("vendor", JSONObject().put("axis", 19)))
        val original = obj("original").put("points", samples)
        val tail = obj("tail")
        val source = arrayListOf(original, tail)
        val actual = IndexedObjectList(source)
        source.clear()
        assertSame(original, actual[0])
        assertSame(samples, actual[0].getJSONArray("points"))
        assertEquals(19, samples.getJSONObject(0).getJSONObject("vendor").getInt("axis"))

        val first = obj("original").put("points", samples)
        val second = obj("fragment")
        assertTrue(actual.replaceById("original", listOf(first, second)))
        assertOrder(listOf(first, second, tail), actual)
        assertSame(samples, actual[0].getJSONArray("points"))
        assertSame(original.getJSONArray("points"), first.getJSONArray("points"))
        assertTrue(actual.replaceById("fragment", emptyList()))
        assertEquals(-1, actual.indexOfId("fragment"))
        assertOrder(listOf(first, tail), actual)
        assertFalse(actual.replaceById("absent", listOf(obj("notInserted"))))
        assertEquals(-1, actual.indexOfId("notInserted"))
    }

    @Test fun randomizedMutationsMatchArrayListOrderAndEveryIdRank() {
        val random = Random(0x494e4bL)
        var serial = 0
        fun fresh(): JSONObject = obj("object_${serial++}")
        val expected = ArrayList<JSONObject>()
        repeat(128) { expected.add(fresh()) }
        val actual = IndexedObjectList(expected)
        repeat(2400) {
            when (if (expected.isEmpty()) 0 else random.nextInt(7)) {
                0, 1 -> {
                    val index = random.nextInt(expected.size + 1)
                    val value = fresh()
                    expected.add(index, value)
                    actual.add(index, value)
                }
                2 -> {
                    val index = random.nextInt(expected.size)
                    val removed = expected.removeAt(index)
                    assertSame(removed, actual.removeAt(index))
                    assertEquals(-1, actual.indexOfId(removed.getString("id")))
                }
                3 -> {
                    val index = random.nextInt(expected.size)
                    val oldId = expected[index].getString("id")
                    val value = if (random.nextBoolean()) obj(oldId) else fresh()
                    assertSame(expected.set(index, value), actual.set(index, value))
                    if (oldId != value.getString("id")) assertEquals(-1, actual.indexOfId(oldId))
                }
                4, 5 -> {
                    val index = random.nextInt(expected.size)
                    val oldId = expected[index].getString("id")
                    val fragments = ArrayList<JSONObject>()
                    repeat(random.nextInt(4)) { fragment ->
                        fragments.add(if (fragment == 0 && random.nextBoolean()) obj(oldId) else fresh())
                    }
                    expected.removeAt(index)
                    expected.addAll(index, fragments)
                    assertTrue(actual.replaceById(oldId, fragments))
                    if (fragments.none { value -> value.getString("id") == oldId }) {
                        assertEquals(-1, actual.indexOfId(oldId))
                    }
                }
                else -> {
                    val index = random.nextInt(expected.size)
                    val referenceIterator = expected.listIterator(index)
                    val actualIterator = actual.listIterator(index)
                    assertSame(referenceIterator.next(), actualIterator.next())
                    referenceIterator.remove()
                    actualIterator.remove()
                }
            }
            assertOrder(expected, actual)
        }
    }

    @Test fun randomizedMutableListIteratorsMatchArrayListCursorsAndMutations() {
        val random = Random(0x517e2L)
        var serial = 0
        fun fresh(): JSONObject = obj("iterator_${serial++}")
        val expected = arrayListOf(fresh(), fresh(), fresh(), fresh())
        val actual = IndexedObjectList(expected)
        val referenceIterator = expected.listIterator(2)
        val actualIterator = actual.listIterator(2)
        var canModify = false
        repeat(1600) { step ->
            when (random.nextInt(5)) {
                0 -> if (referenceIterator.hasNext()) {
                    assertSame(referenceIterator.next(), actualIterator.next())
                    canModify = true
                }
                1 -> if (referenceIterator.hasPrevious()) {
                    assertSame(referenceIterator.previous(), actualIterator.previous())
                    canModify = true
                }
                2 -> {
                    val value = fresh()
                    referenceIterator.add(value)
                    actualIterator.add(value)
                    canModify = false
                }
                3 -> if (canModify) {
                    referenceIterator.remove()
                    actualIterator.remove()
                    canModify = false
                }
                else -> if (canModify) {
                    val value = fresh()
                    referenceIterator.set(value)
                    actualIterator.set(value)
                }
            }
            assertEquals(referenceIterator.nextIndex(), actualIterator.nextIndex())
            assertEquals(referenceIterator.previousIndex(), actualIterator.previousIndex())
            assertEquals(referenceIterator.hasNext(), actualIterator.hasNext())
            assertEquals(referenceIterator.hasPrevious(), actualIterator.hasPrevious())
            if (step % 20 == 0) assertOrder(expected, actual)
        }
        assertOrder(expected, actual)
    }

    @Test fun setAndIteratorSetUpdateIdMapWithoutInvalidatingReadTraversal() {
        val first = obj("first")
        val second = obj("second")
        val actual = IndexedObjectList(listOf(first, second))
        val iterator = actual.listIterator()
        val newFirst = obj("first")
        assertSame(first, actual.set(0, newFirst))
        assertSame(newFirst, iterator.next())
        val renamed = obj("renamed")
        iterator.set(renamed)
        assertEquals(-1, actual.indexOfId("first"))
        assertEquals(0, actual.indexOfId("renamed"))
        assertSame(second, iterator.next())
        assertOrder(listOf(renamed, second), actual)
    }

    @Test fun duplicateAndBlankIdsAreRejectedWithoutCorruptingOrder() {
        assertThrows(IllegalArgumentException::class.java) {
            IndexedObjectList(listOf(obj("duplicate"), obj("duplicate")))
        }
        assertThrows(IllegalArgumentException::class.java) { IndexedObjectList(listOf(obj(" "))) }
        val first = obj("first")
        val second = obj("second")
        val actual = IndexedObjectList(listOf(first, second))
        val expected = listOf(first, second)
        val invalidMutations = listOf<() -> Unit>(
            { actual.add(obj("first")) },
            { actual.add(1, obj("")) },
            { actual[0] = obj("second") },
            { actual[1] = JSONObject() },
            { actual.replaceById("first", listOf(obj("new"), obj("second"))) },
            { actual.replaceById("first", listOf(obj("first"), obj("first"))) },
            { actual.replaceById("first", listOf(obj("new"), obj(" "))) }
        )
        for (mutate in invalidMutations) {
            assertThrows(IllegalArgumentException::class.java) { mutate() }
            assertOrder(expected, actual)
        }
        val iterator = actual.listIterator()
        iterator.next()
        assertThrows(IllegalArgumentException::class.java) { iterator.set(obj("second")) }
        assertThrows(IllegalArgumentException::class.java) { iterator.add(obj("second")) }
        assertSame(second, iterator.next())
        assertOrder(expected, actual)
    }

    @Test fun sameIdSingletonReplacementAlsoSupportsBorrowedSublist() {
        val first = obj("first")
        val middle = obj("middle")
        val tail = obj("tail")
        val actual = IndexedObjectList(listOf(first, middle, tail))
        val changed = obj("middle")
        assertTrue(actual.replaceById("middle", listOf(changed)))
        assertOrder(listOf(first, changed, tail), actual)
        assertTrue(actual.replaceById("middle", actual.subList(1, 2)))
        assertOrder(listOf(first, changed, tail), actual)
        val singleton = IndexedObjectList(listOf(first))
        assertTrue(singleton.replaceById("first", singleton))
        assertOrder(listOf(first), singleton)
    }

    @Test fun structuralChangesInvalidateIteratorsButIteratorMutationsRemainUsable() {
        val actual = IndexedObjectList(listOf(obj("a"), obj("b"), obj("c")))
        val afterAdd = actual.iterator()
        actual.add(1, obj("inserted"))
        assertThrows(ConcurrentModificationException::class.java) { afterAdd.next() }
        val afterRemove = actual.listIterator(actual.size)
        actual.removeAt(0)
        assertThrows(ConcurrentModificationException::class.java) { afterRemove.previous() }
        val afterReplace = actual.iterator()
        actual.replaceById("b", listOf(obj("b")))
        assertThrows(ConcurrentModificationException::class.java) { afterReplace.hasNext() }
        val afterClear = actual.iterator()
        actual.clear()
        assertThrows(ConcurrentModificationException::class.java) { afterClear.next() }
        assertOrder(emptyList(), actual)
        actual.add(obj("reused"))
        assertOrder(listOf(actual[0]), actual)
    }

    @Test fun inheritedMutableListOperationsKeepTheIdIndexConsistent() {
        val values = (0 until 40).map { obj("item_$it") }
        val actual = IndexedObjectList(values)
        val expected = values.toMutableList()
        actual.removeAll { it.getString("id").substringAfter('_').toInt() % 3 == 0 }
        expected.removeAll { it.getString("id").substringAfter('_').toInt() % 3 == 0 }
        assertOrder(expected, actual)
        actual.subList(2, 7).clear()
        expected.subList(2, 7).clear()
        assertOrder(expected, actual)
        val extras = listOf(obj("extra_0"), obj("extra_1"))
        assertEquals(expected.addAll(3, extras), actual.addAll(3, extras))
        assertOrder(expected, actual)
        assertEquals(expected.remove(extras[0]), actual.remove(extras[0]))
        assertOrder(expected, actual)
        val retained = expected.filterIndexed { index, _ -> index % 2 == 0 }
        assertEquals(expected.retainAll(retained), actual.retainAll(retained))
        assertOrder(expected, actual)
        assertThrows(IndexOutOfBoundsException::class.java) { actual[-1] }
        assertThrows(IndexOutOfBoundsException::class.java) { actual[actual.size] }
        assertThrows(IndexOutOfBoundsException::class.java) { actual.add(actual.size + 1, obj("tooFar")) }
        assertThrows(IndexOutOfBoundsException::class.java) { actual.listIterator(-1) }
        assertThrows(IndexOutOfBoundsException::class.java) { actual.listIterator(actual.size + 1) }
    }

    @Test fun iteratorBoundsAndMutationStateMatchTheMutableListContract() {
        val actual = IndexedObjectList(emptyList())
        val iterator = actual.listIterator()
        assertFalse(iterator.hasNext())
        assertFalse(iterator.hasPrevious())
        assertEquals(0, iterator.nextIndex())
        assertEquals(-1, iterator.previousIndex())
        assertThrows(NoSuchElementException::class.java) { iterator.next() }
        assertThrows(NoSuchElementException::class.java) { iterator.previous() }
        assertThrows(IllegalStateException::class.java) { iterator.remove() }
        assertThrows(IllegalStateException::class.java) { iterator.set(obj("unused")) }
        val value = obj("added")
        iterator.add(value)
        assertThrows(IllegalStateException::class.java) { iterator.remove() }
        assertThrows(IllegalStateException::class.java) { iterator.set(obj("unused")) }
        assertSame(value, iterator.previous())
        iterator.remove()
        assertFalse(iterator.hasNext())
        assertFalse(iterator.hasPrevious())
        assertOrder(emptyList(), actual)
    }
}
