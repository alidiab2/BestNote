package app.bestnote.engine.layout

import app.bestnote.core.model.LayoutTemplate
import app.bestnote.core.model.PageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockFlowTest {
    @Test fun `every template's regions stay inside the margins and do not overlap`() {
        for (t in LayoutTemplate.entries) for (size in listOf(PageSize.A4, PageSize.Letter))
            for (margin in listOf(false, true)) for (rtl in listOf(false, true)) {
                val regions = TemplateRegions.of(t, size, margin, rtl)
                regions.forEach {
                    assertTrue("$t ${it.role}", it.rect.left >= TemplateRegions.MARGIN && it.rect.right <= size.widthPt - TemplateRegions.MARGIN)
                    assertTrue("$t ${it.role}", it.rect.bottom <= size.heightPt - TemplateRegions.MARGIN)
                }
                for (i in regions.indices) for (j in i + 1 until regions.size)
                    assertFalse("$t ${regions[i].role}/${regions[j].role}", regions[i].rect.intersects(regions[j].rect))
            }
    }

    @Test fun `cornell routes cues and main side by side`() {
        val flow = BlockFlow(LayoutTemplate.Cornell, PageSize.A4, { _, _ -> 40f })
        val p = flow.place(listOf(FlowItem("cue", RegionRole.Cues), FlowItem("note", RegionRole.Main)))
        assertEquals(p[0].frame.top, p[1].frame.top)
        assertTrue(p[0].frame.right < p[1].frame.left)
    }

    @Test fun `overflow spills to the next page without overlap`() {
        val flow = BlockFlow(LayoutTemplate.Minimalist, PageSize.A4, { _, _ -> 200f })
        val placed = flow.place(List(10) { FlowItem("b$it", RegionRole.Main) })
        assertTrue(placed.last().pageIndex > 0)
        placed.groupBy { it.pageIndex }.values.forEach { onPage ->
            for (i in onPage.indices) for (j in i + 1 until onPage.size) assertFalse(onPage[i].frame.intersects(onPage[j].frame))
        }
    }

    @Test fun `cheat sheet fills columns before paging`() {
        val flow = BlockFlow(LayoutTemplate.CheatSheet, PageSize.A4, { _, _ -> 300f })
        val placed = flow.place(List(6) { FlowItem("b$it", RegionRole.Main) })
        assertEquals(0, placed[5].pageIndex)
        assertEquals(3, placed.map { it.frame.left }.distinct().size)
    }

    @Test fun `a heading is never stranded at the bottom of a page`() {
        // Main region on A4 Minimalist is 700pt tall: three 200pt blocks leave 70pt, enough for
        // the heading alone but not for the heading plus its content.
        val heights = mapOf("h" to 30f)
        val flow = BlockFlow(LayoutTemplate.Minimalist, PageSize.A4, { item, _ -> heights[item.key] ?: 200f })
        val items = List(3) { FlowItem("b$it", RegionRole.Main) } +
            FlowItem("h", RegionRole.Main, keepWithNext = true) + FlowItem("after", RegionRole.Main)
        val placed = flow.place(items).associateBy { it.key }
        assertEquals(1, placed.getValue("h").pageIndex)
        assertEquals(1, placed.getValue("after").pageIndex)
    }

    @Test fun `margin notes sit level with the block they annotate`() {
        val flow = BlockFlow(LayoutTemplate.Minimalist, PageSize.A4, { _, _ -> 120f })
        val items = listOf(
            FlowItem("a", RegionRole.Main), FlowItem("b", RegionRole.Main), FlowItem("c", RegionRole.Main),
            FlowItem("note", RegionRole.Margin, anchorKey = "c"),
        )
        val placed = flow.place(items).associateBy { it.key }
        assertEquals(placed.getValue("c").frame.top, placed.getValue("note").frame.top)
        // LTR: the margin column is on the left of the main text.
        assertTrue(placed.getValue("note").frame.right < placed.getValue("c").frame.left)
    }

    @Test fun `rtl puts the margin on the right`() {
        val flow = BlockFlow(LayoutTemplate.Minimalist, PageSize.A4, { _, _ -> 60f }, rtl = true)
        val placed = flow.place(listOf(FlowItem("a", RegionRole.Main), FlowItem("n", RegionRole.Margin, anchorKey = "a")))
            .associateBy { it.key }
        assertTrue(placed.getValue("n").frame.left > placed.getValue("a").frame.right)
    }
}
