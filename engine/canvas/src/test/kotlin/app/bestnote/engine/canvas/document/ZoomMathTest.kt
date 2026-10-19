package app.bestnote.engine.canvas.document

import app.bestnote.core.model.PageSize
import app.bestnote.engine.layout.document.DocumentLayout
import app.bestnote.engine.layout.document.DocumentMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZoomMathTest {
    private val metrics = DocumentMetrics(topPad = 56, bottomPad = 176, sidePad = 64, gap = 48, addCardHeight = 192)
    private val sizes = List(5) { PageSize.A4 }
    private fun layout(zoom: Float) = DocumentLayout(sizes, 1600, metrics, zoom)

    @Test fun penInputMapsToTheSamePagePointAtEveryZoom() {
        for (zoom in listOf(0.25f, 0.5f, 1f, 2f, 4.5f, 8f)) {
            val l = layout(zoom)
            val sx = (l.contentWidth - 1600).coerceAtLeast(0) / 3
            val sy = l.pageRect(2).top - 100
            val (px, py) = ZoomMath.viewToPage(l, sx, sy, 2, 700f, 900f)
            val (vx, vy) = ZoomMath.pageToView(l, sx, sy, 2, px, py)
            assertEquals(700f, vx, 1e-2f)
            assertEquals(900f, vy, 1e-2f)
            // A 1 pt stroke covers `scale` pixels: input density follows the zoom.
            val (px2, _) = ZoomMath.viewToPage(l, sx, sy, 2, 700f + l.scale, 900f)
            assertEquals(1f, px2 - px, 1e-3f)
        }
    }

    @Test fun zoomScalesPagesBetweenLimits() {
        val fit = layout(1f)
        assertEquals((1600 - 2 * 64) / PageSize.A4.widthPt, fit.scale, 1e-4f)
        assertEquals(fit.scale * 8f, layout(8f).scale, 1e-3f)
        assertEquals(ZoomMath.MAX_ZOOM, ZoomMath.clamp(20f), 0f)
        assertEquals(ZoomMath.MIN_ZOOM, ZoomMath.clamp(0.01f), 0f)
        // At 300% and a pending 2x pinch, a further 2x is cut to what stays under 800%.
        assertEquals(8f / 6f, ZoomMath.clampFactor(3f, 2f, 2f), 1e-5f)
        assertEquals(0.25f / 0.5f, ZoomMath.clampFactor(1f, 0.5f, 0.1f), 1e-5f)
    }

    @Test fun zoomKeepsThePagePointUnderTheFingersStill() {
        val prev = layout(1f)
        val scrollY = prev.pageRect(1).top + 300
        val fx = 520f
        val fy = 610f
        val before = ZoomMath.viewToPage(prev, 0, scrollY, 1, fx, fy)
        for (z in listOf(0.25f, 0.6f, 2.5f, 8f)) {
            val next = layout(z)
            val (nx, ny) = ZoomMath.anchoredScroll(prev, 0, scrollY, fx, fy, next, fx, fy)
            val after = ZoomMath.viewToPage(next, Math.round(nx), Math.round(ny), 1, fx, fy)
            assertEquals(before.first, after.first, 1f / next.scale + 1e-3f)
            assertEquals(before.second, after.second, 1f / next.scale + 1e-3f)
        }
    }

    @Test fun commitOfAPinchPreviewLandsWhereThePreviewShowedIt() {
        // The fingers pinched 2x about (400, 500) and moved 30 px right: the preview shows view point v at 2v + t.
        val p = PreviewTransform().scaledBy(2f, 400f, 500f, 30f, 0f)
        val screenX = 900f
        val screenY = 700f
        val prev = layout(1f)
        val vx = p.unmapX(screenX)
        val vy = p.unmapY(screenY)
        assertEquals(screenX, p.scale * vx + p.tx, 1e-3f)
        val expected = ZoomMath.viewToPage(prev, 0, 400, 0, vx, vy)
        val next = layout(2f)
        val (nx, ny) = ZoomMath.anchoredScroll(prev, 0, 400, vx, vy, next, screenX, screenY)
        val got = ZoomMath.viewToPage(next, Math.round(nx), Math.round(ny), 0, screenX, screenY)
        assertEquals(expected.first, got.first, 0.1f)
        assertEquals(expected.second, got.second, 0.1f)
    }

    @Test fun previewScalingKeepsTheFocusStill() {
        val p = PreviewTransform(1.3f, 12f, -40f).scaledBy(1.7f, 300f, 200f)
        val v = PreviewTransform(1.3f, 12f, -40f).let { (300f - it.tx) / it.scale to (200f - it.ty) / it.scale }
        assertEquals(300f, p.scale * v.first + p.tx, 1e-3f)
        assertEquals(200f, p.scale * v.second + p.ty, 1e-3f)
    }

    @Test fun pinchStepMeasuresSpreadAndCentroidMove() {
        val before = mapOf(1 to (100f to 100f), 2 to (300f to 100f))
        val after = mapOf(1 to (50f to 120f), 2 to (450f to 120f))
        val step = PinchStep.of(before, after)!!
        assertEquals(2f, step.scale, 1e-4f)
        assertEquals(200f, step.focusX, 1e-4f)
        assertEquals(50f, step.dx, 1e-4f)
        assertEquals(20f, step.dy, 1e-4f)
        // The content under the old centroid ends under the new one.
        val p = PreviewTransform().scaledBy(step.scale, step.focusX, step.focusY, step.dx, step.dy)
        assertEquals(250f, p.scale * 200f + p.tx, 1e-3f)
        assertEquals(120f, p.scale * 100f + p.ty, 1e-3f)
    }

    @Test fun oneFingerPansAndALandingFingerNeverJumps() {
        val one = PinchStep.of(mapOf(1 to (10f to 10f)), mapOf(1 to (15f to 30f)))!!
        assertEquals(1f, one.scale, 0f)
        assertEquals(5f, one.dx, 0f)
        assertEquals(20f, one.dy, 0f)
        // Finger 2 only exists after: ignored this frame.
        val landing = PinchStep.of(mapOf(1 to (10f to 10f)), mapOf(1 to (10f to 10f), 2 to (500f to 500f)))!!
        assertEquals(1f, landing.scale, 0f)
        assertEquals(0f, landing.dx, 0f)
        assertNull(PinchStep.of(emptyMap(), mapOf(1 to (0f to 0f))))
    }

    /** REQ-003: a selection dropped over another page lands on it, at the same spot on screen, at every zoom. */
    @Test fun dropOverAnotherPageMovesToItsPagePoints() {
        val mixed = DocumentLayout(listOf(PageSize.A4, PageSize.A4.copy(widthPt = 400f, heightPt = 500f), PageSize.A4), 1600, metrics, 1f)
        for (l in listOf(layout(0.5f), layout(1f), layout(3f), mixed)) {
            val h = PageSize.A4.heightPt
            // Still on its own page (even near its bottom edge): stays.
            assertNull(ZoomMath.dropTarget(l, 0, 100f, h - 1f))
            // Below page 0's bottom, inside page 1: lands on page 1 at the same document pixel.
            val r0 = l.pageRect(0)
            val r1 = l.pageRect(1)
            val py = (r1.top - r0.top) / l.scale + 50f
            val px = (r1.left - r0.left) / l.scale + 30f
            val drop = ZoomMath.dropTarget(l, 0, px, py)!!
            assertEquals(1, drop.page)
            assertEquals(30f, drop.x, 1e-2f)
            assertEquals(50f, drop.y, 1e-2f)
            // Dragged up from page 2 into page 1 (negative y on page 2).
            val r2 = l.pageRect(2)
            val up = ZoomMath.dropTarget(l, 2, (r1.left - r2.left) / l.scale + 10f, (r1.bottom - r2.top) / l.scale - 20f)!!
            assertEquals(1, up.page)
            assertEquals(10f, up.x, 1e-2f)
            assertEquals(r1.height / l.scale - 20f, up.y, 1e-2f)
            // In the gap between pages: no target, it stays.
            assertNull(ZoomMath.dropTarget(l, 0, 100f, (r0.bottom - r0.top) / l.scale + 2f / l.scale))
        }
    }
}
