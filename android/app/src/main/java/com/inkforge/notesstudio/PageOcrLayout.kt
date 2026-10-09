package com.inkforge.notesstudio

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Background-only page analysis. It never changes source strokes or inferred text. */
object PageOcrLayout {
    data class Stroke(val id: String, val source: JSONObject, val bounds: InkBounds,
                      val pointCount: Int, val length: Float, val originalIndex: Int,
                      val captureSeq: Long?, val captureSessionId: String?, val timeBasis: String,
                      val sourceStrokeId: String?, val fragmentOrder: Int)
    data class Invalid(val id: String, val reason: String)
    data class Region(val id: String, val role: String, val bounds: InkBounds,
                      val strokes: List<Stroke>, val order: Int, val timeMode: String,
                      val timeOrigin: Long, val reviewReasons: List<String>)
    data class Result(val regions: List<Region>, val nonTextPreserved: List<Stroke>,
                      val unresolved: List<Stroke>, val invalidInput: List<Invalid>,
                      val layoutAmbiguous: Boolean) {
        fun assertConserved(validIds: Set<String>) {
            val assigned = regions.flatMap { it.strokes.map(Stroke::id) } +
                nonTextPreserved.map(Stroke::id) + unresolved.map(Stroke::id)
            require(assigned.size == assigned.toSet().size && assigned.toSet() == validIds) { "OCR layout lost or duplicated a stroke" }
        }
    }

    fun analyze(objects: List<JSONObject>, width: Float, height: Float): Result {
        val valid = ArrayList<Stroke>()
        val invalid = ArrayList<Invalid>()
        val seen = HashSet<String>()
        objects.forEachIndexed { index, obj ->
            if (obj.optString("type") != "stroke") return@forEachIndexed
            val id = obj.optString("id")
            if (id.isBlank() || !seen.add(id)) { invalid += Invalid(id, "missingOrDuplicateId"); return@forEachIndexed }
            try { valid += scan(id, obj, index) }
            catch (e: IllegalArgumentException) { invalid += Invalid(id, e.message ?: "invalidPoint") }
        }
        val nonText = valid.filter { it.source.optBoolean("hidden") || it.source.optString("brush") == "highlighter" }
        val nonTextIds = nonText.mapTo(HashSet()) { it.id }
        val visible = valid.filterNot { it.id in nonTextIds }
        val heights = visible.map { it.bounds.height }.filter { it >= 3f && it < height * .08f }.sorted()
        val h = heights.getOrNull(heights.size / 2)?.coerceAtLeast(8f) ?: 18f
        val unresolved = visible.filter { it.bounds.height > max(height * .14f, h * 4f) || it.bounds.width > width * .9f }.toMutableList()
        val unresolvedIds = unresolved.mapTo(HashSet()) { it.id }
        val candidates = visible.filterNot { it.id in unresolvedIds }
        val columnSplit = splitColumns(candidates, width, height, h)
        val title = if (columnSplit != null) {
            val bodyTop = candidates.filter { it.bounds.top > min(height * .1f, h * 2f) &&
                (it.bounds.right < columnSplit * .9f || it.bounds.left > columnSplit * 1.1f) }
                .minOfOrNull { it.bounds.top } ?: 0f
            candidates.filter { it.bounds.bottom < bodyTop - h * .2f }
        } else emptyList()
        val titleIds = title.mapTo(HashSet()) { it.id }
        val main = candidates.filterNot { it.id in titleIds }
        val bodyCenters = main.filter { it.bounds.right < width * .7f }.map(::centerY).sorted()
        fun bodyNear(y: Float): Boolean {
            var low = 0; var high = bodyCenters.size
            while (low < high) { val mid = (low + high) ushr 1
                if (bodyCenters[mid] < y - h * 2) low = mid + 1 else high = mid }
            return low < bodyCenters.size && bodyCenters[low] < y + h * 2
        }
        val groups = if (columnSplit == null) listOf("body" to main)
            else listOf("left" to main.filter { centerX(it) < columnSplit },
                "right" to main.filter { centerX(it) >= columnSplit })
        val allLines = mutableListOf<Pair<String, List<Stroke>>>()
        if (title.isNotEmpty()) buildLines(title, h, width, unresolved).forEach { allLines += "title" to it }
        groups.forEach { (role, group) ->
            buildLines(group, h, width, unresolved).forEach { line ->
                val lineY = line.sumOf { centerY(it).toDouble() } / line.size
                val inferredRole = if (role == "body" && line.minOf { it.bounds.left } > width * .78f &&
                    bodyNear(lineY.toFloat())) "margin" else role
                allLines += inferredRole to line
            }
        }
        val ordered = allLines.sortedWith(compareBy<Pair<String, List<Stroke>>> { roleOrder(it.first) }
            .thenBy { it.second.minOf { stroke -> stroke.bounds.top } })
        val regions = ordered.mapIndexed { order, (role, members) ->
            knownRegion("line:${members.minBy(Stroke::originalIndex).id}", role, members, order)
        }
        val result = Result(regions, nonText, unresolved, invalid,
            unresolved.isNotEmpty() || columnSplit == null && regions.any { it.role == "margin" })
        result.assertConserved(valid.map(Stroke::id).toSet())
        return result
    }

