package app.bestnote.ai.scan.figure

import app.bestnote.ai.scan.Raster
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * AI-12: which pixels of a figure crop are the teacher's ink. Adaptive threshold against the local
 * background (a box mean from an integral image, so shadows and glare don't matter), coloured marker
 * kept even when it is light (red on white), blackboards inverted, specks removed.
 */
class InkMask(val width: Int, val height: Int, val ink: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height && ink[y * width + x]

    val count: Int get() = ink.count { it }

    /** 8-connected components: a label per pixel (-1 = background) and the pixel count of each. */
    fun components(): Pair<IntArray, IntArray> {
        val label = IntArray(ink.size) { -1 }
        val sizes = ArrayList<Int>()
        val stack = IntArray(ink.size)
        for (start in ink.indices) {
            if (!ink[start] || label[start] >= 0) continue
            val id = sizes.size
            var n = 0
            var top = 0
            stack[top++] = start
            label[start] = id
            while (top > 0) {
                val p = stack[--top]
                n++
                val px = p % width
                val py = p / width
                for (dy in -1..1) for (dx in -1..1) {
                    val x = px + dx
                    val y = py + dy
                    if (x !in 0 until width || y !in 0 until height) continue
                    val q = y * width + x
                    if (ink[q] && label[q] < 0) {
                        label[q] = id
                        stack[top++] = q
                    }
                }
            }
            sizes += n
        }
        return label to sizes.toIntArray()
    }

    /** This mask without the components of fewer than [minArea] pixels. */
    fun withoutSpecks(minArea: Int): InkMask {
        val (label, sizes) = components()
        return InkMask(width, height, BooleanArray(ink.size) { ink[it] && sizes[label[it]] >= minArea })
    }

    /** Morphological closing with a 3x3 square: bridges one-pixel breaks in marker strokes. */
    fun closed(): InkMask = dilate().erode()

    private fun dilate() = InkMask(width, height, BooleanArray(ink.size) { i -> any3(i) { it } })
    private fun erode() = InkMask(width, height, BooleanArray(ink.size) { i -> !any3(i) { !it } })

    private inline fun any3(i: Int, test: (Boolean) -> Boolean): Boolean {
        val x0 = i % width
        val y0 = i / width
        for (dy in -1..1) for (dx in -1..1) {
            val x = x0 + dx
            val y = y0 + dy
            val v = if (x in 0 until width && y in 0 until height) ink[y * width + x] else false
            if (test(v)) return true
        }
        return false
    }

    companion object {
        /** The ink of [r]. [window] is the background window side (odd); 0 = from the crop's size. */
        fun of(r: Raster, window: Int = 0): InkMask {
            val w = r.width
            val h = r.height
            val luma = r.luma()
            val dark = median(luma) < DARK_BOARD
            if (dark) for (i in luma.indices) luma[i] = 255f - luma[i]
            val side = if (window > 0) window else (min(w, h) / 6).coerceIn(15, 61) or 1
            val half = side / 2
            // Integral image of luma (doubles: no overflow on large crops).
            val sat = DoubleArray((w + 1) * (h + 1))
            for (y in 0 until h) {
                var row = 0.0
                for (x in 0 until w) {
                    row += luma[y * w + x]
                    sat[(y + 1) * (w + 1) + x + 1] = sat[y * (w + 1) + x + 1] + row
                }
            }
            val out = BooleanArray(w * h)
            for (y in 0 until h) {
                val y0 = max(0, y - half)
                val y1 = min(h, y + half + 1)
                for (x in 0 until w) {
                    val x0 = max(0, x - half)
                    val x1 = min(w, x + half + 1)
                    val sum = sat[y1 * (w + 1) + x1] - sat[y0 * (w + 1) + x1] - sat[y1 * (w + 1) + x0] + sat[y0 * (w + 1) + x0]
                    val mean = (sum / ((x1 - x0) * (y1 - y0))).toFloat()
                    val i = y * w + x
                    val drop = mean - luma[i]
                    val c = r.argb[i]
                    val chroma = max(c shr 16 and 0xFF, max(c shr 8 and 0xFF, c and 0xFF)) - min(c shr 16 and 0xFF, min(c shr 8 and 0xFF, c and 0xFF))
                    out[i] = drop > max(MIN_DROP, mean * REL_DROP) || (chroma > COLOUR_CHROMA && drop > COLOUR_DROP)
                }
            }
            val minArea = max(MIN_SPECK, (w * h * SPECK_FRACTION).roundToInt())
            return InkMask(w, h, out).closed().withoutSpecks(minArea)
        }

        private fun median(v: FloatArray): Float {
            val hist = IntArray(256)
            for (x in v) hist[x.toInt().coerceIn(0, 255)]++
            var acc = 0
            for (i in 0 until 256) {
                acc += hist[i]
                if (acc * 2 >= v.size) return i.toFloat()
            }
            return 255f
        }

        private const val DARK_BOARD = 100f
        private const val MIN_DROP = 18f
        private const val REL_DROP = 0.12f
        private const val COLOUR_CHROMA = 55
        private const val COLOUR_DROP = 8f
        private const val MIN_SPECK = 6
        private const val SPECK_FRACTION = 0.00006f
    }
}
