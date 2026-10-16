package app.bestnote.engine.layout

import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.RectPt
import app.bestnote.engine.layout.paper.PaperGeometry
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Notebook paper Smart Board writes on, in page units (the unit the strokes are written in). Every
 * template is reduced to writing lines: printed rules, grid lines (every other one), dot rows, or
 * invisible college ruling on blank paper.
 *
 * @property firstLine y of the first writing line (a baseline).
 * @property lineGap distance between writing lines.
 * @property marginX x of the vertical margin line on the left (the cue line on Cornell paper). The
 *   paper never mirrors: Arabic notes keep the same paper and start at the right edge instead.
 * @property edge empty border kept at the page's other edges.
 * @property template the paper this was made for (what previews draw).
 * @property bottom writing stops above this y (the summary box on Cornell paper).
 * @property showLines false on blank paper: the lines only guide the writing.
 */
data class RuledPaper(
    val width: Float,
    val height: Float,
    val firstLine: Float,
    val lineGap: Float,
    val marginX: Float,
    val edge: Float = lineGap * 0.75f,
    val template: PaperTemplate = PaperTemplate.Ruled,
    val bottom: Float = height,
    val showLines: Boolean = true,
) {
    init {
        require(width > marginX + 4 * lineGap && minOf(height, bottom) > firstLine + lineGap) { "Paper too small: $this" }
    }

    /** Writing lines that fit on the page with room for descenders below the last one. */
    val lineCount: Int get() = floor((minOf(height - edge, bottom) - firstLine) / lineGap + EPS).toInt() + 1

    /** Baseline y of ruled line [i] (0-based). */
    fun lineY(i: Int): Float = firstLine + i * lineGap

    /** Text area between the margin line and the right edge. */
    val textLeft: Float get() = marginX + lineGap * 0.35f
    val textRight: Float get() = width - edge

    companion object {
        private const val EPS = 0.01f

        /** Templates Smart Board can write on, in the order the review screen offers them. */
        val writable: List<PaperTemplate> = listOf(
            PaperTemplate.Ruled,
            PaperTemplate.RuledNarrow,
            PaperTemplate.RuledWide,
            PaperTemplate.Grid,
            PaperTemplate.Dotted,
            PaperTemplate.Blank,
            PaperTemplate.Cornell,
        )

        /** True for the templates with printed writing lines. */
        fun isRuled(template: PaperTemplate) =
            template == PaperTemplate.Ruled || template == PaperTemplate.RuledNarrow || template == PaperTemplate.RuledWide

        /**
         * The writing lines of [template] at [size], matching what the editor prints (PAGES-01
         * geometry, points). Music, planner and graph paper (not writable) falls back to college ruling.
         */
        fun of(template: PaperTemplate, size: PageSize): RuledPaper = when (template) {
            PaperTemplate.Grid, PaperTemplate.Dotted -> lattice(template, size)
            PaperTemplate.Cornell -> cornell(size)
            PaperTemplate.Blank -> ruled(PaperTemplate.Blank, size, PaperGeometry.COLLEGE).copy(showLines = false)
            PaperTemplate.RuledNarrow -> ruled(template, size, PaperGeometry.NARROW)
            PaperTemplate.RuledWide -> ruled(template, size, PaperGeometry.WIDE)
            PaperTemplate.Ruled -> ruled(template, size, PaperGeometry.COLLEGE)
            PaperTemplate.MusicStaff, PaperTemplate.PlannerDaily, PaperTemplate.PlannerWeekly, PaperTemplate.Graph ->
                ruled(PaperTemplate.Ruled, size, PaperGeometry.COLLEGE)
        }

        private fun ruled(template: PaperTemplate, size: PageSize, gap: Float) = RuledPaper(
            width = size.widthPt,
            height = size.heightPt,
            firstLine = PaperGeometry.headerHeight(size, gap),
            lineGap = gap,
            marginX = PaperGeometry.marginX(size),
            edge = gap,
            template = template,
        )

        /**
         * Grid and dot paper: baselines on every other grid line (two squares per line of writing,
         * the way students write on 5 mm squares), text starting on a vertical grid line.
         */
        private fun lattice(template: PaperTemplate, size: PageSize): RuledPaper {
            val pitch = PaperGeometry.GRID
            val gap = 2 * pitch
            val x0 = PaperGeometry.latticeStart(size.widthPt, pitch)
            val y0 = PaperGeometry.latticeStart(size.heightPt, pitch)
            fun snap(origin: Float, v: Float) = origin + ceil((v - origin) / pitch - EPS) * pitch
            return RuledPaper(
                width = size.widthPt,
                height = size.heightPt,
                firstLine = snap(y0, PaperGeometry.headerHeight(size, gap)),
                lineGap = gap,
                // textLeft adds 0.35 gap: put the text start on a grid line.
                marginX = snap(x0, PaperGeometry.marginX(size) * 0.6f) - gap * 0.35f,
                edge = pitch,
                template = template,
            )
        }

        /** Cornell: notes on the rules right of the cue line, stopping above the summary box. */
        private fun cornell(size: PageSize): RuledPaper {
            val gap = PaperGeometry.COLLEGE
            val top = PaperGeometry.headerHeight(size, gap)
            return RuledPaper(
                width = size.widthPt,
                height = size.heightPt,
                firstLine = top + gap,
                lineGap = gap,
                marginX = PaperGeometry.cornellCueX(size),
                edge = gap,
                template = PaperTemplate.Cornell,
                bottom = PaperGeometry.cornellSummaryTop(size) - gap * 0.5f,
            )
        }
    }
}