    internal fun knownRegion(id: String, role: String, members: List<Stroke>, order: Int): Region {
            val sorted = members.sortedWith(compareBy<Stroke> { it.captureSeq ?: Long.MAX_VALUE }
                .thenBy { it.fragmentOrder }.thenBy { it.originalIndex })
            val bounds = bounds(members)
            val session = sorted.firstOrNull()?.captureSessionId
            var timeOrigin = 0L; var lastTime = 0L
            var hasTime = session != null && sorted.all { it.captureSessionId == session && it.timeBasis == "session-monotonic" }
            sorted.forEach { stroke ->
                val points = stroke.source.optJSONArray("points")!!
                for (i in 0 until points.length()) {
                    val time = points.getJSONObject(i).optLong("t")
                    if (time <= 0 || lastTime > time) hasTime = false
                    if (timeOrigin == 0L) timeOrigin = time
                    lastTime = time
                }
            }
            val reasons = buildList {
                if (sorted.any { it.captureSeq == null }) add("legacyOrder")
                if (!hasTime) add("timeBasisUnknown")
                if (role == "margin") add("marginalia")
            }
            return Region(id, role, bounds, sorted, order,
                if (hasTime) "relative" else "none", if (hasTime) timeOrigin else 0L, reasons)
    }

    /** Split only at a wide empty horizontal gap; otherwise leave the region unresolved. */
    internal fun splitForBudget(region: Region): List<Region>? {
        val bytes = region.strokes.map { it.source.toString().toByteArray(Charsets.UTF_8).size }
        val totalBytes = bytes.sumOf { it.toLong() }
        val totalPoints = region.strokes.sumOf { it.pointCount.toLong() }
        if (region.strokes.size > OcrInput.HARD_REGION_STROKES || totalPoints > OcrInput.HARD_REGION_POINTS ||
            totalBytes > OcrInput.HARD_REGION_BYTES) return null
        if (region.strokes.size <= OcrInput.SOFT_REGION_STROKES && totalPoints <= OcrInput.SOFT_REGION_POINTS &&
            totalBytes <= OcrInput.SOFT_REGION_BYTES) return listOf(region)
        val sorted = region.strokes.indices.sortedWith(compareBy<Int> { region.strokes[it].bounds.left }
            .thenBy { region.strokes[it].originalIndex })
        val heights = region.strokes.map { it.bounds.height }.sorted()
        val clearGap = max(36f, (heights.getOrNull(heights.size / 2) ?: 0f) * 2.2f)
        val cuts = HashSet<Int>()
        var right = region.strokes[sorted.first()].bounds.right
        for (i in 1 until sorted.size) {
            val stroke = region.strokes[sorted[i]]
            if (stroke.bounds.left - right >= clearGap) cuts += i
            right = max(right, stroke.bounds.right)
        }
        val parts = ArrayList<Region>()
        var start = 0
        while (start < sorted.size) {
            var count = 0; var points = 0; var partBytes = 0; var lastCut = -1; var end = start
            while (end < sorted.size) {
                val index = sorted[end]
                val next = region.strokes[index]
                if (count + 1 > OcrInput.SOFT_REGION_STROKES || points + next.pointCount > OcrInput.SOFT_REGION_POINTS ||
                    partBytes + bytes[index] > OcrInput.SOFT_REGION_BYTES) break
                count++; points += next.pointCount; partBytes += bytes[index]; end++
                if (end in cuts) lastCut = end
            }
            val stop = if (end == sorted.size) end else lastCut
            if (stop <= start) return null
            val chosen = sorted.subList(start, stop).mapTo(HashSet()) { region.strokes[it].id }
            val members = region.strokes.filter { it.id in chosen }
            parts += knownRegion("${region.id}/part${parts.size + 1}", region.role, members, region.order)
            start = stop
        }
        return parts
    }

    private fun scan(id: String, obj: JSONObject, index: Int): Stroke {
        val points = obj.optJSONArray("points") ?: throw IllegalArgumentException("missingPoints")
        require(points.length() > 0) { "emptyPoints" }
        var left = Float.POSITIVE_INFINITY; var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY; var bottom = Float.NEGATIVE_INFINITY
        var length = 0f; var previousX = 0f; var previousY = 0f
        for (i in 0 until points.length()) {
            val point = points.optJSONObject(i) ?: throw IllegalArgumentException("pointObject:$i")
            val x = OcrInput.number(point, "x").toFloat()
            val y = OcrInput.number(point, "y").toFloat()
            if (i > 0) length += hypot(x - previousX, y - previousY)
            left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
            previousX = x; previousY = y
        }
        return Stroke(id, obj, InkBounds(left, top, right, bottom), points.length(), length, index,
            obj.optLong("captureSeq").takeIf { obj.has("captureSeq") && it >= 0 },
            obj.optString("captureSessionId").ifBlank { null }, obj.optString("timeBasis", "unknown"),
            obj.optString("sourceStrokeId").ifBlank { null }, obj.optInt("fragmentOrder", 0))
    }

