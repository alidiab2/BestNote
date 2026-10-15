package app.bestnote.engine.canvas

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleGateTest {
    @Test fun dropsExactRepeatsAndTimeGoingBack() {
        val g = SampleGate()
        assertTrue(g.accept(1f, 1f, 0))
        assertFalse(g.accept(1f, 1f, 0))
        assertTrue(g.accept(2f, 1f, 0)) // same ms, moved: whole-ms timestamps do this
        assertTrue(g.accept(3f, 1f, 4))
        assertFalse(g.accept(4f, 1f, 3))
    }

    @Test fun copyStartsWhereTheOriginalIs() {
        val g = SampleGate()
        g.accept(1f, 1f, 10)
        val c = g.copy()
        assertFalse(c.accept(0f, 0f, 9))
        assertTrue(c.accept(0f, 0f, 11))
        assertTrue(g.accept(5f, 5f, 11)) // independent of the copy
    }
}
