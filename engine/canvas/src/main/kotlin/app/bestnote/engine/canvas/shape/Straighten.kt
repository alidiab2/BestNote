package app.bestnote.engine.canvas.shape

import app.bestnote.engine.recognition.RecognizedShape
import kotlin.math.abs
import kotlin.math.hypot

/**
 * INK-07 hold-to-straighten: a stroke that *heads somewhere* (a wobbly rule, an underline drawn slowly, a
 * shaky highlight) snaps to the straight line from where it started to where the pen is held, even when it is
 * too wobbly for the shape recognizer to call it a line. It plugs into CANVAS-17's hold-to-shape ghost
 * ([ShapePreview.begin]): real shapes win, this is the fallback, so there is one hold gesture, one ghost and
 * one snap animation for both.
 *
 * Rules (relative, so they hold at any zoom): the start-to-end chord is at least [MIN_LENGTH] page points and
 * at least [MIN_DIRECTNESS] of the path length (no scribbles, loops or words), and no point strays further
 * than [MAX_DEVIATION] of the chord from it (no arcs or corners). Pure Kotlin, page points.
 */
object Straighten {
    /** Shortest line worth straightening, page points (~3 mm). */
    const val MIN_LENGTH = 8f

    /** Chord / path length: a handwritten word is ~0.3, a wobbly line > 0.8. */
    const val MIN_DIRECTNESS = 0.7f

    /** Largest distance of any point from the chord, as a share of the chord. */
    const val MAX_DEVIATION = 0.2f

    /** An open polyline the recognizer found is taken as a wobbly line when it strays less than this. */
    const val POLYLINE_AS_LINE_DEVIATION = 0.1f

    /** How straight [xs]/[ys] ([count] points) is, or null when it is not a candidate line at all. */
    class Fit(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val directness: Float, val deviation: Float) {
        val line: RecognizedShape.Line get() = RecognizedShape.Line(x0, y0, x1, y1)
    }

    fun fit(xs: FloatArray, ys: FloatArray, count: Int): Fit? {
        if (count < 2) return null
        val x0 = xs[0]; val y0 = ys[0]
        val x1 = xs[count - 1]; val y1 = ys[count - 1]
        val chord = hypot(x1 - x0, y1 - y0)
        if (chord < MIN_LENGTH) return null
        var length = 0f
        var worst = 0f
        val ux = (x1 - x0) / chord; val uy = (y1 - y0) / chord
        for (i in 0 until count) {
            if (i > 0) length += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            val d = abs((xs[i] - x0) * uy - (ys[i] - y0) * ux)
            if (d > worst) worst = d
            // Running back past the start or beyond the end is a hook, not a line.
            val along = (xs[i] - x0) * ux + (ys[i] - y0) * uy
            if (along < -MAX_DEVIATION * chord || along > chord * (1f + MAX_DEVIATION)) return null
        }
        return Fit(x0, y0, x1, y1, chord / length.coerceAtLeast(chord), worst / chord)
    }

    /** The straight line [xs]/[ys] snaps to when held, or null when it isn't line-like. */
    fun line(xs: FloatArray, ys: FloatArray, count: Int): RecognizedShape.Line? =
        fit(xs, ys, count)?.takeIf { it.directness >= MIN_DIRECTNESS && it.deviation <= MAX_DEVIATION }?.line

    /**
     * What a hold makes of a stroke the recognizer matched as [recognized] (null = no shape): the shape itself,
     * a straight line for a wobbly line it missed (or read as a slight zigzag), else null.
     */
    fun resolve(recognized: RecognizedShape?, xs: FloatArray, ys: FloatArray, count: Int): RecognizedShape? {
        val wobblyPolyline = recognized is RecognizedShape.Polygon && !recognized.closed &&
            (fit(xs, ys, count)?.let { it.directness >= MIN_DIRECTNESS && it.deviation <= POLYLINE_AS_LINE_DEVIATION } == true)
        if (recognized != null && !wobblyPolyline) return recognized
        return line(xs, ys, count)
    }
}
