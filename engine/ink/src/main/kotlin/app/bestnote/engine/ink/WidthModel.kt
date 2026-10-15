package app.bestnote.engine.ink

import kotlin.math.max
import kotlin.math.min

/**
 * Plain-Kotlin model of the width rules [BrushFamilies] builds into androidx.ink behavior graphs
 * (which need ink's native library, so can't run in JVM tests). It mirrors the graphs node for node
 * (ignoring damping and the exact easing curves), so unit tests can pin the rules that matter to a
 * writer: taps are bold round dots, short dashes keep their body, pressure/speed/taper ranges, nothing thinner than its floor.
 *
 * Distances are in brush sizes, speed in brush sizes per second, pressure 0..1.
 */
object WidthModel {
    /** 0 for a dot, 1 once the stroke (traveled + remaining) is [BrushTuning.Dot.MAX_LENGTH] long. */
    fun longness(traveled: Float, remaining: Float, length: Float = BrushTuning.Dot.MAX_LENGTH): Float =
        easeIn(min(clamp01(traveled / length) + clamp01(remaining / length), 1f))

    /** 1 while a stroke is at most [BrushTuning.Dot.DASH_FULL] long, falling to 0 at [BrushTuning.Dot.DASH_LENGTH]. */
    fun dashness(traveled: Float, remaining: Float): Float = with(BrushTuning.Dot) {
        val s = min(clamp01(traveled / DASH_LENGTH) + clamp01(remaining / DASH_LENGTH), 1f)
        min((1f - s) * DASH_LENGTH / (DASH_LENGTH - DASH_FULL), 1f)
    }

    /** Width multiplier (of the pen's full width) at one point of a stroke. */
    fun multiplier(family: InkFamily, pressure: Float, speed: Float, traveled: Float, remaining: Float): Float {
        val long = longness(traveled, remaining)
        val dot = 1f - long
        val dash = dashness(traveled, remaining)
        fun lifted(v: Float) = max(clamp01(v), dot)
        fun taper(v: Float) = max(clamp01(v), dash)
        fun range(start: Float, end: Float, v: Float) = start + (end - start) * v
        val p = clamp01(pressure)
        val boost = range(1f, BrushTuning.Dot.SCALE, dot)
        val width = when (family) {
            InkFamily.Fountain -> with(BrushTuning.Fountain) {
                val speedN = clamp01((speed - SLOW_SPEED) / (FAST_SPEED - SLOW_SPEED)) * long
                range(MIN_PRESSURE_WIDTH, 1f, lifted(easeOut(p))) *
                    range(1f, FAST_WIDTH, speedN) *
                    range(TAPER_TIP_WIDTH, 1f, taper(easeOut(traveled / TAPER_START))) *
                    range(TAPER_TIP_WIDTH, 1f, taper(easeOut(remaining / TAPER_END)))
            }
            InkFamily.Ballpoint -> range(BrushTuning.Ballpoint.MIN_PRESSURE_WIDTH, 1f, lifted(p))
            InkFamily.BrushPen -> with(BrushTuning.BrushPen) {
                range(MIN_PRESSURE_WIDTH, 1f, lifted(easeIn(p))) *
                    range(TAPER_TIP_WIDTH, 1f, taper(easeOut(traveled / TAPER_LENGTH))) *
                    range(TAPER_TIP_WIDTH, 1f, taper(easeOut(remaining / TAPER_LENGTH)))
            }
            // Tilt shading only widens; at a normal writing angle the pencil follows pressure.
            InkFamily.Pencil -> range(BrushTuning.Pencil.MIN_PRESSURE_WIDTH, 1f, lifted(p))
            InkFamily.Highlighter -> return 1f
        }
        return width * boost
    }

    private fun clamp01(v: Float) = v.coerceIn(0f, 1f)

    // Close enough to CSS ease-out / ease-in for bounds checks.
    private fun easeOut(v: Float): Float = clamp01(v).let { 1f - (1f - it) * (1f - it) }
    private fun easeIn(v: Float): Float = clamp01(v).let { it * it }
}
