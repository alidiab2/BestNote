package app.bestnote.engine.ink

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.StrokeInputBatch
import app.bestnote.core.model.LineDash

/**
 * INK-07: the region a dashed or dotted stroke shows through, in stroke coordinates: its centerline cut into
 * [DashPattern] dashes (or dots), each widened to [DashPattern.bandWidth]. Drawing the stroke's own mesh
 * through this clip keeps the pen's look (pressure, taper, pencil grain) inside every dash. One per thread.
 */
class DashClip {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val center = Path()
    private val input = StrokeInput()

    /** The clip for a stroke with these [inputs], drawn [width] wide (full width) with [dash]; null for solid. */
    fun of(inputs: StrokeInputBatch, dash: LineDash, width: Float, maxWidthScale: Float, out: Path = Path()): Path? {
        val n = inputs.size
        if (n == 0) return null
        center.rewind()
        for (i in 0 until n) {
            inputs.populate(i, input)
            if (i == 0) center.moveTo(input.x, input.y) else center.lineTo(input.x, input.y)
        }
        if (n == 1) {
            inputs.populate(0, input)
            center.lineTo(input.x + TAP_LENGTH, input.y)
        }
        return of(center, dash, width, maxWidthScale, out)
    }

    /** The clip for a centerline [xs]/[ys] ([count] points). */
    fun of(xs: FloatArray, ys: FloatArray, count: Int, dash: LineDash, width: Float, maxWidthScale: Float, out: Path = Path()): Path? {
        if (count == 0) return null
        center.rewind()
        center.moveTo(xs[0], ys[0])
        for (i in 1 until count) center.lineTo(xs[i], ys[i])
        if (count == 1) center.lineTo(xs[0] + TAP_LENGTH, ys[0])
        return of(center, dash, width, maxWidthScale, out)
    }

    private fun of(centerline: Path, dash: LineDash, width: Float, maxWidthScale: Float, out: Path): Path? {
        val intervals = DashPattern.intervals(dash, width) ?: return null
        paint.pathEffect = DashPathEffect(intervals, 0f)
        paint.strokeWidth = DashPattern.bandWidth(dash, width, maxWidthScale)
        paint.strokeCap = if (dash == LineDash.Dotted) Paint.Cap.ROUND else Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.ROUND
        out.rewind()
        @Suppress("DEPRECATION") // getFillPath: the API 34 replacement isn't available on minSdk.
        paint.getFillPath(centerline, out)
        return out
    }

    private companion object {
        /** A tap has one sample: give it a hair of length so it gets its first dash. */
        const val TAP_LENGTH = 0.001f
    }
}
