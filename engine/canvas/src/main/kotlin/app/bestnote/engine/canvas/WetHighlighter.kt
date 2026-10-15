package app.bestnote.engine.canvas

import android.graphics.Matrix
import android.view.MotionEvent
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import app.bestnote.engine.ink.BrushCatalog

/**
 * A highlighter stroke being drawn. Unlike pens it is not drawn by the front-buffered
 * `InProgressStrokesView` (which always paints on top of everything): the page's dry layer draws
 * it inside its highlight layer, *under* the ink and blended with earlier highlights, from the
 * first frame. It costs the highlighter a frame of latency; a highlight never needs pen-speed ink.
 *
 * The stroke is built with an opaque copy of [brush] (highlight layers are drawn opaque and
 * composited translucent); [finish] returns it with the real brush, so the dry stroke is identical.
 */
internal class WetHighlighter(
    val pointerId: Int,
    val brush: Brush,
    private val downTimeMillis: Long,
    /** View -> stroke coordinates (e.g. page points); null = the same. */
    private val viewToStroke: Matrix? = null,
) {
    val id = InProgressStrokeId()
    val stroke = InProgressStroke().also { it.start(BrushCatalog.opaque(brush)) }
    private val real = MutableStrokeInputBatch()
    private val predicted = MutableStrokeInputBatch()
    private val gate = SampleGate()
    private val point = FloatArray(2)

    /** Adds [event]'s samples for [pointerId] (historical first), plus [prediction]'s, and updates the shape. */
    fun add(event: MotionEvent, prediction: MotionEvent?) {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        real.clear()
        predicted.clear()
        for (h in 0 until event.historySize) {
            append(real, gate, event.getHistoricalX(index, h), event.getHistoricalY(index, h), event.getHistoricalEventTime(h),
                event.getHistoricalPressure(index, h), event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, h))
        }
        append(real, gate, event.getX(index), event.getY(index), event.eventTime, event.getPressure(index), event.getAxisValue(MotionEvent.AXIS_TILT, index))
        if (prediction != null && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val p = prediction.findPointerIndex(pointerId)
            if (p >= 0) {
                val predictedGate = gate.copy()
                for (h in 0 until prediction.historySize) {
                    append(predicted, predictedGate, prediction.getHistoricalX(p, h), prediction.getHistoricalY(p, h), prediction.getHistoricalEventTime(h),
                        prediction.getHistoricalPressure(p, h), prediction.getHistoricalAxisValue(MotionEvent.AXIS_TILT, p, h))
                }
                append(predicted, predictedGate, prediction.getX(p), prediction.getY(p), prediction.eventTime, prediction.getPressure(p), prediction.getAxisValue(MotionEvent.AXIS_TILT, p))
            }
        }
        runCatching { stroke.enqueueInputs(real, predicted) }
        stroke.updateShape(event.eventTime - downTimeMillis)
    }

    /** Pen-up: adds the last samples and returns the finished stroke, drawn with the real brush. */
    fun finish(event: MotionEvent): Stroke {
        add(event, null)
        stroke.finishInput()
        stroke.updateShape(event.eventTime - downTimeMillis)
        return stroke.toImmutable().copy(brush)
    }

    private fun append(batch: MutableStrokeInputBatch, gate: SampleGate, x: Float, y: Float, time: Long, pressure: Float, tilt: Float) {
        val t = time - downTimeMillis
        point[0] = x
        point[1] = y
        viewToStroke?.mapPoints(point)
        if (!gate.accept(point[0], point[1], t)) return
        runCatching {
            batch.add(InputToolType.STYLUS, point[0], point[1], t, StrokeInput.NO_STROKE_UNIT_LENGTH, pressure.coerceIn(0f, 1f), tilt.coerceIn(0f, HALF_PI), StrokeInput.NO_ORIENTATION)
        }
    }

    private companion object {
        const val HALF_PI = (Math.PI / 2).toFloat()
    }
}

/**
 * Drops samples androidx.ink rejects: time going backwards, or an exact repeat of the previous
 * sample. Pure, so it is unit-tested on the JVM.
 */
class SampleGate {
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastT = Long.MIN_VALUE

    fun accept(x: Float, y: Float, t: Long): Boolean {
        if (t < lastT) return false
        if (t == lastT && x == lastX && y == lastY) return false
        lastX = x
        lastY = y
        lastT = t
        return true
    }

    fun copy() = SampleGate().also {
        it.lastX = lastX
        it.lastY = lastY
        it.lastT = lastT
    }
}
