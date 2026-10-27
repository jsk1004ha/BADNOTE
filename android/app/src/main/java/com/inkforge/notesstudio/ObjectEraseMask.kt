package com.inkforge.notesstudio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Object-local cutouts. Normalized coordinates travel with move, scale and rotation. */
internal object ObjectEraseMask {
    const val KEY = "eraseMask"

    fun erase(obj: JSONObject, centers: List<InkPoint>, radius: Float): JSONObject {
        if (radius <= 0 || obj.optBoolean("locked") || obj.optBoolean("hidden")) return obj
        val b = InkGeometry.unrotatedBounds(obj)
        val w = b.width.coerceAtLeast(1f); val h = b.height.coerceAtLeast(1f)
        val cx = (b.left+b.right)/2; val cy = (b.top+b.bottom)/2
        val angle = obj.f("rotation").toDouble(); val c = cos(angle); val s = sin(angle)
        val masks = JSONArray()
        obj.optJSONArray(KEY)?.let { old -> for (i in 0 until old.length()) masks.put(old.get(i)) }
        val count = masks.length()
        for (p in centers) {
            if (!InkGeometry.hit(obj,p,radius)) continue
            val x = (cx+(p.x-cx)*c+(p.y-cy)*s).toFloat()
            val y = (cy-(p.x-cx)*s+(p.y-cy)*c).toFloat()
            // Repeated DOWN/UP samples and circles already removed by a larger eraser are no-ops.
            val covered = (0 until masks.length()).any { i ->
                val m = masks.getJSONObject(i)
                val rx=m.f("rx")*w; val ry=m.f("ry")*h
                hypot(x-(b.left+m.f("x")*w),y-(b.top+m.f("y")*h))+radius <= min(rx,ry)+.001f
            }
            if (!covered) masks.put(json("x" to (x-b.left)/w,"y" to (y-b.top)/h,
                "rx" to radius/w,"ry" to radius/h))
        }
        if (count == masks.length()) return obj
        // Borrow immutable geometry; replacement/history own the new mask array.
        return JSONObject().apply {
            obj.keys().forEach { key -> put(key,obj.get(key)) }
            put(KEY,masks); put("revision",obj.optLong("revision")+1)
        }
    }
}
