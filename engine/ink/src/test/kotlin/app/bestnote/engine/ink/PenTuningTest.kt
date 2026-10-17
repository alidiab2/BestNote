package app.bestnote.engine.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class PenTuningTest {
    private val pressures = (0..20).map { it / 20f }
    private val pens = listOf(InkFamily.Fountain, InkFamily.Ballpoint, InkFamily.BrushPen, InkFamily.Pencil)

    // ---------------------------------------------------------------- INK-04 pressure

    @Test fun sensitivityZeroGivesConstantWidthInEveryPen() {
        for (pen in pens) {
            val widths = pressures.map { WidthModel.multiplier(pen, PressureResponse.map(it, 0f), speed = 100f, traveled = 30f, remaining = 30f) }
            assertEquals("$pen", widths.min(), widths.max(), 1e-6f)
        }
    }

    @Test fun maxSensitivityFollowsEachPensOwnCurve() {
        for (pen in pens) for (p in pressures) {
            val spec = WidthModel.multiplier(pen, p, 100f, 30f, 30f)
            val tuned = WidthModel.multiplier(pen, PressureResponse.map(p, 1f), 100f, 30f, 30f)
            assertEquals("$pen at $p", spec, tuned, 1e-6f)
        }
    }

    @Test fun lowerSensitivityNarrowsTheWidthRangeMonotonically() {
        fun range(s: Float): Float {
            val w = pressures.map { WidthModel.multiplier(InkFamily.BrushPen, PressureResponse.map(it, s), 100f, 30f, 30f) }
            return w.max() - w.min()
        }
        val ranges = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map(::range)
        assertTrue("$ranges", ranges.zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun mappedPressureStaysMonotonicAndInRange() {
        for (s in listOf(0.1f, 0.5f, 1f)) for (g in listOf(0.55f, 1f, 1.8f)) {
            val mapped = pressures.map { PressureResponse.map(it, s, g) }
            assertTrue(mapped.all { it in 0f..1f })
            assertTrue("s=$s g=$g", mapped.zipWithNext().all { (a, b) -> b >= a })
        }
        assertEquals(0f, PressureResponse.map(-1f, 1f), 0f)
        assertEquals(1f, PressureResponse.map(2f, 1f), 0f)
    }

    @Test fun softCurveThickensLightStrokesFirmThinsThem() {
        assertTrue(PressureResponse.map(0.3f, 1f, 0.55f) > PressureResponse.map(0.3f, 1f))
        assertTrue(PressureResponse.map(0.3f, 1f, 1.8f) < PressureResponse.map(0.3f, 1f))
    }

    @Test fun defaultTuningIsAPassThrough() {
        assertTrue(PenTuning.Default.isIdentity)
        assertFalse(PenTuning(pressureSensitivity = 0.5f).isIdentity)
        assertFalse(PenTuning(stabilization = 1).isIdentity)
        assertFalse(PenTuning(curveGamma = 1.8f).isIdentity)
        for (p in pressures) assertEquals(p, PenTuning.Default.pressure(p), 0f)
    }

    // ---------------------------------------------------------------- INK-05 stabilization

    private val pxPerMm = 10f
    private val hz = 240.0

    /** Pen moving along x at [speedMmS] with optional y tremor (10 Hz) and noise; returns (raw, filtered) y and final x lag in ms. */
    private fun run(strength: Int, speedMmS: Float, tremorMm: Float = 0f, noiseMm: Float = 0f, seconds: Double = 1.0): Triple<FloatArray, FloatArray, Double> {
        val f = StrokeStabilizer(strength, pxPerMm)
        val rnd = java.util.Random(7)
        val n = (seconds * hz).toInt()
        val rawY = FloatArray(n)
        val outY = FloatArray(n)
        val out = FloatArray(2)
        var lagMs = 0.0
        for (i in 0 until n) {
            val tMs = i * 1000.0 / hz
            val x = speedMmS * pxPerMm * (tMs / 1000.0).toFloat()
            val y = (tremorMm * sin(2 * PI * 10 * tMs / 1000.0).toFloat() + noiseMm * rnd.nextGaussian().toFloat()) * pxPerMm
            f.filter(x, y, tMs, out)
            rawY[i] = y
            outY[i] = out[1]
            if (speedMmS > 0f) lagMs = (x - out[0]) / (speedMmS * pxPerMm) * 1000.0
        }
        return Triple(rawY, outY, lagMs)
    }

    private fun rmsSecondHalf(v: FloatArray): Float {
        val half = v.size / 2
        return sqrt((half until v.size).sumOf { (v[it] * v[it]).toDouble() } / (v.size - half)).toFloat()
    }

    @Test fun strengthZeroIsAPassThrough() {
        val (raw, out, lag) = run(0, 30f, tremorMm = 0.2f, noiseMm = 0.05f)
        assertTrue(raw.contentEquals(out))
        assertEquals(0.0, lag, 1e-6)
    }

    @Test fun fullStrengthRemovesHandJitter() {
        // Slow line (5 mm/s) with 10 Hz tremor and digitizer noise: the wobble the setting is for.
        val (raw, out, _) = run(100, 5f, tremorMm = 0.15f, noiseMm = 0.03f)
        assertTrue("tremor kept ${rmsSecondHalf(out) / rmsSecondHalf(raw)}", rmsSecondHalf(out) < 0.6f * rmsSecondHalf(raw))
        val (rawN, outN, _) = run(100, 5f, noiseMm = 0.05f)
        assertTrue("noise kept ${rmsSecondHalf(outN) / rmsSecondHalf(rawN)}", rmsSecondHalf(outN) < 0.4f * rmsSecondHalf(rawN))
    }

    @Test fun fullStrengthLagsAtMostOneFrameAtWritingSpeed() {
        for (speed in listOf(20f, 40f, 80f, 150f)) {
            val lag = run(100, speed).third
            assertTrue("$speed mm/s lags $lag ms", lag in 0.0..FRAME_MS)
        }
        // Slower than that the line trails by well under half a millimetre.
        for (speed in listOf(2f, 5f, 10f)) {
            val lagMm = run(100, speed).third / 1000.0 * speed
            assertTrue("$speed mm/s trails $lagMm mm", lagMm < 0.35)
        }
    }

    @Test fun moreStrengthSmoothsMore() {
        val kept = listOf(10, 40, 70, 100).map { s -> run(s, 5f, noiseMm = 0.05f).let { rmsSecondHalf(it.second) / rmsSecondHalf(it.first) } }
        assertTrue("$kept", kept.zipWithNext().all { (a, b) -> b < a })
    }

    @Test fun predictionCopyDoesNotDisturbTheRealFilter() {
        val a = StrokeStabilizer(100, pxPerMm)
        val b = StrokeStabilizer(100, pxPerMm)
        val peek = StrokeStabilizer(100, pxPerMm)
        val out = FloatArray(2)
        val outB = FloatArray(2)
        for (i in 0 until 20) {
            a.filter(i * 3f, i * 1f, i * 4.0, out)
            b.filter(i * 3f, i * 1f, i * 4.0, outB)
            peek.copyFrom(a)
            peek.filter(i * 3f + 50f, 0f, i * 4.0 + 8.0, FloatArray(2))
        }
        a.filter(70f, 20f, 84.0, out)
        b.filter(70f, 20f, 84.0, outB)
        assertEquals(outB[0], out[0], 0f)
        assertEquals(outB[1], out[1], 0f)
    }

    @Test fun firstSampleAndSameTimestampSamplesAreSafe() {
        val f = StrokeStabilizer(100, pxPerMm)
        val out = FloatArray(2)
        f.filter(5f, 6f, 10.0, out)
        assertEquals(5f, out[0], 0f)
        assertEquals(6f, out[1], 0f)
        f.filter(9f, 6f, 10.0, out) // coalesced: same timestamp
        assertTrue(out[0].isFinite() && abs(out[0] - 5f) <= 4f)
    }

    private companion object {
        const val FRAME_MS = 1000.0 / 60.0
    }
}
