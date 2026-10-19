package app.bestnote.ai.scan.figure

import app.bestnote.ai.scan.Enhance
import app.bestnote.ai.scan.Perspective
import app.bestnote.ai.scan.Quad
import app.bestnote.ai.scan.Raster
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * REQ-001: where a figure's working crop is cut from. The model reads a 1600 px copy of the board; the
 * redraw can cut each drawing from the photo at full resolution instead (pale markers, dots and
 * arrowheads survive), at no token cost. [crop] gets a rectangle of the flattened board in fractions
 * (0..1) and returns it as a [w] × [h] raster, or null when it cannot (then the 1600 px board is used).
 */
fun interface BoardPixels {
    fun crop(left: Float, top: Float, right: Float, bottom: Float, w: Int, h: Int): Raster?

    companion object {
        /** A board already in memory (tests, the bench): bilinear resampling of the rectangle. */
        fun of(board: Raster): BoardPixels = BoardPixels { l, t, r, b, w, h ->
            sample(w, h, { u, v -> (u * board.width - 0.5f) to (v * board.height - 0.5f) }, l, t, r, b) { x, y -> bilinear(board, x, y) }
        }

        internal fun sample(w: Int, h: Int, at: (Float, Float) -> Pair<Float, Float>, l: Float, t: Float, r: Float, b: Float, px: (Float, Float) -> Int): Raster {
            val out = IntArray(w * h)
            for (y in 0 until h) {
                val v = t + (b - t) * (y + 0.5f) / h
                for (x in 0 until w) {
                    val (sx, sy) = at(l + (r - l) * (x + 0.5f) / w, v)
                    out[y * w + x] = px(sx, sy)
                }
            }
            return Raster(w, h, out)
        }

        internal fun bilinear(src: Raster, x: Float, y: Float): Int {
            val cx = x.coerceIn(0f, src.width - 1f)
            val cy = y.coerceIn(0f, src.height - 1f)
            val x0 = floor(cx).toInt().coerceIn(0, max(0, src.width - 2))
            val y0 = floor(cy).toInt().coerceIn(0, max(0, src.height - 2))
            val x1 = min(x0 + 1, src.width - 1)
            val y1 = min(y0 + 1, src.height - 1)
            val fx = cx - x0
            val fy = cy - y0
            val c00 = src[x0, y0]; val c10 = src[x1, y0]; val c01 = src[x0, y1]; val c11 = src[x1, y1]
            fun ch(s: Int): Float {
                val a = (c00 shr s and 0xFF) * (1 - fx) + (c10 shr s and 0xFF) * fx
                val bb = (c01 shr s and 0xFF) * (1 - fx) + (c11 shr s and 0xFF) * fx
                return a * (1 - fy) + bb * fy
            }
            return Raster.rgb(ch(16), ch(8), ch(0))
        }
    }
}

/**
 * The photo itself as [BoardPixels]: the board inside [quad] (fractions of the upright photo,
 * [width] × [height] pixels at full resolution), flattened per figure. Only the figure's region of the
 * photo is ever decoded: [region] returns the rectangle [x0, y0, x1) × [y0, y1) of the upright photo
 * shrunk by [sample] (a power of two), e.g. with Android's BitmapRegionDecoder; null = not available.
 * [enhance]: cleaned like the board the model saw (`Enhance`), per crop.
 */
class PhotoPixels(
    private val width: Int,
    private val height: Int,
    quad: Quad,
    private val enhance: Boolean,
    private val region: (x0: Int, y0: Int, x1: Int, y1: Int, sample: Int) -> Raster?,
) : BoardPixels {
    private val map = Perspective.mapper(quad)

    override fun crop(left: Float, top: Float, right: Float, bottom: Float, w: Int, h: Int): Raster? {
        // The crop's outline on the photo (a quadrilateral), in photo pixels.
        val corners = listOf(left to top, right to top, right to bottom, left to bottom).map { (u, v) -> map(u, v).let { it.x * width to it.y * height } }
        val x0 = floor(corners.minOf { it.first }).toInt().minus(2).coerceIn(0, width - 1)
        val y0 = floor(corners.minOf { it.second }).toInt().minus(2).coerceIn(0, height - 1)
        val x1 = ceil(corners.maxOf { it.first }).toInt().plus(2).coerceIn(x0 + 1, width)
        val y1 = ceil(corners.maxOf { it.second }).toInt().plus(2).coerceIn(y0 + 1, height)
        // Decode no finer than the crop needs: the largest power of two that keeps a photo pixel per crop pixel.
        val density = min((x1 - x0).toFloat() / w, (y1 - y0).toFloat() / h)
        var sample = 1
        while (sample * 2 <= density) sample *= 2
        val part = region(x0, y0, x1, y1, sample) ?: return null
        val kx = part.width.toFloat() / (x1 - x0)
        val ky = part.height.toFloat() / (y1 - y0)
        val out = BoardPixels.sample(w, h, { u, v -> map(u, v).let { (it.x * width - x0) * kx - 0.5f to (it.y * height - y0) * ky - 0.5f } }, left, top, right, bottom) { x, y -> BoardPixels.bilinear(part, x, y) }
        return if (enhance) Enhance.apply(out) else out
    }

    companion object {
        /**
         * The upright rectangle [x0, x1) × [y0, y1) in the stored pixels ([storedW] × [storedH]) of a photo
         * that turns [rotation]° clockwise to stand upright (EXIF): [left, top, right, bottom].
         */
        fun storedRect(rotation: Int, storedW: Int, storedH: Int, x0: Int, y0: Int, x1: Int, y1: Int): IntArray = when (rotation) {
            90 -> intArrayOf(y0, storedH - x1, y1, storedH - x0)
            180 -> intArrayOf(storedW - x1, storedH - y1, storedW - x0, storedH - y0)
            270 -> intArrayOf(storedW - y1, x0, storedW - y0, x1)
            else -> intArrayOf(x0, y0, x1, y1)
        }
    }

    /** How much finer than [board] this photo shows the board (≤ 1: no gain, the board is used). */
    fun gainOver(board: Raster): Float {
        val c = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f).map { (u, v) -> map(u, v).let { it.x * width to it.y * height } }
        val top = kotlin.math.hypot(c[1].first - c[0].first, c[1].second - c[0].second)
        val bottom = kotlin.math.hypot(c[2].first - c[3].first, c[2].second - c[3].second)
        return (top + bottom) / 2 / board.width
    }
}
