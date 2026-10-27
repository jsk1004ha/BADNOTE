package com.inkforge.notesstudio

import kotlin.math.max

/** Finds word-sized empty intervals without changing ink or inferring a script. */
internal object OcrWordLayout {
    private const val MAX_WORDS = 64
    private const val MIN_WORD_GAP_HEIGHT = .35
    private const val LIMIT_REASON = "wordBoundaryLimitReached"

    fun split(region: PageOcrLayout.Region): List<PageOcrLayout.Region> {
        if (region.strokes.size < 2) return listOf(region)
        // This is an already-fitted line, including detached marks. Individual pen-up
        // components underestimate Korean syllable height and would create false words.
        val lineHeight = region.bounds.bottom.toDouble() - region.bounds.top.toDouble()
        if (!(lineHeight > 0.0)) return listOf(region)
        val minimumGap = lineHeight * MIN_WORD_GAP_HEIGHT
        val sorted = region.strokes.sortedWith(compareBy<PageOcrLayout.Stroke> { it.bounds.left }
            .thenBy { it.originalIndex })
        val cuts = IntArray(MAX_WORDS - 1)
        var cutCount = 0
        var right = sorted.first().bounds.right
        for (i in 1 until sorted.size) {
            val box = sorted[i].bounds
            // Sweep the union of x projections, not just adjacent stroke bounds: a
            // crossing stroke or detached dot occupying an interval prevents a cut.
            if (box.left.toDouble() - right.toDouble() >= minimumGap) {
                if (cutCount == cuts.size) {
                    val intact = if (LIMIT_REASON in region.reviewReasons) region else
                        region.copy(reviewReasons = region.reviewReasons + LIMIT_REASON)
                    return listOf(intact)
                }
                cuts[cutCount++] = i
            }
            right = max(right, box.right)
        }
        if (cutCount == 0) return listOf(region)
        val words = ArrayList<PageOcrLayout.Region>(cutCount + 1)
        var start = 0
        for (i in 0..cutCount) {
            val end = if (i < cutCount) cuts[i] else sorted.size
            val members = sorted.subList(start, end)
            val first = members.minBy { it.originalIndex }
            val word = PageOcrLayout.knownRegion("${region.id}/word:${first.id}",
                region.role, members, region.order)
            words += if (region.reviewReasons.all { it in word.reviewReasons }) word else
                word.copy(reviewReasons = (word.reviewReasons + region.reviewReasons).distinct())
            start = end
        }
        return words
    }
}
