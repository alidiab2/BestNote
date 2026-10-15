package app.bestnote.engine.stylus

import app.bestnote.core.model.Tool

/**
 * S Pen side button as a temporary tool (SPEN-04). Pure and stateful per page; feed it every
 * non-hover [StylusEvent] for a pointer, in order.
 *
 * Rules:
 *  - The tool is fixed per stroke. A stroke that starts with the button held (or with the legacy
 *    Samsung 211/212/213 actions) uses [temporaryTool]; releasing the button mid-stroke keeps it
 *    until the pen lifts, so the end of an erase gesture never turns into ink.
 *  - The stylus' eraser end always erases.
 *  - The selected tool is never modified, so lifting the pen returns to exactly the previous pen.
 *  - Some digitizers report the button a few samples after touch-down. If the button appears
 *    within [LATE_PRESS_MS] of the down, [onMove] returns a [Switch] so the caller cancels the
 *    stray ink and restarts the stroke with the temporary tool. After that the stroke keeps its tool.
 *
 * @property temporaryTool tool used while the button is held; null disables the button.
 */
class SideButtonTool(var temporaryTool: Tool? = Tool.Eraser) {

    /** Re-run the stroke for [pointerId] with [tool] (the ink laid so far must be cancelled). */
    data class Switch(val pointerId: Int, val tool: Tool)

    private class Stroke(val downAt: Long, var tool: Tool, val temporary: Boolean)

    private val strokes = HashMap<Int, Stroke>()

    /** True while at least one stroke uses the temporary tool (drives the "held" UI pill). */
    val temporaryActive: Boolean get() = strokes.values.any { it.temporary }

    /** Tool for a stroke starting with [event] when [selected] is the toolbar tool. */
    fun onDown(event: StylusEvent, selected: Tool): Tool {
        val temporary = event.tool == PointerTool.Stylus && event.primaryButtonPressed && temporaryTool != null
        val tool = resolve(event.tool, event.primaryButtonPressed, selected, temporaryTool)
        strokes[event.pointerId] = Stroke(event.latest.timeMillis, tool, temporary)
        return tool
    }

    /** Non-null when a late button press should switch this stroke to the temporary tool. */
    fun onMove(event: StylusEvent): Switch? {
        val stroke = strokes[event.pointerId] ?: return null
        val temp = temporaryTool ?: return null
        if (stroke.temporary || event.tool != PointerTool.Stylus || !event.primaryButtonPressed) return null
        if (event.latest.timeMillis - stroke.downAt > LATE_PRESS_MS || stroke.tool == temp) return null
        strokes[event.pointerId] = Stroke(stroke.downAt, temp, temporary = true)
        return Switch(event.pointerId, temp)
    }

    /** Tool of the stroke [pointerId] is drawing, or null if none. */
    fun toolOf(pointerId: Int): Tool? = strokes[pointerId]?.tool

    /** Call on Up and Cancel. */
    fun onEnd(pointerId: Int) {
        strokes -= pointerId
    }

    fun reset() = strokes.clear()

    companion object {
        /** Button presses this soon after touch-down still count for the whole stroke. */
        const val LATE_PRESS_MS = 120L

        /** Which tool a pointer uses. Pure, shared by touch and hover (cursor preview). */
        fun resolve(pointer: PointerTool, buttonPressed: Boolean, selected: Tool, temporary: Tool?): Tool = when {
            pointer == PointerTool.Eraser -> Tool.Eraser
            pointer == PointerTool.Stylus && buttonPressed && temporary != null -> temporary
            else -> selected
        }
    }
}
