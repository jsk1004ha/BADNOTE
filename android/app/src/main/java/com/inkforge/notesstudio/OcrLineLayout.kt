package com.inkforge.notesstudio

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Geometry only: protect nearby components before deciding lines; never synthesize text. */
internal object OcrLineLayout {
    private const val NEIGHBOR_LIMIT = 24
    private const val BUCKET_LIMIT = 48
    private const val LINE_LIMIT = 32
    private const val MIN_SCALE = .001f
    private fun cx(b: InkBounds) = (b.left + b.right) / 2
    private fun cy(b: InkBounds) = (b.top + b.bottom) / 2
    private fun xGap(a: InkBounds, b: InkBounds) = max(0f, max(a.left - b.right, b.left - a.right))
    private fun yGap(a: InkBounds, b: InkBounds) = max(0f, max(a.top - b.bottom, b.top - a.bottom))
    private fun union(a: InkBounds, b: InkBounds) = InkBounds(min(a.left,b.left), min(a.top,b.top),
        max(a.right,b.right), max(a.bottom,b.bottom))
    private fun extent(b: InkBounds): Float = when {
        b.width > max(b.height, MIN_SCALE) * 6 -> b.height.coerceAtLeast(MIN_SCALE)
        else -> max(b.width,b.height).coerceAtLeast(MIN_SCALE)
    }
    private fun median(values: List<Float>, fallback: Float): Float = values.sorted().let {
        (it.getOrNull(it.size / 2) ?: fallback).coerceAtLeast(MIN_SCALE)
    }

    private class Component(val members: List<PageOcrLayout.Stroke>, val box: InkBounds, val scale: Float)

    /** Center cells and bounded buckets prevent dense or very long strokes producing quadratic work. */
    private class Neighbors(private val strokes: List<PageOcrLayout.Stroke>, private val cellSize: Float) {
        private val cells = HashMap<Pair<Int,Int>, MutableList<Int>>()
        var capped = false
            private set
        private fun key(b: InkBounds) = floor(cx(b)/cellSize).toInt() to floor(cy(b)/cellSize).toInt()
        init {
            strokes.forEachIndexed { i,s ->
                val bucket = cells.getOrPut(key(s.bounds)) { ArrayList() }
                if (bucket.size < BUCKET_LIMIT) bucket += i else capped = true
            }
        }
        fun around(index: Int): List<Int> {
            val box = strokes[index].bounds
            val (x,y) = key(box)
            val candidates = ArrayList<Int>()
            for (dx in -1..1) for (dy in -1..1) cells[x+dx to y+dy]?.let { candidates.addAll(it) }
            val nearest = candidates.filter { it != index }.sortedWith(compareBy<Int> {
                val other = strokes[it].bounds
                xGap(box,other) + yGap(box,other) + (abs(cx(box)-cx(other)) + abs(cy(box)-cy(other))) * .05f
            }.thenBy { strokes[it].originalIndex })
            if (nearest.size > NEIGHBOR_LIMIT) capped = true
            return nearest.take(NEIGHBOR_LIMIT)
        }
    }

    private fun protect(strokes: List<PageOcrLayout.Stroke>, baseScale: Float,
                        diagnostics: MutableSet<String>): List<Component> {
        if (strokes.isEmpty()) return emptyList()
        val index = Neighbors(strokes, baseScale * 1.5f)
        val neighbors = strokes.indices.map(index::around)
        val local = strokes.indices.map { i ->
            val own = extent(strokes[i].bounds)
            val nearby = neighbors[i].map { extent(strokes[it].bounds) }
                .filter { it >= own * .5f && it <= own * 2f }
            max(own,median(nearby,own)).coerceAtMost(max(own,baseScale * 1.5f))
        }
        if (index.capped) diagnostics += "neighborLimitReached"
        if (strokes.size == 1) diagnostics += "scaleUncertain"
        val parent = IntArray(strokes.size) { it }
        val boxes = strokes.map { it.bounds }.toMutableList()
        val scales = local.toMutableList()
        fun root(index: Int): Int {
            var r = index
            while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
            return r
        }
        strokes.indices.forEach { a -> neighbors[a].filter { it > a }.forEach pair@{ b ->
            val ra = root(a); val rb = root(b)
            if (ra == rb) return@pair
            val aa = strokes[a].bounds; val bb = strokes[b].bounds
            val scale = max(local[a],local[b])
            val padding = scale * .04f
            val xOverlap = min(aa.right+padding,bb.right+padding) - max(aa.left-padding,bb.left-padding)
            val yOverlap = min(aa.bottom+padding,bb.bottom+padding) - max(aa.top-padding,bb.top-padding)
            val connected = xOverlap > 0 && yGap(aa,bb) <= scale * .4f ||
                yOverlap >= min(aa.height+2*padding,bb.height+2*padding) * .45f && xGap(aa,bb) <= scale * .3f
            if (!connected) return@pair
            val joined = union(boxes[ra],boxes[rb])
            // Check the entire merged component, not just the connecting pair.
            if (joined.height > scale * 1.8f || joined.width > scale * 2.2f) return@pair
            parent[rb] = ra; boxes[ra] = joined; scales[ra] = max(scales[ra],scales[rb])
        } }
        val grouped = LinkedHashMap<Int,MutableList<PageOcrLayout.Stroke>>()
        strokes.forEachIndexed { i,s -> grouped.getOrPut(root(i)) { ArrayList() } += s }
        return grouped.map { (r,members) -> Component(members,boxes[r],max(boxes[r].height,scales[r])) }
    }

