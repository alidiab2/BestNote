package app.bestnote.feature.editor

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

/**
 * DEBUG BUILDS ONLY. Synthesizes S Pen input on the open page, because adb cannot press the side
 * button. Used for emulator screenshots and manual QA:
 *
 * ```
 * # side-button click while hovering at 50% / 40% of the page (count 2 = double click)
 * adb shell am broadcast -a app.bestnote.debug.HOVER_CLICK --ef fx 0.5 --ef fy 0.4 --ei count 1
 * # hold / release a stylus touch (toolbar auto-hide)
 * adb shell am broadcast -a app.bestnote.debug.TOUCH --es phase down --ef fx 0.5 --ef fy 0.5
 * ```
 * Real handwriting: `scripts/agent/replay.sh` (QA-INK).
 */
internal object DebugStylusHooks {
    private const val HOVER_CLICK = "app.bestnote.debug.HOVER_CLICK"
    private const val TOUCH = "app.bestnote.debug.TOUCH"
    private const val CLICK_HOLD_MS = 90L
    private const val CLICK_GAP_MS = 160L

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun attach(context: Context, view: View): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val x = intent.getFloatExtra("fx", 0.5f) * view.width
                val y = intent.getFloatExtra("fy", 0.5f) * view.height
                when (intent.action) {
                    HOVER_CLICK -> hoverClick(view, x, y, intent.getIntExtra("count", 1))
                    TOUCH -> touch(view, x, y, intent.getStringExtra("phase") ?: "down")
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(HOVER_CLICK)
            addAction(TOUCH)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        return { context.unregisterReceiver(receiver) }
    }

    private fun event(down: Long, at: Long, action: Int, x: Float, y: Float, pressure: Float, buttons: Int): MotionEvent {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_STYLUS
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = pressure
        }
        return MotionEvent.obtain(down, at, action, 1, arrayOf(props), arrayOf(coords), 0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
    }

    private fun hover(view: View, action: Int, x: Float, y: Float, buttons: Int) {
        val now = SystemClock.uptimeMillis()
        val e = event(now, now, action, x, y, 0f, buttons)
        view.onHoverEvent(e)
        e.recycle()
    }

    private fun hoverClick(view: View, x: Float, y: Float, count: Int) {
        hover(view, MotionEvent.ACTION_HOVER_ENTER, x, y, 0)
        var t = 0L
        repeat(count) {
            view.postDelayed({ hover(view, MotionEvent.ACTION_HOVER_MOVE, x, y, MotionEvent.BUTTON_STYLUS_PRIMARY) }, t)
            view.postDelayed({ hover(view, MotionEvent.ACTION_HOVER_MOVE, x + 1, y, 0) }, t + CLICK_HOLD_MS)
            t += CLICK_HOLD_MS + CLICK_GAP_MS
        }
    }

    private var held: Long? = null

    private fun touch(view: View, x: Float, y: Float, phase: String) {
        val now = SystemClock.uptimeMillis()
        val down = held
        val e = if (phase == "down" && down == null) {
            held = now
            event(now, now, MotionEvent.ACTION_DOWN, x, y, 0.6f, 0)
        } else if (phase == "up" && down != null) {
            held = null
            event(down, now, MotionEvent.ACTION_UP, x, y, 0.6f, 0)
        } else {
            return
        }
        view.dispatchTouchEvent(e)
        e.recycle()
    }

}
