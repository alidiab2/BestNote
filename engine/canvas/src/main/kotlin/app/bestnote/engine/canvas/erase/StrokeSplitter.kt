package app.bestnote.engine.canvas.erase

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The eraser swept from ([ax], [ay]) to ([bx], [by]) with radius [r]: a capsule (two discs and the
 * rectangle between them). A single eraser sample is a capsule with A == B.
 */
data class EraserCapsule(val ax: Float, val ay: Float, val bx: Float, val by: Float, val r: Float) {
    val minX: Float get() = min(ax, bx) - r
    val maxX: Float get() = max(ax, bx) + r
    val minY: Float get() = min(ay, by) - r
    val maxY: Float get() = max(ay, by) + r
}

/**
 * Pixel-eraser geometry (CANVAS-01). Pure Kotlin, so it is unit-tested without androidx.ink.
 *
 * A stroke is its polyline of input samples. Positions along it are a parameter `u` in
 * `[0, count - 1]`: `u = i + t` lies between sample `i` and `i + 1` at fraction `t`. The eraser
 * removes every part of the centerline within the capsule's radius, widened by half the stroke
 * width ([extra]) so that the round end of each remaining piece stops at the eraser's edge: clean
 * ends, no sliver of ink left under the eraser.
 */
object StrokeSplitter {
    private const val EPS = 1e-4f

    /**
     * The kept parts of a stroke with [count] samples at ([xs], [ys]) after erasing [capsules]
     * (each widened by [extra]), as `u` ranges in order. Null when the eraser missed the stroke
     * entirely (leave it untouched); empty when nothing is left. Pieces whose centerline is shorter
     * than [minPieceLength] are dropped: they would only leave crumbs.
     */
    fun keptRanges(
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        capsules: List<EraserCapsule>,
        extra: Float = 0f,
        minPieceLength: Float = 0f,
    ): List<ClosedFloatingPointRange<Float>>? {
        if (count <= 0 || capsules.isEmpty()) return null
        if (count == 1) {
            val hit = capsules.any { distToSegment(xs[0], ys[0], it) <= it.r + extra }
            return if (hit) emptyList() else null
        }
        val erased = ArrayList<FloatArray>()
        for (i in 0 until count - 1) {
            for (c in capsules) {
                val iv = segmentInCapsule(xs[i], ys[i], xs[i + 1], ys[i + 1], c, c.r + extra) ?: continue
                erased += floatArrayOf(i + iv[0], i + iv[1])
            }
        }
        if (erased.isEmpty()) return null
        erased.sortBy { it[0] }
        val last = (count - 1).toFloat()
        val kept = ArrayList<ClosedFloatingPointRange<Float>>()
        var cursor = 0f
        for (iv in erased) {
            if (iv[0] > cursor + EPS) kept += cursor..iv[0]
            cursor = max(cursor, iv[1])
        }
        if (cursor < last - EPS) kept += cursor..last
        return kept.filter { length(xs, ys, it) >= max(minPieceLength, EPS) }
    }

    /** True when any part of the stroke's centerline lies within a capsule (widened by [extra]). */
    fun touches(xs: FloatArray, ys: FloatArray, count: Int, capsules: List<EraserCapsule>, extra: Float = 0f): Boolean {
        if (count <= 0) return false
        if (count == 1) return capsules.any { distToSegment(xs[0], ys[0], it) <= it.r + extra }
        for (i in 0 until count - 1) {
            for (c in capsules) if (segmentInCapsule(xs[i], ys[i], xs[i + 1], ys[i + 1], c, c.r + extra) != null) return true
        }
        return false
    }

    /** Sample indices strictly inside the open range (u0, u1): the inputs a piece copies as they are. */
    fun innerSamples(range: ClosedFloatingPointRange<Float>): IntRange {
        val first = if (abs(range.start - Math.round(range.start)) < EPS) Math.round(range.start) + 1 else ceil(range.start).toInt()
        val last = if (abs(range.endInclusive - Math.round(range.endInclusive)) < EPS) Math.round(range.endInclusive) - 1 else floor(range.endInclusive).toInt()
        return first..last
    }

    /** Centerline length of the range [range]. */
    fun length(xs: FloatArray, ys: FloatArray, range: ClosedFloatingPointRange<Float>): Float {
        var len = 0f
        var px = lerpAt(xs, range.start)
        var py = lerpAt(ys, range.start)
        for (i in innerSamples(range)) {
            len += hypot(xs[i] - px, ys[i] - py)
            px = xs[i]
            py = ys[i]
        }
        return len + hypot(lerpAt(xs, range.endInclusive) - px, lerpAt(ys, range.endInclusive) - py)
    }

