package app.bestnote.engine.canvas.select

import kotlin.math.hypot

/** INK-11: which strokes outline a shape that can be filled. */
object FillGeometry {
    /** The pen may end this far from where it began (as a share of the path length) and still count as closed. */
    private const val CLOSE_SHARE = 0.15f

    /** ...or this many brush widths, for small shapes. */
    private const val CLOSE_BRUSHES = 1.5f

    private const val MIN_POINTS = 4

    /**
     * True when the path ([xs], [ys]) comes back to its start and encloses an area wider than the
     * brush ([brushSize]) in both directions: a circle, rectangle or loop, not a line, arrow or scribble tail.
     */
    fun isClosed(xs: FloatArray, ys: FloatArray, brushSize: Float): Boolean {
        val n = minOf(xs.size, ys.size)
        if (n < MIN_POINTS) return false
        var length = 0f
        var minX = xs[0]
        var maxX = xs[0]
        var minY = ys[0]
        var maxY = ys[0]
        for (i in 1 until n) {
            length += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            minX = minOf(minX, xs[i]); maxX = maxOf(maxX, xs[i])
            minY = minOf(minY, ys[i]); maxY = maxOf(maxY, ys[i])
        }
        if (length <= 0f || maxX - minX <= brushSize || maxY - minY <= brushSize) return false
        val gap = hypot(xs[n - 1] - xs[0], ys[n - 1] - ys[0])
        return gap <= maxOf(length * CLOSE_SHARE, brushSize * CLOSE_BRUSHES)
    }
}
