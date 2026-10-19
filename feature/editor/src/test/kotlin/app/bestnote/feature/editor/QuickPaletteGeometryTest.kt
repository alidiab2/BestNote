package app.bestnote.feature.editor

import app.bestnote.feature.editor.QuickPaletteGeometry.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPaletteGeometryTest {
    private val r = 232f
    private val margin = 24f
    private val w = 2560f
    private val h = 1600f

    private fun place(x: Float, y: Float) = QuickPaletteGeometry.place(Point(x, y), r, margin, w, h)

    private fun assertInside(c: Point) {
        assertTrue("palette off screen: $c", c.x - r >= margin && c.y - r >= margin && c.x + r <= w - margin && c.y + r <= h - margin)
    }

    @Test fun midScreenCentersOnTheTip() {
        val tip = Point(1280f, 800f)
        assertEquals(tip, place(tip.x, tip.y))
    }

    @Test fun clampsAtEveryEdgeAndCorner() {
        val tips = listOf(
            Point(10f, 800f), Point(w - 10f, 800f), Point(1280f, 10f), Point(1280f, h - 10f),
            Point(10f, 10f), Point(w - 10f, 10f), Point(10f, h - 10f), Point(w - 10f, h - 10f),
        )
        for (tip in tips) assertInside(place(tip.x, tip.y))
    }

    @Test fun nearEdgeStaysCenteredUntilTheDiskWouldLeaveTheScreen() {
        // Just inside the reach of the clamp: still exactly on the tip.
        val inside = Point(r + margin + 1f, h - r - margin - 1f)
        assertEquals(inside, place(inside.x, inside.y))
        // One pixel further and the disk would clip: clamped, not centered.
        val outside = Point(r + margin - 1f, h - r - margin + 1f)
        val c = place(outside.x, outside.y)
        assertInside(c)
        assertEquals(r + margin, c.x)
        assertEquals(h - r - margin, c.y)
    }

    @Test fun hoverBeyondTheScreenStaysInsideEvenOnATinyWindow() {
        val c = place(-5000f, 9000f)
        assertInside(c)
        val tiny = QuickPaletteGeometry.place(Point(50f, 50f), r, margin, width = 2 * r, height = 2 * r)
        assertEquals(Point(r + margin, r + margin), tiny)
    }
}
