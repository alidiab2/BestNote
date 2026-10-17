package app.bestnote.engine.recognition

import app.bestnote.core.model.PageRect

/** A group of strokes read together: [strokes] are indices into the list given to [InkSegmenter], in drawing order. */
data class InkSegment(val strokes: List<Int>, val box: PageRect)

/**
 * Splits a page's handwriting into lines and words (REC-02, REC-05) from geometry alone, so each piece
 * can be recognized on its own and its box used for highlighting. Works for both directions: a word is
 * a run of strokes whose boxes touch or sit closer than a fraction of the line's height (Arabic dots and
 * marks, drawn late, fall inside the word's box and stay with it).
 */
object InkSegmenter {
    private const val MARK_SIZE = 0.35f
    private const val MARK_REACH = 1.0f
    private const val MARK_NEAR = 1.5f
    private const val RUN_GAP = 2.5f
    private const val SAME_BAND = 0.3f
    private const val BAND_DRIFT = 0.5f
    private const val SAME_LINE = 0.5f
    private const val STACKED = 0.3f
    private const val SMALL = 0.6f
    private const val RECENT = 3
    private const val LOCAL = 4
    private const val ORDER_GAP = 1.0f
    private const val WORD_GAP = 0.45f
    private const val MIN_HEIGHT_PT = 4f

    private class Box(var l: Float, var t: Float, var r: Float, var b: Float) {
        val cy get() = (t + b) / 2f
        val w get() = r - l
        val h get() = b - t
        fun add(o: Box) {
            l = minOf(l, o.l); t = minOf(t, o.t); r = maxOf(r, o.r); b = maxOf(b, o.b)
        }
    }

    private fun boxOf(stroke: InkStrokeSamples): Box? {
        if (stroke.isEmpty()) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (s in stroke) {
            l = minOf(l, s.x); t = minOf(t, s.y); r = maxOf(r, s.x); b = maxOf(b, s.y)
        }
        return Box(l, t, r, b)
    }

    /**
     * Lines, top to bottom (REC-10). Works in two passes so it copes with tight ruling, sloped lines and
     * strokes that are not in writing order:
     * 1. In drawing order, consecutive letter strokes that sit side by side on the same band form a *run*
     *    (a line break, or a jump to a word added elsewhere later, starts a new run).
     * 2. Runs join the line whose ink *near them* shares their band (so a sloped line stays one line, and a word
     *    moved with the lasso, whose strokes now come last, goes back to its line).
     * Dots, accents and other tiny marks then join the line of the word they were written after, or the line
     * they sit on. Each line's strokes are in drawing order.
     */
    fun lines(strokes: List<InkStrokeSamples>): List<InkSegment> {
        val boxes = strokes.map(::boxOf)
        val present = strokes.indices.filter { boxes[it] != null }
        if (present.isEmpty()) return emptyList()
        val sizes = present.map { maxOf(boxes[it]!!.w, boxes[it]!!.h) }.sorted()
        val markBelow = MARK_SIZE * sizes[((sizes.size - 1) * 0.75f).toInt()]
        val (marks, body) = present.partition { maxOf(boxes[it]!!.w, boxes[it]!!.h) < markBelow }
        val letters = body.ifEmpty { present }
        val heights = letters.map { boxes[it]!!.h }.sorted()
        val unit = heights[heights.size / 2].coerceAtLeast(MIN_HEIGHT_PT)
        // How far a mark may sit from its line: about a tall letter's height (accents, dots above capitals, t-bars).
        val reach = MARK_REACH * heights[((heights.size - 1) * 0.75f).toInt()].coerceAtLeast(MIN_HEIGHT_PT)

        // 1. Runs in drawing order.
        val runs = ArrayList<MutableList<Int>>()
        for (i in letters) {
            val run = runs.lastOrNull()
            if (run != null && continues(run.takeLast(RECENT).map { boxes[it]!! }, boxes[i]!!, unit)) run += i else runs += arrayListOf(i)
        }
        // 2. Runs into lines, longest first, so short runs (a moved word, a late correction) find the line they belong to.
        val lines = ArrayList<MutableList<Int>>()
        for (run in runs.sortedByDescending { r -> r.sumOf { boxes[it]!!.w.toDouble() } }) {
            val runBand = band(run.map { boxes[it]!! })
            // A line never lies on top of itself: the run must fill a gap of the line, not sit over its ink.
            val open = lines.filter { stacked(it, runBand, boxes) <= STACKED }
            val home = open.maxByOrNull { overlap(localBand(it, runBand, boxes), runBand) }
            val nearest = lines.minByOrNull { distance(localBand(it, runBand, boxes), runBand) }
            when {
                home != null && overlap(localBand(home, runBand, boxes), runBand) >= SAME_LINE -> home += run
                // A short, flat run (a dash, a bar, a mark too big to be a dot) goes with the line it is next to.
                nearest != null && (runBand.h < SMALL * unit || runBand.w < SMALL * unit) &&
                    distance(localBand(nearest, runBand, boxes), runBand) <= reach -> nearest += run
                else -> lines += run.toMutableList()
            }
        }
        // Marks: with the word they were written after when they sit on it, otherwise with the nearest line around them.
        val lineOf = HashMap<Int, MutableList<Int>>().apply { lines.forEach { l -> l.forEach { put(it, l) } } }
        val isMark = marks.toHashSet()
        for (m in marks) {
            val mark = boxes[m]!!
            // The letter written just before (a dot, an accent) or just after (the bar a T starts with).
            val before = (m - 1 downTo 0).firstOrNull { boxes[it] != null && it !in isMark }
            val next = (m + 1 until boxes.size).firstOrNull { boxes[it] != null && it !in isMark }
            val neighbour = listOfNotNull(before, next).firstOrNull { n ->
                val l = lineOf[n]
                l != null && distance(localBand(l, mark, boxes), mark) <= reach && near(boxes[n]!!, mark, unit)
            }
            val home = neighbour?.let { lineOf[it] } ?: lines.minByOrNull { distance(localBand(it, mark, boxes), mark) }
            if (home != null && distance(localBand(home, mark, boxes), mark) <= reach) home += m else lines += arrayListOf(m)
        }
        return lines.map { l ->
            val box = Box(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE).also { b -> l.forEach { b.add(boxes[it]!!) } }
            InkSegment(l.sorted(), PageRect(box.l, box.t, box.r, box.b)) to band(l.filter { it !in isMark }.ifEmpty { l }.map { boxes[it]!! }).cy
        }.sortedBy { it.second }.map { it.first }
    }

