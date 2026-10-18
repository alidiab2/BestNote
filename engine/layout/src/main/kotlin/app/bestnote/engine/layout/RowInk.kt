package app.bestnote.engine.layout

import kotlin.math.max

/**
 * LAYOUT-QA: the REAL ink of one row of writing around its baseline, in points (up = above the
 * baseline, down = below it; NaN = none of that kind). Text and pen strokes (underlines, boxes,
 * fraction bars, rules) are kept apart so callers can tell what reaches where; no ink touches any other.
 */
data class RowInk(
    val textUp: Float = Float.NaN,
    val textDown: Float = Float.NaN,
    val penUp: Float = Float.NaN,
    val penDown: Float = Float.NaN,
) {
    val isEmpty: Boolean get() = textUp.isNaN() && penUp.isNaN()

    companion object {
        val None = RowInk()

        /** Air every two pieces of ink keep, in line gaps. */
        const val CLEAR = 0.06f

        /** A dashed rule drawn [above] points above a baseline (pen ink only). */
        fun rule(above: Float, pen: Float = 1f) = RowInk(penUp = above + pen, penDown = -above + pen)

        /**
         * The smallest distance (points) from [prev]'s baseline down to [next]'s baseline at which their
         * ink stays clear; 0 when either has none.
         */
        fun distance(prev: RowInk, next: RowInk, gap: Float): Float {
            var d = 0f
            val clear = CLEAR * gap
            fun need(down: Float, up: Float) {
                if (!down.isNaN() && !up.isNaN()) d = max(d, down + up + clear)
            }
            need(prev.penDown, next.textUp)
            need(prev.penDown, next.penUp)
            need(prev.textDown, next.penUp)
            need(prev.textDown, next.textUp)
            return d
        }

        /** [a] and [b] measured from the same baseline: the ink of both. */
        fun union(a: RowInk, b: RowInk) = RowInk(mx(a.textUp, b.textUp), mx(a.textDown, b.textDown), mx(a.penUp, b.penUp), mx(a.penDown, b.penDown))

        /** [ink] seen from a baseline [dy] points lower (dy > 0) or higher. */
        fun shifted(ink: RowInk, dy: Float) = RowInk(ink.textUp + dy, ink.textDown - dy, ink.penUp + dy, ink.penDown - dy)

        private fun mx(a: Float, b: Float) = if (a.isNaN()) b else if (b.isNaN()) a else max(a, b)
    }
}
