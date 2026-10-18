package app.bestnote.engine.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PencilModelTest {
    private val upright = 0.2f
    private val writing = 0.8f // ~45°, the corpus writer's angle
    private val flat = 1.45f

    @Test fun writingAngleDoesNotShade() {
        assertEquals(0f, PencilModel.shade(upright), 0f)
        assertEquals(0f, PencilModel.shade(writing), 0f)
        assertEquals(0f, PencilModel.shade(BrushTuning.Pencil.TILT_START_RAD), 1e-6f)
        assertEquals(1f, PencilModel.tiltWidth(writing), 0f)
        assertEquals(1f, PencilModel.tiltOpacity(writing), 0f)
    }

    @Test fun tiltWidensAndSoftensMonotonically() {
        var lastWidth = 1f
        var lastOpacity = 1f
        var t = 0f
        while (t <= 1.6f) {
            val w = PencilModel.tiltWidth(t)
            val o = PencilModel.tiltOpacity(t)
            assertTrue("width at $t", w >= lastWidth - 1e-6f)
            assertTrue("opacity at $t", o <= lastOpacity + 1e-6f)
            lastWidth = w
            lastOpacity = o
            t += 0.01f
        }
        assertEquals(BrushTuning.Pencil.TILT_MAX_WIDTH, PencilModel.tiltWidth(flat), 1e-5f)
        assertEquals(BrushTuning.Pencil.TILT_MIN_OPACITY, PencilModel.tiltOpacity(flat), 1e-5f)
        // Halfway through the tilt range the stroke is clearly wider, not yet fully on its side.
        val mid = (BrushTuning.Pencil.TILT_START_RAD + BrushTuning.Pencil.TILT_FULL_RAD) / 2
        assertEquals((1f + BrushTuning.Pencil.TILT_MAX_WIDTH) / 2, PencilModel.tiltWidth(mid), 1e-4f)
    }

    @Test fun badTiltIsTreatedAsUpright() {
        assertEquals(1f, PencilModel.tiltWidth(Float.NaN), 0f)
        assertEquals(1f, PencilModel.tiltWidth(-1f), 0f)
    }

    @Test fun pressureDarkensMoreThanItWidens() {
        val lightOpacity = PencilModel.opacity(0.1f, writing)
        val hardOpacity = PencilModel.opacity(1f, writing)
        val lightWidth = WidthModel.multiplier(InkFamily.Pencil, 0.1f, 0f, 20f, 20f)
        val hardWidth = WidthModel.multiplier(InkFamily.Pencil, 1f, 0f, 20f, 20f)
        assertEquals(1f, hardOpacity, 1e-6f)
        assertTrue(hardOpacity / lightOpacity > hardWidth / lightWidth)
        assertEquals(BrushTuning.Pencil.MIN_PRESSURE_OPACITY, PencilModel.opacity(0f, upright), 1e-6f)
    }

    @Test fun widthModelAddsTiltForThePencilOnly() {
        val upright = WidthModel.multiplier(InkFamily.Pencil, 0.5f, 0f, 20f, 20f, tiltRad = writing)
        val tilted = WidthModel.multiplier(InkFamily.Pencil, 0.5f, 0f, 20f, 20f, tiltRad = flat)
        assertEquals(upright * BrushTuning.Pencil.TILT_MAX_WIDTH, tilted, 1e-4f)
        assertEquals(
            WidthModel.multiplier(InkFamily.Fountain, 0.5f, 100f, 20f, 20f),
            WidthModel.multiplier(InkFamily.Fountain, 0.5f, 100f, 20f, 20f, tiltRad = flat),
            0f,
        )
    }
}
