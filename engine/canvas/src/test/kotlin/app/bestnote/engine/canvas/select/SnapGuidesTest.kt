package app.bestnote.engine.canvas.select

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapGuidesTest {
    private val page = SnapGuides.targets(600f, 800f, listOf(Box(100f, 500f, 300f, 540f)))

    @Test fun centerSnapsToThePageCenter() {
        // A 100 pt wide box centered at 296: 4 pt off the page's center line.
        val s = SnapGuides.snap(Box(246f, 200f, 346f, 230f), page, threshold = 6f)
        assertEquals(4f, s.dx, 1e-4f)
        assertEquals(300f, s.guideX)
        assertNull(s.guideY)
    }

    @Test fun edgesSnapToMarginsAndOtherBoxes() {
        // Left edge 2 pt right of the 36 pt margin; top edge 3 pt below the other box's bottom.
        val s = SnapGuides.snap(Box(38f, 543f, 138f, 573f), page, threshold = 6f)
        assertEquals(-2f, s.dx, 1e-4f)
        assertEquals(36f, s.guideX)
        assertEquals(-3f, s.dy, 1e-4f)
        assertEquals(540f, s.guideY)
        assertTrue(s.snapped)
    }

    @Test fun farFromAnyLineMovesFreely() {
        val s = SnapGuides.snap(Box(150f, 300f, 170f, 320f), page, threshold = 6f)
        assertEquals(0f, s.dx)
        assertEquals(0f, s.dy)
        assertFalse(s.snapped)
    }

    @Test fun theNearestLineWins() {
        val t = SnapGuides.Targets(floatArrayOf(100f, 104f), floatArrayOf())
        val s = SnapGuides.snap(Box(103f, 0f, 120f, 10f), t, threshold = 6f)
        assertEquals(104f, s.guideX)
        assertEquals(1f, s.dx, 1e-4f)
    }
}