/**
 * Lays notes out on ruled paper the way a student fills a notebook: every block starts on a ruled
 * line and takes whole lines, blocks follow the (logical) lecture order down the page and spill to
 * the next page, and margin notes sit in a side column level with the block they comment on.
 *
 * - **Margin column** on the side away from where lines start (right for LTR, left for RTL), so a
 *   note's arrow points back at the line it belongs to. Created only when there are margin notes;
 *   the main column narrows to leave room for it. A note that no longer fits beside its anchor on
 *   that page goes into the main column instead of being lost.
 * - **Keep with next:** a heading or definition never ends a page without the block after it.
 * - Frames are the bands between ruled lines: a block on lines `a..b` has `top = lineY(a) - gap`
 *   and `bottom = lineY(b)`, so neighbouring frames touch but never overlap. Descenders may dip
 *   below the line, like real writing.
 * - A block taller than a page is placed anyway (it runs off the bottom) so nothing is lost.
 *
 * @param spaceBefore blank lines wanted before an item (skipped at the top of a page).
 * @param reserved lines already written on by the user (page index, line index): a main-column block
 *   never covers one, so a Smart Board re-layout leaves the user's own ink untouched.
 */
class RuledFlow(
    private val paper: RuledPaper,
    private val measurer: BlockMeasurer,
    private val rtl: Boolean = false,
    private val reserved: (page: Int, line: Int) -> Boolean = { _, _ -> false },
    private val spaceBefore: (FlowItem) -> Int = { 0 },
) {
    /** A placed item, with the ruled lines it occupies. */
    data class Slot(val placement: Placement, val firstLine: Int, val lines: Int, val inMargin: Boolean)

    /** Horizontal extent of the main and margin columns for these items. */
    data class Columns(val mainLeft: Float, val mainRight: Float, val marginLeft: Float?, val marginRight: Float?)

    fun columns(hasNotes: Boolean): Columns {
        val left = paper.textLeft
        val right = paper.textRight
        if (!hasNotes) return Columns(left, right, null, null)
        val noteWidth = (right - left) * MARGIN_FRACTION
        val gutter = paper.lineGap * 0.6f
        return if (!rtl) {
            Columns(left, right - noteWidth - gutter, right - noteWidth, right)
        } else {
            Columns(left + noteWidth + gutter, right, left, left + noteWidth)
        }
    }

    fun place(items: List<FlowItem>): List<Slot> {
        val hasNotes = items.any { it.role == RegionRole.Margin }
        val cols = columns(hasNotes)
        val mainWidth = cols.mainRight - cols.mainLeft
        val lineCount = paper.lineCount
        val out = ArrayList<Slot>(items.size)
        val byKey = HashMap<String, Slot>()
        var page = 0
        var line = 0
        var marginPage = 0
        var marginLine = 0

        fun linesOf(item: FlowItem, width: Float) = ceil(measurer.heightFor(item, width) / paper.lineGap - EPS).toInt().coerceAtLeast(1)

        for ((index, item) in items.withIndex()) {
            val anchor = item.anchorKey?.let(byKey::get)
            if (item.role == RegionRole.Margin && cols.marginLeft != null && anchor != null) {
                val width = cols.marginRight!! - cols.marginLeft
                val n = linesOf(item, width)
                if (marginPage < anchor.placement.pageIndex) {
                    marginPage = anchor.placement.pageIndex
                    marginLine = 0
                }
                val start = maxOf(marginLine, anchor.firstLine)
                if (marginPage == anchor.placement.pageIndex && start + n <= lineCount) {
                    val slot = slot(item, marginPage, start, n, cols.marginLeft, cols.marginRight, inMargin = true)
                    out += slot
                    byKey[item.key] = slot
                    marginLine = start + n
                    continue
                }
                // No room beside the anchor: falls through to the main column.
            }
            val n = linesOf(item, mainWidth)
            val blank = if (line == 0) 0 else spaceBefore(item)
            val next = items.getOrNull(index + 1)?.takeIf { item.keepWithNext && it.role != RegionRole.Margin }
            val needed = blank + n + (next?.let { spaceBefore(it) + linesOf(it, mainWidth) } ?: 0)
            var start = line + blank
            if (line > 0 && line + needed > lineCount) {
                page++
                start = 0
            }
            // Skip the user's own lines: the block goes on the first free run of lines that fits.
            var guard = 0
            while ((start until start + n).any { reserved(page, it) } && guard++ < MAX_SKIP) {
                start++
                if (start + n > lineCount && start > 0) {
                    page++
                    start = 0
                }
            }
            val slot = slot(item, page, start, n, cols.mainLeft, cols.mainRight, inMargin = false)
            out += slot
            byKey[item.key] = slot
            line = start + n
            if (line >= lineCount) {
                page++
                line = 0
            }
        }
        return out
    }

    private fun slot(item: FlowItem, page: Int, first: Int, lines: Int, left: Float, right: Float, inMargin: Boolean): Slot {
        val top = paper.lineY(first) - paper.lineGap
        val bottom = paper.lineY(first + lines - 1)
        return Slot(Placement(item.key, page, RectPt(left, top, right, bottom)), first, lines, inMargin)
    }

    private companion object {
        const val MARGIN_FRACTION = 0.27f
        const val MAX_SKIP = 4096
        const val EPS = 0.02f
    }
}
