package app.bestnote.engine.canvas

import android.graphics.Matrix
import android.view.MotionEvent
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput

/**
 * INK-02: feeds one pencil stroke to the wet layer as stroke inputs that always carry the pen's tilt.
 *
 * androidx.ink's own MotionEvent path keeps AXIS_TILT only when the event's [android.view.InputDevice] declares a
 * tilt motion range; some digitizers (and injected or replayed input) report tilt values without one, so the
 * pencil would never shade. This reads AXIS_TILT and orientation straight from each stylus sample instead.
 * Coordinates are mapped from view to stroke space ([viewToStroke]); times are relative to [downTimeMillis].
 * Per event it allocates one input batch (the wet view reads it on its own thread), O(samples in the event).
 */
internal class TiltFeed(private val downTimeMillis: Long, private val viewToStroke: Matrix) {
    private val gate = SampleGate()
    private val point = FloatArray(2)
    private val input = StrokeInput()

    /** The first input of the stroke (pen-down sample of [event]), or null when [pointerId] isn't in it. */
    fun first(event: MotionEvent, pointerId: Int): StrokeInput? {
        val i = event.findPointerIndex(pointerId)
        if (i < 0) return null
        val input = StrokeInput()
        sample(event, i, -1, input)
        gate.accept(input.x, input.y, input.elapsedTimeMillis)
        return input
    }

    /** [event]'s samples for [pointerId] (historical first, the current one unless [current] is false). */
    fun real(event: MotionEvent, pointerId: Int, current: Boolean = true): MutableStrokeInputBatch {
        // A fresh batch per event: the wet view may still be reading the previous one on its render thread.
        val real = MutableStrokeInputBatch()
        append(event, pointerId, real, gate, current)
        return real
    }

    /** [prediction]'s samples, after the real ones, without disturbing the stroke's own sample order. */
    fun predicted(prediction: MotionEvent?, pointerId: Int): MutableStrokeInputBatch? {
        if (prediction == null) return null
        val predicted = MutableStrokeInputBatch()
        append(prediction, pointerId, predicted, gate.copy(), true)
        return predicted
    }

    /** The pen-up sample of [event] (the stroke's last input). */
    fun last(event: MotionEvent, pointerId: Int): StrokeInput? {
        val i = event.findPointerIndex(pointerId)
        if (i < 0) return null
        val input = StrokeInput()
        sample(event, i, -1, input)
        return input
    }

    private fun append(event: MotionEvent, pointerId: Int, out: MutableStrokeInputBatch, gate: SampleGate, current: Boolean) {
        val i = event.findPointerIndex(pointerId)
        if (i < 0) return
        val n = event.historySize
        for (h in 0..n) {
            if (h == n && !current) break
            sample(event, i, if (h == n) -1 else h)
            if (!gate.accept(input.x, input.y, input.elapsedTimeMillis)) continue
            runCatching {
                out.add(input.toolType, input.x, input.y, input.elapsedTimeMillis, input.strokeUnitLengthCm, input.pressure, input.tiltRadians, input.orientationRadians)
            }
        }
    }

    /** Fills [input] from sample [h] of pointer index [i] (-1 = the current sample). */
    private fun sample(e: MotionEvent, i: Int, h: Int, input: StrokeInput = this.input) {
        val cur = h < 0
        point[0] = if (cur) e.getX(i) else e.getHistoricalX(i, h)
        point[1] = if (cur) e.getY(i) else e.getHistoricalY(i, h)
        viewToStroke.mapPoints(point)
        val time = (if (cur) e.eventTime else e.getHistoricalEventTime(h)) - downTimeMillis
        val stylus = e.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS
        val pressure = (if (cur) e.getPressure(i) else e.getHistoricalPressure(i, h)).coerceIn(0f, 1f)
        val tilt = if (cur) e.getAxisValue(MotionEvent.AXIS_TILT, i) else e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, h)
        val orientation = if (cur) e.getOrientation(i) else e.getHistoricalOrientation(i, h)
        input.update(
            point[0], point[1], time.coerceAtLeast(0L),
            if (stylus) InputToolType.STYLUS else InputToolType.TOUCH,
            StrokeInput.NO_STROKE_UNIT_LENGTH,
            pressure,
            if (stylus && tilt.isFinite()) tilt.coerceIn(0f, HALF_PI) else StrokeInput.NO_TILT,
            if (stylus && orientation.isFinite()) normalized(orientation) else StrokeInput.NO_ORIENTATION,
        )
    }

    private fun normalized(radians: Float): Float {
        val r = radians % FULL
        return if (r < 0f) (r + FULL).coerceAtMost(FULL - 1e-6f) else r
    }

    private companion object {
        const val HALF_PI = (Math.PI / 2).toFloat()
        const val FULL = (2 * Math.PI).toFloat()
    }
}
