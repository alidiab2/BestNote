package app.bestnote.engine.layout

import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.RectPt
import kotlin.math.ceil
import kotlin.math.max

/**
 * The packed notebook page ([PageLayout.Packed]): many things on ONE page, organised like the best
 * student's notebook, whatever elements the AI returned.
 *
 * - **Topics.** A Heading starts a topic; everything after it belongs to it until the next heading.
 *   Topics are separated by a dashed horizontal rule inside their column.
 * - **Columns.** Column blocks flow through 2 columns per page (3 on wide pages when [Density.Dense]),
 *   newspaper style: down the first column, then the next. The first column is on the start side
 *   (right for Hebrew/Arabic). A dashed vertical rule divides neighbouring columns.
 * - **Spans.** A [PackBlock.span] block (the title, a wide table) runs across the whole width; the
 *   columns above it are balanced so they end level, and a dashed rule separates the sections.
 * - **Pages fill.** A page is filled before the next starts; only the end of a run of columns is
 *   balanced. Blocks split between whole rows, never leaving a heading at a column's foot, a block's
 *   first lone line at the bottom (orphan) or its last lone line at the top of the next column
 *   (widow); short blocks ([PackBlock.keepWhole] or [SHORT] lines) never split.
 * - Rows the user already wrote on ([reserved], whole lines across the page) are skipped.
 *
 * Pure geometry: the caller measures rows ([PackBlock] rows are whole ruled lines at a width) and draws.
 */
