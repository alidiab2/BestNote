package app.bestnote.engine.canvas.erase

import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import app.bestnote.engine.canvas.EraserMode
import app.bestnote.engine.ink.BrushCatalog
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * The stroke and pixel erasers on androidx.ink strokes (CANVAS-01, CANVAS-02). Geometry lives in
 * [StrokeSplitter]; this class caches each stroke's centerline and bounds (strokes are immutable,
 * so the cache never goes stale) and rebuilds split pieces as real strokes.
 */
class InkEraser {
    private class Outline(val xs: FloatArray, val ys: FloatArray, val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

    private val outlines = WeakHashMap<Stroke, Outline>()
    private val scratch = StrokeInput()

    /** One eraser step on a page: what to remove and what replaces it. */
    class Result(val removed: List<Stroke>, val replacements: Map<Stroke, List<Stroke>>) {
        val isEmpty: Boolean get() = removed.isEmpty() && replacements.isEmpty()
    }

    /**
     * Erases along [capsules] (page points) from [strokes] with [mode]. Whole-stroke modes report
     * [Result.removed]; the pixel eraser reports each touched stroke's remaining pieces (possibly none).
     */
    fun erase(strokes: List<Stroke>, capsules: List<EraserCapsule>, mode: EraserMode): Result {
        if (capsules.isEmpty()) return Result(emptyList(), emptyMap())
        val minX = capsules.minOf { it.minX }
        val minY = capsules.minOf { it.minY }
        val maxX = capsules.maxOf { it.maxX }
        val maxY = capsules.maxOf { it.maxY }
        val removed = ArrayList<Stroke>()
        val replaced = LinkedHashMap<Stroke, List<Stroke>>()
        for (stroke in strokes) {
            if (mode == EraserMode.HighlighterOnly && !BrushCatalog.isHighlighter(stroke.brush)) continue
            val o = outline(stroke)
            val half = stroke.brush.size / 2
            if (o.maxX + half < minX || o.minX - half > maxX || o.maxY + half < minY || o.minY - half > maxY) continue
            val count = o.xs.size
            if (mode == EraserMode.Pixel) {
                val kept = StrokeSplitter.keptRanges(o.xs, o.ys, count, capsules, extra = half, minPieceLength = half) ?: continue
                replaced[stroke] = kept.map { piece(stroke, it) }
            } else if (StrokeSplitter.touches(o.xs, o.ys, count, capsules, extra = half)) {
                removed += stroke
            }
        }
        return Result(removed, replaced)
    }

    private fun outline(stroke: Stroke): Outline = outlines.getOrPut(stroke) {
        val inputs = stroke.inputs
        val n = inputs.size
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        var x0 = Float.POSITIVE_INFINITY
        var y0 = Float.POSITIVE_INFINITY
        var x1 = Float.NEGATIVE_INFINITY
        var y1 = Float.NEGATIVE_INFINITY
        for (i in 0 until n) {
            inputs.populate(i, scratch)
            xs[i] = scratch.x
            ys[i] = scratch.y
            x0 = min(x0, xs[i]); y0 = min(y0, ys[i]); x1 = max(x1, xs[i]); y1 = max(y1, ys[i])
        }
        Outline(xs, ys, x0, y0, x1, y1)
    }

    /** A new stroke with [stroke]'s brush along `u` range [range]: interpolated ends, original samples between. */
    private fun piece(stroke: Stroke, range: ClosedFloatingPointRange<Float>): Stroke {
        val src = stroke.inputs
        val batch = MutableStrokeInputBatch()
        val a = StrokeInput()
        val b = StrokeInput()
        fun addAt(u: Float) {
            val i = u.toInt().coerceIn(0, src.size - 1)
            val t = u - i
            src.populate(i, a)
            if (t <= 0f || i >= src.size - 1) return add(batch, a)
            src.populate(i + 1, b)
            batch.add(
                a.toolType,
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.elapsedTimeMillis + ((b.elapsedTimeMillis - a.elapsedTimeMillis) * t).toLong(),
                a.strokeUnitLengthCm,
                if (a.hasPressure) a.pressure + (b.pressure - a.pressure) * t else StrokeInput.NO_PRESSURE,
                if (a.hasTilt) a.tiltRadians + (b.tiltRadians - a.tiltRadians) * t else StrokeInput.NO_TILT,
                if (a.hasOrientation) a.orientationRadians else StrokeInput.NO_ORIENTATION,
            )
        }
        addAt(range.start)
        for (i in StrokeSplitter.innerSamples(range)) {
            src.populate(i, a)
            add(batch, a)
        }
        addAt(range.endInclusive)
        return Stroke(stroke.brush, batch)
    }

    private fun add(batch: MutableStrokeInputBatch, s: StrokeInput) {
        batch.add(s.toolType, s.x, s.y, s.elapsedTimeMillis, s.strokeUnitLengthCm, s.pressure, s.tiltRadians, s.orientationRadians)
    }
}
