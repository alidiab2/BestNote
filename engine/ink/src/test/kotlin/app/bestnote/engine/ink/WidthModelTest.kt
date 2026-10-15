package app.bestnote.engine.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidthModelTest {
    private val pens = listOf(InkFamily.Fountain, InkFamily.Ballpoint, InkFamily.BrushPen, InkFamily.Pencil)

    @Test fun aTapIsABoldRoundDotInEveryPen() {
        // A real 40-75 ms tap: barely moves, pressure still ramping in.
        for (pen in pens) for (pressure in listOf(0.02f, 0.1f, 0.3f)) {
            val m = WidthModel.multiplier(pen, pressure, speed = 0f, traveled = 0f, remaining = 0.1f)
            assertEquals("$pen at $pressure", BrushTuning.Dot.SCALE, m, 0.03f)
        }
    }

    @Test fun aSlightlyMovingTapStillMakesADot() {
        // Hand tremor during a dot: a fraction of a brush size of travel, pressure barely there.
        for (pen in pens) {
            val m = WidthModel.multiplier(pen, 0.05f, 30f, traveled = 0.3f, remaining = 0.3f)
            assertTrue("$pen dot $m", m >= 0.75f)
        }
    }

    @Test fun aShortDashIsNotAllTaper() {
        // Ruqaa two-dot dash: ~3 mm at 3.2 mm x-height, about 5 brush sizes, normal pressure.
        for (pen in listOf(InkFamily.Fountain, InkFamily.BrushPen)) {
            val nearEnd = WidthModel.multiplier(pen, 0.5f, 100f, traveled = 4f, remaining = 1f)
            val longNearEnd = WidthModel.multiplier(pen, 0.5f, 100f, traveled = 20f, remaining = 1f)
            assertTrue("$pen dash end $nearEnd vs $longNearEnd", nearEnd > longNearEnd * 1.15f)
        }
        assertEquals(1f, WidthModel.dashness(3f, 3f), 0f)
        assertEquals(0f, WidthModel.dashness(BrushTuning.Dot.DASH_LENGTH, 0f), 0f)
    }

    @Test fun dotLiftFadesOutForRealStrokes() {
        assertEquals(0f, WidthModel.longness(0f, 0f), 0f)
        assertEquals(1f, WidthModel.longness(BrushTuning.Dot.MAX_LENGTH, 0f), 0f)
        assertEquals(1f, WidthModel.longness(0f, BrushTuning.Dot.MAX_LENGTH), 0f)
        assertEquals(1f, WidthModel.longness(10f, 10f), 0f)
        // Mid-stroke at light pressure the pressure rule is back in charge.
        val m = WidthModel.multiplier(InkFamily.BrushPen, 0.1f, 100f, traveled = 20f, remaining = 20f)
        assertTrue(m < 0.4f)
    }

    @Test fun fountainRespondsToPressureSpeedAndTapers() {
        val mid = { p: Float, v: Float -> WidthModel.multiplier(InkFamily.Fountain, p, v, 20f, 20f) }
        assertTrue(mid(0.9f, 50f) > mid(0.3f, 50f) * 1.3f) // pressure swells
        assertTrue(mid(0.5f, 600f) < mid(0.5f, 50f)) // speed thins
        val start = WidthModel.multiplier(InkFamily.Fountain, 0.5f, 50f, traveled = 0.5f, remaining = 20f)
        val end = WidthModel.multiplier(InkFamily.Fountain, 0.5f, 50f, traveled = 20f, remaining = 0f)
        assertTrue(end < mid(0.5f, 50f) * 0.7f) // tapered exit
        assertTrue(start < mid(0.5f, 50f))
        // Gentle and legible: never thinner than ~20% of full width.
        assertTrue(WidthModel.multiplier(InkFamily.Fountain, 0f, 1000f, 20f, 0f) >= 0.12f)
    }

    @Test fun brushPenKeepsCountersOpenAtNormalPressure() {
        // Normal writing pressure ~0.55; 3.2 mm x-height letters have ~10 px counters at 320 dpi.
        val full = BrushCatalog.DEFAULT_SIZE * BrushTuning.BrushPen.SIZE_SCALE
        val width = full * WidthModel.multiplier(InkFamily.BrushPen, 0.55f, 100f, 20f, 20f)
        assertTrue("brush $width px", width <= 4.5f)
        assertTrue(full >= 8f) // pressing hard still gives a real brush stroke
    }

    @Test fun ballpointStaysNearConstant() {
        val light = WidthModel.multiplier(InkFamily.Ballpoint, 0.1f, 100f, 20f, 20f)
        assertTrue(light >= 0.85f)
    }
}
