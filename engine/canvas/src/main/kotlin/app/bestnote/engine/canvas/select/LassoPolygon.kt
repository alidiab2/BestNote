package app.bestnote.engine.canvas.select

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A closed freehand lasso in page points (CANVAS-08), with an even-odd point test that only looks
 * at the edges crossing the point's horizontal band, so long lassos stay fast. Pure Kotlin.
 */
class LassoPolygon(private val xs: FloatArray, private val ys: FloatArray) {
    init {
        require(xs.size == ys.size) { "xs and ys differ in size" }
    }

    val size: Int get() = xs.size
    val bounds: Box? = Box.of(xs, ys)

    private val bandCount = if (size < 3) 1 else (size / EDGES_PER_BAND).coerceIn(1, MAX_BANDS)
    private val bandHeight = bounds?.let { (it.height / bandCount).coerceAtLeast(MIN_BAND) } ?: 1f

    /** Per band, the indices i of the edges (i -> i+1, wrapping) whose y-range overlaps it. */
    private val bands: Array<IntArray> = buildBands()

    private fun buildBands(): Array<IntArray> {
        val b = bounds ?: return Array(1) { IntArray(0) }
        val lists = Array(bandCount) { ArrayList<Int>() }
        for (i in 0 until size) {
            val j = if (i + 1 == size) 0 else i + 1
            val lo = band(min(ys[i], ys[j]), b)
            val hi = band(max(ys[i], ys[j]), b)
            for (k in lo..hi) lists[k] += i
        }
        return Array(bandCount) { lists[it].toIntArray() }
    }

    private fun band(y: Float, b: Box): Int = ((y - b.minY) / bandHeight).toInt().coerceIn(0, bandCount - 1)

    /** Even-odd rule: is ([x], [y]) enclosed by the lasso (its last point joined back to its first)? */
    fun contains(x: Float, y: Float): Boolean {
        val b = bounds ?: return false
        if (size < 3 || !b.contains(x, y)) return false
        var inside = false
        for (i in bands[band(y, b)]) {
            val j = if (i + 1 == size) 0 else i + 1
            val yi = ys[i]
            val yj = ys[j]
            if ((yi > y) != (yj > y)) {
                val cx = xs[i] + (y - yi) / (yj - yi) * (xs[j] - xs[i])
                if (x < cx) inside = !inside
            }
        }
        return inside
    }

    /**
     * How much of a stroke's centerline ([count] samples at [sx], [sy]) the lasso encloses, 0..1,
     * weighted by length: each piece of a segment (at most [STEP] long) counts by its midpoint. A single dot is in or out.
     */
    fun fractionInside(sx: FloatArray, sy: FloatArray, count: Int = sx.size): Float {
        if (count <= 0) return 0f
        if (count == 1) return if (contains(sx[0], sy[0])) 1f else 0f
        var total = 0f
        var inside = 0f
        for (i in 0 until count - 1) {
            val len = hypot(sx[i + 1] - sx[i], sy[i + 1] - sy[i])
            total += len
            if (len == 0f) continue
            // Long segments (straight lines) are split so a lasso edge crossing one is weighed fairly.
            val pieces = kotlin.math.ceil(len / STEP).toInt().coerceIn(1, MAX_PIECES)
            for (k in 0 until pieces) {
                val t = (k + 0.5f) / pieces
                if (contains(sx[i] + (sx[i + 1] - sx[i]) * t, sy[i] + (sy[i + 1] - sy[i]) * t)) inside += len / pieces
            }
        }
        // Every sample at the same place: a dot drawn as several inputs.
        if (total == 0f) return if (contains(sx[0], sy[0])) 1f else 0f
        return inside / total
    }

    /** The CANVAS-08 rule: a stroke is selected when more than half of it lies inside the lasso. */
    fun selects(sx: FloatArray, sy: FloatArray, count: Int = sx.size): Boolean = fractionInside(sx, sy, count) > SELECT_FRACTION

    companion object {
        const val SELECT_FRACTION = 0.5f
        /** Page points per probe along a stroke segment. */
        const val STEP = 4f
        private const val MAX_PIECES = 64
        private const val EDGES_PER_BAND = 8
        private const val MAX_BANDS = 256
        private const val MIN_BAND = 1e-3f
    }
}
