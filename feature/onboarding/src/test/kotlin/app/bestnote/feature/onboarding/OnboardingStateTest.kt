package app.bestnote.feature.onboarding

import app.bestnote.engine.stylus.PointerTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStateTest {
    @Test
    fun `stylus and its eraser end are pens, fingers are fingers, mouse is neither`() {
        assertEquals(Contact.Pen, contactOf(PointerTool.Stylus))
        assertEquals(Contact.Pen, contactOf(PointerTool.Eraser))
        assertEquals(Contact.Finger, contactOf(PointerTool.Finger))
        assertEquals(Contact.Other, contactOf(PointerTool.Mouse))
        assertEquals(Contact.Other, contactOf(PointerTool.Unknown))
    }

    @Test
    fun `no pen offer appears only after a finger touch and no pen`() {
        var s = PenCheckState()
        assertFalse(s.offerNoPen)
        s = s.onContact(Contact.Other)
        assertFalse(s.offerNoPen)
        s = s.onContact(Contact.Finger)
        assertTrue(s.offerNoPen)
        assertEquals(Contact.Finger, s.last)
        s = s.onContact(Contact.Pen)
        assertFalse(s.offerNoPen)
        assertEquals(PenOutcome.PenSeen, s.outcome)
    }

    @Test
    fun `no pen answer maps to finger drawing and a later pen wins`() {
        val answered = PenCheckState().onContact(Contact.Finger).chooseNoPen()
        assertFalse(answered.offerNoPen)
        assertTrue(answered.fingersDraw)
        assertEquals(PenOutcome.FingerDrawing, answered.outcome)
        val then = answered.onContact(Contact.Pen)
        assertFalse(then.fingersDraw)
        assertEquals(PenOutcome.PenSeen, then.outcome)
    }

    @Test
    fun `choosing no pen after a pen was seen is ignored`() {
        val s = PenCheckState().onContact(Contact.Pen).chooseNoPen()
        assertFalse(s.noPen)
        assertEquals(PenOutcome.PenSeen, s.outcome)
    }

    @Test
    fun `nothing touched leaves settings unchanged`() {
        assertEquals(PenOutcome.Unchanged, PenCheckState().outcome)
        assertEquals(PenOutcome.Unchanged, PenCheckState().onContact(Contact.Finger).outcome)
    }

    @Test
    fun `steps run welcome, pen check, palm test, start and stop at the ends`() {
        var s = OnboardingState()
        assertTrue(s.isFirst)
        assertEquals(s, s.back())
        val seen = mutableListOf(s.step)
        repeat(5) { s = s.next(); if (seen.last() != s.step) seen += s.step }
        assertEquals(
            listOf(OnboardingStep.Welcome, OnboardingStep.PenCheck, OnboardingStep.PalmTest, OnboardingStep.Start),
            seen,
        )
        assertTrue(s.isLast)
        assertEquals(OnboardingStep.PalmTest, s.back().step)
    }

    @Test
    fun `contacts are kept while moving between steps`() {
        val s = OnboardingState().next().onContact(Contact.Pen).next().back()
        assertTrue(s.pen.penSeen)
    }
}
