package app.bestnote.engine.ink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkTexturesTest {
    @Test fun grainIsDeterministicMostlyCoveredWithVisibleGaps() {
        val a = InkTextures.grainAlpha(64, BrushTuning.Pencil.GRAIN_STRENGTH)
        assertArrayEquals(a, InkTextures.grainAlpha(64, BrushTuning.Pencil.GRAIN_STRENGTH))
        val mean = a.average()
        assertTrue("mean $mean", mean in 170.0..245.0)
        assertTrue(a.min() < 150) // paper shows through somewhere
        assertTrue(a.max() == 255)
    }

    @Test fun zeroStrengthIsSolid() {
        assertTrue(InkTextures.grainAlpha(16, 0f).all { it == 255 })
    }
}
