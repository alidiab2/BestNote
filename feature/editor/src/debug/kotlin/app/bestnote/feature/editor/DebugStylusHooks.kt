package app.bestnote.feature.editor

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import app.bestnote.engine.canvas.PageCanvasView
import java.io.File

/**
 * DEBUG BUILDS ONLY. Synthesizes S Pen input on the open page, because adb cannot press the side
 * button or write with pressure. Used for emulator screenshots and manual QA:
 *
 * ```
 * # side-button click while hovering at 50% / 40% of the page (count 2 = double click)
 * adb shell am broadcast -a app.bestnote.debug.HOVER_CLICK --ef fx 0.5 --ef fy 0.4 --ei count 1
 * # write handwriting from <external files>/ink.txt (one stroke per line: "x,y,p x,y,p ...", dp)
 * adb shell am broadcast -a app.bestnote.debug.WRITE --es file ink.txt
 * # hold / release a stylus touch (toolbar auto-hide)
 * adb shell am broadcast -a app.bestnote.debug.TOUCH --es phase down --ef fx 0.5 --ef fy 0.5
 * ```
 * `scripts/agent/handwrite.py` produces ink.txt from text.
 */
internal object DebugStylusHooks {
    private const val TAG = "BestNote/DebugStylus"
    private const val HOVER_CLICK = "app.bestnote.debug.HOVER_CLICK"
    private const val WRITE = "app.bestnote.debug.WRITE"
    private const val TOUCH = "app.bestnote.debug.TOUCH"
    private const val CLICK_HOLD_MS = 90L
    private const val CLICK_GAP_MS = 160L
    private const val SAMPLE_MS = 4L
    private const val STROKE_GAP_MS = 24L

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun attach(context: Context, view: PageCanvasView): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val x = intent.getFloatExtra("fx", 0.5f) * view.width
                val y = intent.getFloatExtra("fy", 0.5f) * view.height
                when (intent.action) {
                    HOVER_CLICK -> hoverClick(view, x, y, intent.getIntExtra("count", 1))
                    WRITE -> write(c, view, intent.getStringExtra("file") ?: "ink.txt")
                    TOUCH -> touch(view, x, y, intent.getStringExtra("phase") ?: "down")
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(HOVER_CLICK)
            addAction(WRITE)
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

    private fun hover(view: PageCanvasView, action: Int, x: Float, y: Float, buttons: Int) {
        val now = SystemClock.uptimeMillis()
        val e = event(now, now, action, x, y, 0f, buttons)
        view.onHoverEvent(e)
        e.recycle()
    }

    private fun hoverClick(view: PageCanvasView, x: Float, y: Float, count: Int) {
        hover(view, MotionEvent.ACTION_HOVER_ENTER, x, y, 0)
        var t = 0L
        repeat(count) {
            view.postDelayed({ hover(view, MotionEvent.ACTION_HOVER_MOVE, x, y, MotionEvent.BUTTON_STYLUS_PRIMARY) }, t)
            view.postDelayed({ hover(view, MotionEvent.ACTION_HOVER_MOVE, x + 1, y, 0) }, t + CLICK_HOLD_MS)
            t += CLICK_HOLD_MS + CLICK_GAP_MS
        }
    }

    private var held: Long? = null

    private fun touch(view: PageCanvasView, x: Float, y: Float, phase: String) {
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

    private fun write(context: Context, view: PageCanvasView, name: String) {
        val file = File(context.getExternalFilesDir(null), name)
        if (!file.exists()) {
            Log.w(TAG, "no ink file at $file")
            return
        }
        val density = view.resources.displayMetrics.density
        val strokes = file.readLines().filter { it.isNotBlank() }.map { line ->
            line.trim().split(' ').map { p ->
                val (x, y, pr) = p.split(',').map(String::toFloat)
                Triple(x * density, y * density, pr)
            }
        }
        var delayMs = 0L
        for (stroke in strokes) {
            view.postDelayed({ drawStroke(view, stroke) }, delayMs)
            delayMs += STROKE_GAP_MS
        }
        Log.i(TAG, "writing ${strokes.size} strokes from $file")
    }

    private fun drawStroke(view: PageCanvasView, points: List<Triple<Float, Float, Float>>) {
        if (points.isEmpty()) return
        val start = SystemClock.uptimeMillis()
        points.forEachIndexed { i, (x, y, p) ->
            val action = when (i) {
                0 -> MotionEvent.ACTION_DOWN
                points.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val e = event(start, start + i * SAMPLE_MS, action, x, y, p, 0)
            view.dispatchTouchEvent(e)
            e.recycle()
        }
    }
}
