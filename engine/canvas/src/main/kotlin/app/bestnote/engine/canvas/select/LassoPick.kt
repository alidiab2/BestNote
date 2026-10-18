package app.bestnote.engine.canvas.select

import app.bestnote.core.model.PageElement
import kotlin.math.hypot

/**
 * What a lasso loop or a lasso tap picks on a page, through a [LassoFilter] (CANVAS-20). Generic over the stroke
 * type so it is tested without androidx.ink: [outline] gives a stroke's centerline in page points. Pure Kotlin.
 */
object LassoPick {
    /** The [candidates] a lasso [poly] selects (partially enclosed, see [LassoPolygon.selects]) that [filter] takes. */
    fun <S> strokes(candidates: List<S>, poly: LassoPolygon, filter: LassoFilter, isShape: (S) -> Boolean, outline: (S) -> Pair<FloatArray, FloatArray>): List<S> =
        candidates.filter { s ->
            filter.takesStroke(isShape(s)) && outline(s).let { (xs, ys) -> poly.selects(xs, ys) }
        }

    /** The [elements] a lasso [poly] picks up: unlocked ones of a kind [filter] takes. */
    fun elements(elements: List<PageElement>, poly: LassoPolygon, filter: LassoFilter): List<PageElement> =
        elements.filter { filter.takesElement(it) && ElementTransforms.lassoPicks(poly, it) }

    /** The topmost element under a tap at ([x], [y]) that [filter] takes (locked ones too: a tap selects them to unlock). */
    fun tappedElement(elements: List<PageElement>, x: Float, y: Float, filter: LassoFilter): PageElement? =
        elements.lastOrNull { filter.takesElement(it) && ElementTransforms.contains(it, x, y) }

    /**
     * The topmost shape stroke under a tap: within [tolerance] of its line, or inside it when [closed]. Only shapes
     * (a tap on handwriting would grab single letters by accident), and only when [filter] takes shapes.
     */
    fun <S> tappedShape(
        candidates: List<S>,
        x: Float,
        y: Float,
        tolerance: Float,
        filter: LassoFilter,
        isShape: (S) -> Boolean,
        closed: (S) -> Boolean,
        outline: (S) -> Pair<FloatArray, FloatArray>,
    ): S? {
        if (!filter.takesStroke(isShape = true)) return null
        return candidates.lastOrNull { s ->
            if (!isShape(s)) return@lastOrNull false
            val (xs, ys) = outline(s)
            near(xs, ys, x, y, tolerance) || (closed(s) && xs.size >= 3 && LassoPolygon(xs, ys).contains(x, y))
        }
    }

    /** Everything on the page that [filter] takes, for "Select all": unlocked elements only. */
    fun <S> all(strokes: List<S>, elements: List<PageElement>, filter: LassoFilter, isShape: (S) -> Boolean): Pair<List<S>, List<PageElement>> =
        strokes.filter { filter.takesStroke(isShape(it)) } to elements.filter { !it.locked && filter.takesElement(it) }

    /** Is ([x], [y]) within [tolerance] of the polyline [xs], [ys]? */
    fun near(xs: FloatArray, ys: FloatArray, x: Float, y: Float, tolerance: Float): Boolean {
        val n = minOf(xs.size, ys.size)
        if (n == 0) return false
        if (n == 1) return hypot(xs[0] - x, ys[0] - y) <= tolerance
        for (i in 1 until n) {
            if (segmentDistance(xs[i - 1], ys[i - 1], xs[i], ys[i], x, y) <= tolerance) return true
        }
        return false
    }

    private fun segmentDistance(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(ax + t * dx - px, ay + t * dy - py)
    }
}
