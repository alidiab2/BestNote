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
    fun fractionInside(sx: FloatArray, sy: FloatArray, count: Int = sx.size): Float = coverage(sx, sy, count).fraction

    /** Enclosed share of a stroke's centerline: as a fraction of its length and in page points. */
    data class Coverage(val fraction: Float, val insideLength: Float)

    fun coverage(sx: FloatArray, sy: FloatArray, count: Int = sx.size): Coverage {
        if (count <= 0) return Coverage(0f, 0f)
        if (count == 1) return if (contains(sx[0], sy[0])) Coverage(1f, 0f) else Coverage(0f, 0f)
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
        if (total == 0f) return if (contains(sx[0], sy[0])) Coverage(1f, 0f) else Coverage(0f, 0f)
        return Coverage(inside / total, inside)
    }

    /**
     * A stroke is selected when a meaningful part of it lies inside the lasso: at least [SELECT_FRACTION]
     * of its length, or [SELECT_LENGTH] points of it. Circling part of a big shape picks the whole shape,
     * while a neighbour the lasso only grazes stays out.
     */
    fun selects(sx: FloatArray, sy: FloatArray, count: Int = sx.size): Boolean {
        val c = coverage(sx, sy, count)
        return c.fraction >= SELECT_FRACTION || c.insideLength >= SELECT_LENGTH
    }

    companion object {
        const val SELECT_FRACTION = 0.1f
        /** Page points (about 8 mm) of enclosed centerline that select any stroke, however long. */
        const val SELECT_LENGTH = 24f
        /** Page points per probe along a stroke segment. */
        const val STEP = 4f
        private const val MAX_PIECES = 64
        private const val EDGES_PER_BAND = 8
        private const val MAX_BANDS = 256
        private const val MIN_BAND = 1e-3f
    }
}
