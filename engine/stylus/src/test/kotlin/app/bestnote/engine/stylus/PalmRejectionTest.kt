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

    @Test fun stylusOnlyFingersNavigate() {
        val palm = PalmRejection(stylusOnly = true)
        assertEquals(InputDecision.Navigate, palm.decide(ev(0, PointerTool.Finger, StylusAction.Down)))
        assertEquals(InputDecision.Navigate, palm.decide(ev(0, PointerTool.Finger, StylusAction.Move)))
        assertEquals(InputDecision.Navigate, palm.decide(ev(0, PointerTool.Finger, StylusAction.Up)))
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

    @Test fun autoStylusOnlyFlipsOnFirstPenTouchAndReportsOnce() {
        val palm = PalmRejection().apply { autoStylusOnly = true }
        var reported = 0
        palm.onPenSeen = { reported++ }
        // No pen yet: a finger writes.
        assertEquals(InputDecision.Draw, palm.decide(ev(0, PointerTool.Finger, StylusAction.Down, t = 0)))
        palm.decide(ev(0, PointerTool.Finger, StylusAction.Up, t = 10))
        assertEquals(InputDecision.Draw, palm.decide(ev(1, PointerTool.Stylus, StylusAction.Down, t = 20)))
        palm.decide(ev(1, PointerTool.Stylus, StylusAction.Up, t = 30))
        palm.decide(ev(1, PointerTool.Stylus, StylusAction.Down, t = 40))
        palm.decide(ev(1, PointerTool.Stylus, StylusAction.Up, t = 50))
        assertTrue(palm.penSeen)
        assertTrue(palm.fingersNavigate)
        assertEquals(1, reported)
        // From now on fingers only navigate, even long after the pen left.
        assertEquals(InputDecision.Navigate, palm.decide(ev(0, PointerTool.Finger, StylusAction.Down, t = 10_000)))
    }

    @Test fun autoStylusOnlyFlipsOnPenHoverToo() {
        val palm = PalmRejection().apply { autoStylusOnly = true }
        palm.decide(ev(1, PointerTool.Stylus, StylusAction.HoverEnter, t = 0))
        palm.decide(ev(1, PointerTool.Stylus, StylusAction.HoverExit, t = 5))
        assertEquals(InputDecision.Navigate, palm.decide(ev(0, PointerTool.Finger, StylusAction.Down, t = 5_000)))
    }

    @Test fun restoredPenSeenMakesFingersNavigateFromTheStart() {
        val palm = PalmRejection().apply { autoStylusOnly = true; penSeen = true }
        assertEquals(InputDecision.Navigate, palm.decide(ev(0, PointerTool.Finger, StylusAction.Down)))
    }

    @Test fun drawWithFingerOptInKeepsFingersInking() {
        // "Draw with finger" on: neither stylusOnly nor auto mode.
        val palm = PalmRejection().apply { penSeen = true }
        assertTrue(!palm.fingersNavigate)
        assertEquals(InputDecision.Draw, palm.decide(ev(0, PointerTool.Finger, StylusAction.Down, t = 5_000)))
    }

    @Test fun mouseAlwaysDraws() {
        val palm = PalmRejection(stylusOnly = true)
        assertEquals(InputDecision.Draw, palm.decide(ev(5, PointerTool.Mouse, StylusAction.Down)))
        assertEquals(InputDecision.Draw, palm.decide(ev(5, PointerTool.Mouse, StylusAction.Move)))
    }

    @Test fun secondFingerTurnsFingerStrokeIntoNavigation() {
        val palm = PalmRejection()
        assertEquals(InputDecision.Draw, palm.decide(ev(0, PointerTool.Finger, StylusAction.Down)))
        assertEquals(InputDecision.Navigate, palm.decide(ev(1, PointerTool.Finger, StylusAction.Down)))
        assertEquals(listOf(0), palm.drainCancels())
        assertTrue(!palm.isPalm(0))
        assertEquals(InputDecision.Navigate, palm.decide(ev(0, PointerTool.Finger, StylusAction.Move)))
    }

    @Test fun palmLandingWhilePenWritesNeverScrolls() {
        val palm = PalmRejection(stylusOnly = true)
        palm.decide(ev(1, PointerTool.Stylus, StylusAction.Down))
        assertEquals(InputDecision.Ignore, palm.decide(ev(0, PointerTool.Finger, StylusAction.Down)))
        assertTrue(palm.isPalm(0))
        assertEquals(InputDecision.Ignore, palm.decide(ev(0, PointerTool.Finger, StylusAction.Move)))
        palm.decide(ev(1, PointerTool.Stylus, StylusAction.Up))
        // Still a palm after the pen lifts, until it lifts itself.
        assertEquals(InputDecision.Ignore, palm.decide(ev(0, PointerTool.Finger, StylusAction.Move)))
        assertEquals(InputDecision.Ignore, palm.decide(ev(0, PointerTool.Finger, StylusAction.Up)))
        assertTrue(!palm.isPalm(0))
    }

    @Test fun palmLandingWhilePenHoversNeverScrolls() {
        val palm = PalmRejection(stylusOnly = true)
        palm.decide(ev(1, PointerTool.Stylus, StylusAction.HoverMove, t = 1_000))
        assertEquals(InputDecision.Ignore, palm.decide(ev(0, PointerTool.Finger, StylusAction.Down, t = 1_100)))
    }

    @Test fun palmDownBeforeThePenIsCancelledWhenThePenArrives() {
        val palm = PalmRejection(stylusOnly = true)
        assertEquals(InputDecision.Navigate, palm.decide(ev(0, PointerTool.Finger, StylusAction.Down, t = 0)))
        palm.decide(ev(1, PointerTool.Stylus, StylusAction.HoverEnter, t = 30))
        assertEquals(listOf(0), palm.drainCancels())
        assertTrue(palm.isPalm(0))
        assertEquals(InputDecision.Ignore, palm.decide(ev(0, PointerTool.Finger, StylusAction.Move, t = 40)))

        val touch = PalmRejection(stylusOnly = true)
        touch.decide(ev(0, PointerTool.Finger, StylusAction.Down, t = 0))
        touch.decide(ev(1, PointerTool.Stylus, StylusAction.Down, t = 50))
        assertEquals(listOf(0), touch.drainCancels())
        assertTrue(touch.isPalm(0))
    }

    @Test fun systemCancelledPalmIsCancelled() {
        val palm = PalmRejection(stylusOnly = true)
        palm.decide(ev(0, PointerTool.Finger, StylusAction.Down))
        assertEquals(InputDecision.Cancel, palm.decide(ev(0, PointerTool.Finger, StylusAction.Cancel)))
    }
}
