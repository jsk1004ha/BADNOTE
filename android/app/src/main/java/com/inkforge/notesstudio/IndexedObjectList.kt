package com.inkforge.notesstudio

import org.json.JSONObject
import java.util.ConcurrentModificationException

/**
 * Editor-owned object order. JSON objects (including all raw samples) are borrowed, never copied.
 * IDs must be unique and nonblank, and callers must replace objects rather than mutate stored IDs.
 *
 * The implicit treap stores list rank in subtree sizes; the ID map points at the same nodes.
 * Parent links make rank lookup and allocation-free in-order traversal possible. Priorities come
 * from a fixed-seed generator, independent of IDs and insertion positions. Indexed operations take
 * expected O(log n); a complete iterator traversal takes O(n). No mutation snapshots page order.
 */
internal class IndexedObjectList(source: Collection<JSONObject>) : AbstractMutableList<JSONObject>() {
    private class Node(var value: JSONObject, var id: String, val priority: Int) {
        var parent: Node? = null
        var left: Node? = null
        var right: Node? = null
        var subtreeSize = 1
    }

    private var root: Node? = null
    private val nodesById = HashMap<String, Node>(source.size)
    private var priorityState = 0x6d2b79f5

    init {
        source.forEach { add(it) }
    }

    override val size: Int get() = sizeOf(root)

    override fun get(index: Int): JSONObject = nodeAt(index).value

    override fun set(index: Int, element: JSONObject): JSONObject = setNode(nodeAt(index), element)

    override fun add(index: Int, element: JSONObject) {
        checkPositionIndex(index)
        val id = checkedId(element)
        insertNode(index, Node(element, id, nextPriority()))
    }

    override fun removeAt(index: Int): JSONObject = removeNode(nodeAt(index))

    override fun clear() {
        if (root == null) return
        root = null
        nodesById.clear()
        modCount++
    }

    /** Exact current page rank, or -1 when the ID is absent. Expected O(log n). */
    fun indexOfId(id: String): Int = nodesById[id]?.let(::rankOf) ?: -1

    /**
     * Replace one object in place with its ordered fragments, returning false if the ID is absent.
     * Validate before changing order, allowing the original ID in the replacement exactly once.
     * The original is removed before fragments are inserted. Expected O((1 + k) log(n + k)).
     */
    fun replaceById(id: String, replacement: List<JSONObject>): Boolean {
        val original = nodesById[id] ?: return false
        if (replacement.isEmpty()) {
            removeNode(original)
            return true
        }
        if (replacement.size == 1) {
            // Retain the reference before removal, including when replacement is a sublist of us.
            val value = replacement[0]
            val replacementId = checkedId(value, original)
            val position = rankOf(original)
            removeNode(original)
            insertNode(position, Node(value, replacementId, nextPriority()))
            return true
        }
        val replacementIds = HashSet<String>(replacement.size)
        for (value in replacement) {
            val replacementId = checkedId(value, original)
            require(replacementIds.add(replacementId)) { "Duplicate object ID: $replacementId" }
        }
        var position = rankOf(original)
        removeNode(original)
        for (value in replacement) {
            insertNode(position++, Node(value, value.optString("id"), nextPriority()))
        }
        return true
    }

    override fun iterator(): MutableIterator<JSONObject> = listIterator()

    override fun listIterator(): MutableListIterator<JSONObject> = listIterator(0)

    override fun listIterator(index: Int): MutableListIterator<JSONObject> {
        checkPositionIndex(index)
        return ObjectIterator(index)
    }

    private fun checkedId(value: JSONObject, replacing: Node? = null): String {
        val id = value.optString("id")
        require(id.isNotBlank()) { "Object ID must be nonblank" }
        val existing = nodesById[id]
        require(existing == null || existing === replacing) { "Duplicate object ID: $id" }
        return id
    }

    private fun setNode(node: Node, value: JSONObject): JSONObject {
        val id = checkedId(value, node)
        val previous = node.value
        if (id != node.id) {
            nodesById.remove(node.id)
            node.id = id
            nodesById[id] = node
        }
        node.value = value
        return previous
    }

    private fun nextPriority(): Int {
        var state = priorityState
        state = state xor (state shl 13)
        state = state xor (state ushr 17)
        state = state xor (state shl 5)
        priorityState = state
        return state
    }

    private fun sizeOf(node: Node?): Int = node?.subtreeSize ?: 0

    private fun updateSize(node: Node) {
        node.subtreeSize = 1 + sizeOf(node.left) + sizeOf(node.right)
    }

    private fun updateSizes(start: Node?) {
        var node = start
        while (node != null) {
            updateSize(node)
            node = node.parent
        }
    }

    private fun checkPositionIndex(index: Int) {
        if (index < 0 || index > size) throw IndexOutOfBoundsException("Index: $index, size: $size")
    }

