package app.bestnote.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PopoverMathTest {
    private val w = 2560
    private val h = 1600
    private val gap = 20
    private val margin = 24
    private val inset = 40

    private fun place(anchor: PxRect, side: TraySide, width: Int = 680, height: Int = 900) =
        PopoverMath.place(anchor, w, h, width, height, side, gap, margin, inset)

    private fun assertOnScreen(p: PopoverPlacement, width: Int = 680, height: Int = 900) {
        assertTrue("left off screen: $p", p.x >= margin)
        assertTrue("top off screen: $p", p.y >= margin)
        assertTrue("right off screen: $p", p.x + width <= w - margin)
        assertTrue("bottom off screen: $p", p.y + height <= h - margin)
    }

    @Test
    fun `top tray opens below the tapped pen, centered on it, caret pointing at it`() {
        val pen = PxRect(1000, 40, 1080, 168)
        val p = place(pen, TraySide.Top)
        assertEquals(CaretEdge.Top, p.edge)
        assertEquals(168 + gap, p.y)
        assertEquals(1040 - 340, p.x)
        assertEquals(340, p.caret) // the caret sits right under the pen's center
        assertEquals(pen.centerX, p.x + p.caret)
    }

    @Test
    fun `a pen near the window edge slides the body in but the caret still points at the pen`() {
        val pen = PxRect(60, 40, 140, 168)
        val p = place(pen, TraySide.Top)
        assertEquals(margin, p.x)
        assertEquals(pen.centerX, p.x + p.caret)
        assertOnScreen(p)
    }

    @Test
    fun `the caret never runs into the rounded corners`() {
        val pen = PxRect(0, 40, 30, 168)
        val p = place(pen, TraySide.Top)
        assertEquals(inset, p.caret)
        val far = place(PxRect(2530, 40, 2560, 168), TraySide.Top)
        assertEquals(680 - inset, far.caret)
    }

    @Test
    fun `bottom tray on compact opens above the pen`() {
        val pen = PxRect(500, 1450, 580, 1578)
        val p = place(pen, TraySide.Bottom, width = 600, height = 700)
        assertEquals(CaretEdge.Bottom, p.edge)
        assertEquals(1450 - gap - 700, p.y)
        assertEquals(pen.centerX, p.x + p.caret)
        assertOnScreen(p, 600, 700)
    }

    @Test
    fun `top preference flips above only when below is too short and above has more room`() {
        val low = PxRect(1000, 1300, 1080, 1400)
        val p = place(low, TraySide.Top)
        assertEquals(CaretEdge.Bottom, p.edge)
        assertOnScreen(p)
    }

    @Test
    fun `taller than the room below never covers the tray, it is capped instead`() {
        val pen = PxRect(1000, 40, 1080, 168)
        val p = place(pen, TraySide.Top, height = 1500)
        assertEquals(CaretEdge.Top, p.edge)
        assertEquals(168 + gap, p.y)
        assertEquals(h - margin - (168 + gap), p.maxHeight)
    }

    @Test
    fun `above a bottom tray a tall popover is capped and ends right above the pen`() {
        val pen = PxRect(500, 1450, 580, 1578)
        val p = place(pen, TraySide.Bottom, height = 1500)
        assertEquals(CaretEdge.Bottom, p.edge)
        assertEquals(1450 - gap - margin, p.maxHeight)
        assertEquals(1450 - gap, p.y + p.maxHeight)
    }

    @Test
    fun `side docks open beside the pen with the caret on the facing edge`() {
        val left = place(PxRect(20, 600, 148, 680), TraySide.Left)
        assertEquals(CaretEdge.Left, left.edge)
        assertEquals(148 + gap, left.x)
        assertEquals(640, left.y + left.caret)

        val right = place(PxRect(2412, 600, 2540, 680), TraySide.Right)
        assertEquals(CaretEdge.Right, right.edge)
        assertEquals(2412 - gap - 680, right.x)
        assertEquals(640, right.y + right.caret)
    }

    @Test
    fun `side dock near the bottom keeps the popover on screen and the caret on the pen`() {
        val p = place(PxRect(20, 1450, 148, 1530), TraySide.Left)
        assertOnScreen(p)
        assertEquals(1490, p.y + p.caret)
    }
}
