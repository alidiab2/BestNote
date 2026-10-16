package app.bestnote.engine.ink

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Stroke stabilization (INK-05): a 2D 1-euro filter (Casiez et al. 2012) on pen positions.
 *
 * The cut-off frequency rises with pen speed: when the hand moves slowly (where tremor shows as a
 * wobbly line) it smooths hard; at writing speed it opens up so the line never trails the pen by
 * more than a frame. Strength 0 is a pass-through. O(1) per sample, no allocation: it runs on the
 * wet-ink path for every digitizer sample.
 *
 * @param strength 0..100 (see [PenTuning.stabilization]).
 * @param pxPerMm screen pixels per millimetre, so the speed response matches the hand, not the zoom.
 */
class StrokeStabilizer(strength: Int, private val pxPerMm: Float) {
    private val s = strength.coerceIn(0, PenTuning.MAX_STABILIZATION) / PenTuning.MAX_STABILIZATION.toFloat()

    val enabled: Boolean get() = s > 0f

    /** Cut-off (Hz) when the pen is still. Strength 1 barely filters, 100 smooths hard. */
    private val minCutoff: Float = with(BrushTuning.Stabilization) {
        // Geometric interpolation: perceived smoothing is roughly logarithmic in the cut-off.
        MIN_CUTOFF_LOW_HZ * Math.pow((MIN_CUTOFF_HIGH_HZ / MIN_CUTOFF_LOW_HZ).toDouble(), s.toDouble()).toFloat()
    }

    private var started = false
    private var x = 0f
    private var y = 0f
    private var rawX0 = 0f
    private var rawY0 = 0f
    private var speed = 0f
    private var lastT = 0.0

    fun reset() {
        started = false
    }

    /** Copies [other]'s state, e.g. to filter predicted samples without disturbing the real ones. */
    fun copyFrom(other: StrokeStabilizer) {
        started = other.started
        x = other.x
        y = other.y
        rawX0 = other.rawX0
        rawY0 = other.rawY0
        speed = other.speed
        lastT = other.lastT
    }

    /** Filters the sample ([rawX], [rawY]) at [tMs]; the result is in [out] (x, y). */
    fun filter(rawX: Float, rawY: Float, tMs: Double, out: FloatArray) {
        if (!enabled) {
            out[0] = rawX
            out[1] = rawY
            return
        }
        if (!started) {
            started = true
            x = rawX
            y = rawY
            rawX0 = rawX
            rawY0 = rawY
            speed = 0f
            lastT = tMs
            out[0] = rawX
            out[1] = rawY
            return
        }
        // Coalesced samples can share a timestamp: treat them as 1 ms apart.
        val dt = ((tMs - lastT) / MS_PER_S).coerceAtLeast(MIN_DT_S).toFloat()
        lastT = tMs
        val vx = rawX - rawX0
        val vy = rawY - rawY0
        rawX0 = rawX
        rawY0 = rawY
        val rawSpeed = sqrt(vx * vx + vy * vy) / pxPerMm / dt
        speed += alpha(BrushTuning.Stabilization.SPEED_CUTOFF_HZ, dt) * (rawSpeed - speed)
        val a = alpha(minCutoff + BrushTuning.Stabilization.SPEED_COEFF * speed, dt)
        x += a * (rawX - x)
        y += a * (rawY - y)
        out[0] = x
        out[1] = y
    }

    private fun alpha(cutoffHz: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz)
        return 1f / (1f + tau / dt)
    }

    private companion object {
        const val MS_PER_S = 1000.0
        const val MIN_DT_S = 0.001
    }
}
