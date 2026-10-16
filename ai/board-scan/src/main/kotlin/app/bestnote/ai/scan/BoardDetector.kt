package app.bestnote.ai.scan

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the whiteboard, blackboard or page on a photo, as four corners (AI-04).
 *
 * Works on a small copy (about 256 px on the long side), so it runs in a few milliseconds:
 * 1. luma, blurred, and its Sobel edge map, thresholded with Otsu's method;
 * 2. candidate regions grown from seeds near the centre — over pixels that are not edges
 *    (any board with a visible border), and over pixels on the bright or dark side of an Otsu split
 *    of the luma (a light board on a dark wall, a blackboard on a light one);
 * 3. each region's convex hull, and the largest quadrilateral inscribed in it;
 * 4. the best candidate by how much of the photo it covers and how quad-like its hull is.
 *
 * Returns null when nothing board-like stands out (then the crop starts as the whole photo).
 */
object BoardDetector {
    /** A found outline and how sure the detector is (0..1). */
    data class Result(val quad: Quad, val confidence: Float)

    private const val WORK_SIDE = 256
    private const val MIN_AREA = 0.12f
    private const val MAX_AREA = 0.985f
    private const val MIN_QUADNESS = 0.86f

    fun detect(image: Raster): Result? {
        val small = image.shrink(WORK_SIDE)
        val w = small.width
        val h = small.height
        if (w < 16 || h < 16) return null
        val luma = blur(blur(small.luma(), w, h), w, h)
        val grad = sobel(luma, w, h)
        val edge = dilate(threshold(grad, otsu(grad, 0f, grad.maxOrNull() ?: 1f)), w, h)
        val split = otsu(luma, 0f, 255f)
        val candidates = ArrayList<BooleanArray>(3)
        candidates += grow(w, h) { !edge[it] }
        candidates += grow(w, h) { luma[it] > split }
        candidates += grow(w, h) { luma[it] <= split }
        var best: Result? = null
        for (mask in candidates) {
            val r = fit(fillHoles(mask, w, h), w, h) ?: continue
            if (best == null || r.confidence > best.confidence) best = r
        }
        return best
    }

    /** Hull → inscribed quad → score. */
    private fun fit(mask: BooleanArray, w: Int, h: Int): Result? {
        val count = mask.count { it }
        val coverage = count.toFloat() / (w * h)
        if (coverage < MIN_AREA * 0.6f) return null
        val pts = ArrayList<Pt>()
        for (y in 0 until h) {
            var lo = -1
            var hi = -1
            for (x in 0 until w) if (mask[y * w + x]) {
                if (lo < 0) lo = x
                hi = x
            }
            if (lo >= 0) {
                pts += Pt(lo.toFloat(), y.toFloat())
                pts += Pt(hi + 1f, y.toFloat())
                pts += Pt(lo.toFloat(), y + 1f)
                pts += Pt(hi + 1f, y + 1f)
            }
        }
        val hull = hull(pts)
        if (hull.size < 4) return null
        val quadPx = maxQuad(hull)
        val hullArea = Geometry.area(hull)
        val quadArea = Geometry.area(quadPx)
        val quadness = quadArea / hullArea
        val area = quadArea / (w * h)
        if (area !in MIN_AREA..MAX_AREA || quadness < MIN_QUADNESS) return null
        // Mostly-filled hull: a region with big bites out of it is not a board.
        val solidity = count / hullArea
        if (solidity < 0.8f) return null
        val quad = Quad.ordered(quadPx.map { Pt(it.x / w, it.y / h) })
        if (!quad.isConvex) return null
        val confidence = ((quadness - MIN_QUADNESS) / (1 - MIN_QUADNESS)).coerceIn(0f, 1f) * 0.5f +
            min(1f, area / 0.35f) * 0.3f + min(1f, solidity) * 0.2f
        return Result(quad, confidence)
    }

    // ------------------------------------------------------------------ regions

    /** Union of the 4-connected regions of [inside] that contain one of a few seeds near the centre; the biggest wins. */
    private fun grow(w: Int, h: Int, inside: (Int) -> Boolean): BooleanArray {
        var best = BooleanArray(w * h)
        var bestCount = 0
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        for ((fx, fy) in SEEDS) {
            val seed = (fy * h).toInt() * w + (fx * w).toInt()
            if (seen[seed] || !inside(seed)) continue
            val mask = BooleanArray(w * h)
            var n = 0
            var top = 0
            stack[top++] = seed
            seen[seed] = true
            while (top > 0) {
                val i = stack[--top]
                mask[i] = true
                n++
                val x = i % w
                val y = i / w
                if (x > 0) push(i - 1, seen, inside, stack, top).let { if (it) top++ }
                if (x < w - 1) push(i + 1, seen, inside, stack, top).let { if (it) top++ }
                if (y > 0) push(i - w, seen, inside, stack, top).let { if (it) top++ }
                if (y < h - 1) push(i + w, seen, inside, stack, top).let { if (it) top++ }
            }
            if (n > bestCount) {
                best = mask
                bestCount = n
            }
        }
        return best
    }

    private fun push(i: Int, seen: BooleanArray, inside: (Int) -> Boolean, stack: IntArray, top: Int): Boolean {
        if (seen[i] || !inside(i)) return false
        seen[i] = true
        stack[top] = i
        return true
    }

