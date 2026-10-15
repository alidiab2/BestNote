package app.bestnote.engine.layout

import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.RectPt
import app.bestnote.engine.layout.paper.PaperGeometry
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Ruled notebook paper, in page units (the unit the strokes are written in).
 *
 * @property firstLine y of the first ruled writing line (a baseline).
 * @property lineGap distance between ruled lines.
 * @property marginX x of the vertical margin line on the left. The paper never mirrors: Arabic
 *   notes keep the same paper and start at the right edge instead.
 * @property edge empty border kept at the page's other edges.
 */
data class RuledPaper(
    val width: Float,
    val height: Float,
    val firstLine: Float,
    val lineGap: Float,
    val marginX: Float,
    val edge: Float = lineGap * 0.75f,
) {
    init {
        require(width > marginX + 4 * lineGap && height > firstLine + lineGap) { "Paper too small: $this" }
    }

    /** Ruled lines that fit on the page with room for descenders below the last one. */
    val lineCount: Int get() = floor((height - edge - firstLine) / lineGap).toInt() + 1

    /** Baseline y of ruled line [i] (0-based). */
    fun lineY(i: Int): Float = firstLine + i * lineGap

    /** Text area between the margin line and the right edge. */
    val textLeft: Float get() = marginX + lineGap * 0.35f
    val textRight: Float get() = width - edge

    companion object {
        /** True for the templates Smart Board can write on line by line. */
        fun isRuled(template: PaperTemplate) =
            template == PaperTemplate.Ruled || template == PaperTemplate.RuledNarrow || template == PaperTemplate.RuledWide

        /**
         * The ruled paper the editor prints for [template] at [size] (PAGES-01 geometry, points).
         * Templates without writing lines fall back to college ruling.
         */
        fun of(template: PaperTemplate, size: PageSize): RuledPaper {
            val gap = when (template) {
                PaperTemplate.RuledNarrow -> PaperGeometry.NARROW
                PaperTemplate.RuledWide -> PaperGeometry.WIDE
                else -> PaperGeometry.COLLEGE
            }
            return RuledPaper(
                width = size.widthPt,
                height = size.heightPt,
                firstLine = PaperGeometry.headerHeight(size, gap),
                lineGap = gap,
                marginX = PaperGeometry.marginX(size),
                edge = gap,
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
 */
class RuledFlow(
    private val paper: RuledPaper,
    private val measurer: BlockMeasurer,
    private val rtl: Boolean = false,
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
        const val EPS = 0.02f
    }
}
