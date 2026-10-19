package app.bestnote.ai.scan

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Flattens a photographed board: the [Quad] becomes an upright rectangle (perspective correction). */
object Perspective {
    /**
     * The output size for [quad] on an image [w]x[h]: the longer of each pair of opposite sides,
     * capped so the long side is at most [maxSide].
     */
    fun outputSize(quad: Quad, w: Int, h: Int, maxSide: Int = 2048): Pair<Int, Int> {
        fun len(a: Pt, b: Pt) = hypot((a.x - b.x) * w, (a.y - b.y) * h)
        val ow = max(len(quad.topLeft, quad.topRight), len(quad.bottomLeft, quad.bottomRight))
        val oh = max(len(quad.topLeft, quad.bottomLeft), len(quad.topRight, quad.bottomRight))
        val k = minOf(1f, maxSide / max(ow, oh))
        return max(8, (ow * k).roundToInt()) to max(8, (oh * k).roundToInt())
    }

    /** Warps the [quad] region of [src] into an upright [outW]x[outH] image (bilinear). */
    fun warp(src: Raster, quad: Quad, outW: Int, outH: Int): Raster {
        val corners = quad.corners.map { Pt(it.x * (src.width - 1), it.y * (src.height - 1)) }
        val dst = listOf(Pt(0f, 0f), Pt(outW - 1f, 0f), Pt(outW - 1f, outH - 1f), Pt(0f, outH - 1f))
        val m = homography(dst, corners)
        val out = IntArray(outW * outH)
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val d = m[6] * x + m[7] * y + 1.0
                val sx = ((m[0] * x + m[1] * y + m[2]) / d).toFloat()
                val sy = ((m[3] * x + m[4] * y + m[5]) / d).toFloat()
                out[y * outW + x] = sample(src, sx, sy)
            }
        }
        return Raster(outW, outH, out)
    }

    /** [mapBack] with the homography worked out once: for mapping many points (a figure's crop, REQ-001). */
    fun mapper(quad: Quad): (Float, Float) -> Pt {
        val m = homography(listOf(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f)), quad.corners)
        return { u, v ->
            val d = m[6] * u + m[7] * v + 1.0
            Pt(((m[0] * u + m[1] * v + m[2]) / d).toFloat(), ((m[3] * u + m[4] * v + m[5]) / d).toFloat())
        }
    }

    /** Maps a normalised point of the flattened image back onto the photo (for previews and tests). */
    fun mapBack(quad: Quad, u: Float, v: Float): Pt {
        val m = homography(listOf(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f)), quad.corners)
        val d = m[6] * u + m[7] * v + 1.0
        return Pt(((m[0] * u + m[1] * v + m[2]) / d).toFloat(), ((m[3] * u + m[4] * v + m[5]) / d).toFloat())
    }

    private fun sample(src: Raster, x: Float, y: Float): Int {
        val cx = x.coerceIn(0f, src.width - 1f)
        val cy = y.coerceIn(0f, src.height - 1f)
        val x0 = floor(cx).toInt().coerceAtMost(src.width - 2).coerceAtLeast(0)
        val y0 = floor(cy).toInt().coerceAtMost(src.height - 2).coerceAtLeast(0)
        if (src.width < 2 || src.height < 2) return src[x0, y0]
        val fx = cx - x0
        val fy = cy - y0
        val a = src[x0, y0]
        val b = src[x0 + 1, y0]
        val c = src[x0, y0 + 1]
        val d = src[x0 + 1, y0 + 1]
        fun ch(shift: Int): Float {
            val top = (a shr shift and 0xFF) * (1 - fx) + (b shr shift and 0xFF) * fx
            val bottom = (c shr shift and 0xFF) * (1 - fx) + (d shr shift and 0xFF) * fx
            return top * (1 - fy) + bottom * fy
        }
        return Raster.rgb(ch(16), ch(8), ch(0))
    }

    /** The 3x3 homography (h33 = 1, as 8 numbers) taking each [from] point to the matching [to] point. */
    internal fun homography(from: List<Pt>, to: List<Pt>): DoubleArray {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = from[i].x.toDouble()
            val y = from[i].y.toDouble()
            val u = to[i].x.toDouble()
            val v = to[i].y.toDouble()
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        // Gauss-Jordan with partial pivoting.
        for (col in 0 until 8) {
            val pivot = (col until 8).maxBy { abs(a[it][col]) }
            val t = a[col]; a[col] = a[pivot]; a[pivot] = t
            val p = a[col][col]
            require(abs(p) > 1e-12) { "Degenerate quad" }
            for (k in col until 9) a[col][k] /= p
            for (r in 0 until 8) if (r != col) {
                val f = a[r][col]
                if (f != 0.0) for (k in col until 9) a[r][k] -= f * a[col][k]
            }
        }
        return DoubleArray(8) { a[it][8] }
    }
}
