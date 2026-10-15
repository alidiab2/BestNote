package app.bestnote.engine.stylus

import app.bestnote.core.model.StylusSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmRejectionTest {
    private fun ev(id: Int, tool: PointerTool, action: StylusAction, button: Boolean = false, t: Long = 0L) = StylusEvent(
        pointerId = id,
        tool = tool,
        action = action,
        samples = listOf(StylusSample(0f, 0f, 0.5f, 0f, 0f, t)),
        primaryButtonPressed = button,
        secondaryButtonPressed = false,
        canceled = action == StylusAction.Cancel,
    )

    @Test fun stylusOnlyIgnoresFingers() {
        val palm = PalmRejection(stylusOnly = true)
        assertEquals(InputDecision.Ignore, palm.decide(ev(0, PointerTool.Finger, StylusAction.Down)))
        assertEquals(InputDecision.Ignore, palm.decide(ev(0, PointerTool.Finger, StylusAction.Move)))
        assertEquals(InputDecision.Draw, palm.decide(ev(1, PointerTool.Stylus, StylusAction.Down)))
        assertEquals(InputDecision.Draw, palm.decide(ev(1, PointerTool.Stylus, StylusAction.Move)))
    }

    @Test fun fingerDrawsWhenStylusOnlyOff() {
        val palm = PalmRejection(stylusOnly = false)
        assertEquals(InputDecision.Draw, palm.decide(ev(0, PointerTool.Finger, StylusAction.Down)))
        assertEquals(InputDecision.Draw, palm.decide(ev(0, PointerTool.Finger, StylusAction.Move)))
        assertEquals(InputDecision.Draw, palm.decide(ev(0, PointerTool.Finger, StylusAction.Up)))
    }

    @Test fun stylusArrivalCancelsFingerStroke() {
        val palm = PalmRejection(stylusOnly = false)
        palm.decide(ev(0, PointerTool.Finger, StylusAction.Down))
        assertEquals(InputDecision.Draw, palm.decide(ev(1, PointerTool.Stylus, StylusAction.Down)))
        assertEquals(listOf(0), palm.drainCancels())
        assertTrue(palm.drainCancels().isEmpty())
        assertEquals(InputDecision.Ignore, palm.decide(ev(0, PointerTool.Finger, StylusAction.Move)))
    }

    @Test fun cancelAlwaysWins() {
        val palm = PalmRejection()
        palm.decide(ev(1, PointerTool.Stylus, StylusAction.Down))
        assertEquals(InputDecision.Cancel, palm.decide(ev(1, PointerTool.Stylus, StylusAction.Cancel)))
        assertEquals(InputDecision.Ignore, palm.decide(ev(1, PointerTool.Stylus, StylusAction.Move)))
    }

    @Test fun eraserEndErasesSideButtonIsLeftToSideButtonTool() {
        val palm = PalmRejection()
        assertEquals(InputDecision.Erase, palm.decide(ev(1, PointerTool.Eraser, StylusAction.Down)))
        assertEquals(InputDecision.Draw, palm.decide(ev(2, PointerTool.Stylus, StylusAction.Down, button = true)))
    }

    @Test fun fingerDoesNotInkWhileStylusTouches() {
        val palm = PalmRejection(stylusOnly = false)
        palm.decide(ev(1, PointerTool.Stylus, StylusAction.Down))
        assertEquals(InputDecision.Ignore, palm.decide(ev(0, PointerTool.Finger, StylusAction.Down)))
        assertEquals(InputDecision.Ignore, palm.decide(ev(0, PointerTool.Finger, StylusAction.Move)))
    }

    @Test fun fingerDoesNotInkWhileStylusHovers() {
        val palm = PalmRejection(stylusOnly = false)
        palm.decide(ev(1, PointerTool.Stylus, StylusAction.HoverMove, t = 1_000))
        assertEquals(InputDecision.Ignore, palm.decide(ev(0, PointerTool.Finger, StylusAction.Down, t = 1_200)))
    }

    @Test fun hoverExitOrStaleHoverFreesFingers() {
        val palm = PalmRejection(stylusOnly = false)
        palm.decide(ev(1, PointerTool.Stylus, StylusAction.HoverMove, t = 1_000))
        palm.decide(ev(1, PointerTool.Stylus, StylusAction.HoverExit, t = 1_010))
        assertEquals(InputDecision.Draw, palm.decide(ev(0, PointerTool.Finger, StylusAction.Down, t = 1_020)))
        palm.decide(ev(0, PointerTool.Finger, StylusAction.Up, t = 1_030))

        // HOVER_EXIT missed: the hover stops counting after HOVER_GRACE_MS.
        palm.decide(ev(1, PointerTool.Stylus, StylusAction.HoverMove, t = 2_000))
        val late = 2_000 + PalmRejection.HOVER_GRACE_MS + 1
        assertEquals(InputDecision.Draw, palm.decide(ev(0, PointerTool.Finger, StylusAction.Down, t = late)))
    }

    @Test fun hoverNeverDraws() {
        assertEquals(InputDecision.Ignore, PalmRejection().decide(ev(1, PointerTool.Stylus, StylusAction.HoverMove)))
    }
}
