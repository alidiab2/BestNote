package app.bestnote.engine.stylus

import app.bestnote.core.model.StylusSample
import app.bestnote.core.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SideButtonToolTest {
    private fun ev(action: StylusAction, button: Boolean, t: Long = 0L, tool: PointerTool = PointerTool.Stylus, id: Int = 1) = StylusEvent(
        pointerId = id,
        tool = tool,
        action = action,
        samples = listOf(StylusSample(0f, 0f, 0.5f, 0f, 0f, t)),
        primaryButtonPressed = button,
        secondaryButtonPressed = false,
        canceled = false,
    )

    @Test fun resolve() {
        assertEquals(Tool.Eraser, SideButtonTool.resolve(PointerTool.Eraser, false, Tool.Pen, Tool.Eraser))
        assertEquals(Tool.Eraser, SideButtonTool.resolve(PointerTool.Eraser, false, Tool.Pen, null))
        assertEquals(Tool.Eraser, SideButtonTool.resolve(PointerTool.Stylus, true, Tool.Pen, Tool.Eraser))
        assertEquals(Tool.Highlighter, SideButtonTool.resolve(PointerTool.Stylus, true, Tool.Pen, Tool.Highlighter))
        assertEquals(Tool.Pen, SideButtonTool.resolve(PointerTool.Stylus, true, Tool.Pen, null))
        assertEquals(Tool.Pen, SideButtonTool.resolve(PointerTool.Stylus, false, Tool.Pen, Tool.Eraser))
        // Fingers and mice never get the stylus button behavior.
        assertEquals(Tool.Pencil, SideButtonTool.resolve(PointerTool.Finger, true, Tool.Pencil, Tool.Eraser))
        assertEquals(Tool.Pencil, SideButtonTool.resolve(PointerTool.Mouse, true, Tool.Pencil, Tool.Eraser))
    }

    @Test fun pressEraseReleaseReturnsToPen() {
        val side = SideButtonTool()
        assertEquals(Tool.Eraser, side.onDown(ev(StylusAction.Down, button = true), Tool.Pen))
        assertTrue(side.temporaryActive)
        side.onEnd(1)
        assertFalse(side.temporaryActive)
        assertEquals(Tool.Pen, side.onDown(ev(StylusAction.Down, button = false), Tool.Pen))
    }

    @Test fun releasingButtonMidStrokeKeepsErasingUntilLift() {
        val side = SideButtonTool()
        side.onDown(ev(StylusAction.Down, button = true), Tool.Pen)
        assertNull(side.onMove(ev(StylusAction.Move, button = false, t = 50)))
        assertEquals(Tool.Eraser, side.toolOf(1))
    }

    @Test fun latePressSwitchesStrokeOnce() {
        val side = SideButtonTool()
        side.onDown(ev(StylusAction.Down, button = false, t = 1_000), Tool.Pen)
        assertNull(side.onMove(ev(StylusAction.Move, button = false, t = 1_010)))
        assertEquals(SideButtonTool.Switch(1, Tool.Eraser), side.onMove(ev(StylusAction.Move, button = true, t = 1_040)))
        assertNull(side.onMove(ev(StylusAction.Move, button = true, t = 1_050)))
        assertEquals(Tool.Eraser, side.toolOf(1))
    }

    @Test fun pressLongAfterDownDoesNotSwitch() {
        val side = SideButtonTool()
        side.onDown(ev(StylusAction.Down, button = false, t = 1_000), Tool.Pen)
        assertNull(side.onMove(ev(StylusAction.Move, button = true, t = 1_000 + SideButtonTool.LATE_PRESS_MS + 1)))
        assertEquals(Tool.Pen, side.toolOf(1))
    }

    @Test fun disabledButtonAndEraserEnd() {
        val side = SideButtonTool(temporaryTool = null)
        assertEquals(Tool.Pen, side.onDown(ev(StylusAction.Down, button = true), Tool.Pen))
        assertNull(side.onMove(ev(StylusAction.Move, button = true, t = 10)))
        assertFalse(side.temporaryActive)
        assertEquals(Tool.Eraser, side.onDown(ev(StylusAction.Down, button = false, tool = PointerTool.Eraser, id = 2), Tool.Pen))
    }

    @Test fun selectedEraserIsNotSwitchedAgain() {
        val side = SideButtonTool()
        side.onDown(ev(StylusAction.Down, button = false), Tool.Eraser)
        assertNull(side.onMove(ev(StylusAction.Move, button = true, t = 10)))
    }
}
