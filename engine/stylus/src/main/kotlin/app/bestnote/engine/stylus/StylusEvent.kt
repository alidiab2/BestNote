/**
 * :engine:stylus — turns raw Android input into BestNote's stylus vocabulary.
 *
 * [StylusEventMapper] converts a [MotionEvent] (via the [PointerEventSource] seam, so the
 * mapping is unit-testable on the JVM) into per-pointer [StylusEvent]s carrying tool type,
 * pressure, tilt, orientation, hover state, the S Pen side button and all historical samples.
 * [PalmRejection] then decides what each pointer is allowed to do.
 */
package app.bestnote.engine.stylus

import android.view.MotionEvent
import app.bestnote.core.model.StylusSample

/** Physical source of a pointer. */
enum class PointerTool { Stylus, Eraser, Finger, Mouse, Unknown }

enum class StylusAction { Down, Move, Up, Cancel, HoverEnter, HoverMove, HoverExit }

/**
 * One pointer's slice of a MotionEvent.
 *
 * @property samples historical samples first, current sample last (always non-empty).
 * @property primaryButtonPressed S Pen / stylus side button (BUTTON_STYLUS_PRIMARY).
 * @property canceled the system cancelled this gesture (ACTION_CANCEL, or FLAG_CANCELED on
 *   ACTION_UP / ACTION_POINTER_UP when an accidental touch such as a palm is detected).
 */
data class StylusEvent(
    val pointerId: Int,
    val tool: PointerTool,
    val action: StylusAction,
    val samples: List<StylusSample>,
    val primaryButtonPressed: Boolean,
    val secondaryButtonPressed: Boolean,
    val canceled: Boolean,
) {
    val isHover: Boolean
        get() = action == StylusAction.HoverEnter || action == StylusAction.HoverMove || action == StylusAction.HoverExit
    val latest: StylusSample get() = samples.last()
}

/**
 * Read-only view of a [MotionEvent]. `historyPos == CURRENT` addresses the current sample.
 * Exists so mapping logic runs in plain JVM tests (android.jar's MotionEvent is a stub there).
 */
interface PointerEventSource {
    val actionMasked: Int
    val actionIndex: Int
    val pointerCount: Int
    val historySize: Int
    val buttonState: Int
    val flags: Int
    fun pointerId(index: Int): Int
    fun toolType(index: Int): Int
    fun x(index: Int, historyPos: Int): Float
    fun y(index: Int, historyPos: Int): Float
    fun pressure(index: Int, historyPos: Int): Float
    fun tilt(index: Int, historyPos: Int): Float
    fun orientation(index: Int, historyPos: Int): Float
    fun timeMillis(historyPos: Int): Long

    companion object {
        const val CURRENT = -1
    }
}

/** Adapts a real [MotionEvent]. */
class MotionEventSource(private val e: MotionEvent) : PointerEventSource {
    override val actionMasked get() = e.actionMasked
    override val actionIndex get() = e.actionIndex
    override val pointerCount get() = e.pointerCount
    override val historySize get() = e.historySize
    override val buttonState get() = e.buttonState
    override val flags get() = e.flags
    override fun pointerId(index: Int) = e.getPointerId(index)
    override fun toolType(index: Int) = e.getToolType(index)
    override fun x(index: Int, historyPos: Int) =
        if (historyPos == PointerEventSource.CURRENT) e.getX(index) else e.getHistoricalX(index, historyPos)
    override fun y(index: Int, historyPos: Int) =
        if (historyPos == PointerEventSource.CURRENT) e.getY(index) else e.getHistoricalY(index, historyPos)
    override fun pressure(index: Int, historyPos: Int) =
        if (historyPos == PointerEventSource.CURRENT) e.getPressure(index) else e.getHistoricalPressure(index, historyPos)
    override fun tilt(index: Int, historyPos: Int) =
        if (historyPos == PointerEventSource.CURRENT) e.getAxisValue(MotionEvent.AXIS_TILT, index)
        else e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, historyPos)
    override fun orientation(index: Int, historyPos: Int) =
        if (historyPos == PointerEventSource.CURRENT) e.getAxisValue(MotionEvent.AXIS_ORIENTATION, index)
        else e.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, index, historyPos)
    override fun timeMillis(historyPos: Int) =
        if (historyPos == PointerEventSource.CURRENT) e.eventTime else e.getHistoricalEventTime(historyPos)
}

