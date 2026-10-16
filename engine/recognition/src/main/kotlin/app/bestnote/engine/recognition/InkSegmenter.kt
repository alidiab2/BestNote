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
    private const val LINE_GAP = 0.55f
    private const val MARK_SIZE = 0.35f
    private const val MARK_REACH = 1.0f
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

    private class Line(val strokes: MutableList<Int>, boxes: List<Box?>) {
        val box = Box(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE).also { m -> strokes.forEach { m.add(boxes[it]!!) } }
        private val heights = strokes.map { boxes[it]!!.h }.toMutableList()

        val cy get() = box.cy

        /** A height that ignores dots and tiny marks: the upper quartile of the stroke heights. */
        val body: Float get() = heights.sorted()[((heights.size - 1) * 0.75f).toInt()].coerceAtLeast(MIN_HEIGHT_PT)

        fun add(index: Int, b: Box) {
            strokes += index
            box.add(b)
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
     * Lines, top to bottom. Strokes are clustered on their vertical centers (a gap bigger than a fraction
     * of a typical stroke starts a new line), then lines that are only dots or marks join the line they sit on.
     * Each line's strokes keep their drawing order.
     */
    fun lines(strokes: List<InkStrokeSamples>): List<InkSegment> {
        val boxes = strokes.map(::boxOf)
        val present = strokes.indices.filter { boxes[it] != null }
        if (present.isEmpty()) return emptyList()
        // Dots, accents and other tiny marks follow the letters they sit on, so they do not make lines of their own.
        val sizes = present.map { maxOf(boxes[it]!!.w, boxes[it]!!.h) }.sorted()
        val markBelow = MARK_SIZE * sizes[((sizes.size - 1) * 0.75f).toInt()]
        val (marks, body) = present.partition { maxOf(boxes[it]!!.w, boxes[it]!!.h) < markBelow }
        val letters = body.ifEmpty { present }.sortedBy { boxes[it]!!.cy }
        val heights = letters.map { boxes[it]!!.h }.sorted()
        val gap = (LINE_GAP * heights[heights.size / 2]).coerceAtLeast(MIN_HEIGHT_PT / 2f)
        val clusters = ArrayList<MutableList<Int>>()
        for (i in letters) {
            val last = clusters.lastOrNull()
            if (last == null || boxes[i]!!.cy - boxes[last.last()]!!.cy > gap) clusters += arrayListOf(i) else last += i
        }
        val lines = clusters.map { c -> Line(c, boxes) }.toMutableList()
        if (body.isNotEmpty()) {
            for (m in marks) {
                val mark = boxes[m]!!
                val home = lines.minByOrNull { verticalGap(it.box, mark) }!!
                if (verticalGap(home.box, mark) <= MARK_REACH * home.body) home.add(m, mark) else lines += Line(arrayListOf(m), boxes)
            }
        }
        return lines.sortedBy { it.cy }.map { l ->
            InkSegment(l.strokes.sorted(), PageRect(l.box.l, l.box.t, l.box.r, l.box.b))
        }
    }

    private fun verticalGap(a: Box, b: Box) = maxOf(0f, maxOf(a.t, b.t) - minOf(a.b, b.b))

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
