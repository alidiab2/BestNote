package app.bestnote.engine.canvas.shape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldDetectorTest {
    private fun detector() = HoldDetector(slop = 6f, minTravel = 24f, holdMillis = 500)

    /** Draws 100 px to the right in 200 ms, from t = 0. */
    private fun HoldDetector.draw() {
        start(0f, 0f, 0)
        for (i in 1..50) add(i * 2f, 0f, i * 4L)
    }

    @Test
    fun `holding still at the end for 500 ms completes a hold once`() {
        val h = detector()
        h.draw()
        var fired = 0
        for (t in 204L..800L step 4) if (h.add(100f + (t % 3) * 0.5f, 0.4f, t)) fired++
        assertEquals(1, fired)
        assertTrue(h.held)
    }

    @Test
    fun `a pause shorter than the hold does nothing`() {
        val h = detector()
        h.draw()
        for (t in 204L..600L step 4) h.add(100f, 0f, t)
        assertFalse(h.held)
    }

    @Test
    fun `moving on after a hold cancels it`() {
        val h = detector()
        h.draw()
        for (t in 204L..800L step 4) h.add(100f, 0f, t)
        assertTrue(h.held)
        h.add(130f, 0f, 820)
        assertFalse(h.held)
    }

    @Test
    fun `a still pen with no events completes on check`() {
        val h = detector()
        h.draw()
        // Counted from when the pen came to rest near its end.
        assertTrue(h.deadline!! in 600L..700L)
        assertFalse(h.check(600))
        assertTrue(h.check(701))
        assertNull(h.deadline)
    }

    @Test
    fun `pressing and holding without drawing is no hold`() {
        val h = detector()
        h.start(10f, 10f, 0)
        for (t in 4L..900L step 4) h.add(10f, 10f, t)
        assertFalse(h.held)
    }
}
