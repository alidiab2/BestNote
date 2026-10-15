package app.bestnote.engine.stylus

import kotlin.math.hypot

/**
 * Disambiguates the S Pen side button (SPEN-04 follow-up, quick palette). Pure state machine; feed
 * it every stylus [StylusEvent] (hover and touch) in order.
 *
 * - Button **clicked while hovering** (pressed and released without the tip touching, within
 *   [maxClickMs] and [maxDriftPx]) = [Gesture.QuickPalette] at the hover point.
 * - Button **held while touching** = temporary eraser: handled by [SideButtonTool]; the moment the tip
 *   touches, the pending click is abandoned here.
 * - With [doubleClickUndo], two hover clicks within [doubleClickMs] = [Gesture.Undo]. A single click
 *   then waits [doubleClickMs] before opening the palette: call [onTick] at [deadline].
 * - [enabled] false: never emits anything (the setting "Side button click while hovering: Off").
 */
class HoverButtonGestures(
    var enabled: Boolean = true,
    var doubleClickUndo: Boolean = false,
    private val maxClickMs: Long = MAX_CLICK_MS,
    private val doubleClickMs: Long = DOUBLE_CLICK_MS,
    private val maxDriftPx: Float = MAX_DRIFT_PX,
) {
    sealed interface Gesture {
        data class QuickPalette(val x: Float, val y: Float) : Gesture
        data object Undo : Gesture
    }

    private class Press(val at: Long, val x: Float, val y: Float)
    private class Click(val at: Long, val x: Float, val y: Float)

    private var press: Press? = null
    private var pending: Click? = null

    /** False after the tip touched until a hover sample shows the button up: a button still held from writing is no click. */
    private var armed = true

    /** Time at which [onTick] resolves a waiting single click, or null. */
    val deadline: Long? get() = pending?.let { it.at + doubleClickMs }

    fun onEvent(event: StylusEvent): Gesture? {
        if (event.tool != PointerTool.Stylus) return null
        val s = event.latest
        if (!enabled) {
            reset()
            return null
        }
        if (!event.isHover) {
            // The tip touched: a held button is the temporary eraser, and a click just before writing
            // must not pop anything over the page.
            reset()
            armed = false
            return null
        }
        if (!event.primaryButtonPressed) armed = true
        if (event.action == StylusAction.HoverExit) {
            press = null
            return null
        }
        val current = press
        return when {
            event.primaryButtonPressed && current == null && armed -> {
                press = Press(s.timeMillis, s.x, s.y)
                null
            }
            !event.primaryButtonPressed && current != null -> {
                press = null
                val quick = s.timeMillis - current.at <= maxClickMs
                val still = hypot(s.x - current.x, s.y - current.y) <= maxDriftPx
                if (quick && still) onClick(Click(s.timeMillis, current.x, current.y)) else null
            }
            else -> null
        }
    }

    /** Resolves a single click once the double-click window passed. */
    fun onTick(now: Long): Gesture? {
        val click = pending ?: return null
        if (now - click.at < doubleClickMs) return null
        pending = null
        return Gesture.QuickPalette(click.x, click.y)
    }

    fun reset() {
        press = null
        pending = null
    }

    private fun onClick(click: Click): Gesture? {
        if (!doubleClickUndo) return Gesture.QuickPalette(click.x, click.y)
        val first = pending
        return if (first != null && click.at - first.at <= doubleClickMs) {
            pending = null
            Gesture.Undo
        } else {
            pending = click
            null
        }
    }

    companion object {
        const val MAX_CLICK_MS = 450L
        const val DOUBLE_CLICK_MS = 300L

        /** Pen drift allowed during a click (px); hovering hands are never perfectly still. */
        const val MAX_DRIFT_PX = 48f
    }
}
