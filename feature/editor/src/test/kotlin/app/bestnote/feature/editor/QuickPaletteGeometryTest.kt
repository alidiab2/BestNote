package app.bestnote.feature.editor

import app.bestnote.feature.editor.QuickPaletteGeometry.Point
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPaletteGeometryTest {
    private val r = 232f
    private val gap = 36f
    private val margin = 24f
    private val w = 2560f
    private val h = 1600f

    private fun place(x: Float, y: Float, leftHanded: Boolean = false) =
        QuickPaletteGeometry.place(Point(x, y), r, gap, margin, w, h, leftHanded)

    private fun assertGood(tip: Point, c: Point) {
        assertFalse("palette covers the pen tip", QuickPaletteGeometry.covers(c, r, tip))
        assertTrue("palette off screen: $c", QuickPaletteGeometry.fits(c, r, margin, w, h))
    }

    @Test fun midScreenOpensUpAndAwayFromTheHand() {
        val tip = Point(1280f, 800f)
        val right = place(tip.x, tip.y)
        assertGood(tip, right)
        assertTrue(right.x < tip.x && right.y < tip.y) // right-handed: up-left
        val left = place(tip.x, tip.y, leftHanded = true)
        assertGood(tip, left)
        assertTrue(left.x > tip.x && left.y < tip.y) // left-handed: up-right
    }

    @Test fun flipsAtEveryEdgeAndCorner() {
        val tips = listOf(
            Point(10f, 800f), Point(w - 10f, 800f), Point(1280f, 10f), Point(1280f, h - 10f),
            Point(10f, 10f), Point(w - 10f, 10f), Point(10f, h - 10f), Point(w - 10f, h - 10f),
        )
        for (tip in tips) {
            for (lefty in listOf(false, true)) assertGood(tip, place(tip.x, tip.y, lefty))
        }
    }

    @Test fun topEdgeOpensBelow() {
        val tip = Point(1280f, 40f)
        assertTrue(place(tip.x, tip.y).y > tip.y)
    }
}