object StylusEventMapper {
    // Mirrors of android.view.MotionEvent constants, so tests never touch the android.jar stub.
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val ACTION_CANCEL = 3
    const val ACTION_POINTER_DOWN = 5
    const val ACTION_POINTER_UP = 6
    const val ACTION_HOVER_MOVE = 7
    const val ACTION_HOVER_ENTER = 9
    const val ACTION_HOVER_EXIT = 10
    const val TOOL_TYPE_FINGER = 1
    const val TOOL_TYPE_STYLUS = 2
    const val TOOL_TYPE_MOUSE = 3
    const val TOOL_TYPE_ERASER = 4
    const val BUTTON_STYLUS_PRIMARY = 0x20
    const val BUTTON_STYLUS_SECONDARY = 0x40
    /** MotionEvent.FLAG_CANCELED (public since API 33, set by the framework earlier too). */
    const val FLAG_CANCELED = 0x20

    /**
     * Private action codes some older Samsung firmware sends instead of DOWN / UP / MOVE while the
     * S Pen side button is held (**[verify]** in docs/platform/samsung-s-pen.md §4). Mapped to the
     * standard actions with [StylusEvent.primaryButtonPressed] set, so nothing downstream knows.
     */
    const val SAMSUNG_ACTION_PEN_DOWN = 211
    const val SAMSUNG_ACTION_PEN_UP = 212
    const val SAMSUNG_ACTION_PEN_MOVE = 213

    fun map(event: MotionEvent): List<StylusEvent> = map(MotionEventSource(event))

    /**
     * Per-pointer events for one MotionEvent. MOVE / CANCEL / HOVER_* report every pointer;
     * DOWN / UP variants report only the pointer that changed. MOVE and HOVER_MOVE batch all
     * historical samples (oldest first) before the current one, so nothing the digitizer
     * reported between frames is lost.
     */
    fun map(src: PointerEventSource): List<StylusEvent> {
        val canceledFlag = (src.flags and FLAG_CANCELED) != 0
        val legacyButton = src.actionMasked in SAMSUNG_ACTION_PEN_DOWN..SAMSUNG_ACTION_PEN_MOVE
        val (action, indices) = when (src.actionMasked) {
            ACTION_DOWN, ACTION_POINTER_DOWN, SAMSUNG_ACTION_PEN_DOWN -> StylusAction.Down to listOf(src.actionIndex)
            ACTION_UP, ACTION_POINTER_UP, SAMSUNG_ACTION_PEN_UP ->
                (if (canceledFlag) StylusAction.Cancel else StylusAction.Up) to listOf(src.actionIndex)
            ACTION_MOVE, SAMSUNG_ACTION_PEN_MOVE -> StylusAction.Move to (0 until src.pointerCount).toList()
            ACTION_CANCEL -> StylusAction.Cancel to (0 until src.pointerCount).toList()
            ACTION_HOVER_ENTER -> StylusAction.HoverEnter to (0 until src.pointerCount).toList()
            ACTION_HOVER_MOVE -> StylusAction.HoverMove to (0 until src.pointerCount).toList()
            ACTION_HOVER_EXIT -> StylusAction.HoverExit to (0 until src.pointerCount).toList()
            else -> return emptyList()
        }
        val buttons = src.buttonState
        return indices.map { i ->
            StylusEvent(
                pointerId = src.pointerId(i),
                tool = toolOf(src.toolType(i)),
                action = action,
                samples = samplesOf(src, i, includeHistory = action == StylusAction.Move || action == StylusAction.HoverMove),
                primaryButtonPressed = legacyButton || (buttons and BUTTON_STYLUS_PRIMARY) != 0,
                secondaryButtonPressed = (buttons and BUTTON_STYLUS_SECONDARY) != 0,
                canceled = action == StylusAction.Cancel,
            )
        }
    }

    fun toolOf(toolType: Int): PointerTool = when (toolType) {
        TOOL_TYPE_STYLUS -> PointerTool.Stylus
        TOOL_TYPE_ERASER -> PointerTool.Eraser
        TOOL_TYPE_FINGER -> PointerTool.Finger
        TOOL_TYPE_MOUSE -> PointerTool.Mouse
        else -> PointerTool.Unknown
    }

    private fun samplesOf(src: PointerEventSource, index: Int, includeHistory: Boolean): List<StylusSample> {
        val history = if (includeHistory) (0 until src.historySize) else IntRange.EMPTY
        return (history + PointerEventSource.CURRENT).map { h ->
            StylusSample(
                x = src.x(index, h),
                y = src.y(index, h),
                pressure = src.pressure(index, h).coerceIn(0f, 1f),
                tiltRad = src.tilt(index, h),
                orientationRad = src.orientation(index, h),
                timeMillis = src.timeMillis(h),
            )
        }
    }
}
