package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

fun uid(prefix: String) = "${prefix}_${UUID.randomUUID()}"
fun JSONObject.copyJson() = JSONObject(toString())
fun JSONObject.f(key: String, fallback: Float = 0f) = optDouble(key, fallback.toDouble()).toFloat().takeIf { it.isFinite() } ?: fallback
fun JSONObject.array(key: String) = optJSONArray(key) ?: JSONArray()
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun json(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }

/** Binary references belong to these schema fields, never arbitrary note text. */
fun isAssetField(key: String) = key in setOf("src", "backgroundImage", "pdfSource", "dataUrl")

/** Remap links when imported pages receive globally unique database IDs. */
fun rewritePageReferences(value: Any?, pageIds: Map<String, String>) {
    when (value) {
        is JSONObject -> value.keys().asSequence().toList().forEach { key ->
            val child = value.opt(key)
            if (key in setOf("pageId", "lastPageId", "targetPageId") && child is String)
                pageIds[child]?.let { value.put(key, it) }
            else rewritePageReferences(child, pageIds)
        }
        is JSONArray -> (0 until value.length()).forEach { rewritePageReferences(value.opt(it), pageIds) }
    }
}

data class DocumentInfo(val id: String, val data: JSONObject) {
    val title get() = data.optString("title", "새 노트")
    val folder get() = data.optString("folderId", "root")
    val favorite get() = data.optBoolean("favorite")
    val trashed get() = data.optBoolean("trashed")
    fun continuous(default: Boolean) = when (data.optJSONObject("settings")?.optString("pageMode")) {
        "single" -> false
        "continuous" -> true
        else -> default
    }
    fun outlineEntries(pages: Sequence<NotePage>): List<JSONObject> {
        val entries = (data.optJSONArray("outlines") ?: data.array("outline")).objects().toMutableList()
        pages.forEachIndexed { index, page ->
            fun add(title: String, objectId: String = "") {
                if (title.isBlank()) return
                if (entries.any { it.optString("pageId") == page.id &&
                        it.optString("title", it.optString("text")) == title }) return
                entries += json("title" to title, "pageId" to page.id, "pageIndex" to index, "objectId" to objectId)
            }
            add(page.meta.optString("title"))
            page.objects.filter { it.optString("type") == "text" && it.f("fontSize") >= 30f }
                .forEach { add(it.optString("text").substringBefore('\n').take(60), it.optString("id")) }
        }
        return entries
    }
}

data class NotePage(val id: String, val meta: JSONObject, var objects: MutableList<JSONObject>) {
    val width get() = meta.f("width", 1000f).coerceIn(32f,30000f)
    val height get() = meta.f("height", 1414f).coerceIn(32f,30000f)
    fun json() = meta.copyJson().put("id", id).put("objects", JSONArray(objects))
    /** Adopt editor storage without replacing the page shared with its owner. Geometry references stay intact. */
    internal fun prepareForEditing():NotePage {
        if(objects !is IndexedObjectList)objects=IndexedObjectList(objects)
        return this
    }
    companion object {
        fun blank(template: String = "grid", width: Float = 1000f, height: Float = 1414f) =
            NotePage(uid("page"), json("template" to template, "width" to width, "height" to height,
                "createdAt" to System.currentTimeMillis()), mutableListOf())
        fun from(data: JSONObject): NotePage {
            val objects = data.array("objects").objects().map { it.copyJson().apply {
                if (optString("id").isEmpty()) put("id", uid("obj"))
            } }.toMutableList()
            val meta = metadataWithoutObjects(data, true)
            meta.put("width", meta.f("width", 1000f).coerceIn(32f, 30000f))
            meta.put("height", meta.f("height", 1414f).coerceIn(32f, 30000f))
            return NotePage(data.optString("id").ifBlank { uid("page") }, meta, objects)
        }

        /** Replay owns the parsed JSON for this call and never mutates its object tree. */
        internal fun fromBorrowed(data: JSONObject): NotePage {
            val array = data.array("objects")
            val objects = ArrayList<JSONObject>(array.length())
            for (index in 0 until array.length()) {
                val obj = requireNotNull(array.optJSONObject(index)) { "invalidReplayObject" }
                require(obj.optString("id").isNotBlank()) { "missingReplayObjectId" }
                objects.add(obj)
            }
            val meta = metadataWithoutObjects(data, false)
            meta.put("width", meta.f("width", 1000f).coerceIn(32f, 30000f))
            meta.put("height", meta.f("height", 1414f).coerceIn(32f, 30000f))
            return NotePage(data.optString("id").ifBlank { uid("page") }, meta, objects)
        }

        private fun metadataWithoutObjects(data: JSONObject, copyNested: Boolean): JSONObject = JSONObject().apply {
            val keys = data.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key == "objects") continue
                val value = data.get(key)
                put(key, if (copyNested) when (value) {
                    is JSONObject -> JSONObject(value.toString())
                    is JSONArray -> JSONArray(value.toString())
                    else -> value
                } else value)
            }
        }
    }
}