    /** True when [next] continues the run whose last strokes are [recent]: beside them and on the same band. */
    private fun continues(recent: List<Box>, next: Box, unit: Float): Boolean {
        val l = recent.minOf { it.l }
        val r = recent.maxOf { it.r }
        val beside = maxOf(next.l - r, l - next.r) <= RUN_GAP * unit
        val b = band(recent)
        val sameBand = overlap(b, next) >= SAME_BAND || kotlin.math.abs(next.cy - b.cy) <= BAND_DRIFT * unit
        return beside && sameBand
    }

    /** The typical band of [boxes]: median top to median bottom (one tall or deep letter does not stretch it). */
    private fun band(boxes: List<Box>): Box {
        val t = boxes.map { it.t }.sorted()
        val b = boxes.map { it.b }.sorted()
        return Box(boxes.minOf { it.l }, t[t.size / 2], boxes.maxOf { it.r }, b[b.size / 2])
    }

    /** The band of [line] around [at] horizontally: its strokes closest in x (lines slope, so far ink says little). */
    private fun localBand(line: List<Int>, at: Box, boxes: List<Box?>): Box {
        val cx = (at.l + at.r) / 2f
        val near = line.map { boxes[it]!! }.sortedBy { maxOf(0f, maxOf(it.l - cx, cx - it.r)) }.take(LOCAL)
        return band(near)
    }

    /** How much of [run]'s width already has ink of [line] under or over it (0 = it fills a gap, 1 = stacked on ink). */
    private fun stacked(line: List<Int>, run: Box, boxes: List<Box?>): Float {
        if (run.w <= 0f) return 0f
        var covered = 0f
        for (i in line) {
            val b = boxes[i]!!
            covered += maxOf(0f, minOf(b.r, run.r) - maxOf(b.l, run.l))
        }
        return covered / run.w
    }

    /** Vertical overlap of two bands as a share of the smaller one. */
    private fun overlap(a: Box, b: Box): Float {
        val h = minOf(a.h, b.h)
        val o = minOf(a.b, b.b) - maxOf(a.t, b.t)
        return if (h <= 0f) (if (o >= 0f) 1f else 0f) else (o / h).coerceAtLeast(0f)
    }

    private fun distance(band: Box, b: Box) = maxOf(0f, maxOf(band.t - b.b, b.t - band.b))

    private fun near(a: Box, b: Box, unit: Float) = maxOf(0f, maxOf(b.l - a.r, a.l - b.r)) <= MARK_NEAR * unit