    /** Fills everything the outside can't reach: writing on a board, holes in a page. */
    private fun fillHoles(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val outside = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var top = 0
        fun seed(i: Int) {
            if (!mask[i] && !outside[i]) { outside[i] = true; stack[top++] = i }
        }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        while (top > 0) {
            val i = stack[--top]
            val x = i % w
            val y = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w)
            if (y < h - 1) seed(i + w)
        }
        return BooleanArray(w * h) { !outside[it] }
    }

    private val SEEDS = listOf(
        0.5f to 0.5f, 0.38f to 0.5f, 0.62f to 0.5f, 0.5f to 0.38f, 0.5f to 0.62f,
        0.35f to 0.35f, 0.65f to 0.35f, 0.35f to 0.65f, 0.65f to 0.65f,
    )

    // ------------------------------------------------------------------ hull and quad

    /** Andrew's monotone chain; counter-clockwise in math axes (clockwise on screen). */
    internal fun hull(points: List<Pt>): List<Pt> {
        val p = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (p.size < 3) return p
        fun cross(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = ArrayList<Pt>()
        for (q in p) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), q) <= 0) lower.removeAt(lower.lastIndex)
            lower += q
        }
        val upper = ArrayList<Pt>()
        for (q in p.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), q) <= 0) upper.removeAt(upper.lastIndex)
            upper += q
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    /**
     * Largest-area quadrilateral with its corners on hull vertices: for every diagonal (i, k) the best
     * third and fourth corners are the vertices farthest from it on either side. O(n³) on a hull that
     * is simplified to at most [MAX_HULL] vertices first.
     */
    internal fun maxQuad(hullIn: List<Pt>): List<Pt> {
        val hull = simplify(hullIn)
        val n = hull.size
        if (n <= 4) return (hull + List(4 - n) { hull.last() })
        var best = 0f
        var out = hull.take(4)
        for (i in 0 until n) for (k in i + 2 until n) {
            val a = hull[i]
            val c = hull[k]
            var bestJ = -1
            var dj = 0f
            for (j in i + 1 until k) {
                val d = abs(tri(a, c, hull[j]))
                if (d > dj) { dj = d; bestJ = j }
            }
            var bestL = -1
            var dl = 0f
            for (l in (k + 1 until n) + (0 until i)) {
                val d = abs(tri(a, c, hull[l]))
                if (d > dl) { dl = d; bestL = l }
            }
            if (bestJ < 0 || bestL < 0) continue
            if (dj + dl > best) {
                best = dj + dl
                out = listOf(a, hull[bestJ], c, hull[bestL])
            }
        }
        return out
    }

    private const val MAX_HULL = 48

    /** Drops the hull vertices that bend least until at most [MAX_HULL] remain. */
    private fun simplify(hull: List<Pt>): List<Pt> {
        val p = hull.toMutableList()
        while (p.size > MAX_HULL) {
            var drop = 0
            var least = Float.MAX_VALUE
            for (i in p.indices) {
                val t = abs(tri(p[(i + p.size - 1) % p.size], p[(i + 1) % p.size], p[i]))
                if (t < least) { least = t; drop = i }
            }
            p.removeAt(drop)
        }
        return p
    }

    private fun tri(a: Pt, b: Pt, c: Pt) = ((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) / 2

    // ------------------------------------------------------------------ filters

    internal fun blur(v: FloatArray, w: Int, h: Int): FloatArray {
        val tmp = FloatArray(v.size)
        val out = FloatArray(v.size)
        for (y in 0 until h) for (x in 0 until w) {
            val l = v[y * w + max(0, x - 1)]
            val r = v[y * w + min(w - 1, x + 1)]
            tmp[y * w + x] = (l + 2 * v[y * w + x] + r) / 4
        }
        for (y in 0 until h) for (x in 0 until w) {
            val u = tmp[max(0, y - 1) * w + x]
            val d = tmp[min(h - 1, y + 1) * w + x]
            out[y * w + x] = (u + 2 * tmp[y * w + x] + d) / 4
        }
        return out
    }

    private fun sobel(v: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(v.size)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            fun p(dx: Int, dy: Int) = v[(y + dy) * w + x + dx]
            val gx = p(1, -1) + 2 * p(1, 0) + p(1, 1) - p(-1, -1) - 2 * p(-1, 0) - p(-1, 1)
            val gy = p(-1, 1) + 2 * p(0, 1) + p(1, 1) - p(-1, -1) - 2 * p(0, -1) - p(1, -1)
            out[y * w + x] = hypot(gx, gy)
        }
        // The frame of the photo is an edge too, so regions never leak around the border.
        return out
    }

    private fun threshold(v: FloatArray, t: Float) = BooleanArray(v.size) { v[it] > t }

    private fun dilate(m: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = m.copyOf()
        for (y in 0 until h) for (x in 0 until w) if (m[y * w + x]) {
            if (x > 0) out[y * w + x - 1] = true
            if (x < w - 1) out[y * w + x + 1] = true
            if (y > 0) out[(y - 1) * w + x] = true
            if (y < h - 1) out[(y + 1) * w + x] = true
        }
        return out
    }

    /** Otsu's threshold of [v] over [lo]..[hi] (64 bins). */
    internal fun otsu(v: FloatArray, lo: Float, hi: Float): Float {
        val bins = 64
        val span = max(hi - lo, 1e-3f)
        val hist = IntArray(bins)
        for (x in v) hist[((x - lo) / span * (bins - 1)).toInt().coerceIn(0, bins - 1)]++
        val total = v.size.toDouble()
        var sum = 0.0
        for (i in 0 until bins) sum += i * hist[i].toDouble()
        var sumB = 0.0
        var wB = 0.0
        var best = 0.0
        var at = bins / 2
        for (i in 0 until bins) {
            wB += hist[i]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; at = i }
        }
        return lo + (at + 0.5f) / (bins - 1) * span
    }

}
