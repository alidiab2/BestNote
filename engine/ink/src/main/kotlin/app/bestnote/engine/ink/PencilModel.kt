package app.bestnote.engine.ink

/**
 * Plain-Kotlin model of the pencil's graphite rules (INK-02), mirroring the behaviors [BrushFamilies] builds
 * for [InkFamily.Pencil] (minus damping): pressure darkens more than it widens, and tilting the S Pen past
 * [BrushTuning.Pencil.TILT_START_RAD] lays the lead on its side, widening the stroke up to
 * [BrushTuning.Pencil.TILT_MAX_WIDTH] and softening it to [BrushTuning.Pencil.TILT_MIN_OPACITY].
 * The vector export ([WidthModel] callers) draws pencil with these numbers, so it shades like the live ink.
 *
 * Tilt is Android's AXIS_TILT: radians from perpendicular (0 = upright, PI/2 = flat on the glass).
 */
object PencilModel {
    /** 0 at a writing angle, easing up to 1 once the pen is tilted to [BrushTuning.Pencil.TILT_FULL_RAD]. */
    fun shade(tiltRad: Float): Float = with(BrushTuning.Pencil) {
        if (!tiltRad.isFinite()) return 0f
        val t = ((tiltRad - TILT_START_RAD) / (TILT_FULL_RAD - TILT_START_RAD)).coerceIn(0f, 1f)
        // EASE_IN_OUT, close enough to CSS ease-in-out for the export and bounds checks.
        t * t * (3f - 2f * t)
    }

    /** Width multiplier from tilt alone: 1 upright, [BrushTuning.Pencil.TILT_MAX_WIDTH] flat. */
    fun tiltWidth(tiltRad: Float): Float = 1f + (BrushTuning.Pencil.TILT_MAX_WIDTH - 1f) * shade(tiltRad)

    /** Opacity multiplier from tilt alone: 1 upright, [BrushTuning.Pencil.TILT_MIN_OPACITY] flat. */
    fun tiltOpacity(tiltRad: Float): Float = 1f + (BrushTuning.Pencil.TILT_MIN_OPACITY - 1f) * shade(tiltRad)

    /** Opacity multiplier from pressure (0..1): [BrushTuning.Pencil.MIN_PRESSURE_OPACITY] light, 1 pressed hard. */
    fun pressureOpacity(pressure: Float): Float = with(BrushTuning.Pencil) {
        MIN_PRESSURE_OPACITY + (1f - MIN_PRESSURE_OPACITY) * pressure.coerceIn(0f, 1f)
    }

    /** Graphite darkness (opacity multiplier, before the brush's own alpha) at one point of a stroke. */
    fun opacity(pressure: Float, tiltRad: Float): Float = pressureOpacity(pressure) * tiltOpacity(tiltRad)
}