    private fun nodeAt(index: Int): Node {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("Index: $index, size: $size")
        var node = root!!
        var remaining = index
        while (true) {
            val leftSize = sizeOf(node.left)
            when {
                remaining < leftSize -> node = node.left!!
                remaining == leftSize -> return node
                else -> {
                    remaining -= leftSize + 1
                    node = node.right!!
                }
            }
        }
    }

    private fun rankOf(start: Node): Int {
        var node = start
        var rank = sizeOf(node.left)
        var parent = node.parent
        while (parent != null) {
            if (parent.right === node) rank += 1 + sizeOf(parent.left)
            node = parent
            parent = node.parent
        }
        return rank
    }

    private fun insertNode(index: Int, node: Node) {
        val currentRoot = root
        if (currentRoot == null) {
            root = node
        } else {
            var parent: Node = currentRoot
            var remaining = index
            while (true) {
                val leftSize = sizeOf(parent.left)
                if (remaining <= leftSize) {
                    val left = parent.left
                    if (left == null) {
                        parent.left = node
                        node.parent = parent
                        break
                    }
                    parent = left
                } else {
                    remaining -= leftSize + 1
                    val right = parent.right
                    if (right == null) {
                        parent.right = node
                        node.parent = parent
                        break
                    }
                    parent = right
                }
            }
            updateSizes(parent)
            while (node.parent?.let { node.priority < it.priority } == true) rotateUp(node)
        }
        nodesById[node.id] = node
        modCount++
    }

    /** Rotate one edge, preserving order, parent links and both affected subtree sizes. */
    private fun rotateUp(node: Node) {
        val parent = node.parent!!
        val grandparent = parent.parent
        if (parent.left === node) {
            parent.left = node.right
            node.right?.parent = parent
            node.right = parent
        } else {
            parent.right = node.left
            node.left?.parent = parent
            node.left = parent
        }
        parent.parent = node
        node.parent = grandparent
        if (grandparent == null) root = node
        else if (grandparent.left === parent) grandparent.left = node
        else grandparent.right = node
        updateSize(parent)
        updateSize(node)
    }

    private fun removeNode(node: Node): JSONObject {
        // Promote the lower-priority child until the removed node is a leaf. The remaining tree
        // keeps its heap order; rotations never shift or copy another object's storage.
        while (true) {
            val left = node.left
            val right = node.right
            val child = when {
                left == null -> right
                right == null -> left
                left.priority <= right.priority -> left
                else -> right
            } ?: break
            rotateUp(child)
        }
        val parent = node.parent
        if (parent == null) root = null
        else if (parent.left === node) parent.left = null
        else parent.right = null
        node.parent = null
        updateSizes(parent)
        nodesById.remove(node.id)
        modCount++
        return node.value
    }

    private fun firstNode(start: Node?): Node? {
        var node = start ?: return null
        while (true) node = node.left ?: return node
    }

    private fun lastNode(start: Node?): Node? {
        var node = start ?: return null
        while (true) node = node.right ?: return node
    }

    private fun successor(start: Node): Node? {
        start.right?.let { return firstNode(it) }
        var node = start
        var parent = node.parent
        while (parent != null && parent.right === node) {
            node = parent
            parent = node.parent
        }
        return parent
    }

    private fun predecessor(start: Node): Node? {
        start.left?.let { return lastNode(it) }
        var node = start
        var parent = node.parent
        while (parent != null && parent.left === node) {
            node = parent
            parent = node.parent
        }
        return parent
    }

    private inner class ObjectIterator(index: Int) : MutableListIterator<JSONObject> {
        private var nextNode = if (index == size) null else nodeAt(index)
        private var cursor = index
        private var lastReturned: Node? = null
        private var expectedModCount = modCount

        private fun checkModification() {
            if (expectedModCount != modCount) throw ConcurrentModificationException()
        }

        override fun hasNext(): Boolean {
            checkModification()
            return nextNode != null
        }

        override fun hasPrevious(): Boolean {
            checkModification()
            return cursor > 0
        }

        override fun nextIndex(): Int = cursor

        override fun previousIndex(): Int = cursor - 1

        override fun next(): JSONObject {
            checkModification()
            val node = nextNode ?: throw NoSuchElementException()
            nextNode = successor(node)
            cursor++
            lastReturned = node
            return node.value
        }

        override fun previous(): JSONObject {
            checkModification()
            val node = (if (nextNode == null) lastNode(root) else predecessor(nextNode!!))
                ?: throw NoSuchElementException()
            nextNode = node
            cursor--
            lastReturned = node
            return node.value
        }

        override fun remove() {
            checkModification()
            val node = lastReturned ?: throw IllegalStateException()
            if (nextNode === node) nextNode = successor(node) else cursor--
            removeNode(node)
            expectedModCount = modCount
            lastReturned = null
        }

        override fun set(element: JSONObject) {
            checkModification()
            setNode(lastReturned ?: throw IllegalStateException(), element)
        }

        override fun add(element: JSONObject) {
            checkModification()
            this@IndexedObjectList.add(cursor, element)
            cursor++
            expectedModCount = modCount
            lastReturned = null
        }
    }
}
