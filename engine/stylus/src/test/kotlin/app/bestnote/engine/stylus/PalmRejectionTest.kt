package app.bestnote.engine.stylus

import app.bestnote.core.model.StylusSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmRejectionTest {
    private fun ev(id: Int, tool: PointerTool, action: StylusAction, button: Boolean = false) = StylusEvent(
        pointerId = id,
        tool = tool,
        action = action,
        samples = listOf(StylusSample(0f, 0f, 0.5f, 0f, 0f, 0L)),
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

    @Test fun eraserEndAndSideButtonErase() {
        val palm = PalmRejection()
        assertEquals(InputDecision.Erase, palm.decide(ev(1, PointerTool.Eraser, StylusAction.Down)))
        assertEquals(InputDecision.Erase, palm.decide(ev(2, PointerTool.Stylus, StylusAction.Down, button = true)))
    }

    @Test fun hoverNeverDraws() {
        assertEquals(InputDecision.Ignore, PalmRejection().decide(ev(1, PointerTool.Stylus, StylusAction.HoverMove)))
    }
}