/** Only changed objects are retained by undo, never document/asset snapshots. */
data class ObjectChange(val before: List<JSONObject>, val after: List<JSONObject>,
                        val beforePositions: Map<String, Int> = emptyMap(),
                        val afterPositions: Map<String, Int> = emptyMap()) {
    fun reversed() = ObjectChange(after, before, afterPositions, beforePositions)
    // Shared changes may be queued more than once; serialize their immutable trees only once.
    val bytes:Int by lazy {
        (before.sumOf{it.toString().length.toLong()*2}+after.sumOf{it.toString().length.toLong()*2})
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    fun apply(page: NotePage) = apply(page, copyAfter = true)
    /** Adopt fresh edit-owned objects. Subsequent editor changes must replace them, never mutate their geometry. */
    internal fun applyOwnedAfter(page: NotePage) = apply(page, copyAfter = false)
    private fun apply(page: NotePage, copyAfter: Boolean) {
        val remove = (before + after).mapTo(HashSet()) { it.optString("id") }
        val indexed=page.objects as? IndexedObjectList
        if(indexed!=null)for(id in remove){val position=indexed.indexOfId(id);if(position>=0)indexed.removeAt(position)}
        else page.objects.removeAll { it.optString("id") in remove }
        after.sortedBy { afterPositions[it.optString("id")] ?: Int.MAX_VALUE }.forEach {
            val position = (afterPositions[it.optString("id")] ?: page.objects.size).coerceIn(0, page.objects.size)
            page.objects.add(position, if (copyAfter) it.copyJson() else it)
        }
    }
}

/** One low-priority worker bounds concurrent JSON sizing across every page's history. */
private object HistorySizingWorker {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ink-history-sizing").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    fun execute(task: () -> Unit) { executor.execute(task) }
}

class EditHistory internal constructor(
    private val limit: Int,
    private val scheduleSizing: (() -> Unit) -> Unit
) {
    constructor(limit: Int = 16 * 1024 * 1024) : this(limit, HistorySizingWorker::execute)

    private enum class State { UNDO, REDO, DISCARDED }
    private class Entry(val change: ObjectChange, var charge: Int? = null) {
        @Volatile var state = State.UNDO
        val ids = HashSet<String>().apply {
            for (obj in change.before) add(obj.optString("id"))
            for (obj in change.after) add(obj.optString("id"))
        }
    }

    private val undo = ArrayDeque<Entry>()
    private val redo = ArrayDeque<Entry>()
    private var bytes = 0L
    private var pendingSizes = 0
    private var evictionPending = false
    private val references = HashMap<String, Int>()
    private val discarded = HashSet<String>()
    internal val retainedObjectIds: Set<String> get() = references.keys

    private fun reference(entry: Entry, delta: Int) {
        for (id in entry.ids) {
            val count = (references[id] ?: 0) + delta
            if (count == 0) {
                references.remove(id)
                discarded.add(id)
            } else {
                references[id] = count
                discarded.remove(id)
            }
        }
    }

    private fun discard(entry: Entry) {
        entry.state = State.DISCARDED
        if (entry.charge == null) pendingSizes--
        reference(entry, -1)
    }

    internal fun takeDiscardedObjectIds(): Set<String> {
        if (discarded.isEmpty()) return emptySet()
        return HashSet(discarded).also { discarded.clear() }
    }

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    private fun append(entry: Entry) {
        for (previous in redo) discard(previous)
        redo.clear()
        undo.addLast(entry)
        reference(entry, 1)
        bytes += entry.charge ?: 0
        if (entry.charge == null) pendingSizes++
        evictionPending = true
    }

    private fun evictMeasured() {
        // Unknown charges are not estimates. Wait even for redo entries so callback order cannot
        // change a pending push's eviction decision. Extra history is retained only temporarily.
        if (!evictionPending || pendingSizes != 0) return
        evictionPending = false
        while (bytes > limit && undo.size > 1) {
            val removed = undo.removeFirst()
            bytes -= requireNotNull(removed.charge)
            discard(removed)
        }
    }

    /** Legacy callers retain synchronous, exact serialized-byte charging and push-only eviction. */
    fun push(change: ObjectChange) {
        append(Entry(change, change.bytes))
        evictMeasured()
    }

    /**
     * Retains immutable edit-owned JSON immediately, without serializing it on the caller thread.
     * [dispatch] must post completion back to the thread owning this history. Immediate branch
     * removals remain available through [takeDiscardedObjectIds]; late removals are drained into
     * [onDiscarded]. Undo/redo move entries and their measured charges, never reading change.bytes.
     */
    internal fun pushDeferred(
        change: ObjectChange,
        dispatch: (() -> Unit) -> Unit,
        onDiscarded: (Set<String>) -> Unit
    ) {
        val entry = Entry(change)
        append(entry)
        scheduleSizing {
            if (entry.state != State.DISCARDED) {
                val charge = change.bytes
                dispatch {
                    if (entry.state != State.DISCARDED) {
                        entry.charge = charge
                        pendingSizes--
                        if (entry.state == State.UNDO) bytes += charge
                        evictMeasured()
                        val removed = takeDiscardedObjectIds()
                        if (removed.isNotEmpty()) onDiscarded(removed)
                    }
                }
            }
        }
    }

    fun undo(): ObjectChange? {
        val entry = undo.removeLastOrNull() ?: return null
        bytes -= entry.charge ?: 0
        entry.state = State.REDO
        redo.addLast(entry)
        return entry.change.reversed()
    }

    fun redo(): ObjectChange? {
        val entry = redo.removeLastOrNull() ?: return null
        entry.state = State.UNDO
        undo.addLast(entry)
        bytes += entry.charge ?: 0
        return entry.change
    }

    fun clear() {
        for (entry in undo) entry.state = State.DISCARDED
        for (entry in redo) entry.state = State.DISCARDED
        discarded.addAll(references.keys)
        references.clear()
        undo.clear()
        redo.clear()
        bytes = 0
        pendingSizes = 0
        evictionPending = false
    }
}
