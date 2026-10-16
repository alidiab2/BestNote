package app.bestnote.engine.canvas

import android.view.MotionEvent
import app.bestnote.engine.ink.PenTuning
import app.bestnote.engine.ink.StrokeStabilizer

/**
 * Applies a pen's [PenTuning] (INK-04 pressure sensitivity, INK-05 stabilization) to the stylus
 * samples of the strokes being written, before they reach androidx.ink.
 *
 * Wet-ink cost: per sample, a pressure mapping and a 1-euro filter step (O(1), nothing that grows
 * with the document); per event, one pooled [MotionEvent] copy that the caller recycles. With the
 * default tuning nothing is copied. The shaped samples are what the stroke stores, so a stroke keeps
 * its look when settings change later, and existing strokes are never rewritten.
 *
 * The tuning is captured per stroke at pen-down: a settings change never bends a stroke mid-way.
 */
internal class InkInputShaper(private val pxPerMm: Float) {
    private class Active(val tuning: PenTuning, val filter: StrokeStabilizer)

    private val strokes = HashMap<Int, Active>()
    private val peek = HashMap<Int, StrokeStabilizer>()
    private var props: Array<MotionEvent.PointerProperties> = emptyArray()
    private var coords: Array<MotionEvent.PointerCoords> = emptyArray()
    private val point = FloatArray(2)

    /** Pen-down for [pointerId]: starts shaping its samples with [tuning]. */
    fun begin(pointerId: Int, tuning: PenTuning) {
        if (tuning.isIdentity) {
            strokes.remove(pointerId)
            return
        }
        strokes[pointerId] = Active(tuning, StrokeStabilizer(tuning.stabilization, pxPerMm))
    }

    fun end(pointerId: Int) {
        strokes.remove(pointerId)
        peek.remove(pointerId)
    }

    /**
     * [event] with [pointerId]'s samples shaped, or [event] itself when that pointer isn't shaped.
     * A new event must be passed to [release] after use. [predicted] events are shaped from a copy
     * of the filter state, so predictions never disturb the real stroke. [last] (the pen-up) ends on
     * the real pen position, so stabilization never shortens a stroke.
     */
    fun shape(event: MotionEvent, pointerId: Int, predicted: Boolean = false, last: Boolean = false): MotionEvent {
        val active = strokes[pointerId] ?: return event
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return event
        val filter = if (predicted) {
            peek.getOrPut(pointerId) { StrokeStabilizer(active.tuning.stabilization, pxPerMm) }.also { it.copyFrom(active.filter) }
        } else {
            active.filter
        }
        val n = event.pointerCount
        ensure(n)
        for (i in 0 until n) event.getPointerProperties(i, props[i])
        var out: MotionEvent? = null
        val samples = event.historySize + 1
        for (h in 0 until samples) {
            val current = h == event.historySize
            for (i in 0 until n) {
                val c = coords[i]
                if (current) event.getPointerCoords(i, c) else event.getHistoricalPointerCoords(i, h, c)
            }
            val time = if (current) event.eventTime else event.getHistoricalEventTime(h)
            val c = coords[index]
            c.pressure = active.tuning.pressure(c.pressure)
            filter.filter(c.x, c.y, time.toDouble(), point)
            if (!(last && current)) {
                c.x = point[0]
                c.y = point[1]
            }
            if (out == null) {
                out = MotionEvent.obtain(
                    event.downTime, time, event.action, n, props, coords, event.metaState, event.buttonState,
                    event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags, event.source, event.flags,
                )
            } else {
                out.addBatch(time, coords, event.metaState)
            }
        }
        return out ?: event
    }

    /** Recycles [shaped] when [shape] made a copy of [original]. */
    fun release(original: MotionEvent?, shaped: MotionEvent?) {
        if (shaped != null && shaped !== original) shaped.recycle()
    }

    private fun ensure(n: Int) {
        if (props.size >= n) return
        props = Array(n) { MotionEvent.PointerProperties() }
        coords = Array(n) { MotionEvent.PointerCoords() }
    }
}