    fun build(strokes: List<PageOcrLayout.Stroke>, fallbackScale: Float, pageWidth: Float,
              unresolved: MutableList<PageOcrLayout.Stroke>, diagnostics: MutableSet<String>): List<List<PageOcrLayout.Stroke>> {
        val dimensions = strokes.map { extent(it.bounds) }.filter { it >= fallbackScale * .25f }
        val baseScale = median(dimensions,fallbackScale)
        fun small(stroke: PageOcrLayout.Stroke) = max(stroke.bounds.width,stroke.bounds.height) < baseScale * .23f
        fun crossbar(stroke: PageOcrLayout.Stroke) = stroke.bounds.height < baseScale * .2f &&
            stroke.bounds.width in (baseScale * .23f)..(baseScale * 1.6f)
        // A dash/crossbar can be written before its taller letters. Let actual glyph components
        // establish the line before attaching it; otherwise its zero height creates a false row.
        // A page containing only horizontal strokes still needs its original recognition path.
        val hasAnchors = strokes.any { !small(it) && !crossbar(it) }
        val marks = strokes.filter { small(it) || hasAnchors && crossbar(it) }
        val markIds = marks.mapTo(HashSet()) { it.id }
        val components = protect(strokes.filterNot { it.id in markIds },baseScale,diagnostics)
        class Line(first: Component) {
            val components = mutableListOf(first)
            val marks = ArrayList<PageOcrLayout.Stroke>()
            var box = first.box
            val scale = max(first.box.height,first.scale * .65f)
            fun add(component: Component) { this.components.add(component); box = union(box,component.box) }
        }
        val lines = ArrayList<Line>()
        val cells = HashMap<Int,MutableSet<Int>>()
        val cellHeight = baseScale * 2
        fun cell(y: Float) = floor(y/cellHeight).toInt()
        fun nearby(box: InkBounds): Set<Int> {
            val result = LinkedHashSet<Int>()
            val middle = cell(cy(box))
            for (key in middle-1..middle+1) cells[key]?.forEach { if (result.size <= LINE_LIMIT) result += it }
            return result
        }
        components.sortedWith(compareBy<Component> { cy(it.box) }.thenBy { it.members.minOf { s -> s.originalIndex } })
            .forEach { component ->
                val choices = nearby(component.box)
                if (choices.size > LINE_LIMIT) {
                    diagnostics += "lineCandidateLimitReached"
                    unresolved += component.members; return@forEach
                }
                val matches = choices.filter { i ->
                    val line = lines[i]
                    val overlap = min(line.box.bottom,component.box.bottom) - max(line.box.top,component.box.top)
                    val scale = max(line.scale,component.scale)
                    val sameBand = overlap >= min(line.box.height,component.box.height).coerceAtLeast(MIN_SCALE) * .35f ||
                        abs(cy(component.box)-cy(line.box)) <= min(line.scale,component.scale) * .55f
                    sameBand && union(line.box,component.box).height <= scale * 1.55f &&
                        xGap(line.box,component.box) < min(pageWidth * .24f,scale * 6f)
                }.sortedBy { abs(cy(component.box)-cy(lines[it].box)) }
                val chosen = matches.firstOrNull()
                val id = if (chosen == null) lines.size.also { lines += Line(component) }
                    else chosen.also { lines[it].add(component) }
                cells.getOrPut(cell(cy(component.box))) { LinkedHashSet() } += id
            }
        val componentCells = HashMap<Pair<Int,Int>,MutableList<Pair<Int,Component>>>()
        fun componentCell(box: InkBounds) = floor(cx(box)/(baseScale*1.5f)).toInt() to
            floor(cy(box)/(baseScale*1.5f)).toInt()
        lines.forEachIndexed { i,line -> line.components.forEach { component ->
            val bucket = componentCells.getOrPut(componentCell(component.box)) { ArrayList() }
            if (bucket.size < BUCKET_LIMIT) bucket += i to component else diagnostics += "neighborLimitReached"
        } }
        marks.forEach { mark ->
            val (x,y) = componentCell(mark.bounds)
            val candidates = ArrayList<Pair<Int,Component>>()
            for (dx in -1..1) for (dy in -1..1) componentCells[x+dx to y+dy]?.let { candidates.addAll(it) }
            val nearby = candidates.sortedBy { xGap(mark.bounds,it.second.box) + yGap(mark.bounds,it.second.box) }
                .take(NEIGHBOR_LIMIT)
            // Compare against actual components, never a long line's empty interior.
            val matches = nearby.mapNotNull { (i,nearest) ->
                val line = lines[i]
                val scale = line.scale
                if (xGap(mark.bounds,nearest.box) > scale * .6f || yGap(mark.bounds,nearest.box) > scale * .32f)
                    null else i to ((xGap(mark.bounds,nearest.box) + yGap(mark.bounds,nearest.box)) / scale)
            }.groupBy { it.first }.map { (_,values) -> values.minBy { it.second } }.sortedBy { it.second }
            if (matches.isEmpty() || matches.size > 1 && matches[1].second - matches[0].second <= .15f)
                unresolved += mark
            else lines[matches.first().first].marks += mark
        }
        return lines.map { line -> line.components.flatMap { it.members } + line.marks }
            .sortedBy { members -> members.minOf { it.bounds.top } }
    }
}
