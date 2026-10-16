package app.bestnote.engine.stylus

import app.bestnote.core.model.StylusSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiFingerTapTest {
    private fun ev(id: Int, action: StylusAction, t: Long, x: Float = 100f * id, y: Float = 100f, tool: PointerTool = PointerTool.Finger) = StylusEvent(
        pointerId = id,
        tool = tool,
        action = action,
        samples = listOf(StylusSample(x, y, 1f, 0f, 0f, t)),
        primaryButtonPressed = false,
        secondaryButtonPressed = false,
        canceled = false,
    )

    private val nav = InputDecision.Navigate
    private fun tap() = MultiFingerTap(slopPx = 20f)

    @Test fun twoFingerTapIsUndo() {
        val g = tap()
        assertNull(g.onEvent(ev(0, StylusAction.Down, 0), nav))
        assertNull(g.onEvent(ev(1, StylusAction.Down, 30), nav))
        assertNull(g.onEvent(ev(1, StylusAction.Move, 60, x = 105f), nav))
        assertNull(g.onEvent(ev(0, StylusAction.Up, 120), nav))
        assertEquals(MultiFingerTap.Gesture.Undo, g.onEvent(ev(1, StylusAction.Up, 140), nav))
    }

    @Test fun threeFingerTapIsRedo() {
        val g = tap()
        g.onEvent(ev(0, StylusAction.Down, 0), nav)
        g.onEvent(ev(1, StylusAction.Down, 10), nav)
        g.onEvent(ev(2, StylusAction.Down, 20), nav)
        g.onEvent(ev(2, StylusAction.Up, 100), nav)
        g.onEvent(ev(0, StylusAction.Up, 110), nav)
        assertEquals(MultiFingerTap.Gesture.Redo, g.onEvent(ev(1, StylusAction.Up, 120), nav))
    }

    @Test fun oneFingerTapIsNothing() {
        val g = tap()
        g.onEvent(ev(0, StylusAction.Down, 0), nav)
        assertNull(g.onEvent(ev(0, StylusAction.Up, 50), nav))
    }

    @Test fun pinchIsNotATap() {
        val g = tap()
        g.onEvent(ev(0, StylusAction.Down, 0), nav)
        g.onEvent(ev(1, StylusAction.Down, 10), nav)
        g.onEvent(ev(1, StylusAction.Move, 50, x = 160f), nav)
        g.onEvent(ev(0, StylusAction.Up, 100), nav)
        assertNull(g.onEvent(ev(1, StylusAction.Up, 110, x = 160f), nav))
    }

    @Test fun slowHoldIsNotATap() {
        val g = tap()
        g.onEvent(ev(0, StylusAction.Down, 0), nav)
        g.onEvent(ev(1, StylusAction.Down, 10), nav)
        g.onEvent(ev(0, StylusAction.Up, 600), nav)
        assertNull(g.onEvent(ev(1, StylusAction.Up, 610), nav))
    }

    @Test fun notWhileWritingOrWithThePen() {
        val g = tap()
        g.onEvent(ev(0, StylusAction.Down, 0), nav)
        g.onEvent(ev(1, StylusAction.Down, 10), nav)
        g.invalidate() // the host saw ink in progress
        g.onEvent(ev(0, StylusAction.Up, 50), nav)
        assertNull(g.onEvent(ev(1, StylusAction.Up, 60), nav))

        g.onEvent(ev(0, StylusAction.Down, 100), nav)
        g.onEvent(ev(1, StylusAction.Down, 110), nav)
        g.onEvent(ev(5, StylusAction.Down, 120, tool = PointerTool.Stylus), InputDecision.Draw)
        g.onEvent(ev(0, StylusAction.Up, 150), nav)
        assertNull(g.onEvent(ev(1, StylusAction.Up, 160), nav))
    }

    @Test fun palmsSpoilTheTap() {
        val g = tap()
        g.onEvent(ev(0, StylusAction.Down, 0), InputDecision.Ignore)
        g.onEvent(ev(1, StylusAction.Down, 10), InputDecision.Ignore)
        g.onEvent(ev(0, StylusAction.Up, 50), InputDecision.Ignore)
        assertNull(g.onEvent(ev(1, StylusAction.Up, 60), InputDecision.Ignore))
    }

    @Test fun recoversAfterASpoiledGesture() {
        val g = tap()
        g.onEvent(ev(0, StylusAction.Down, 0), nav)
        g.onEvent(ev(1, StylusAction.Down, 400), nav) // too late: spoiled
        g.onEvent(ev(0, StylusAction.Up, 420), nav)
        assertNull(g.onEvent(ev(1, StylusAction.Up, 430), nav))
        g.onEvent(ev(0, StylusAction.Down, 1_000), nav)
        g.onEvent(ev(1, StylusAction.Down, 1_020), nav)
        g.onEvent(ev(0, StylusAction.Up, 1_100), nav)
        assertEquals(MultiFingerTap.Gesture.Undo, g.onEvent(ev(1, StylusAction.Up, 1_110), nav))
    }
}
