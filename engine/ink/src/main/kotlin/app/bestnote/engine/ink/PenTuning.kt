package app.bestnote.engine.ink

import kotlin.math.pow

/**
 * How a pen reacts to the hand (INK-04, INK-05): pressure sensitivity and stroke stabilization.
 *
 * Both act on the *input samples* before they reach androidx.ink, not on the brush: a stroke is
 * stored with the shaped samples, so it renders the same forever, and changing a setting never
 * touches strokes already on the page. Each pen keeps its own pressure curve (the brush behavior
 * in [BrushFamilies], see [BrushTuning]); [pressureSensitivity] blends between that curve (1) and a
 * constant [BrushTuning.Pressure.NEUTRAL] pressure (0 = constant width).
 *
 * @property pressureSensitivity 0..1. 1 = the pen's own curve, 0 = pressure ignored.
 * @property stabilization 0..100. 0 = raw input, 100 = strongest jitter removal ([StrokeStabilizer]).
 * @property curveGamma the user's global pressure feel (Settings > Stylus: soft < 1 < firm), applied
 *   to raw pressure first.
 */
data class PenTuning(
    val pressureSensitivity: Float = 1f,
    val stabilization: Int = 0,
    val curveGamma: Float = 1f,
) {
    /** True when shaping leaves every sample untouched: the wet path skips it entirely. */
    val isIdentity: Boolean get() = pressureSensitivity >= 1f && stabilization <= 0 && curveGamma == 1f

    /** Pressure handed to the brush for a raw digitizer [pressure] (0..1). */
    fun pressure(pressure: Float): Float = PressureResponse.map(pressure, pressureSensitivity, curveGamma)

    companion object {
        val Default = PenTuning()
        const val MAX_STABILIZATION = 100
    }
}

/** Pure pressure mapping for [PenTuning]. Allocation-free; safe on the wet-ink path. */
object PressureResponse {
    /**
     * [raw] shaped by [gamma], then blended toward [BrushTuning.Pressure.NEUTRAL] as [sensitivity]
     * falls: 1 returns the shaped pressure (the pen's own curve does the rest), 0 a constant.
     */
    fun map(raw: Float, sensitivity: Float, gamma: Float = 1f): Float {
        val p = raw.coerceIn(0f, 1f)
        val shaped = if (gamma == 1f) p else p.pow(gamma)
        val s = sensitivity.coerceIn(0f, 1f)
        if (s == 1f) return shaped
        val neutral = BrushTuning.Pressure.NEUTRAL
        return neutral + (shaped - neutral) * s
    }
}
