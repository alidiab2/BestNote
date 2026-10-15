package app.bestnote.engine.canvas.shape

import kotlin.math.hypot

/**
 * INK-08: notices the pen staying still at the end of a stroke ("draw, then hold"). Feed it every
 * sample of one pointer; [held] turns true once the pen has drawn at least [minTravel] and then
 * stayed within [slop] of one spot for [holdMillis]. Moving on afterwards clears it again, so only
 * a hold at the very end of the stroke counts. Pure Kotlin; units are whatever the caller feeds.
 */
class HoldDetector(
    private val slop: Float,
    private val minTravel: Float,
    val holdMillis: Long = DEFAULT_HOLD_MS,
) {
    private var anchorX = 0f
    private var anchorY = 0f
    private var anchorT = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var travel = 0f
    private var started = false

    /** The pen has been still long enough at the current end of the stroke. */
    var held: Boolean = false
        private set

    fun start(x: Float, y: Float, t: Long) {
        anchorX = x; anchorY = y; anchorT = t
        lastX = x; lastY = y
        travel = 0f
        held = false
        started = true
    }

    /** One more sample. @return true exactly when this sample completes a hold. */
    fun add(x: Float, y: Float, t: Long): Boolean {
        if (!started) {
            start(x, y, t)
            return false
        }
        travel += hypot(x - lastX, y - lastY)
        lastX = x; lastY = y
        if (hypot(x - anchorX, y - anchorY) > slop) {
            anchorX = x; anchorY = y; anchorT = t
            held = false
            return false
        }
        return check(t)
    }

    /** The pen sent nothing new (it may be perfectly still): re-checks at time [t]. @return true when the hold completes now. */
    fun check(t: Long): Boolean {
        if (!started || held || travel < minTravel || t - anchorT < holdMillis) return false
        held = true
        return true
    }

    /** When [check] could next succeed, or null when no hold is pending. */
    val deadline: Long? get() = if (started && !held) anchorT + holdMillis else null

    fun reset() {
        started = false
        held = false
    }

    companion object {
        const val DEFAULT_HOLD_MS = 500L
    }
}