class PackedFlow(
    private val paper: RuledPaper,
    private val rtl: Boolean,
    private val density: Density = Density.Normal,
    private val reserved: (page: Int, line: Int) -> Boolean = { _, _ -> false },
    /** Narrower columns than this (points) are unreadable at the text size: then one column per page. */
    private val minColumnWidth: Float = 0f,
) {
    /** One row of a block at a width: whole ruled [lines]; [blank] rows are air that may be dropped at a column's top. */
    data class Row(val lines: Int, val blank: Boolean = false, val glue: Boolean = false, val ink: RowInk = RowInk.None)

    /**
     * A block to place. [rows] gives its rows at a width (column or full). [topic]: starts a topic
     * (headings). [keepWithNext]: never ends a column without the next block's first lines. [span]: runs
     * across all columns. [keepWhole]: never split (a boxed formula, a small table).
     */
    class PackBlock(
        val key: String,
        val rows: (width: Float) -> List<Row>,
        val topic: Boolean = false,
        val keepWithNext: Boolean = false,
        val span: Boolean = false,
        val keepWhole: Boolean = false,
    )

    /** Rows [rowFrom] until [rowTo] of block [key] on [page] in [frame] (its first line is [firstLine]); [column] -1 = spans. */
    data class Slot(val key: String, val page: Int, val rowFrom: Int, val rowTo: Int, val frame: RectPt, val firstLine: Int, val column: Int)

    /** A dashed rule to draw on [page] from ([x0], [y0]) to ([x1], [y1]). */
    data class Rule(val page: Int, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    class Result(val slots: List<Slot>, val rules: List<Rule>, val columns: Int, val columnWidth: Float) {
        val pageCount: Int get() = (slots.maxOfOrNull { it.page } ?: -1) + 1
    }

    private val gap = paper.lineGap
    private val left = paper.textLeft
    private val right = paper.textRight
    private val fullWidth = right - left
    private val lineCount = paper.lineCount

    /** Columns per page: 3 only for dense notes on a wide page where a column stays readable, 1 when even 2 would be too narrow (big text on a small page). */
    val columns: Int = when {
        density == Density.Dense && paper.width >= paper.height && colWidth(3) >= max(MIN_COLUMN_LINES * gap, minColumnWidth) -> 3
        colWidth(2) >= minColumnWidth -> 2
        else -> 1
    }

    val gutter: Float get() = GUTTER * gap

    fun colWidth(n: Int = columns): Float = (fullWidth - (n - 1) * GUTTER * gap) / n

    /** Left x of column [c] (0 = the first in reading order: the right one in RTL). */
    fun columnLeft(c: Int): Float {
        val w = colWidth()
        val visual = if (rtl) columns - 1 - c else c
        return left + visual * (w + gutter)
    }

    // ------------------------------------------------------------------ pieces

    /** One row of one block, as the packer sees it. */
    private class Piece(
        val block: Int,
        val row: Int,
        val lines: Int,
        val blank: Boolean,
        /** First row of a topic block (a separator goes above it unless it opens a column). */
        val topicStart: Boolean,
        val blockStart: Boolean,
        /** Lines from this row to the block's end. */
        val restLines: Int,
        val blockLines: Int,
        /** Lines of this row and the rows glued after it (a pen box drawn by a later row). */
        val chain: Int,
        /** Real ink around this row's baseline (LAYOUT-QA): a block's first row must clear the ink above it in the column. */
        val ink: RowInk = RowInk.None,
    )

    private class Placed(val piece: Piece, val column: Int, val line: Int)

    /** [tail]: the ink of all columns seen from the baseline of the region's last line. */
    private class Fill(val placed: List<Placed>, val separators: List<Pair<Int, Int>>, val next: Int, val used: IntArray, val tail: RowInk = RowInk.None)

    /** Extra lines (at most [MAX_PUSH]) so ink [next] written [lines] below the baseline of [above] clears it. */
    private fun push(above: RowInk, next: RowInk, lines: Int): Int {
        if (above.isEmpty || next.isEmpty) return 0
        val need = RowInk.distance(above, next, gap)
        var extra = 0
        while (extra < MAX_PUSH && (lines + extra) * gap + 0.01f * gap < need) extra++
        return extra
    }

    fun place(blocks: List<PackBlock>): Result {
        val slots = ArrayList<Slot>()
        val rules = ArrayList<Rule>()
        var page = 0
        var top = 0
        var lastWasTitle = false
        // Ink hanging below what was written last across the page (a full-width block, a rule, a run of
        // columns), from the baseline of line top - 1, on page tailPage (LAYOUT-QA).
        var tail = RowInk.None
        var tailPage = -1
        fun tailHere() = if (tailPage == page && top > 0) tail else RowInk.None
        val ruleInk = RowInk.rule(RULE_ABOVE * gap)
        var i = 0
        val colW = colWidth()
        while (i < blocks.size) {
            val b = blocks[i]
            if (b.span) {
                val rows = b.rows(fullWidth)
                val n = rows.sumOf { it.lines }.coerceAtLeast(1)
                val rule = top > 0 && !lastWasTitle
                // The rule, then the block's first written row, clear the ink above them.
                var above = tailHere()
                if (rule) top += push(above, ruleInk, 1)
                if (rule) above = ruleInk
                var start = top + (if (rule) 1 else 0)
                rows.indices.firstOrNull { !rows[it].ink.isEmpty }?.let { k -> start += push(above, rows[k].ink, rows.subList(0, k + 1).sumOf { it.lines }) }
                // A heading-like span keeps the next block's first lines with it.
                val need = n + if (b.keepWithNext) 2 else 0
                if (top > 0 && start + need > lineCount) {
                    page++
                    top = 0
                    start = 0
                } else if (rule) {
                    rules += hRule(page, top, left, right)
                }
                start = skipReserved(page, start, n)
                slots += Slot(b.key, page, 0, rows.size, frame(start, n, left, right), start, -1)
                tail = tailOf(rows)
                tailPage = page
                top = start + n
                lastWasTitle = b.keepWithNext
                if (top >= lineCount) { page++; top = 0 }
                i++
                continue
            }
            // A run of column blocks up to the next span.
            var j = i
            while (j < blocks.size && !blocks[j].span) j++
            val run = blocks.subList(i, j)
            keepNext = run.indices.filter { run[it].keepWithNext }.toSet()
            whole = run.indices.filter { run[it].keepWhole }.toSet()
            val pieces = pieces(run, colW)
            var s = 0
            var firstRegion = true
            while (s < pieces.size) {
                if (top > 0 && firstRegion && !lastWasTitle) {
                    top += push(tailHere(), ruleInk, 1)
                    rules += hRule(page, top, left, right)
                    top++
                    tail = ruleInk
                    tailPage = page
                }
                firstRegion = false
                if (top >= lineCount - 1) { page++; top = 0 }
                // Columns start clear of the ink above them (the title, a rule).
                startInk = tailHere()
                tailPage = -1
                val avail = lineCount - top
                val full = fill(pieces, s, page, top, avail)
                val fill = if (full.next >= pieces.size) balanced(pieces, s, page, top, avail) ?: full else full
                if (fill.placed.isEmpty()) {
                    // Only air was left (blank rows dropped at a column's top): done with this run.
                    if (fill.next > s) { s = fill.next; continue }
                    page++; top = 0; continue
                }
                emit(fill, run, page, top, colW, slots, rules)
                s = fill.next
                if (s < pieces.size) {
                    page++
                    top = 0
                } else {
                    top += fill.used.max()
                    tail = fill.tail
                    tailPage = page
                }
            }
            lastWasTitle = false
            if (top >= lineCount) { page++; top = 0 }
            i = j
        }
        return Result(slots, rules, columns, colW)
    }

    private fun pieces(run: List<PackBlock>, width: Float): List<Piece> {
        val out = ArrayList<Piece>()
        for ((bi, b) in run.withIndex()) {
            val rows = b.rows(width).ifEmpty { listOf(Row(1, blank = true)) }
            val total = rows.sumOf { it.lines }
            var rest = total
            for ((ri, r) in rows.withIndex()) {
                var chain = r.lines
                var k = ri
                while (rows[k].glue && k + 1 < rows.size) chain += rows[++k].lines
                out += Piece(bi, ri, r.lines, r.blank, topicStart = b.topic && ri == 0, blockStart = ri == 0, restLines = rest, blockLines = total, chain = chain, ink = r.ink)
                rest -= r.lines
            }
        }
        return out
    }

    /**
     * Greedy newspaper fill of up to [columns] columns, [height] lines each, from piece [start].
     * Returns where every piece went, the topic separators (column, line) and the next unplaced piece.
     */
    private fun fill(pieces: List<Piece>, start: Int, page: Int, top: Int, height: Int, avail: Int = height): Fill {
        val placed = ArrayList<Placed>()
        val seps = ArrayList<Pair<Int, Int>>()
        val used = IntArray(columns)
        // Ink written so far in each column, seen from the baseline on line inkLine[c] (LAYOUT-QA).
        val ink = Array(columns) { startInk }
        val inkLine = IntArray(columns) { -1 }
        var col = 0
        var i = start
        while (i < pieces.size && col < columns) {
            val p = pieces[i]
            // Air at a column's top is dropped, but not the first line of a pen box (glued to the rows it encloses).
            if (used[col] == 0 && p.blank && p.chain == p.lines) { i++; continue }
            var sep = if (p.topicStart && used[col] > 0) 1 else 0
            var above = ink[col]
            var aboveLine = inkLine[col]
            if (sep == 1) {
                // The dashed topic rule clears the ink above it too.
                val rule = RowInk.rule(RULE_ABOVE * gap)
                while (sep < MAX_PUSH && !above.isEmpty && RowInk.distance(above, rule, gap) > (used[col] + sep - 1 - aboveLine) * gap + 0.01f * gap) sep++
                above = RowInk.union(RowInk.shifted(above, (used[col] + sep - 1 - aboveLine) * gap), rule)
                aboveLine = used[col] + sep - 1
            }
            var at = skipReservedIn(page, top, used[col] + sep, p.lines, height)
            // A block's first row, or any row that starts a column (under the title, a rule), clears the ink above.
            if ((p.blockStart || used[col] == 0) && !above.isEmpty && !p.ink.isEmpty) {
                val need = RowInk.distance(above, p.ink, gap)
                var extra = 0
                while (extra < MAX_PUSH && (at + extra + p.lines - 1 - aboveLine) * gap + 0.01f * gap < need) extra++
                if (extra > 0) at = skipReservedIn(page, top, at + extra, p.lines, height)
            }
            val need = needed(pieces, i)
            // Ink hanging below the page (a fraction on the last line whose air row moves on) does not fit either.
            val hang = listOf(p.ink.textDown, p.ink.penDown).filter { !it.isNaN() }.maxOrNull()
            val spills = hang != null && paper.lineY(top + at + p.lines - 1) + hang > paper.height
            if (at + need > height || spills) {
                // In an empty column the keep rules give way only when they can never be met (a block
                // taller than a whole column is split row by row; a single row taller than the column is
                // placed anyway, nothing is lost).
                // (Below a full-width block a fresh page may still take it whole: then it is not hopeless.)
                val hopeless = at + need > avail && (top == 0 || need > lineCount)
                // (Rows glued under one pen box go together: a box never loses its top line to another column.)
                val forced = used[col] == 0 && hopeless && (at + p.chain <= height || (p.chain > height && top == 0))
                if (!forced) {
                    col++
                    continue
                }
            }
            if (sep > 0) seps += col to (used[col] + sep - 1)
            placed += Placed(p, col, at)
            if (!p.ink.isEmpty) {
                val base = at + p.lines - 1
                ink[col] = RowInk.union(RowInk.shifted(ink[col], (base - inkLine[col]) * gap), p.ink)
                inkLine[col] = base
            }
            used[col] = at + p.lines
            i++
        }
        val bottom = (used.maxOrNull() ?: 0) - 1
        var tail = RowInk.None
        for (c in 0 until columns) if (!ink[c].isEmpty) tail = RowInk.union(tail, RowInk.shifted(ink[c], (bottom - inkLine[c]) * gap))
        return Fill(placed, seps, i, used, tail)
    }

    /** Lines that must fit in the column for piece [i] to go there (keep rules). */
    private fun needed(pieces: List<Piece>, i: Int): Int {
        val p = pieces[i]
        var need = p.chain
        // Short blocks stay whole: place the block's lines together.
        if (p.blockStart && (p.blockLines <= SHORT || keepWhole(p))) need = p.blockLines
        // Orphan: a long block's first rows need at least ORPHAN lines at the column's foot.
        else if (p.blockStart) need = max(need, minOf(p.blockLines, ORPHAN))
        val after = p.restLines - p.lines
        // Widow: never leave a lone last line for the next column.
        if (after in 1 until WIDOW) need = max(need, p.restLines)
        // Keep with next: a heading carries what the next block needs to start in the same column.
        if (after == 0 && keepNext.contains(p.block)) {
            val n = pieces.getOrNull(i + 1)
            if (n != null && n.block != p.block) need += startNeed(n)
        }
        return need
    }

    /** Lines block-start piece [n] needs at a column's foot before it may start there. */
    private fun startNeed(n: Piece): Int = when {
        n.blockLines <= SHORT || keepWhole(n) -> n.blockLines
        else -> max(n.chain, minOf(n.blockLines, ORPHAN))
    }

    // Set per run of column blocks before filling: blocks that keep with the next one / stay whole.
    private var keepNext: Set<Int> = emptySet()

    /** Ink above the first line of the region being filled (LAYOUT-QA): a full-width block's tail. */
    private var startInk: RowInk = RowInk.None

    /** The ink of [rows] seen from their last baseline. */
    private fun tailOf(rows: List<Row>): RowInk {
        val total = rows.sumOf { it.lines }
        var cum = 0
        var out = RowInk.None
        for (r in rows) {
            cum += r.lines
            if (!r.ink.isEmpty) out = RowInk.union(out, RowInk.shifted(r.ink, (total - cum) * gap))
        }
        return out
    }
    private var whole: Set<Int> = emptySet()

    private fun keepWhole(p: Piece) = p.block in whole

    /** Smallest equal column height that still takes every piece (columns end level), or null. */
    private fun balanced(pieces: List<Piece>, start: Int, page: Int, top: Int, avail: Int): Fill? {
        val total = (start until pieces.size).sumOf { pieces[it].lines }
        val tallest = (start until pieces.size).maxOfOrNull { pieces[it].lines } ?: 1
        var lo = max(tallest, ceil(total / columns.toFloat()).toInt())
        var hi = avail
        var best: Fill? = null
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val f = fill(pieces, start, page, top, mid, avail)
            if (f.next >= pieces.size) {
                best = f
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return best
    }

    private fun emit(fill: Fill, run: List<PackBlock>, page: Int, top: Int, colW: Float, slots: MutableList<Slot>, rules: MutableList<Rule>) {
        // Consecutive rows of one block in one column (without a gap) become one slot.
        var k = 0
        val placed = fill.placed
        while (k < placed.size) {
            val a = placed[k]
            var e = k
            var lineEnd = a.line + a.piece.lines
            while (e + 1 < placed.size && placed[e + 1].column == a.column && placed[e + 1].piece.block == a.piece.block && placed[e + 1].line == lineEnd) {
                e++
                lineEnd += placed[e].piece.lines
            }
            val x0 = columnLeft(a.column)
            val first = top + a.line
            val n = lineEnd - a.line
            slots += Slot(run[a.piece.block].key, page, a.piece.row, placed[e].piece.row + 1, frame(first, n, x0, x0 + colW), first, a.column)
            k = e + 1
        }
        for ((c, line) in fill.separators) {
            val x0 = columnLeft(c)
            rules += hRule(page, top + line, x0 + 0.2f * gap, x0 + colW - 0.2f * gap)
        }
        // Vertical dividers between neighbouring columns that both have writing.
        for (c in 0 until columns - 1) {
            val h = minOf(max(fill.used[c], fill.used[c + 1]), lineCount - top)
            if (fill.used[c] == 0 || fill.used[c + 1] == 0) continue
            val x = (if (rtl) columnLeft(c + 1) + colW else columnLeft(c) + colW) + gutter / 2
            // It starts below whatever hangs down from above (the title's descenders).
            val hang = listOf(startInk.textDown, startInk.penDown).filter { !it.isNaN() }.maxOrNull()
            val y0 = max(paper.lineY(top) - gap + 0.25f * gap, if (top > 0 && hang != null) paper.lineY(top - 1) + hang + RowInk.CLEAR * gap else 0f)
            val y1 = paper.lineY(top + h - 1) + 0.25f * gap
            rules += Rule(page, x, y0, x, y1)
        }
    }

    /** A rule in the middle of line [line]'s band. */
    private fun hRule(page: Int, line: Int, x0: Float, x1: Float): Rule {
        val y = paper.lineY(line) - RULE_ABOVE * gap
        return Rule(page, x0, y, x1, y)
    }

    private fun frame(first: Int, lines: Int, x0: Float, x1: Float) = RectPt(x0, paper.lineY(first) - gap, x1, paper.lineY(first + lines - 1))

    private fun skipReserved(page: Int, start: Int, n: Int): Int {
        var s = start
        while (s < lineCount && (s until minOf(lineCount, s + n)).any { reserved(page, it) }) s++
        return s
    }

    private fun skipReservedIn(page: Int, top: Int, at: Int, n: Int, height: Int): Int {
        var a = at
        while (a < height && (a until a + n).any { reserved(page, top + it) }) a++
        return a
    }

    companion object {
        /** A topic rule sits this far (line gaps) above its line. */
        const val RULE_ABOVE = 0.45f

        /** At most this many lines are added to clear ink (a giant matrix is placed anyway). */
        const val MAX_PUSH = 4

        /** Gutter between columns, in line gaps. */
        const val GUTTER = 1.0f

        /** Blocks this short (lines) never split. */
        const val SHORT = 6

        /** A split block leaves at least this many lines on each side. */
        const val ORPHAN = 2
        const val WIDOW = 2

        /** A third column only when each column holds at least this many line gaps of writing. */
        const val MIN_COLUMN_LINES = 9f

        /** True for paper whose writing lines sit on every other grid line (grid and dot paper). */
        fun isLattice(paper: RuledPaper) = paper.template == PaperTemplate.Grid || paper.template == PaperTemplate.Dotted

        /**
         * The writing lines for packed notes: on grid and dot paper (Normal, Dense) EVERY grid line is a
         * line of writing, the way students fill 5 mm squares; other paper keeps its own lines.
         */
        fun paperFor(paper: RuledPaper, density: Density): RuledPaper =
            if (isLattice(paper) && density != Density.Airy) paper.copy(lineGap = paper.lineGap / 2, edge = paper.edge) else paper

        /** Body text size factor for packed notes on [packed] (made from [sheet] by [paperFor]). */
        fun bodyFactor(sheet: RuledPaper, packed: RuledPaper, density: Density): Float = if (packed.lineGap < sheet.lineGap) {
            if (density == Density.Dense) 1.18f else 1.34f
        } else {
            when (density) {
                Density.Airy -> 1f
                Density.Normal -> 0.95f
                Density.Dense -> 0.88f
            }
        }
    }
}
