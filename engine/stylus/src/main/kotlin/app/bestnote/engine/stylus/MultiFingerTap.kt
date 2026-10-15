package app.bestnote.engine.stylus

import kotlin.math.hypot

/**
 * CANVAS-04: two-finger tap = undo, three-finger tap = redo. Pure; feed it every touch [StylusEvent]
 * (with the [InputDecision] [PalmRejection] gave it) and it returns a [Gesture] on the last finger up.
 *
 * A tap: 2 or 3 fingers land within [downWindowMs] of the first, none moves more than [slopPx],
 * and all lift within [maxDurationMs]. Any pen touch, palm, cancel or [invalidate] (the host calls it
 * while something is being written) spoils the gesture until every finger has lifted.
 */
class MultiFingerTap(
    private val slopPx: Float,
    private val maxDurationMs: Long = MAX_DURATION_MS,
    private val downWindowMs: Long = DOWN_WINDOW_MS,
) {
    enum class Gesture { Undo, Redo }

    private val downs = HashMap<Int, Pair<Float, Float>>()
    private var start = 0L
    private var most = 0
    private var spoiled = false

    fun invalidate() {
        if (downs.isNotEmpty()) spoiled = true
    }

    fun onEvent(event: StylusEvent, decision: InputDecision): Gesture? {
        if (event.isHover) return null
        val finger = event.tool == PointerTool.Finger || event.tool == PointerTool.Unknown
        if (!finger) {
            if (event.action == StylusAction.Down) invalidate()
            return null
        }
        val id = event.pointerId
        val t = event.latest.timeMillis
        when {
            event.canceled || event.action == StylusAction.Cancel || decision == InputDecision.Cancel -> {
                spoiled = true
                return lift(id, t, count = false)
            }
            event.action == StylusAction.Down -> {
                if (downs.isEmpty()) {
                    start = t
                    most = 0
                    spoiled = false
                }
                downs[id] = event.latest.x to event.latest.y
                most = maxOf(most, downs.size)
                if (t - start > downWindowMs || decision == InputDecision.Ignore) spoiled = true
            }
            event.action == StylusAction.Move -> moved(id, event)
            event.action == StylusAction.Up -> {
                moved(id, event)
                return lift(id, t, count = true)
            }
            else -> Unit
        }
        return null
    }

    private fun moved(id: Int, event: StylusEvent) {
        val (x0, y0) = downs[id] ?: return
        if (event.samples.any { hypot(it.x - x0, it.y - y0) > slopPx }) spoiled = true
    }

    private fun lift(id: Int, t: Long, count: Boolean): Gesture? {
        if (downs.remove(id) == null || downs.isNotEmpty()) return null
        if (!count || spoiled || t - start > maxDurationMs) return null
        return when (most) {
            2 -> Gesture.Undo
            3 -> Gesture.Redo
            else -> null
        }
    }

    companion object {
        const val MAX_DURATION_MS = 350L
        const val DOWN_WINDOW_MS = 150L
    }
}
