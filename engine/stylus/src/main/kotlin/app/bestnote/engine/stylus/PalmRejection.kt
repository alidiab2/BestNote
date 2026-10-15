package app.bestnote.engine.stylus

/** What a pointer gesture should do on the page. */
enum class InputDecision {
    /** Lay down ink with the current tool. */
    Draw,

    /** Erase regardless of the selected tool (the stylus' eraser end). The side button is [SideButtonTool]'s job. */
    Erase,

    /** A finger that navigates: scroll, pan, pinch-zoom, taps and multi-finger tap gestures. Never ink. */
    Navigate,

    /** Do nothing with it (hover, or a palm resting while the pen writes or hovers). */
    Ignore,

    /** Abort and discard the in-progress stroke for this pointer. */
    Cancel,
}

/**
 * Palm rejection and finger routing (SPEN-02). Pure and stateful; feed it every [StylusEvent] in order.
 *
 * Rules:
 *  1. ACTION_CANCEL / FLAG_CANCELED always cancel (the system identified an accidental touch).
 *  2. Hover never draws.
 *  3. Stylus draws (the eraser end erases). Mouse draws.
 *  4. Auto stylus-only: with [autoStylusOnly] on, the first stylus event (touch or hover) sets
 *     [penSeen] and calls [onPenSeen] once, so the host can persist it. From then on fingers navigate.
 *  5. Fingers navigate when [fingersNavigate]; otherwise one finger may draw while no stylus is
 *     touching or hovering, and a second finger turns the gesture into navigation.
 *  6. Palms: a finger that touches down while the pen touches or hovers (a hover counts for
 *     [HOVER_GRACE_MS] after its last sample, so a missed HOVER_EXIT can't lock fingers out) is
 *     ignored until it lifts. A pen touching down or starting to hover turns every finger already
 *     down into a palm: its stroke or scroll is cancelled ([drainCancels], then [isPalm]).
 */
class PalmRejection(var stylusOnly: Boolean = false) {
    /** "Fingers draw until a pen is seen" (the default): see rule 4. */
    var autoStylusOnly: Boolean = false

    /** A pen has been used on this device. The host restores it from settings; it only ever turns on here. */
    var penSeen: Boolean = false

    /** Called once when [penSeen] turns on because of an event. */
    var onPenSeen: (() -> Unit)? = null

    /** True when fingers never ink and only scroll / zoom / tap. */
    val fingersNavigate: Boolean get() = stylusOnly || (autoStylusOnly && penSeen)

    private val stylusDown = HashSet<Int>()
    private val fingerDrawing = HashSet<Int>()
    private val fingerNavigating = HashSet<Int>()
    private val palms = HashSet<Int>()
    private val pendingCancels = ArrayList<Int>()

    /** Event time of the last stylus hover sample, or null when the pen left (HOVER_EXIT). */
    private var lastStylusHoverAt: Long? = null

    /** Decide for [event]. After each event, call [drainCancels] for finger pointers to abort. */
    fun decide(event: StylusEvent): InputDecision {
        val pen = event.tool == PointerTool.Stylus || event.tool == PointerTool.Eraser
        if (pen) markPenSeen()
        if (event.canceled || event.action == StylusAction.Cancel) {
            release(event.pointerId)
            return InputDecision.Cancel
        }
        if (event.isHover) {
            if (pen) {
                if (event.action == StylusAction.HoverExit) {
                    lastStylusHoverAt = null
                } else {
                    if (lastStylusHoverAt == null) fingersBecomePalms()
                    lastStylusHoverAt = event.latest.timeMillis
                }
            }
            return InputDecision.Ignore
        }
        val id = event.pointerId
        return when (event.action) {
            StylusAction.Down -> onDown(event)
            StylusAction.Up -> {
                val decision = current(id, event.tool)
                release(id)
                decision
            }
            else -> current(id, event.tool)
        }
    }

    private fun current(id: Int, tool: PointerTool): InputDecision = when (id) {
        in palms -> InputDecision.Ignore
        in stylusDown, in fingerDrawing -> InputDecision.Draw
        in fingerNavigating -> InputDecision.Navigate
        else -> if (tool == PointerTool.Mouse) InputDecision.Draw else InputDecision.Ignore
    }

    /** Finger pointers whose stroke or scroll must be aborted (a stylus or a second finger arrived). */
    fun drainCancels(): List<Int> = pendingCancels.toList().also { pendingCancels.clear() }

    /** True while [pointerId] is a finger judged to be a palm: drop it, never scroll with it. */
    fun isPalm(pointerId: Int): Boolean = pointerId in palms

    fun reset() {
        stylusDown.clear()
        fingerDrawing.clear()
        fingerNavigating.clear()
        palms.clear()
        pendingCancels.clear()
        lastStylusHoverAt = null
    }

    private fun markPenSeen() {
        if (penSeen) return
        penSeen = true
        onPenSeen?.invoke()
    }

    private fun stylusHovering(now: Long): Boolean = lastStylusHoverAt?.let { now - it <= HOVER_GRACE_MS } ?: false

    /** The pen arrived: every finger already on the glass is a palm. */
    private fun fingersBecomePalms() {
        val fingers = fingerDrawing + fingerNavigating
        if (fingers.isEmpty()) return
        palms += fingers
        pendingCancels += fingers
        fingerDrawing.clear()
        fingerNavigating.clear()
    }

    private fun onDown(event: StylusEvent): InputDecision = when (event.tool) {
        PointerTool.Stylus, PointerTool.Eraser -> {
            stylusDown += event.pointerId
            fingersBecomePalms()
            lastStylusHoverAt = null
            if (event.tool == PointerTool.Eraser) InputDecision.Erase else InputDecision.Draw
        }
        PointerTool.Mouse -> InputDecision.Draw
        PointerTool.Finger, PointerTool.Unknown -> {
            val id = event.pointerId
            when {
                stylusDown.isNotEmpty() || stylusHovering(event.latest.timeMillis) -> {
                    palms += id
                    InputDecision.Ignore
                }
                !fingersNavigate && fingerDrawing.isEmpty() && fingerNavigating.isEmpty() -> {
                    fingerDrawing += id
                    InputDecision.Draw
                }
                else -> {
                    // Second finger: whatever the first finger was doing becomes a gesture.
                    if (fingerDrawing.isNotEmpty()) {
                        pendingCancels += fingerDrawing
                        fingerNavigating += fingerDrawing
                        fingerDrawing.clear()
                    }
                    fingerNavigating += id
                    InputDecision.Navigate
                }
            }
        }
    }

    private fun release(pointerId: Int) {
        stylusDown -= pointerId
        fingerDrawing -= pointerId
        fingerNavigating -= pointerId
        palms -= pointerId
    }

    companion object {
        /** How long a hover sample keeps fingers from inking when HOVER_EXIT never arrives. */
        const val HOVER_GRACE_MS = 1_000L
    }
}
