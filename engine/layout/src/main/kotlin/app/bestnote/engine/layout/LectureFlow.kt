package app.bestnote.engine.layout

import app.bestnote.core.model.RectPt

/**
 * Recovers the *logical* order of a lecture from where things ended up on the board.
 *
 * Lecturers write wherever there is space: a formula squeezed into a corner, a definition in the
 * second panel, a side remark next to a proof. A student copying the board doesn't copy it
 * spatially. They copy it in the order it was taught. This class does the same:
 *
 * 1. If the transcriber gave an explicit [FlowItem.order], that wins. Items without one inherit
 *    the order of the item before them, so they stay next to their neighbours.
 * 2. Otherwise the board is read as panels: wide items (titles, full-width lines) split the board
 *    into horizontal bands; inside a band, items are grouped into columns by horizontal overlap
 *    and read column by column (right-to-left for RTL boards), top to bottom.
 * 3. The page title ([FIRST]) leads, and margin notes ([FlowItem.anchorKey]) are moved to directly after the block they comment on.
 */
object LectureFlow {
    private const val SPANNING_WIDTH = 0.6f

    /** Items with this order (the page title) always come first and don't count as "ordered". */
    const val FIRST = Int.MIN_VALUE

    fun order(items: List<FlowItem>, rtl: Boolean = false): List<FlowItem> {
        val (notes, rest) = items.partition { it.anchorKey != null }
        val (first, body) = rest.partition { it.order == FIRST }
        val ordered = if (body.any { it.order != null }) byExplicitOrder(body) else byBoardReading(body, rtl)
        return attachNotes(first + ordered, notes)
    }

    private fun byExplicitOrder(items: List<FlowItem>): List<FlowItem> {
        var last = Int.MIN_VALUE
        return items.mapIndexed { i, item ->
            val o = item.order ?: last
            last = o
            Triple(o, i, item)
        }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }

    private fun byBoardReading(items: List<FlowItem>, rtl: Boolean): List<FlowItem> {
        val located = items.withIndex().filter { it.value.sourceBox != null }
        if (located.isEmpty()) return items
        val boardWidth = located.maxOf { it.value.sourceBox!!.right } - located.minOf { it.value.sourceBox!!.left }

        val spanning = located.filter { it.value.sourceBox!!.width >= boardWidth * SPANNING_WIDTH }
            .sortedBy { it.value.sourceBox!!.top }
        val panelItems = located - spanning.toSet()

        // Bands: everything above the first spanning item, between spanning items, and below the last.
        val cuts = spanning.map { it.value.sourceBox!!.center.y }
        val bands = Array(cuts.size + 1) { ArrayList<IndexedValue<FlowItem>>() }
        for (it in panelItems) bands[cuts.count { c -> it.value.sourceBox!!.center.y > c }] += it

        val out = ArrayList<FlowItem>(items.size)
        for (b in bands.indices) {
            out += readColumns(bands[b], rtl)
            spanning.getOrNull(b)?.let { out += it.value }
        }
        // Items with no position keep their original relative place at the end of the sequence.
        out += items.filter { it.sourceBox == null }
        return out
    }

    private fun readColumns(items: List<IndexedValue<FlowItem>>, rtl: Boolean): List<FlowItem> {
        val columns = ArrayList<Column>()
        for (it in items.sortedBy { it.value.sourceBox!!.left }) {
            val box = it.value.sourceBox!!
            val col = columns.firstOrNull { c -> c.overlaps(box) }
            if (col == null) columns += Column(box.left, box.right, mutableListOf(it)) else col.add(it)
        }
        val sorted = if (rtl) columns.sortedByDescending { it.right } else columns.sortedBy { it.left }
        return sorted.flatMap { c -> c.items.sortedWith(compareBy({ it.value.sourceBox!!.top }, { it.index })).map { it.value } }
    }

    private fun attachNotes(body: List<FlowItem>, notes: List<FlowItem>): List<FlowItem> {
        if (notes.isEmpty()) return body
        val keys = body.mapTo(HashSet()) { it.key }
        val byAnchor = notes.filter { it.anchorKey in keys }.groupBy { it.anchorKey }
        val out = ArrayList<FlowItem>(body.size + notes.size)
        for (item in body) {
            out += item
            byAnchor[item.key]?.let { out += it }
        }
        // Notes whose anchor is missing still appear, at the end, rather than being lost.
        out += notes.filter { it.anchorKey !in keys }
        return out
    }

    private class Column(var left: Float, var right: Float, val items: MutableList<IndexedValue<FlowItem>>) {
        fun overlaps(b: RectPt): Boolean {
            val overlap = minOf(right, b.right) - maxOf(left, b.left)
            return overlap > 0.5f * minOf(right - left, b.width)
        }

        fun add(item: IndexedValue<FlowItem>) {
            items += item
            left = minOf(left, item.value.sourceBox!!.left)
            right = maxOf(right, item.value.sourceBox!!.right)
        }
    }
}
