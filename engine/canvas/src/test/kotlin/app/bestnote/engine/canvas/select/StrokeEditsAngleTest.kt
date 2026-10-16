package app.bestnote.engine.canvas.select

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rotating a selection must keep every input's orientation in androidx.ink's [0, 2π) range. */
class StrokeEditsAngleTest {
    private val full = (2 * Math.PI).toFloat()

    @Test fun negativeAngleWrapsIntoRange() {
        // The owner's crash: -0.367305π after rotating a lasso selection.
        val a = StrokeEdits.normalizedAngle((-0.367305 * Math.PI).toFloat())
        assertEquals((1.632695 * Math.PI).toFloat(), a, 1e-4f)
    }

    @Test fun angleAboveFullTurnWraps() {
        assertEquals(1f, StrokeEdits.normalizedAngle(1f + full), 1e-4f)
    }

    @Test fun resultIsAlwaysInRange() {
        for (deg in -1080..1080 step 7) {
            val a = StrokeEdits.normalizedAngle(Math.toRadians(deg.toDouble()).toFloat())
            assertTrue("$deg° → $a", a >= 0f && a <= full)
        }
    }
}
