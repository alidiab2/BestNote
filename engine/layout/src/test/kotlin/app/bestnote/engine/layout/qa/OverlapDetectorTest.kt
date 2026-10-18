package app.bestnote.engine.layout.qa

import app.bestnote.core.model.RectPt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlapDetectorTest {
    private val page = RectPt(0f, 0f, 595f, 842f)
    private val column = RectPt(50f, 0f, 300f, 842f)
    private val detector = OverlapDetector()

    private fun text(owner: String, part: Int, l: Float, t: Float, r: Float, b: Float, size: Float = 12f) =
        InkBox(owner, part, InkKind.Text, RectPt(l, t, r, b), size, "$owner#$part", column)

    @Test
    fun formulaOnAHeadingsUnderlineIsAnOverlap() {
        val underline = OverlapDetector.strokeBoxes(floatArrayOf(60f, 200f), floatArrayOf(100f, 100f), 1.5f)
            .map { InkBox("heading", 0, InkKind.Stroke, it, frame = column) }
        val formula = text("formula", 0, 80f, 96f, 150f, 120f)
        val issues = detector.check(0, underline + formula, page)
        assertEquals(1, issues.size)
        assertEquals(LayoutIssue.Type.Overlap, issues[0].type)
    }

    @Test
    fun inkOfOneRowMayTouchAndNeighbouringLinesThatClearAreFine() {
        // A fraction: bar and numerator are one row of one element.
        val bar = InkBox("f", 0, InkKind.Stroke, RectPt(60f, 100f, 120f, 101.5f), frame = column)
        val num = text("f", 0, 70f, 88f, 110f, 101f)
        val nextLine = text("f", 1, 60f, 110f, 200f, 128f)
        assertTrue(detector.check(0, listOf(bar, num, nextLine), page).isEmpty())
    }

    @Test
    fun aBoxOutlineDoesNotCoverWhatItEncloses() {
        val xs = floatArrayOf(55f, 250f, 250f, 55f, 55f)
        val ys = floatArrayOf(90f, 90f, 140f, 140f, 90f)
        val box = OverlapDetector.strokeBoxes(xs, ys, 1.5f).map { InkBox("result", 1, InkKind.Stroke, it, frame = column) }
        val inside = text("result", 0, 70f, 100f, 230f, 130f)
        assertTrue(detector.check(0, box + inside, page).isEmpty())
    }

    @Test
    fun refineDecidesWhetherCollidingBoxesReallyTouch() {
        val a = text("a", 0, 60f, 100f, 200f, 120f)
        val b = text("b", 0, 60f, 115f, 200f, 135f)
        assertEquals(1, detector.check(0, listOf(a, b), page).size)
        assertTrue(detector.check(0, listOf(a, b), page) { _, _ -> 0f }.isEmpty())
    }

    @Test
    fun overflowClippingAndTinyText() {
        val wide = text("p", 0, 60f, 100f, 310f, 120f)
        val off = text("q", 0, 60f, 830f, 200f, 850f)
        val tiny = text("r", 0, 60f, 200f, 70f, 203f, size = 3f)
        val types = detector.check(0, listOf(wide, off, tiny), page).map { it.type }.toSet()
        assertEquals(setOf(LayoutIssue.Type.Overflow, LayoutIssue.Type.Clipped, LayoutIssue.Type.Tiny), types)
    }

    @Test
    fun aMarginArrowMayReachItsTargetButNothingElse() {
        val arrow = InkBox("note", -1, InkKind.Stroke, RectPt(150f, 100f, 165f, 110f), target = "result")
        val target = InkBox("result", 0, InkKind.Stroke, RectPt(161f, 104f, 170f, 106f))
        val other = InkBox("other", 0, InkKind.Text, RectPt(150f, 100f, 158f, 108f), 12f)
        val issues = detector.check(0, listOf(arrow, target, other), page)
        assertEquals(listOf("other"), issues.flatMap { listOfNotNull(it.a.owner, it.b?.owner) }.filter { it != "note" })
    }
}