    /** Linear interpolation of per-sample values [v] at `u`. */
    fun lerpAt(v: FloatArray, u: Float): Float {
        val i = floor(u).toInt().coerceIn(0, v.size - 1)
        if (i >= v.size - 1) return v[v.size - 1]
        val t = u - i
        return v[i] + (v[i + 1] - v[i]) * t
    }

    /**
     * The `t` interval of segment P0→P1 inside capsule [c] with radius [r], or null. The set is
     * convex (distance to a segment is a convex function), so the union of the hits on both discs
     * and the rectangle between them is one interval: from the smallest start to the largest end.
     */
    internal fun segmentInCapsule(x0: Float, y0: Float, x1: Float, y1: Float, c: EraserCapsule, r: Float): FloatArray? {
        // Cheap reject on bounds.
        if (max(x0, x1) < min(c.ax, c.bx) - r || min(x0, x1) > max(c.ax, c.bx) + r ||
            max(y0, y1) < min(c.ay, c.by) - r || min(y0, y1) > max(c.ay, c.by) + r
        ) {
            return null
        }
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        fun take(iv: FloatArray?) {
            if (iv == null) return
            lo = min(lo, iv[0])
            hi = max(hi, iv[1])
        }
        take(segmentInDisc(x0, y0, x1, y1, c.ax, c.ay, r))
        if (c.ax != c.bx || c.ay != c.by) {
            take(segmentInDisc(x0, y0, x1, y1, c.bx, c.by, r))
            take(segmentInRect(x0, y0, x1, y1, c, r))
        }
        return if (lo <= hi) floatArrayOf(lo, hi) else null
    }

    private fun segmentInDisc(x0: Float, y0: Float, x1: Float, y1: Float, cx: Float, cy: Float, r: Float): FloatArray? {
        val dx = (x1 - x0).toDouble()
        val dy = (y1 - y0).toDouble()
        val fx = (x0 - cx).toDouble()
        val fy = (y0 - cy).toDouble()
        val a = dx * dx + dy * dy
        val c = fx * fx + fy * fy - r.toDouble() * r
        if (a < 1e-12) return if (c <= 0) floatArrayOf(0f, 1f) else null
        val b = 2 * (fx * dx + fy * dy)
        val disc = b * b - 4 * a * c
        if (disc < 0) return null
        val sq = sqrt(disc)
        val t0 = ((-b - sq) / (2 * a)).coerceAtLeast(0.0)
        val t1 = ((-b + sq) / (2 * a)).coerceAtMost(1.0)
        return if (t0 <= t1) floatArrayOf(t0.toFloat(), t1.toFloat()) else null
    }

    /** Liang–Barsky clip of the segment against the capsule's rectangle, in the capsule's own frame. */
    private fun segmentInRect(x0: Float, y0: Float, x1: Float, y1: Float, c: EraserCapsule, r: Float): FloatArray? {
        val ex = (c.bx - c.ax).toDouble()
        val ey = (c.by - c.ay).toDouble()
        val len = sqrt(ex * ex + ey * ey)
        if (len < 1e-9) return null
        val ux = ex / len
        val uy = ey / len
        val px = (x0 - c.ax).toDouble()
        val py = (y0 - c.ay).toDouble()
        val dx = (x1 - x0).toDouble()
        val dy = (y1 - y0).toDouble()
        // Along (u) and across (v) coordinates as linear functions of t.
        val u0 = px * ux + py * uy
        val du = dx * ux + dy * uy
        val v0 = -px * uy + py * ux
        val dv = -dx * uy + dy * ux
        var t0 = 0.0
        var t1 = 1.0
        fun clip(p: Double, q: Double): Boolean { // p * t <= q
            if (abs(p) < 1e-12) return q >= 0
            val t = q / p
            if (p < 0) t0 = max(t0, t) else t1 = min(t1, t)
            return t0 <= t1
        }
        if (!clip(-du, u0)) return null // u >= 0
        if (!clip(du, len - u0)) return null // u <= len
        if (!clip(-dv, v0 + r)) return null // v >= -r
        if (!clip(dv, r - v0)) return null // v <= r
        return floatArrayOf(t0.toFloat(), t1.toFloat())
    }

    private fun distToSegment(x: Float, y: Float, c: EraserCapsule): Float {
        val ex = c.bx - c.ax
        val ey = c.by - c.ay
        val l2 = ex * ex + ey * ey
        val t = if (l2 <= 0f) 0f else (((x - c.ax) * ex + (y - c.ay) * ey) / l2).coerceIn(0f, 1f)
        return hypot(x - (c.ax + t * ex), y - (c.ay + t * ey))
    }
}
