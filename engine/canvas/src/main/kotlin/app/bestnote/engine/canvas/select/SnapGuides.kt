package app.bestnote.engine.canvas.select

import kotlin.math.abs

/**
 * Magnetic guides for moving text boxes and pictures (TEXT-04): while dragged, the selection's left / center / right
 * and top / middle / bottom lines pull onto the page's edges, margins and center and onto other boxes' lines when
 * they come within a threshold. Pure: page points in, the correction and the guides to draw out.
 */
object SnapGuides {
    /** Lines things snap to, page points: vertical guides are x positions, horizontal guides y positions. */
    class Targets(val xs: FloatArray, val ys: FloatArray)

    /** The move correction ([dx], [dy]) to add, and the guide lines it lines up on (null = none on that axis). */
    data class Snap(val dx: Float, val dy: Float, val guideX: Float?, val guideY: Float?) {
        val snapped: Boolean get() = guideX != null || guideY != null
    }

    /** Page margin, as a fraction of the page width (the same margin text boxes grow to). */
    const val MARGIN = 0.06f

    /** Targets of a [pageWidth] x [pageHeight] page holding [others] (boxes that are not moving). */
    fun targets(pageWidth: Float, pageHeight: Float, others: List<Box>): Targets {
        val m = pageWidth * MARGIN
        val xs = ArrayList<Float>(5 + others.size * 3).apply { addAll(listOf(0f, m, pageWidth / 2f, pageWidth - m, pageWidth)) }
        val ys = ArrayList<Float>(5 + others.size * 3).apply { addAll(listOf(0f, m, pageHeight / 2f, pageHeight - m, pageHeight)) }
        for (b in others) {
            xs += b.minX; xs += b.centerX; xs += b.maxX
            ys += b.minY; ys += b.centerY; ys += b.maxY
        }
        return Targets(xs.toFloatArray(), ys.toFloatArray())
    }

    /** Where [moving] (already moved by the pen) snaps: the nearest line pair within [threshold] on each axis. */
    fun snap(moving: Box, targets: Targets, threshold: Float): Snap {
        val (dx, gx) = nearest(floatArrayOf(moving.minX, moving.centerX, moving.maxX), targets.xs, threshold)
        val (dy, gy) = nearest(floatArrayOf(moving.minY, moving.centerY, moving.maxY), targets.ys, threshold)
        return Snap(dx, dy, gx, gy)
    }

    private fun nearest(lines: FloatArray, targets: FloatArray, threshold: Float): Pair<Float, Float?> {
        var best = threshold
        var delta = 0f
        var guide: Float? = null
        for (l in lines) for (t in targets) {
            val d = t - l
            if (abs(d) <= best) {
                // Ties keep the first: the box's start edge, then its center.
                if (abs(d) < best || guide == null) {
                    best = abs(d)
                    delta = d
                    guide = t
                }
            }
        }
        return delta to guide
    }
}
