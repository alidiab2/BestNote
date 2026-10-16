package app.bestnote.ai.scan

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Makes a flattened board photo easy to read (for the AI and for the user): even lighting, a clean
 * background, crisp, saturated marker colours.
 *
 * - **Light boards and paper:** the background is estimated per colour channel as a smooth
 *   envelope of local maxima (blocks of about 1/24 of the image, spread to neighbours so thick
 *   marker strokes don't count as background). Dividing by it removes shadows, glare gradients and
 *   colour casts in one go: the board turns white and ink keeps its contrast even inside a glare
 *   patch. Then a gentle curve whitens what is left of the background and deepens the ink.
 * - **Blackboards:** the same with local minima, subtracting instead, then stretching chalk to white.
 */
object Enhance {
    fun apply(src: Raster): Raster {
        val w = src.width
        val h = src.height
        val dark = meanLuma(src) < DARK_BOARD
        val block = max(4, max(w, h) / 24)
        val bw = (w + block - 1) / block
        val bh = (h + block - 1) / block
        val out = IntArray(w * h)
        val channels = Array(3) { FloatArray(w * h) }
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            val grid = FloatArray(bw * bh) { if (dark) 255f else 0f }
            for (y in 0 until h) for (x in 0 until w) {
                val v = (src.argb[y * w + x] shr shift and 0xFF).toFloat()
                val g = (y / block) * bw + x / block
                grid[g] = if (dark) min(grid[g], v) else max(grid[g], v)
            }
            val spread = spread(grid, bw, bh, dark)
            val smooth = smooth(spread, bw, bh)
            val ch = channels[c]
            for (y in 0 until h) {
                val gy = ((y + 0.5f) / block - 0.5f).coerceIn(0f, bh - 1f)
                for (x in 0 until w) {
                    val gx = ((x + 0.5f) / block - 0.5f).coerceIn(0f, bw - 1f)
                    val bg = bilinear(smooth, bw, bh, gx, gy)
                    val v = (src.argb[y * w + x] shr shift and 0xFF).toFloat()
                    ch[y * w + x] = if (dark) {
                        ((v - bg) / max(255f - bg, 24f)).coerceIn(0f, 1f) // chalk on a black ground: 0 = board
                    } else {
                        (v / max(bg, 24f)).coerceIn(0f, 1f) // 1 = board
                    }
                }
            }
        }
        for (i in 0 until w * h) {
            var r = channels[0][i]
            var g = channels[1][i]
            var b = channels[2][i]
            if (dark) {
                // Stretch chalk: a faint stroke becomes clearly visible, the board stays near black.
                r = curveDark(r); g = curveDark(g); b = curveDark(b)
                out[i] = Raster.rgb(BLACKBOARD_R + r * (255 - BLACKBOARD_R), BLACKBOARD_G + g * (255 - BLACKBOARD_G), BLACKBOARD_B + b * (255 - BLACKBOARD_B))
            } else {
                r = curveLight(r); g = curveLight(g); b = curveLight(b)
                // A little more saturation so red, green and blue markers stay distinct.
                val l = (r + g + b) / 3
                r = l + (r - l) * SATURATION; g = l + (g - l) * SATURATION; b = l + (b - l) * SATURATION
                out[i] = Raster.rgb(r * 255, g * 255, b * 255)
            }
        }
        return Raster(w, h, out)
    }

    private const val DARK_BOARD = 95f
    private const val SATURATION = 1.35f
    private const val BLACKBOARD_R = 28
    private const val BLACKBOARD_G = 38
    private const val BLACKBOARD_B = 34

    /** Background (≥ 0.9) → white; ink pushed darker. */
    private fun curveLight(v: Float): Float = when {
        v >= 0.9f -> 1f
        else -> (v / 0.9f).pow(1.6f)
    }

    private fun curveDark(v: Float): Float = when {
        v <= 0.06f -> 0f
        else -> ((v - 0.06f) / 0.5f).coerceAtMost(1f).pow(0.8f)
    }

    private fun meanLuma(r: Raster): Float {
        var s = 0.0
        val step = max(1, r.argb.size / 20_000)
        var n = 0
        var i = 0
        while (i < r.argb.size) {
            s += Raster.lumaOf(r.argb[i])
            n++
            i += step
        }
        return (s / n).toFloat()
    }

    /** Each block takes the max (min) of its 3x3 neighbourhood: a block full of marker borrows its neighbours' board. */
    private fun spread(g: FloatArray, w: Int, h: Int, dark: Boolean): FloatArray = FloatArray(g.size) { i ->
        val x = i % w
        val y = i / w
        var v = g[i]
        for (dy in -1..1) for (dx in -1..1) {
            val xx = x + dx
            val yy = y + dy
            if (xx in 0 until w && yy in 0 until h) v = if (dark) min(v, g[yy * w + xx]) else max(v, g[yy * w + xx])
        }
        v
    }

    private fun smooth(g: FloatArray, w: Int, h: Int): FloatArray = FloatArray(g.size) { i ->
        val x = i % w
        val y = i / w
        var s = 0f
        var n = 0
        for (dy in -1..1) for (dx in -1..1) {
            val xx = x + dx
            val yy = y + dy
            if (xx in 0 until w && yy in 0 until h) { s += g[yy * w + xx]; n++ }
        }
        s / n
    }

    private fun bilinear(g: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val x0 = x.toInt().coerceIn(0, w - 1)
        val y0 = y.toInt().coerceIn(0, h - 1)
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val fx = x - x0
        val fy = y - y0
        val top = g[y0 * w + x0] * (1 - fx) + g[y0 * w + x1] * fx
        val bottom = g[y1 * w + x0] * (1 - fx) + g[y1 * w + x1] * fx
        return top * (1 - fy) + bottom * fy
    }
}
