package app.bestnote.engine.canvas.shape

import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import app.bestnote.core.model.StylusSample
import app.bestnote.engine.recognition.RecognizedShape
import kotlin.math.hypot

/**
 * INK-08: clean shape strokes made from a hand-drawn one. The clean stroke keeps the raw stroke's
 * brush (pen, color, size) and its typical pressure, tilt and speed, so it looks drawn with the
 * same pen, just steadier.
 */
internal class ShapeStroke private constructor(
    val stroke: Stroke,
    val outline: Polyline,
    private val times: LongArray,
    private val toolType: androidx.ink.brush.InputToolType,
    private val unitCm: Float,
    private val pressure: Float,
    private val tilt: Float,
    private val orientation: Float,
) {
    /** The stroke with its points moved to [points] (same count as [outline]): one frame of the snap morph. */
    fun withPoints(points: Polyline): Stroke {
        val batch = MutableStrokeInputBatch()
        for (i in 0 until points.size) {
            batch.add(toolType, points.xs[i], points.ys[i], times[i], unitCm, pressure, tilt, orientation)
        }
        return Stroke(stroke.brush, batch)
    }

    companion object {
        /** The clean [shape] drawn with [raw]'s brush; [rawSamples] are [raw]'s inputs (see [samplesOf]). */
        fun build(shape: RecognizedShape, raw: Stroke, rawSamples: List<StylusSample>): ShapeStroke {
            val first = rawSamples.first()
            val outline = ShapeOutline.of(shape, first.x, first.y, spacing = maxOf(ShapeOutline.DEFAULT_SPACING, raw.brush.size / 4))
            val probe = StrokeInput()
            raw.inputs.populate(0, probe)
            val middle = rawSamples.subList(rawSamples.size / 10, rawSamples.size - rawSamples.size / 10).ifEmpty { rawSamples }
            val pressure = if (probe.hasPressure) median(middle.map { it.pressure }) else StrokeInput.NO_PRESSURE
            val tilt = if (probe.hasTilt) median(middle.map { it.tiltRad }) else StrokeInput.NO_TILT
            val orientation = if (probe.hasOrientation) median(middle.map { it.orientationRad }) else StrokeInput.NO_ORIENTATION
            // Same average speed as the hand-drawn part (a hold at the end doesn't count).
            val duration = drawingMillis(rawSamples).coerceAtLeast(MIN_DURATION_MS)
            val cum = FloatArray(outline.size)
            for (i in 1 until outline.size) cum[i] = cum[i - 1] + hypot(outline.xs[i] - outline.xs[i - 1], outline.ys[i] - outline.ys[i - 1])
            val total = cum.last().takeIf { it > 0f } ?: 1f
            val times = LongArray(outline.size) { (duration * cum[it] / total).toLong() }
            val unitCm = probe.strokeUnitLengthCm
            val batch = MutableStrokeInputBatch()
            for (i in 0 until outline.size) batch.add(probe.toolType, outline.xs[i], outline.ys[i], times[i], unitCm, pressure, tilt, orientation)
            return ShapeStroke(Stroke(raw.brush, batch), outline, times, probe.toolType, unitCm, pressure, tilt, orientation)
        }

        /** [stroke]'s inputs as samples (page points, elapsed time). */
        fun samplesOf(stroke: Stroke): List<StylusSample> {
            val inputs = stroke.inputs
            val s = StrokeInput()
            return List(inputs.size) { i ->
                inputs.populate(i, s)
                StylusSample(
                    s.x, s.y,
                    if (s.hasPressure) s.pressure else 0f,
                    if (s.hasTilt) s.tiltRadians else 0f,
                    if (s.hasOrientation) s.orientationRadians else 0f,
                    s.elapsedTimeMillis,
                )
            }
        }

        /** Time until the pen had covered 98% of its path: the drawing itself, without a final hold. */
        private fun drawingMillis(samples: List<StylusSample>): Long {
            var total = 0f
            for (i in 1 until samples.size) total += hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
            var acc = 0f
            for (i in 1 until samples.size) {
                acc += hypot(samples[i].x - samples[i - 1].x, samples[i].y - samples[i - 1].y)
                if (acc >= total * DRAWN_SHARE) return samples[i].timeMillis - samples[0].timeMillis
            }
            return samples.last().timeMillis - samples[0].timeMillis
        }

        private fun median(v: List<Float>): Float = v.sorted()[v.size / 2]

        private const val DRAWN_SHARE = 0.98f
        private const val MIN_DURATION_MS = 60L
    }
}
