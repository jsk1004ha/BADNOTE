package com.inkforge.notesstudio

import com.inkforge.ocr.contracts.MathAvailability
import com.inkforge.ocr.contracts.MathRecognitionEngine
import org.json.JSONObject
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Spatial composition of real English text-model hypotheses, not a dedicated math model. */
object OcrMathLayout : MathRecognitionEngine {
    override val availability = MathAvailability.SUPPORTED_SUBSET
    const val ENGINE_ID = "mlkit-en-spatial-math-subset-v2"
    const val SUPPORTED_SUBSET_EXPLANATION = "English ML Kit text candidates with spatial fractions and " +
        "superscripts; supports Latin variables, numbers, arithmetic and parentheses on separate lines. " +
        "Not a dedicated mathematics model; every result requires review and has no math confidence score."
    private const val MAX_DEPTH = 6
    private const val MAX_LINES = 64
    private const val MAX_REGIONS = 128
    private const val MAX_CANDIDATES = 3

    data class Line(val region: PageOcrLayout.Region, val candidates: List<String>,
                    val reviewReasons: List<String>, val errorCode: String? = null)

    fun recognize(objects: List<JSONObject>, width: Float, height: Float,
                  recognize: (PageOcrLayout.Region) -> List<String>,
                  checkCurrent: () -> Unit = {}): List<Line> {
        checkCurrent()
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0) { "mathInvalidPageSize" }
        // Filtering only selects references. Original JSON, points and capture metadata are never written.
        val originalIndices = HashMap<String, Int>()
        val visible = ArrayList<JSONObject>()
        objects.forEachIndexed { index, source ->
            checkCurrent()
            if (source.optString("type") == "stroke" && !source.optBoolean("hidden") &&
                source.optString("brush") != "highlighter" && source.optDouble("opacity", 1.0) > 0 &&
                (source.optJSONObject("settings")?.optDouble("opacity", 1.0) ?: 1.0) > 0) {
                visible += source
                originalIndices[source.optString("id")] = index
            }
        }
        // Bound structural work by pen-up count. Dense source points are not SDK payload:
        // they are simplified only after fractions, powers and rows have been fitted.
        require(visible.size <= OcrInput.HARD_REGION_STROKES) { "mathInputBudgetExceeded" }
        val analysis = PageOcrLayout.analyze(visible, width, height)
        checkCurrent()
        require(analysis.invalidInput.isEmpty()) { "mathInvalidInput:${analysis.invalidInput.first().reason}" }
        val strokes = (analysis.regions.flatMap { it.strokes } + analysis.unresolved).map { stroke ->
            val index = originalIndices.getValue(stroke.id)
            if (stroke.originalIndex == index) stroke else stroke.copy(originalIndex = index)
        }.sortedBy { it.originalIndex }
        if (strokes.isEmpty()) return emptyList()
        return Session(recognize, checkCurrent).recognize(strokes)
    }

    private sealed class Part {
        data class Text(val strokes: List<PageOcrLayout.Stroke>) : Part()
        data class Fraction(val numerator: Part, val denominator: Part) : Part()
        data class Power(val prefix: Part, val exponent: Part) : Part()
        data class Sequence(val parts: List<Part>) : Part()
    }
    private data class MathUnit(val part: Part, val strokes: List<PageOcrLayout.Stroke>, val box: InkBounds)
    private class Band(first: MathUnit) {
        val units = arrayListOf(first)
        val anchor = first.box
        var box = first.box
        fun add(unit: MathUnit) { units += unit; box = union(box, unit.box) }
    }
    private data class Row(val part: Part, val strokes: List<PageOcrLayout.Stroke>, val reasons: List<String>)
    private data class Hypotheses(val candidates: List<String>, val composableCandidates: Set<String>) {
        val composable get() = candidates.firstOrNull()?.let { it in composableCandidates } == true
    }

    private class Session(private val model: (PageOcrLayout.Region) -> List<String>,
                          private val checkCurrent: () -> Unit) {
        private var requests = 0
        private var layouts = 0

        fun recognize(strokes: List<PageOcrLayout.Stroke>): List<Line> {
            val rows = layout(strokes, 0)
            require(rows.size <= MAX_LINES) { "mathLineLimitExceeded" }
            return rows.mapIndexed { order, row ->
                checkCurrent()
                val region = PageOcrLayout.knownRegion("math:${row.strokes.minBy { it.originalIndex }.id}",
                    "displayMath", row.strokes, order)
                val reasons = linkedSetOf("mathSupportedSubset")
                reasons.addAll(region.reviewReasons)
                reasons.addAll(row.reasons)
                try {
                    val result = evaluate(row.part, region, reasons)
                    val candidates = if (row.part !is Part.Text && !result.composable) {
                        reasons += "mathStructureUnresolved"
                        native(region, reasons).candidates
                    } else if (row.part is Part.Text) result.candidates
                        else result.candidates.filter { it in result.composableCandidates }
                    checkCurrent()
                    Line(region, candidates, reasons.toList())
                } catch (error: Exception) {
                    if (error is CancellationException || error is InterruptedException ||
                        error.message == "cancelled" || error.message == "stalePage") throw error
                    checkCurrent()
                    reasons += "mathModelFailure"
                    // Keep other rows usable without presenting this row as successful math.
                    Line(region, emptyList(), reasons.toList(), error.message ?: "mathRecognitionFailed")
                }
            }
        }

        private fun layout(strokes: List<PageOcrLayout.Stroke>, depth: Int): List<Row> {
            checkCurrent()
            if (depth >= MAX_DEPTH) return listOf(Row(Part.Text(strokes), strokes, listOf("mathLayoutDepthExceeded")))
            if (layouts++ >= MAX_REGIONS * 4)
                return listOf(Row(Part.Text(strokes), strokes, listOf("mathLayoutBudgetExceeded")))
            val scale = characteristicHeight(strokes)
            val remaining = strokes.toMutableList()
            val units = ArrayList<MathUnit>()
            val ambiguous = HashSet<String>()
            val deferredReasons = HashMap<String, List<String>>()
            // Outer bars first; bars without real content on both sides remain model input.
            for (bar in strokes.filter(::horizontalBar).sortedByDescending { it.bounds.width }) {
                checkCurrent()
                if (remaining.none { it.id == bar.id }) continue
                val b = bar.bounds
                val y = cy(b)
                val reach = max(scale * 1.7f, b.width * 1.1f)
                val aligned = remaining.filter { candidate ->
                    val a = candidate.bounds
                    candidate.id != bar.id && cx(a) in (b.left - scale * .12f)..(b.right + scale * .12f) &&
                        min(a.right, b.right) - max(a.left, b.left) >= min(a.width, b.width) * .5f &&
                        abs(cy(a) - y) <= reach
                }
                val clearance = max(thickness(bar), b.height) * .5f
                val above = aligned.filter { it.bounds.bottom < y - clearance }
                val below = aligned.filter { it.bounds.top > y + clearance }
                if (above.isEmpty() || below.isEmpty() || above.all(::horizontalBar) || below.all(::horizontalBar)) continue
                val parallel = aligned.any { horizontalBar(it) && abs(cy(it.bounds) - y) <= scale * .3f &&
                    min(it.bounds.right, b.right) - max(it.bounds.left, b.left) >= min(it.bounds.width, b.width) * .8f }
                val crossing = aligned.any { it.bounds.top <= y + clearance && it.bounds.bottom >= y - clearance }
                val top = bounds(above)
                val bottom = bounds(below)
                if (parallel || crossing || top.width > b.width * 1.6f || bottom.width > b.width * 1.6f ||
                    top.height > reach || bottom.height > reach) {
                    ambiguous += bar.id
                    continue
                }
                val numerator = layout(above, depth + 1)
                val denominator = layout(below, depth + 1)
                if (numerator.size != 1 || denominator.size != 1 ||
                    numerator.single().reasons.isNotEmpty() || denominator.single().reasons.isNotEmpty()) {
                    ambiguous += bar.id
                    deferredReasons[bar.id] = (numerator + denominator).flatMap { it.reasons }
                    continue
                }
                val all = above + bar + below
                units += MathUnit(Part.Fraction(numerator.single().part, denominator.single().part), all, bounds(all))
                val ids = all.mapTo(HashSet()) { it.id }
                remaining.removeAll { it.id in ids }
            }
            units += connectedUnits(remaining)
            val bands = ArrayList<Band>()
            for (unit in units.sortedByDescending { it.box.height }) {
                checkCurrent()
                val b = unit.box
                val matches = bands.filter { band ->
                    val a = band.anchor // Never grow a row transitively through the next row.
                    val h = max(a.height, b.height).coerceAtLeast(.001f)
                    val ordinary = (yGap(a, b) == 0f || abs(cy(a) - cy(b)) <= h * .65f && yGap(a, b) <= h * .25f) &&
                        union(a, b).height <= h * 1.8f
                    ordinary || band.units.any { raisedNeighbour(it.box, b) || raisedNeighbour(b, it.box) }
                }
                val best = matches.minByOrNull { abs(cy(it.anchor) - cy(b)) }
                if (best == null) bands += Band(unit) else best.add(unit)
            }
            return bands.sortedWith(compareBy<Band> { it.box.top }.thenBy { it.box.left }).map { band ->
                val reasons = LinkedHashSet<String>()
                if (band.units.any { unit -> unit.strokes.any { it.id in ambiguous } }) reasons += "mathAmbiguousLayout"
                val members = band.units.flatMap { it.strokes }
                members.forEach { deferredReasons[it.id]?.let(reasons::addAll) }
                Row(rowPart(band.units, depth, reasons), members, reasons.toList())
            }
        }

        private fun connectedUnits(strokes: List<PageOcrLayout.Stroke>): List<MathUnit> {
            val parents = IntArray(strokes.size) { it }
            fun root(index: Int): Int {
                var r = index
                while (parents[r] != r) { parents[r] = parents[parents[r]]; r = parents[r] }
                return r
            }
            // Only endpoint contacts join pen-up strokes. No resampling or source-point copies.
            val sorted = strokes.indices.sortedBy { strokes[it].bounds.left }
            val active = ArrayList<Int>()
            val tolerance = strokes.maxOfOrNull(::thickness)?.times(1.5f) ?: 0f
            for (i in sorted) {
                checkCurrent()
                val a = strokes[i]
                active.removeAll { strokes[it].bounds.right + tolerance < a.bounds.left }
                for (j in active) {
                    val b = strokes[j]
                    val local = max(thickness(a), thickness(b)) * 1.5f
                    if (xGap(a.bounds, b.bounds) > local || yGap(a.bounds, b.bounds) > local) continue
                    val ap = a.source.getJSONArray("points")
                    val bp = b.source.getJSONArray("points")
                    var touching = false
                    for (ai in 0..1) for (bi in 0..1) {
                        val aa = ap.getJSONObject(if (ai == 0) 0 else ap.length() - 1)
                        val bb = bp.getJSONObject(if (bi == 0) 0 else bp.length() - 1)
                        val dx = aa.getDouble("x") - bb.getDouble("x")
                        val dy = aa.getDouble("y") - bb.getDouble("y")
                        if (dx * dx + dy * dy <= local * local) touching = true
                    }
                    if (touching) parents[root(i)] = root(j)
                }
                active += i
            }
            val groups = LinkedHashMap<Int, MutableList<PageOcrLayout.Stroke>>()
            strokes.forEachIndexed { i, stroke -> groups.getOrPut(root(i)) { ArrayList() } += stroke }
            return groups.values.map { MathUnit(Part.Text(it), it, bounds(it)) }
        }

        private fun rowPart(units: List<MathUnit>, depth: Int, reasons: MutableSet<String>): Part {
            val actualHeight = units.maxOf { it.box.height }
            val height = actualHeight.coerceAtLeast(.001f)
            // A numerical denominator floor must not exclude all real zero-height baseline ink.
            val bottom = units.maxOf { if (it.box.height >= actualHeight * .8f) it.box.bottom else Float.NEGATIVE_INFINITY }
            val elevated = units.filter { it.part is Part.Text && it.box.height <= height * .7f &&
                it.box.bottom < bottom - height * .42f }
            // A tiny real digit is allowed, but a lone dot/crossbar is not invented into an exponent.
            val cores = elevated.filter { it.box.height >= height * .08f }
            val raised = elevated.filter { it in cores || cores.any { core ->
                xGap(it.box, core.box) <= height * .3f && yGap(it.box, core.box) <= height * .3f
            } }
            val baseline = units.filterNot { it in raised }.sortedBy { it.box.left }
            if (elevated.any { it !in raised && baseline.any { base -> raisedNeighbour(base.box, it.box) } })
                reasons += "mathAmbiguousLayout"
            if (baseline.isEmpty() || raised.isEmpty()) return sequence(units.sortedBy { it.box.left }.map { it.part })
            val groups = ArrayList<Band>()
            for (unit in raised.sortedBy { it.box.left }) {
                val previous = groups.lastOrNull()
                val crossedBase = previous != null && baseline.any {
                    it.box.left > previous.box.left && it.box.left < unit.box.left && it.box.right > previous.box.right }
                if (previous != null && !crossedBase && xGap(previous.box, unit.box) <= height * .3f &&
                    yGap(previous.box, unit.box) <= height * .3f) previous.add(unit) else groups += Band(unit)
            }
            val assignments = LinkedHashMap<MathUnit, MutableList<PageOcrLayout.Stroke>>()
            val unattached = ArrayList<MathUnit>()
            for (group in groups) {
                val b = group.box
                val base = baseline.lastOrNull { it.box.left < b.left && b.left - it.box.right <= height * .75f &&
                    b.left >= it.box.left + it.box.width * .35f && yGap(it.box, b) <= height * .6f }
                if (base == null) { unattached.addAll(group.units); reasons += "mathAmbiguousLayout" }
                else assignments.getOrPut(base) { ArrayList() }.addAll(group.units.flatMap { it.strokes })
            }
            val parts = ArrayList<Part>()
            val pending = ArrayList<PageOcrLayout.Stroke>()
            fun flush() { if (pending.isNotEmpty()) { parts += Part.Text(pending.toList()); pending.clear() } }
            for (unit in (baseline + unattached).sortedBy { it.box.left }) {
                checkCurrent()
                if (unit.part is Part.Text) pending.addAll(unit.strokes) else { flush(); parts += unit.part }
                val exponent = assignments[unit] ?: continue
                val exponentRows = layout(exponent, depth + 1)
                if (exponentRows.size != 1 || exponentRows.single().reasons.isNotEmpty()) {
                    reasons += "mathAmbiguousLayout"
                    reasons.addAll(exponentRows.flatMap { it.reasons })
                    return Part.Text(units.flatMap { it.strokes })
                }
                flush()
                val prefix = sequence(parts)
                parts.clear()
                parts += Part.Power(prefix, exponentRows.single().part)
            }
            flush()
            return sequence(parts)
        }

        private fun evaluate(part: Part, line: PageOcrLayout.Region, reasons: MutableSet<String>,
                             fragment: Boolean = false): Hypotheses {
            checkCurrent()
            val result = when (part) {
                is Part.Text -> native(PageOcrLayout.knownRegion("${line.id}/text:${part.strokes.minBy { it.originalIndex }.id}",
                    line.role, part.strokes, line.order), reasons, fragment)
                is Part.Fraction -> combine(listOf(evaluate(part.numerator, line, reasons),
                    evaluate(part.denominator, line, reasons))) { "((${it[0]})/(${it[1]}))" }
                is Part.Power -> combine(listOf(evaluate(part.prefix, line, reasons, true),
                    evaluate(part.exponent, line, reasons))) { power(it[0], it[1]) }
                is Part.Sequence -> combine(part.parts.map { evaluate(it, line, reasons, true) }) { it.joinToString("") }
            }
            if (!fragment && part !is Part.Text) {
                val accepted = result.candidates.filterTo(LinkedHashSet(), ::supported)
                if (accepted.size != result.candidates.size) {
                    if (result.candidates.any { !balanced(it) }) reasons += "mathUnbalancedParentheses"
                    reasons += "mathUnsupportedNotation"
                }
                return Hypotheses(result.candidates, accepted)
            }
            return result
        }

        private fun native(region: PageOcrLayout.Region, reasons: MutableSet<String>, fragment: Boolean = false): Hypotheses {
            checkCurrent()
            if (requests >= MAX_REGIONS) {
                reasons += "mathRecognitionBudgetExceeded"
                return Hypotheses(emptyList(), emptySet())
            }
            requests++
            val raw = try { model(region) } catch (error: OcrInkGeometry.InputLimitException) {
                checkCurrent()
                val parts = splitModelInput(region)
                if (parts == null) {
                    reasons += error.reason
                    return Hypotheses(emptyList(), emptySet())
                }
                reasons += "mathModelInputSplit"
                val joined = combine(parts.map { native(it, reasons, true) }) { it.joinToString("") }
                if (fragment) return joined
                val accepted = joined.candidates.filterTo(LinkedHashSet(), ::supported)
                if (accepted.size != joined.candidates.size) reasons += "mathUnsupportedNotation"
                return Hypotheses(joined.candidates, accepted)
            }
            checkCurrent() // A stale callback must not escape as a usable line.
            val candidates = raw.asSequence().map(::normalize).filter { it.isNotBlank() }.distinct().take(MAX_CANDIDATES).toList()
            if (candidates.isEmpty()) reasons += "mathNoCandidate"
            if (candidates.size > 1) reasons += "mathCandidateAmbiguous"
            val accepted = LinkedHashSet<String>()
            for (candidate in candidates) {
                val acceptedCandidate = if (fragment) supportedTokens(candidate) else supported(candidate)
                if (!fragment && !balanced(candidate)) reasons += "mathUnbalancedParentheses"
                if (acceptedCandidate) accepted += candidate else reasons += "mathUnsupportedNotation"
            }
            // Keep unsupported native alternatives visible, but compose each supported hypothesis
            // independently. A lower-ranked unsupported token must not invalidate the native top.
            return Hypotheses(candidates, accepted)
        }

        /** Only cut empty horizontal space between complete source strokes, never point chunks.
         * This is evaluated after spatial structure, so a fraction or power is not flattened. */
        private fun splitModelInput(region: PageOcrLayout.Region): List<PageOcrLayout.Region>? {
            val sorted = region.strokes.sortedWith(compareBy<PageOcrLayout.Stroke> { it.bounds.left }
                .thenBy { it.originalIndex })
            val clearance = max(region.bounds.height * .12f, sorted.maxOf(::thickness) * 2f)
            var right = sorted.first().bounds.right
            var cut = -1
            for (index in 1 until sorted.size) {
                checkCurrent()
                val next = sorted[index]
                if (next.bounds.left - right > clearance &&
                    (cut < 0 || abs(index - sorted.size / 2) < abs(cut - sorted.size / 2))) cut = index
                right = max(right, next.bounds.right)
            }
            if (cut < 0) return null
            return listOf(sorted.subList(0, cut), sorted.subList(cut, sorted.size)).mapIndexed { index, strokes ->
                PageOcrLayout.knownRegion("${region.id}/input${index + 1}", region.role, strokes, region.order)
            }
        }

        private fun combine(parts: List<Hypotheses>, join: (List<String>) -> String?): Hypotheses {
            if (parts.isEmpty() || parts.any { !it.composable }) return Hypotheses(emptyList(), emptySet())
            val top = parts.map { it.candidates.first() }
            val candidates = LinkedHashSet<String>()
            fun add(values: List<String>): Boolean {
                checkCurrent()
                val text = join(values) ?: return false
                candidates += text
                return true
            }
            if (!add(top)) return Hypotheses(emptyList(), emptySet())
            // Native top plus one-region-at-a-time alternatives, never a Cartesian product or a score.
            for (rank in 1 until MAX_CANDIDATES) for (index in parts.indices) {
                if (candidates.size >= MAX_CANDIDATES) break
                if (rank < parts[index].candidates.size && parts[index].candidates[rank] in parts[index].composableCandidates) {
                    val variant = top.toMutableList()
                    variant[index] = parts[index].candidates[rank]
                    add(variant)
                }
            }
            return Hypotheses(candidates.toList(), candidates)
        }
    }

    private fun sequence(parts: List<Part>): Part {
        if (parts.size == 1) return parts.single()
        val merged = ArrayList<Part>()
        val pending = ArrayList<PageOcrLayout.Stroke>()
        fun flush() { if (pending.isNotEmpty()) { merged += Part.Text(pending.toList()); pending.clear() } }
        for (part in parts) {
            if (part is Part.Text) pending.addAll(part.strokes) else { flush(); merged += part }
        }
        flush()
        return if (merged.size == 1) merged.single() else Part.Sequence(merged)
    }
    private fun normalize(text: String) = text.trim().replace('×', '*').replace('·', '*').replace('÷', '/').replace('−', '-')
    private fun balanced(text: String): Boolean {
        var depth = 0
        for (char in text) {
            if (char == '(') depth++
            if (char == ')' && --depth < 0) return false
        }
        return depth == 0
    }

    private fun supportedTokens(text: String) = text.isNotBlank() && text.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in ".+-*/^=() \t"
    }

    /** Validation only: no evaluation, letter/digit substitution, function guessing or token insertion. */
    private fun supported(text: String): Boolean {
        if (!balanced(text) || text.isBlank()) return false
        var operand = true
        var atom = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == ' ' || char == '\t' -> Unit
                char in '0'..'9' || char == '.' -> {
                    var digits = 0
                    var dots = 0
                    while (index < text.length && (text[index] in '0'..'9' || text[index] == '.')) {
                        if (text[index] == '.') dots++ else digits++
                        index++
                    }
                    if (digits == 0 || dots > 1) return false
                    operand = false; atom = true
                    continue
                }
                char in 'a'..'z' || char in 'A'..'Z' -> { operand = false; atom = true }
                char == '(' -> operand = true
                char == ')' -> { if (operand) return false; operand = false }
                char == '+' || char == '-' -> operand = true
                char == '*' || char == '/' || char == '^' || char == '=' -> {
                    if (operand) return false
                    operand = true
                }
                else -> return false
            }
            index++
        }
        return atom && !operand
    }

    private fun power(prefix: String, exponent: String): String? {
        val text = prefix.trimEnd()
        if (text.isEmpty()) return null
        var start = text.lastIndex
        when {
            text.last() == ')' -> {
                var depth = 0
                while (start >= 0) {
                    if (text[start] == ')') depth++ else if (text[start] == '(') depth--
                    if (depth == 0) break
                    start--
                }
                if (start < 0) return null
            }
            text.last() in '0'..'9' || text.last() == '.' ->
                while (start > 0 && (text[start - 1] in '0'..'9' || text[start - 1] == '.')) start--
            text.last() !in 'a'..'z' && text.last() !in 'A'..'Z' -> return null
        }
        return text.substring(0, start) + "(" + text.substring(start) + ")^(" + exponent + ")"
    }

    private fun characteristicHeight(strokes: List<PageOcrLayout.Stroke>): Float {
        val sizes = strokes.map { max(it.bounds.height, min(it.bounds.width, max(it.bounds.height * 2, thickness(it) * 4))) }
            .sorted()
        return sizes[((sizes.size - 1) * 3) / 4].coerceAtLeast(.001f)
    }
    private fun thickness(stroke: PageOcrLayout.Stroke) = stroke.source.optDouble("width", 1.0).toFloat()
        .takeIf { it.isFinite() && it > 0 } ?: 1f
    private fun horizontalBar(stroke: PageOcrLayout.Stroke): Boolean {
        val b = stroke.bounds
        if (stroke.pointCount < 2 || b.width < max(b.height, thickness(stroke)) * 5) return false
        val points = stroke.source.getJSONArray("points")
        return abs(points.getJSONObject(points.length() - 1).getDouble("x") - points.getJSONObject(0).getDouble("x")) >= b.width * .9f &&
            stroke.length <= b.width * 1.15f
    }
    private fun raisedNeighbour(base: InkBounds, high: InkBounds) = base.height > 0 && high.height <= base.height * .7f &&
        high.bottom < base.bottom - base.height * .42f && high.left >= base.left + base.width * .35f &&
        xGap(base, high) <= base.height * .75f && yGap(base, high) <= base.height * .6f &&
        union(base, high).height <= base.height * 2
    private fun bounds(strokes: List<PageOcrLayout.Stroke>) = InkBounds(strokes.minOf { it.bounds.left },
        strokes.minOf { it.bounds.top }, strokes.maxOf { it.bounds.right }, strokes.maxOf { it.bounds.bottom })
    private fun cx(box: InkBounds) = (box.left + box.right) / 2
    private fun cy(box: InkBounds) = (box.top + box.bottom) / 2
    private fun xGap(a: InkBounds, b: InkBounds) = max(0f, max(a.left - b.right, b.left - a.right))
    private fun yGap(a: InkBounds, b: InkBounds) = max(0f, max(a.top - b.bottom, b.top - a.bottom))
    private fun union(a: InkBounds, b: InkBounds) = InkBounds(min(a.left, b.left), min(a.top, b.top),
        max(a.right, b.right), max(a.bottom, b.bottom))
}
