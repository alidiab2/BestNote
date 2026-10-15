package app.bestnote.engine.stylus

/** What a pointer gesture should do on the page. */
enum class InputDecision {
    /** Lay down ink with the current tool. */
    Draw,

    /** Erase regardless of the selected tool (the stylus' eraser end). The side button is [SideButtonTool]'s job. */
    Erase,

    /** Not for drawing: leave it to scroll/zoom or ignore it. */
    Ignore,

    /** Abort and discard the in-progress stroke for this pointer. */
    Cancel,
}

/**
 * Palm rejection policy. Pure and stateful per page; feed it every [StylusEvent] in order.
 *
 * Rules:
 *  1. ACTION_CANCEL / FLAG_CANCELED always cancel (the system identified an accidental touch).
 *  2. Hover never draws.
 *  3. Stylus draws (the eraser end erases). Mouse draws.
 *  4. Fingers draw only when [stylusOnly] is off **and** no stylus is touching **or hovering**
 *     (a palm lands while the pen hovers just above the page). A hover counts for
 *     [HOVER_GRACE_MS] after its last sample, so a missed HOVER_EXIT can't lock fingers out.
 *  5. A stylus touching down cancels in-flight finger strokes (the palm usually lands first).
 */
class PalmRejection(var stylusOnly: Boolean = false) {
    private val stylusDown = HashSet<Int>()
    private val fingerDrawing = HashSet<Int>()

    /** Event time of the last stylus hover sample, or null when the pen left (HOVER_EXIT). */
    private var lastStylusHoverAt: Long? = null

    /** Decide for [event]. After a Down, call [drainCancels] for finger strokes to abort. */
    fun decide(event: StylusEvent): InputDecision {
        if (event.canceled || event.action == StylusAction.Cancel) {
            release(event.pointerId)
            return InputDecision.Cancel
        }
        if (event.isHover) {
            if (event.tool == PointerTool.Stylus || event.tool == PointerTool.Eraser) {
                lastStylusHoverAt = if (event.action == StylusAction.HoverExit) null else event.latest.timeMillis
            }
            return InputDecision.Ignore
        }
        return when (event.action) {
            StylusAction.Down -> onDown(event)
            StylusAction.Up -> {
                val wasFinger = event.pointerId in fingerDrawing
                val wasStylus = event.pointerId in stylusDown
                release(event.pointerId)
                if (wasFinger || wasStylus || event.tool == PointerTool.Mouse) InputDecision.Draw else InputDecision.Ignore
            }
            else -> when (event.pointerId) {
                in stylusDown, in fingerDrawing -> InputDecision.Draw
                else -> if (event.tool == PointerTool.Mouse) InputDecision.Draw else InputDecision.Ignore
            }
        }
    }

    private val pendingCancels = ArrayList<Int>()

    /** Finger pointers whose in-flight strokes must be cancelled (stylus or second finger arrived). */
    fun drainCancels(): List<Int> = pendingCancels.toList().also { pendingCancels.clear() }

    fun reset() {
        stylusDown.clear()
        fingerDrawing.clear()
        pendingCancels.clear()
        lastStylusHoverAt = null
    }

    private fun stylusHovering(now: Long): Boolean = lastStylusHoverAt?.let { now - it <= HOVER_GRACE_MS } ?: false

    private fun onDown(event: StylusEvent): InputDecision = when (event.tool) {
        PointerTool.Stylus, PointerTool.Eraser -> {
            stylusDown += event.pointerId
            if (fingerDrawing.isNotEmpty()) {
                pendingCancels += fingerDrawing
                fingerDrawing.clear()
            }
            lastStylusHoverAt = null
            if (event.tool == PointerTool.Eraser) InputDecision.Erase else InputDecision.Draw
        }
        PointerTool.Mouse -> InputDecision.Draw
        PointerTool.Finger, PointerTool.Unknown ->
            if (!stylusOnly && stylusDown.isEmpty() && fingerDrawing.isEmpty() && !stylusHovering(event.latest.timeMillis)) {
                fingerDrawing += event.pointerId
                InputDecision.Draw
            } else {
                // Second finger: whatever the first finger was doing becomes a gesture.
                if (fingerDrawing.isNotEmpty()) {
                    pendingCancels += fingerDrawing
                    fingerDrawing.clear()
                }
                InputDecision.Ignore
            }
    }

    private fun release(pointerId: Int) {
        stylusDown -= pointerId
        fingerDrawing -= pointerId
    }

    companion object {
        /** How long a hover sample keeps fingers from inking when HOVER_EXIT never arrives. */
        const val HOVER_GRACE_MS = 1_000L
    }
}
