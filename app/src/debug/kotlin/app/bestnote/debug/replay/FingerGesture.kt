package app.bestnote.debug.replay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Debug-only: a two-finger pinch (and pan) injected through the view's real touch pipeline, since
 * `adb shell input` can only do one pointer. Fingers start [span0] px apart around ([cx], [cy]) and
 * end [span1] px apart around ([cx] + [dx], [cy] + [dy]), over [steps] frames at 120 Hz.
 *
 *   adb shell am broadcast -a app.bestnote.debug.PINCH -p <pkg> --ef cx 1200 --ef cy 700 \
 *       --ef span0 200 --ef span1 600 --ef dx 0 --ef dy 0 --ei steps 40
 */
internal class FingerGesture(
    private val target: View,
    private val cx: Float,
    private val cy: Float,
    private val span0: Float,
    private val span1: Float,
    private val dx: Float,
    private val dy: Float,
    private val steps: Int,
    private val done: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val down = SystemClock.uptimeMillis()
    private var step = 0

    fun start() {
        send(MotionEvent.ACTION_DOWN, 0f, onePointer = true)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0f)
        handler.postDelayed(::tick, FRAME_MS)
    }

    private fun tick() {
        step++
        val f = step.toFloat() / steps
        if (step <= steps) {
            send(MotionEvent.ACTION_MOVE, f)
            handler.postDelayed(::tick, FRAME_MS)
            return
        }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 1f)
        send(MotionEvent.ACTION_UP, 1f, onePointer = true)
        Log.i("BestNoteReplay", "PINCH done")
        done()
    }

    private fun send(action: Int, f: Float, onePointer: Boolean = false) {
        val span = span0 + (span1 - span0) * f
        val mx = cx + dx * f
        val my = cy + dy * f
        val n = if (onePointer) 1 else 2
        val props = Array(n) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(n) { i ->
            val a = ANGLE + i * Math.PI
            MotionEvent.PointerCoords().apply {
                x = mx + (span / 2 * cos(a)).toFloat()
                y = my + (span / 2 * sin(a)).toFloat()
                pressure = 1f
                size = 0.1f
            }
        }
        val now = down + step * FRAME_MS
        val e = MotionEvent.obtain(down, now, action, n, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            target.dispatchTouchEvent(e)
        } finally {
            e.recycle()
        }
    }

    private companion object {
        const val FRAME_MS = 8L
        const val ANGLE = 0.6
    }
}
