package app.bestnote.engine.stylus

import app.bestnote.core.model.StylusSample
import app.bestnote.engine.stylus.HoverButtonGestures.Gesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HoverButtonGesturesTest {
    private fun ev(
        action: StylusAction,
        button: Boolean,
        t: Long,
        x: Float = 100f,
        y: Float = 200f,
        tool: PointerTool = PointerTool.Stylus,
    ) = StylusEvent(
        pointerId = 0,
        tool = tool,
        action = action,
        samples = listOf(StylusSample(x, y, if (action.isTouch) 0.5f else 0f, 0f, 0f, t)),
        primaryButtonPressed = button,
        secondaryButtonPressed = false,
        canceled = false,
    )

    private val StylusAction.isTouch get() = this == StylusAction.Down || this == StylusAction.Move || this == StylusAction.Up

    /** Hover in, press, release: returns what the release produced. */
    private fun HoverButtonGestures.click(at: Long, x: Float = 100f, y: Float = 200f): Gesture? {
        onEvent(ev(StylusAction.HoverMove, false, at, x, y))
        onEvent(ev(StylusAction.HoverMove, true, at + 10, x, y))
        return onEvent(ev(StylusAction.HoverMove, false, at + 120, x + 3, y + 2))
    }

    @Test fun hoverClickOpensQuickPaletteAtPressPoint() {
        val g = HoverButtonGestures()
        g.onEvent(ev(StylusAction.HoverEnter, false, 0))
        assertEquals(Gesture.QuickPalette(100f, 200f), g.click(at = 10))
    }

    @Test fun buttonHeldWhileTouchingIsTheTemporaryEraserNotAClick() {
        val g = HoverButtonGestures()
        g.onEvent(ev(StylusAction.HoverMove, true, 0)) // pressed while hovering…
        assertNull(g.onEvent(ev(StylusAction.Down, true, 50))) // …then writes with it held
        assertNull(g.onEvent(ev(StylusAction.Move, true, 60)))
        assertNull(g.onEvent(ev(StylusAction.Up, true, 200)))
        assertNull(g.onEvent(ev(StylusAction.HoverMove, true, 210)))
        assertNull(g.onEvent(ev(StylusAction.HoverMove, false, 260))) // released in the air afterwards
    }

    @Test fun pressAlreadyHeldWhenHoverStartsIsNotAClick() {
        val g = HoverButtonGestures()
        g.onEvent(ev(StylusAction.Up, true, 0))
        // First hover sample already has the button down: counts as a fresh press only if released quickly.
        g.onEvent(ev(StylusAction.HoverEnter, true, 10))
        assertNull(g.onEvent(ev(StylusAction.HoverMove, false, 10 + HoverButtonGestures.MAX_CLICK_MS + 1)))
    }

    @Test fun longPressInTheAirIsNotAClick() {
        val g = HoverButtonGestures()
        g.onEvent(ev(StylusAction.HoverMove, true, 0))
        assertNull(g.onEvent(ev(StylusAction.HoverMove, false, 900)))
    }

    @Test fun pressThenLargeMoveIsNotAClick() {
        val g = HoverButtonGestures()
        g.onEvent(ev(StylusAction.HoverMove, true, 0, x = 100f))
        assertNull(g.onEvent(ev(StylusAction.HoverMove, false, 100, x = 400f)))
    }

    @Test fun leavingHoverRangeCancelsThePress() {
        val g = HoverButtonGestures()
        g.onEvent(ev(StylusAction.HoverMove, true, 0))
        g.onEvent(ev(StylusAction.HoverExit, true, 50))
        assertNull(g.onEvent(ev(StylusAction.HoverEnter, false, 80)))
    }

    @Test fun fingersAndEraserEndAreIgnored() {
        val g = HoverButtonGestures()
        g.onEvent(ev(StylusAction.HoverMove, true, 0, tool = PointerTool.Eraser))
        assertNull(g.onEvent(ev(StylusAction.HoverMove, false, 50, tool = PointerTool.Eraser)))
        assertNull(g.onEvent(ev(StylusAction.Down, true, 60, tool = PointerTool.Finger)))
    }

    @Test fun disabledNeverFires() {
        val g = HoverButtonGestures(enabled = false)
        assertNull(g.click(at = 0))
    }

    @Test fun doubleClickUndoes() {
        val g = HoverButtonGestures(doubleClickUndo = true)
        assertNull(g.click(at = 0)) // waits: might be the first half of a double click
        assertEquals(Gesture.Undo, g.click(at = 200))
        assertNull(g.deadline)
        assertNull(g.onTick(10_000))
    }

    @Test fun singleClickWithDoubleClickEnabledOpensAfterTheWindow() {
        val g = HoverButtonGestures(doubleClickUndo = true)
        assertNull(g.click(at = 0))
        val deadline = requireNotNull(g.deadline)
        assertNull(g.onTick(deadline - 1))
        assertEquals(Gesture.QuickPalette(100f, 200f), g.onTick(deadline))
        assertNull(g.onTick(deadline + 1))
    }

    @Test fun touchingAfterAClickCancelsThePendingPalette() {
        val g = HoverButtonGestures(doubleClickUndo = true)
        g.click(at = 0)
        g.onEvent(ev(StylusAction.Down, false, 150))
        assertNull(g.deadline)
        assertNull(g.onTick(5_000))
    }

    @Test fun slowSecondClickIsTwoPalettes() {
        val g = HoverButtonGestures(doubleClickUndo = true)
        g.click(at = 0)
        assertEquals(Gesture.QuickPalette(100f, 200f), g.onTick(1_000))
        assertNull(g.click(at = 2_000))
        assertEquals(Gesture.QuickPalette(100f, 200f), g.onTick(3_000))
    }
}
