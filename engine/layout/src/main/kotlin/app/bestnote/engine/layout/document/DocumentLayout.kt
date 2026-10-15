package app.bestnote.engine.layout.document

import app.bestnote.core.model.PageSize
import kotlin.math.max
import kotlin.math.roundToInt

/** An integer pixel rectangle in document coordinates (origin at the top of the first page's padding). */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

/**
 * Spacing of the paged document, in pixels.
 *
 * @property gap space between two pages.
 * @property addCardHeight height of the "+ New page" card after the last page (0 = no card).
 */
data class DocumentMetrics(
    val topPad: Int,
    val bottomPad: Int,
    val sidePad: Int,
    val gap: Int,
    val addCardHeight: Int,
)

/**
 * Where every page of a vertical, paged document sits (CANVAS-07).
 *
 * One scale for the whole document: the widest page fits the viewport width minus [DocumentMetrics.sidePad]
 * on each side, times [zoom]. Narrower pages are centered. Page rectangles are integer pixels, so a page's
 * origin is pixel-aligned and [app.bestnote.engine.layout.paper.PixelSnap] keeps template lines crisp.
 * Construction is O(pages) (prefix sums); every query after that is O(log pages) or O(1).
 */
class DocumentLayout(
    private val sizes: List<PageSize>,
    val viewportWidth: Int,
    val metrics: DocumentMetrics,
    val zoom: Float = 1f,
) {
    val pageCount: Int get() = sizes.size

    /** Pixels per point. */
    val scale: Float

    /** Content width: the viewport, or wider when zoomed in. */
    val contentWidth: Int

    private val tops = IntArray(sizes.size)
    private val heights = IntArray(sizes.size)
    private val widths = IntArray(sizes.size)

    val addCard: PxRect

    /** Total scrollable height in pixels. */
    val contentHeight: Int

    init {
        require(viewportWidth > 0) { "viewportWidth must be > 0" }
        val widest = sizes.maxOfOrNull { it.widthPt } ?: PageSize.A4.widthPt
        val avail = (viewportWidth - 2 * metrics.sidePad).coerceAtLeast(1)
        scale = avail / widest * zoom
        var y = metrics.topPad
        var maxW = 0
        for (i in sizes.indices) {
            widths[i] = (sizes[i].widthPt * scale).roundToInt().coerceAtLeast(1)
            heights[i] = (sizes[i].heightPt * scale).roundToInt().coerceAtLeast(1)
            tops[i] = y
            y += heights[i] + metrics.gap
            maxW = max(maxW, widths[i])
        }
        contentWidth = max(viewportWidth, maxW + 2 * metrics.sidePad)
        val cardW = if (sizes.isEmpty()) avail else widths[sizes.size - 1]
        val cardLeft = (contentWidth - cardW) / 2
        addCard = if (metrics.addCardHeight > 0) {
            PxRect(cardLeft, y, cardLeft + cardW, y + metrics.addCardHeight)
        } else {
            PxRect(cardLeft, y - metrics.gap, cardLeft + cardW, y - metrics.gap)
        }
        contentHeight = addCard.bottom + metrics.bottomPad
    }

    fun pageRect(index: Int): PxRect {
        val left = (contentWidth - widths[index]) / 2
        return PxRect(left, tops[index], left + widths[index], tops[index] + heights[index])
    }

    fun maxScroll(viewportHeight: Int): Int = (contentHeight - viewportHeight).coerceAtLeast(0)

    /** Pages intersecting `[scrollY, scrollY + viewportHeight)`; empty when none (e.g. only the add card shows). */
    fun visiblePages(scrollY: Int, viewportHeight: Int): IntRange {
        if (sizes.isEmpty() || viewportHeight <= 0) return IntRange.EMPTY
        val bottom = scrollY + viewportHeight
        // First page whose bottom is below scrollY.
        var lo = 0
        var hi = sizes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tops[mid] + heights[mid] <= scrollY) lo = mid + 1 else hi = mid
        }
        val first = lo
        if (first >= sizes.size || tops[first] >= bottom) return IntRange.EMPTY
        // Last page whose top is above the viewport bottom.
        lo = first
        hi = sizes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tops[mid] < bottom) lo = mid + 1 else hi = mid
        }
        return first..(lo - 1)
    }

    /**
     * The page the reader is on, for the "3 / 12" indicator: the page with the most visible pixels
     * (the earlier one on a tie). Past the last page (looking at the add card) it is the last page.
     */
    fun currentPage(scrollY: Int, viewportHeight: Int): Int {
        if (sizes.isEmpty()) return -1
        val visible = visiblePages(scrollY, viewportHeight)
        if (visible.isEmpty()) return if (scrollY < metrics.topPad) 0 else sizes.size - 1
        val bottom = scrollY + viewportHeight
        var best = visible.first
        var bestPx = -1
        for (i in visible) {
            val px = minOf(bottom, tops[i] + heights[i]) - maxOf(scrollY, tops[i])
            if (px > bestPx) {
                best = i
                bestPx = px
            }
        }
        return best
    }

    /** Index of the page under document point ([x], [y]), or -1 (gap, desk or add card). */
    fun pageAt(x: Float, y: Float): Int {
        if (sizes.isEmpty()) return -1
        var lo = 0
        var hi = sizes.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (tops[mid] <= y) lo = mid else hi = mid - 1
        }
        return if (pageRect(lo).contains(x, y)) lo else -1
    }

    /** Scroll offset that shows page [index] at the top with the usual padding above it. */
    fun scrollYFor(index: Int, viewportHeight: Int): Int =
        (tops[index.coerceIn(0, sizes.size - 1)] - metrics.topPad).coerceIn(0, maxScroll(viewportHeight))
}
