package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun uid(prefix: String) = "${prefix}_${UUID.randomUUID()}"
fun JSONObject.copyJson() = JSONObject(toString())
fun JSONObject.f(key: String, fallback: Float = 0f) = optDouble(key, fallback.toDouble()).toFloat().takeIf { it.isFinite() } ?: fallback
fun JSONObject.array(key: String) = optJSONArray(key) ?: JSONArray()
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun json(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }

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
}

data class NotePage(val id: String, val meta: JSONObject, val objects: MutableList<JSONObject>) {
    val width get() = meta.f("width", 1000f).coerceIn(32f,30000f)
    val height get() = meta.f("height", 1414f).coerceIn(32f,30000f)
    fun json() = meta.copyJson().put("id", id).put("objects", JSONArray(objects))
    companion object {
        fun blank(template: String = "grid", width: Float = 1000f, height: Float = 1414f) =
            NotePage(uid("page"), json("template" to template, "width" to width, "height" to height,
                "createdAt" to System.currentTimeMillis()), mutableListOf())
        fun from(data: JSONObject): NotePage {
            val objects = data.array("objects").objects().map { it.copyJson().apply {
                if (optString("id").isEmpty()) put("id", uid("obj"))
            } }.toMutableList()
            val meta = data.copyJson().apply { remove("objects") }
            meta.put("width", meta.f("width", 1000f).coerceIn(32f, 30000f))
            meta.put("height", meta.f("height", 1414f).coerceIn(32f, 30000f))
            return NotePage(data.optString("id").ifBlank { uid("page") }, meta, objects)
        }
    }
}

/** Only changed objects are retained by undo, never document/asset snapshots. */
data class ObjectChange(val before: List<JSONObject>, val after: List<JSONObject>,
                        val beforePositions: Map<String, Int> = emptyMap(),
                        val afterPositions: Map<String, Int> = emptyMap()) {
    fun reversed() = ObjectChange(after, before, afterPositions, beforePositions)
    val bytes get() = before.sumOf { it.toString().length * 2 } + after.sumOf { it.toString().length * 2 }
    fun apply(page: NotePage) {
        val remove = (before + after).map { it.optString("id") }.toSet()
        page.objects.removeAll { it.optString("id") in remove }
        after.sortedBy { afterPositions[it.optString("id")] ?: Int.MAX_VALUE }.forEach {
            val position = (afterPositions[it.optString("id")] ?: page.objects.size).coerceIn(0, page.objects.size)
            page.objects.add(position, it.copyJson())
        }
    }
}

class EditHistory(private val limit: Int = 16 * 1024 * 1024) {
    private val undo = ArrayDeque<ObjectChange>()
    private val redo = ArrayDeque<ObjectChange>()
    private var bytes = 0
    val canUndo get()=undo.isNotEmpty()
    val canRedo get()=redo.isNotEmpty()
    fun push(change: ObjectChange) {
        redo.clear(); undo.addLast(change); bytes += change.bytes
        while (bytes > limit && undo.size > 1) bytes -= undo.removeFirst().bytes
    }
    fun undo(): ObjectChange? = undo.removeLastOrNull()?.also { bytes -= it.bytes; redo.addLast(it) }?.reversed()
    fun redo(): ObjectChange? = redo.removeLastOrNull()?.also { undo.addLast(it); bytes += it.bytes }
    fun clear() { undo.clear(); redo.clear(); bytes = 0 }
}