    /**
     * The strokes [indices] of one line in an order a recognizer can read (REC-10). Strokes drawn one after the other
     * side by side (a word, its dots, the next word) keep their drawing order; such runs then follow each other along
     * the line in the writing direction ([rtl] for Arabic). So ink written out of order (a word moved with the lasso or
     * brought back by undo comes last in the page's stroke list) is read where it sits, and ink in order is unchanged.
     */
    fun writingOrder(strokes: List<InkStrokeSamples>, indices: List<Int>, rtl: Boolean): List<Int> {
        val boxes = indices.associateWith { boxOf(strokes[it]) }
        val drawn = indices.filter { boxes[it] != null }.sorted()
        if (drawn.isEmpty()) return indices
        val heights = drawn.map { boxes[it]!!.h }.sorted()
        val gap = ORDER_GAP * heights[heights.size / 2].coerceAtLeast(MIN_HEIGHT_PT)
        val sizes = drawn.map { maxOf(boxes[it]!!.w, boxes[it]!!.h) }.sorted()
        val markBelow = MARK_SIZE * sizes[((sizes.size - 1) * 0.75f).toInt()]
        val runs = ArrayList<MutableList<Int>>()
        for (i in drawn) {
            val run = runs.lastOrNull()
            val b = boxes[i]!!
            val recent = run?.takeLast(RECENT)?.map { boxes[it]!! }
            val continues = recent != null && run.isNotEmpty() && run.let {
                val l = recent.minOf { it.l }
                val r = recent.maxOf { it.r }
                val beside = maxOf(b.l - r, l - b.r) <= gap
                // A dot or bar goes on the ink just written; a letter goes on along the line, not back over it.
                val mark = maxOf(b.w, b.h) < markBelow
                // Ink that was not drawn right after the run (the page's stroke list skips some strokes here: it was
                // moved or brought back later) must not lie wholly behind the last letter written. Ink drawn straight on
                // may go either way (an English word or a number inside an Arabic line runs left to right).
                val last = run.lastOrNull { maxOf(boxes[it]!!.w, boxes[it]!!.h) >= markBelow }?.let { boxes[it]!! }
                val skipped = i - run.last() > 1
                val back = skipped && last != null && if (rtl) b.l > last.r else b.r < last.l
                !skipped || (beside && (mark || !back))
            }
            if (continues) run!! += i else runs += arrayListOf(i)
        }
        // A run also breaks where other ink sits in the space it jumps over (a word moved into the gap it left).
        val pieces = ArrayList<List<Int>>()
        for (run in runs) {
            val others = runs.filter { it !== run }.flatten().map { boxes[it]!! }.map { (it.l + it.r) / 2f }
            var piece = arrayListOf(run.first())
            for ((a, b) in run.zipWithNext()) {
                val ba = boxes[a]!!
                val bb = boxes[b]!!
                val from = minOf(ba.r, bb.r)
                val to = maxOf(ba.l, bb.l)
                if (to > from && others.any { it in from..to }) {
                    pieces += piece
                    piece = arrayListOf()
                }
                piece += b
            }
            pieces += piece
        }
        val ordered = if (rtl) pieces.sortedByDescending { r -> r.maxOf { boxes[it]!!.r } } else pieces.sortedBy { r -> r.minOf { boxes[it]!!.l } }
        return ordered.flatten() + indices.filter { boxes[it] == null }
    }


    /** Words of one [line] (from [lines]), left to right on the page. */
    fun words(strokes: List<InkStrokeSamples>, line: InkSegment): List<InkSegment> {
        val boxes = line.strokes.associateWith { boxOf(strokes[it]) }
        val heights = boxes.values.filterNotNull().map { it.h }.sorted()
        val body = (heights.getOrNull(((heights.size - 1) * 0.75f).toInt()) ?: MIN_HEIGHT_PT).coerceAtLeast(MIN_HEIGHT_PT)
        val gap = WORD_GAP * body
        val byX = line.strokes.filter { boxes[it] != null }.sortedBy { boxes[it]!!.l }
        val groups = ArrayList<MutableList<Int>>()
        var right = -Float.MAX_VALUE
        for (i in byX) {
            val b = boxes[i]!!
            if (groups.isEmpty() || b.l - right > gap) {
                groups += arrayListOf(i)
                right = b.r
            } else {
                groups.last() += i
                right = maxOf(right, b.r)
            }
        }
        return groups.map { g ->
            val merged = Box(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE).also { m -> g.forEach { m.add(boxes[it]!!) } }
            InkSegment(g.sorted(), PageRect(merged.l, merged.t, merged.r, merged.b))
        }
    }

    /** Every word of [strokes] with its line number, in reading order of the lines (top to bottom). */
    fun words(strokes: List<InkStrokeSamples>): List<InkSegment> = lines(strokes).flatMap { words(strokes, it) }
}