    private fun splitColumns(strokes: List<Stroke>, width: Float, height: Float, h: Float): Float? {
        val body = strokes.filter { it.bounds.top > min(height * .1f, h * 2) }
        if (body.size < 6) return null
        val left = body.filter { it.bounds.right < width * .46f }
        val right = body.filter { it.bounds.left > width * .54f }
        if (left.size < 3 || right.size < 3) return null
        val leftRows = left.map { (centerY(it) / (h * 1.8f)).toInt() }.toSet().size
        val rightRows = right.map { (centerY(it) / (h * 1.8f)).toInt() }.toSet().size
        if (leftRows < 3 || rightRows < 3) return null
        val overlapTop = max(left.minOf { it.bounds.top }, right.minOf { it.bounds.top })
        val overlapBottom = min(left.maxOf { it.bounds.bottom }, right.maxOf { it.bounds.bottom })
        if (overlapBottom - overlapTop < h * 3) return null
        val gapLeft = left.maxOf { it.bounds.right }; val gapRight = right.minOf { it.bounds.left }
        // A candidate gutter must be empty in the actual body, including strokes excluded
        // from the left/right samples. Otherwise continuous writing fabricates two columns.
        if (body.any { it.bounds.left < gapRight && it.bounds.right > gapLeft }) return null
        return if (gapRight - gapLeft >= width * .06f) (gapLeft + gapRight) / 2 else null
    }

    private fun buildLines(strokes: List<Stroke>, h: Float, pageWidth: Float,
                           unresolved: MutableList<Stroke>): List<List<Stroke>> {
        class Line(first: Stroke) {
            val members = mutableListOf(first)
            var box = first.bounds
            var sumY = PageOcrLayout.centerY(first).toDouble()
            val meanY: Float get() = (sumY / members.size).toFloat()
            fun add(stroke: Stroke) {
                members += stroke; sumY += PageOcrLayout.centerY(stroke)
                box = InkBounds(min(box.left, stroke.bounds.left), min(box.top, stroke.bounds.top),
                    max(box.right, stroke.bounds.right), max(box.bottom, stroke.bounds.bottom))
            }
        }
        val tiny = strokes.filter { it.bounds.height < h * .23f && it.bounds.width < h * .35f }
        val tinyIds = tiny.mapTo(HashSet()) { it.id }
        val regular = strokes.filterNot { it.id in tinyIds }.sortedWith(compareBy<Stroke> { centerY(it) }.thenBy { it.originalIndex })
        val lines = mutableListOf<Line>()
        val cells = HashMap<Int, MutableSet<Int>>()
        val cellHeight = max(8f, h * 2f)
        fun cell(y: Float) = kotlin.math.floor(y / cellHeight).toInt()
        fun candidateLines(y: Float): Set<Int> = ((cell(y)-1)..(cell(y)+1)).flatMap { cells[it].orEmpty() }.toSet()
        regular.forEach { stroke ->
            val y = centerY(stroke)
            val indices = candidateLines(y)
            if (indices.size > 16) { unresolved += stroke; return@forEach }
            val nearby = indices.map { it to lines[it] }.filter { (_, line) ->
                abs(y - line.meanY) <= h * .8f &&
                    horizontalGap(stroke.bounds, line.box) < pageWidth * .24f
            }
            val best = nearby.minByOrNull { (_, line) -> abs(y - line.meanY) }
            val index = if (best != null) best.first.also { lines[it].add(stroke) }
                else lines.size.also { lines += Line(stroke) }
            cells.getOrPut(cell(y)) { mutableSetOf() } += index
        }
        tiny.forEach { dot ->
            val indices = candidateLines(centerY(dot))
            if (indices.size > 16) { unresolved += dot; return@forEach }
            val nearby = indices.map { it to lines[it] }.filter { (_, line) ->
                abs(centerY(dot) - line.meanY) <= h * .6f &&
                    horizontalGap(dot.bounds, line.box) <= h * .5f
            }.sortedBy { (_, line) -> abs(centerY(dot) - line.meanY) }
            if (nearby.isEmpty() || nearby.size > 1 &&
                abs(centerY(dot) - nearby[0].second.meanY) + h * .1f >=
                abs(centerY(dot) - nearby[1].second.meanY)) unresolved += dot
            else lines[nearby.first().first].add(dot)
        }
        return lines.map { it.members }.sortedBy { bounds(it).top }
    }

    private fun roleOrder(role: String) = when (role) { "title" -> 0; "left", "body" -> 1; "right" -> 2; else -> 3 }
    private fun centerX(stroke: Stroke) = (stroke.bounds.left + stroke.bounds.right) / 2
    private fun centerY(stroke: Stroke) = (stroke.bounds.top + stroke.bounds.bottom) / 2
    private fun horizontalGap(a: InkBounds, b: InkBounds) = max(0f, max(a.left - b.right, b.left - a.right))
    private fun bounds(strokes: List<Stroke>) = InkBounds(strokes.minOf { it.bounds.left }, strokes.minOf { it.bounds.top },
        strokes.maxOf { it.bounds.right }, strokes.maxOf { it.bounds.bottom })
}
