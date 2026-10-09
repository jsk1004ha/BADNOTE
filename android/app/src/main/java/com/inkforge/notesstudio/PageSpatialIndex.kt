package com.inkforge.notesstudio

import org.json.JSONObject
import kotlin.math.floor

/** Page-local broad phase only. Callers still run exact stroke/shape geometry hit tests. */
class PageSpatialIndex(private val cell: Float = 96f) {
    private val buckets = HashMap<Long, MutableSet<String>>()
    private val wide = LinkedHashSet<String>()
    private val objects = HashMap<String, JSONObject>()
    private val occupied = HashMap<String, List<Long>>()
    private val bounds = HashMap<String, InkBounds>()
    private fun key(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
    private fun cells(box: InkBounds): List<Long>? {
        val minX = floor(box.left / cell).toInt(); val maxX = floor(box.right / cell).toInt()
        val minY = floor(box.top / cell).toInt(); val maxY = floor(box.bottom / cell).toInt()
        val columns=maxX.toLong()-minX+1;val rows=maxY.toLong()-minY+1
        if(columns<=0||rows<=0||columns>256||rows>256||columns*rows>256)return null
        return buildList { for (x in minX..maxX) for (y in minY..maxY) add(key(x, y)) }
    }
    fun rebuild(source: List<JSONObject>) {
        buckets.clear();wide.clear();objects.clear();occupied.clear();bounds.clear()
        source.forEach(::put)
    }
    fun put(obj: JSONObject) {
        val id = obj.optString("id");if (id.isBlank()) return
        remove(id)
        val box = InkGeometry.bounds(obj)
        if (listOf(box.left, box.top, box.right, box.bottom).any { !it.isFinite() }) return
        objects[id] = obj;bounds[id] = box
        val keys = cells(box)
        if (keys == null) wide += id
        else { occupied[id] = keys;keys.forEach { buckets.getOrPut(it) { LinkedHashSet() } += id } }
    }
    fun remove(id: String) {
        wide.remove(id);objects.remove(id);bounds.remove(id)
        occupied.remove(id)?.forEach { cellKey -> buckets[cellKey]?.let { bucket ->
            bucket.remove(id);if (bucket.isEmpty()) buckets.remove(cellKey)
        } }
    }
    fun query(box: InkBounds): List<JSONObject> {
        val ids = LinkedHashSet(wide)
        val keys = cells(box)
        if (keys == null) ids.addAll(objects.keys)
        else keys.forEach { buckets[it]?.let(ids::addAll) }
        return ids.mapNotNull { id -> if (bounds[id]?.overlaps(box) == true) objects[id] else null }
    }
}
