package app.bestnote.engine.stylus

/** What a pointer gesture should do on the page. */
enum class InputDecision {
    /** Lay down ink with the current tool. */
    Draw,

    /** Erase regardless of the selected tool (eraser end, or stylus side button held). */
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
 *  3. Stylus draws (or erases with the eraser end / side button held). Mouse draws.
 *  4. Fingers draw only when [stylusOnly] is off **and** no stylus is currently touching.
 *  5. A stylus touching down cancels in-flight finger strokes (the palm usually lands first).
 */
class PalmRejection(var stylusOnly: Boolean = false) {
    private val stylusDown = HashSet<Int>()
    private val fingerDrawing = HashSet<Int>()

    /** Decide for [event]. After a Down, call [drainCancels] for finger strokes to abort. */
    fun decide(event: StylusEvent): InputDecision {
        if (event.canceled || event.action == StylusAction.Cancel) {
            release(event.pointerId)
            return InputDecision.Cancel
        }
        if (event.isHover) return InputDecision.Ignore
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
    }

    private fun onDown(event: StylusEvent): InputDecision = when (event.tool) {
        PointerTool.Stylus, PointerTool.Eraser -> {
            stylusDown += event.pointerId
            if (fingerDrawing.isNotEmpty()) {
                pendingCancels += fingerDrawing
                fingerDrawing.clear()
            }
            if (event.tool == PointerTool.Eraser || event.primaryButtonPressed) InputDecision.Erase else InputDecision.Draw
        }
        PointerTool.Mouse -> InputDecision.Draw
        PointerTool.Finger, PointerTool.Unknown ->
            if (!stylusOnly && stylusDown.isEmpty() && fingerDrawing.isEmpty()) {
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
}
