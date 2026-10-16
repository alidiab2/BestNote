package app.bestnote.engine.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPlanTest {
    private fun ok(text: String, n: Int = 10) = (ExportPlan.parseRange(text, n) as RangeParse.Ok).indices

    @Test fun `single numbers and ranges are one-based and sorted`() {
        assertEquals(listOf(0, 1, 2, 4), ok("5, 1-3"))
    }

    @Test fun `open ended range runs to the last page`() {
        assertEquals(listOf(7, 8, 9), ok("8-"))
    }

    @Test fun `duplicates and overlaps collapse`() {
        assertEquals(listOf(0, 1, 2), ok("1-2 2-3;1"))
    }

    @Test fun `arabic digits and comma are understood`() {
        assertEquals(listOf(0, 1, 4), ok("١-٢،٥"))
    }

    @Test fun `bad input reports the token`() {
        assertEquals(RangeParse.Invalid("11"), ExportPlan.parseRange("1, 11", 10))
        assertEquals(RangeParse.Invalid("0"), ExportPlan.parseRange("0", 10))
        assertEquals(RangeParse.Invalid("5-3"), ExportPlan.parseRange("5-3", 10))
        assertEquals(RangeParse.Invalid("a"), ExportPlan.parseRange("a", 10))
        assertEquals(RangeParse.Invalid("1-2-3"), ExportPlan.parseRange("1-2-3", 10))
        assertEquals(RangeParse.Invalid("-3"), ExportPlan.parseRange("-3", 10))
        assertTrue(ExportPlan.parseRange("  ", 10) is RangeParse.Invalid)
    }

    @Test fun `select clamps and filters`() {
        assertEquals(listOf(0, 1, 2), ExportPlan.select(PageRange.All, 3))
        assertEquals(listOf(2), ExportPlan.select(PageRange.Single(9), 3))
        assertEquals(listOf(0, 2), ExportPlan.select(PageRange.Pages(listOf(2, 0, 2, 7)), 3))
        assertEquals(emptyList<Int>(), ExportPlan.select(PageRange.All, 0))
    }

    private fun map(m: FloatArray, u: Float, v: Float) = floatArrayOf(m[0] * u + m[2] * v + m[4], m[1] * u + m[3] * v + m[5])

    @Test fun `overlay transform keeps top-left on the visual top-left for every rotation`() {
        // Visual top-left in overlay form space (y-up) is (0, h).
        // Unrotated 200x100 page at offset (10, 20), overlay 200x100.
        assertArrayEquals(floatArrayOf(10f, 120f), map(OverlayTransform.of(0, 10f, 20f, 200f, 100f, 200f, 100f), 0f, 100f), 0.001f)
        // Rotated 90 clockwise: top-left of the view is the unrotated bottom-left.
        assertArrayEquals(floatArrayOf(10f, 20f), map(OverlayTransform.of(90, 10f, 20f, 200f, 100f, 100f, 200f), 0f, 200f), 0.001f)
        // 180: top-left of the view is the unrotated bottom-right.
        assertArrayEquals(floatArrayOf(210f, 20f), map(OverlayTransform.of(180, 10f, 20f, 200f, 100f, 200f, 100f), 0f, 100f), 0.001f)
        // 270: top-left of the view is the unrotated top-right.
        assertArrayEquals(floatArrayOf(210f, 120f), map(OverlayTransform.of(270, 10f, 20f, 200f, 100f, 100f, 200f), 0f, 200f), 0.001f)
    }

    @Test fun `overlay is scaled to the visible box`() {
        val m = OverlayTransform.of(0, 0f, 0f, 400f, 200f, 200f, 100f)
        assertArrayEquals(floatArrayOf(400f, 200f), map(m, 200f, 100f), 0.001f)
    }
}
