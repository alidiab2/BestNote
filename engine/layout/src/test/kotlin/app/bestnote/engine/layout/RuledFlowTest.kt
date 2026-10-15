package app.bestnote.engine.layout

import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.layout.paper.PaperGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RuledFlowTest {
    private val paper = RuledPaper.of(PaperTemplate.Ruled, PageSize.A4)
    private fun lines(n: Int) = BlockMeasurer { _, _ -> n * paper.lineGap }

    @Test fun `paper matches the ruled lines the editor prints`() {
        val printed = PaperGeometry.of(PaperTemplate.Ruled, PageSize.A4).lines
            .filter { it.horizontal }.map { it.y0 }
        assertEquals(printed.size, paper.lineCount)
        printed.forEachIndexed { i, y -> assertTrue(abs(paper.lineY(i) - y) < 0.01f) }
        assertEquals(PaperGeometry.marginX(PageSize.A4), paper.marginX)
        assertTrue(paper.textLeft > paper.marginX)
    }

    @Test fun `blocks take whole ruled lines inside the text column and never overlap`() {
        val flow = RuledFlow(paper, lines(3))
        val slots = flow.place(List(20) { FlowItem("b$it", RegionRole.Main) })
        assertTrue(slots.last().placement.pageIndex > 0)
        for (s in slots) {
            val f = s.placement.frame
            assertTrue(f.left >= paper.textLeft && f.right <= paper.textRight)
            assertEquals(paper.lineY(s.firstLine) - paper.lineGap, f.top, 0.01f)
            assertEquals(paper.lineY(s.firstLine + s.lines - 1), f.bottom, 0.01f)
            assertTrue(s.firstLine + s.lines <= paper.lineCount)
        }
        slots.groupBy { it.placement.pageIndex }.values.forEach { page ->
            for (i in page.indices) for (j in i + 1 until page.size) assertFalse(page[i].placement.frame.intersects(page[j].placement.frame))
        }
    }

    @Test fun `margin notes sit beside their anchor in the outer column`() {
        val items = listOf(
            FlowItem("p0", RegionRole.Main),
            FlowItem("p1", RegionRole.Main),
            FlowItem("note", RegionRole.Margin, anchorKey = "p1"),
        )
        for (rtl in listOf(false, true)) {
            val slots = RuledFlow(paper, lines(2), rtl = rtl).place(items).associateBy { it.placement.key }
            val note = slots.getValue("note")
            val anchor = slots.getValue("p1")
            assertTrue(note.inMargin)
            assertEquals(anchor.firstLine, note.firstLine)
            if (rtl) assertTrue(note.placement.frame.right < anchor.placement.frame.left)
            else assertTrue(note.placement.frame.left > anchor.placement.frame.right)
        }
    }

    @Test fun `a heading is never stranded at the bottom of a page`() {
        val n = paper.lineCount
        val items = listOf(FlowItem("fill", RegionRole.Main), FlowItem("h", RegionRole.Main, keepWithNext = true), FlowItem("body", RegionRole.Main))
        val sizes = mapOf("fill" to n - 2, "h" to 1, "body" to 3)
        val slots = RuledFlow(paper, { item, _ -> sizes.getValue(item.key) * paper.lineGap }).place(items).associateBy { it.placement.key }
        assertEquals(1, slots.getValue("h").placement.pageIndex)
        assertEquals(1, slots.getValue("body").placement.pageIndex)
    }
}
