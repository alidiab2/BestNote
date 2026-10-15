/*
 * Debug-only: dispatches a [ReplayPlan] into a view as real stylus MotionEvents.
 */
package app.bestnote.debug.replay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

/**
 * Feeds [events] into [target] through its normal entry points: touch actions go through
 * dispatchTouchEvent (so onInterceptTouchEvent/onTouchEvent, palm rules, prediction and wet ink all
 * run), hover actions through dispatchGenericMotionEvent. Events are TOOL_TYPE_STYLUS from a
 * stylus+touchscreen source, carry pressure, AXIS_TILT and orientation, the side button as
 * BUTTON_STYLUS_PRIMARY, and historical samples batched per frame. Runs on the main thread at the
 * original timing; [beforeDown] runs before every pen-down (re-applies the chosen pen).
 */
class StrokeReplayer(
    private val target: View,
    private val events: List<ReplayEvent>,
    private val beforeDown: () -> Unit,
    private val onDone: (dispatched: Int, elapsedMs: Long) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var index = 0
    private var base = 0L
    private var downTime = 0L
    private var cancelled = false

    fun start(delayMs: Long = 300) {
        base = SystemClock.uptimeMillis() + delayMs
        schedule()
    }

    fun cancel() {
        cancelled = true
        handler.removeCallbacksAndMessages(null)
    }

    private fun schedule() {
        if (cancelled) return
        if (index >= events.size) {
            onDone(index, SystemClock.uptimeMillis() - base)
            return
        }
        handler.postAtTime(::step, base + events[index].atMs.toLong())
    }

    private fun step() {
        // Dispatch every event that is due (the main thread may have been busy).
        val now = SystemClock.uptimeMillis()
        do {
            dispatch(events[index])
            index++
        } while (index < events.size && base + events[index].atMs <= now)
        schedule()
    }

    private fun dispatch(e: ReplayEvent) {
        val action = when (e.action) {
            ReplayAction.Down -> MotionEvent.ACTION_DOWN
            ReplayAction.Move -> MotionEvent.ACTION_MOVE
            ReplayAction.Up -> MotionEvent.ACTION_UP
            ReplayAction.HoverEnter -> MotionEvent.ACTION_HOVER_ENTER
            ReplayAction.HoverMove -> MotionEvent.ACTION_HOVER_MOVE
            ReplayAction.HoverExit -> MotionEvent.ACTION_HOVER_EXIT
        }
        if (e.action == ReplayAction.Down) {
            beforeDown()
            downTime = base + e.samples.first().tMs.toLong()
        }
        val hover = e.action in HOVER
        val buttons = if (e.button) MotionEvent.BUTTON_STYLUS_PRIMARY else 0
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val first = e.samples.first()
        val event = MotionEvent.obtain(
            if (hover) base + first.tMs.toLong() else downTime,
            base + first.tMs.toLong(),
            action,
            1,
            props,
            arrayOf(coords(first, hover)),
            0,
            buttons,
            1f,
            1f,
            DEVICE_ID,
            0,
            SOURCE,
            0,
        )
        for (s in e.samples.drop(1)) event.addBatch(base + s.tMs.toLong(), arrayOf(coords(s, hover)), 0)
        try {
            if (hover) target.dispatchGenericMotionEvent(event) else target.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun coords(s: ReplaySample, hover: Boolean) = MotionEvent.PointerCoords().apply {
        x = s.x
        y = s.y
        pressure = if (hover) 0f else s.pressure
        size = 0.01f
        orientation = s.orientationRad
        setAxisValue(MotionEvent.AXIS_TILT, s.tiltRad)
        if (hover) setAxisValue(MotionEvent.AXIS_DISTANCE, 1f)
    }

    private companion object {
        val HOVER = setOf(ReplayAction.HoverEnter, ReplayAction.HoverMove, ReplayAction.HoverExit)
        const val SOURCE = InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN
        const val DEVICE_ID = 0x5E9
    }
}
