package app.bestnote.engine.stylus

import app.bestnote.core.model.StylusSample
import app.bestnote.core.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HoverCursorTest {
    private val density = 2f

    private fun ev(action: StylusAction, t: Long = 0L, tool: PointerTool = PointerTool.Stylus, button: Boolean = false) = StylusEvent(
        pointerId = 0,
        tool = tool,
        action = action,
        samples = listOf(StylusSample(10f, 20f, 0f, 0f, 0f, t)),
        primaryButtonPressed = button,
        secondaryButtonPressed = false,
        canceled = false,
    )

    @Test fun penCursorMatchesDesign() {
        val s = HoverCursorStyle.of(Tool.Pen, 0xFF1E3A8A.toInt(), brushWidthPx = 3f, eraseRadiusPx = 20f, density = density)
        assertEquals(24f, s.ringRadius)
        assertEquals(2.4f, s.ringStroke, 1e-4f)
        assertEquals(2.4f, s.dotRadius, 1e-4f) // 2.4dp dot wins over a 3px brush
        assertEquals(0xFF1E3A8A.toInt(), s.colorArgb)
        assertEquals(0x1F1E3A8A, s.fillArgb)
        assertFalse(s.erasing)
    }

    @Test fun wideBrushGrowsDotAndRing() {
        val s = HoverCursorStyle.of(Tool.Highlighter, 0x80FFEB3B.toInt(), brushWidthPx = 60f, eraseRadiusPx = 20f, density = density)
        assertEquals(30f, s.dotRadius)
        assertTrue(s.ringRadius > s.dotRadius)
        assertEquals(0xFF, s.colorArgb ushr 24) // translucent highlighter still gets a crisp ring
    }

    @Test fun eraserShowsEraseRadius() {
        val s = HoverCursorStyle.of(Tool.Eraser, 0xFF000000.toInt(), brushWidthPx = 5f, eraseRadiusPx = 20f, density = density)
        assertTrue(s.erasing)
        assertEquals(20f, s.ringRadius)
        assertEquals(0f, s.dotRadius)
    }

    @Test fun eraserRingIsDashedAndPenRingIsSolid() {
        val eraser = HoverCursorStyle.of(Tool.Eraser, 0xFF000000.toInt(), brushWidthPx = 5f, eraseRadiusPx = 20f, density = density)
        assertEquals(HoverCursorStyle.ERASER_DASH_DP * density, eraser.dashPx, 1e-4f)
        assertTrue(eraser.gapPx > 0f)
        assertTrue(eraser.ringStroke > HoverCursorStyle.RING_STROKE_DP * density)
        val pen = HoverCursorStyle.of(Tool.Pen, 0xFF000000.toInt(), brushWidthPx = 5f, eraseRadiusPx = 20f, density = density)
        assertEquals(0f, pen.dashPx)
        assertTrue(pen.dotRadius > 0f)
    }

    @Test fun showsOnHoverHidesOnExitAndTouch() {
        val h = HoverTracker()
        assertTrue(h.onEvent(ev(StylusAction.HoverEnter)))
        assertEquals(10f, h.position!!.x)
        h.onEvent(ev(StylusAction.Down))
        assertNull(h.position)
        h.onEvent(ev(StylusAction.HoverMove))
        assertNotNull(h.position)
        h.onEvent(ev(StylusAction.HoverExit))
        assertNull(h.position)
    }

    @Test fun onlyPensGetACursor() {
        val h = HoverTracker()
        h.onEvent(ev(StylusAction.HoverMove, tool = PointerTool.Mouse))
        assertNull(h.position)
        h.onEvent(ev(StylusAction.HoverMove, tool = PointerTool.Eraser))
        assertEquals(PointerTool.Eraser, h.position!!.pointer)
    }

    @Test fun buttonStateCarriedForEraserPreview() {
        val h = HoverTracker()
        h.onEvent(ev(StylusAction.HoverMove, button = true))
        assertTrue(h.position!!.buttonPressed)
    }

    @Test fun missedExitExpires() {
        val h = HoverTracker()
        h.onEvent(ev(StylusAction.HoverMove, t = 1_000))
        assertFalse(h.expire(1_000 + HoverTracker.STALE_MS - 1))
        assertTrue(h.expire(1_000 + HoverTracker.STALE_MS))
        assertNull(h.position)
    }
}
