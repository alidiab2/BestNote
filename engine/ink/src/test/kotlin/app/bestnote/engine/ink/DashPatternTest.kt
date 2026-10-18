package app.bestnote.engine.ink

import app.bestnote.core.model.LineDash
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashPatternTest {
    @Test fun solidHasNoPattern() {
        assertNull(DashPattern.intervals(LineDash.Solid, 4f))
    }

    @Test fun dashesScaleWithTheWidthAndHaveAFloor() {
        assertArrayEquals(floatArrayOf(12f, 8.8f), DashPattern.intervals(LineDash.Dashed, 4f)!!, 1e-5f)
        assertArrayEquals(floatArrayOf(24f, 17.6f), DashPattern.intervals(LineDash.Dashed, 8f)!!, 1e-5f)
        // A hairline still gets dashes long enough to see.
        assertArrayEquals(DashPattern.intervals(LineDash.Dashed, DashPattern.MIN_WIDTH), DashPattern.intervals(LineDash.Dashed, 0.1f)!!, 0f)
    }

    @Test fun dotsAreSpacedByThePitch() {
        val dots = DashPattern.intervals(LineDash.Dotted, 4f)!!
        assertEquals(DashPattern.DOT_PITCH * 4f, dots[0] + dots[1], 1e-5f)
        assertTrue(dots[0] < 0.1f)
        // Each dot is one pen width round.
        assertEquals(4f, DashPattern.bandWidth(LineDash.Dotted, 4f, 1f), 0f)
    }

    @Test fun spansStartWithInkAndTileTheLine() {
        val iv = DashPattern.intervals(LineDash.Dashed, 2f)!! // 6 on, 4.4 off
        val spans = DashPattern.spans(50f, iv)
        assertEquals(0f, spans.first().start, 0f)
        assertEquals(6f, spans.first().endInclusive, 1e-5f)
        assertEquals(10.4f, spans[1].start, 1e-5f)
        // 50 / 10.4 -> dashes at 0, 10.4, 20.8, 31.2, 41.6 (the last cut at 47.6)
        assertEquals(5, spans.size)
        assertTrue(spans.all { it.endInclusive <= 50f })
        val inked = spans.sumOf { (it.endInclusive - it.start).toDouble() }
        assertEquals(30.0, inked, 1e-3)
    }

    @Test fun aTapIsOneDash() {
        val spans = DashPattern.spans(0f, DashPattern.intervals(LineDash.Dashed, 3f)!!)
        assertEquals(listOf(0f..0f), spans)
    }

    @Test fun theBandCoversATiltedPencil() {
        val w = 3f
        val pencil = DashPattern.bandWidth(LineDash.Dashed, w, DashPattern.maxWidthScale(InkFamily.Pencil))
        assertTrue(pencil > w * BrushTuning.Pencil.TILT_MAX_WIDTH)
        val pen = DashPattern.bandWidth(LineDash.Dashed, w, DashPattern.maxWidthScale(InkFamily.Fountain))
        assertTrue(pen > w && pen < pencil)
    }
}
