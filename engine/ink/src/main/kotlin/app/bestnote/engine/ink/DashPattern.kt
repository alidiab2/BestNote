package app.bestnote.engine.ink

import app.bestnote.core.model.LineDash
import kotlin.math.max

/**
 * INK-07: where a dashed or dotted stroke shows ink, along its centerline. Lengths scale with the pen's full
 * width (so a thick marker gets long dashes and a fine pen short ones), with a floor so hairlines still read
 * as dashed. Pure: [DashClip] turns it into the clip every renderer (live, tiles, export) draws through, so a
 * dashed stroke looks the same everywhere. The stroke itself keeps every sample.
 */
object DashPattern {
    /** Dash length, gap and dot pitch, in multiples of the pen's full width. */
    const val DASH = 3f
    const val DASH_GAP = 2.2f
    const val DOT_PITCH = 2.2f

    /** Narrowest width the pattern is computed for (page points), so very fine pens get visible gaps. */
    const val MIN_WIDTH = 1.2f

    /** A dot is drawn as a dash this short (with round ends), in page points. */
    const val DOT_LENGTH = 0.01f

    /**
     * The repeating (on, off) lengths for [dash] on a pen [width] wide, or null for a solid line. The first
     * interval is ink, so a stroke always starts with a dash or a dot (a tap stays visible).
     */
    fun intervals(dash: LineDash, width: Float): FloatArray? {
        val w = max(width, MIN_WIDTH)
        return when (dash) {
            LineDash.Solid -> null
            LineDash.Dashed -> floatArrayOf(DASH * w, DASH_GAP * w)
            LineDash.Dotted -> floatArrayOf(DOT_LENGTH, DOT_PITCH * w - DOT_LENGTH)
        }
    }

    /**
     * Width of the band, around the centerline, that a dash lets through: the widest the pen can draw (its
     * full width times [maxWidthScale], e.g. a tilted pencil) plus a margin. A dot is exactly one pen width
     * round, so dots come out as round as the pen.
     */
    fun bandWidth(dash: LineDash, width: Float, maxWidthScale: Float): Float = when (dash) {
        LineDash.Dotted -> max(width, MIN_WIDTH * 0.5f)
        else -> width * maxWidthScale * BAND_MARGIN + BAND_EXTRA
    }

    /**
     * The inked spans of a centerline [length] long, as (start, end) distances along it. Pure, for tests and
     * for checking that a stroke starts and ends sensibly.
     */
    fun spans(length: Float, intervals: FloatArray): List<ClosedFloatingPointRange<Float>> {
        require(intervals.size % 2 == 0 && intervals.all { it > 0f }) { "intervals are (on, off) pairs" }
        val out = ArrayList<ClosedFloatingPointRange<Float>>()
        var at = 0f
        var i = 0
        while (at <= length) {
            val on = intervals[i % intervals.size]
            out += at..minOf(at + on, length)
            at += on + intervals[(i + 1) % intervals.size]
            i += 2
        }
        return out
    }

    /** The widest a [family] stroke gets, as a multiple of its full width (tilted pencil lead). */
    fun maxWidthScale(family: InkFamily?): Float = if (family?.isPencil == true) BrushTuning.Pencil.TILT_MAX_WIDTH else 1f

    private const val BAND_MARGIN = 1.25f
    private const val BAND_EXTRA = 1f
}
