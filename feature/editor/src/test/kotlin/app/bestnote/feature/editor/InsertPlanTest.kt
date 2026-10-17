package app.bestnote.feature.editor

import app.bestnote.core.model.ImageElement
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsertPlanTest {
    private val nb = NotebookId("n")

    @Test fun `position after the current page or at the end`() {
        assertEquals(3, InsertPlan.insertIndex(InsertPosition.AfterCurrent, 2, 5))
        assertEquals(5, InsertPlan.insertIndex(InsertPosition.AtEnd, 2, 5))
        assertEquals(5, InsertPlan.insertIndex(InsertPosition.AfterCurrent, 4, 5))
        assertEquals(0, InsertPlan.insertIndex(InsertPosition.AfterCurrent, 0, 0))
        assertEquals(5, InsertPlan.insertIndex(InsertPosition.AfterCurrent, 99, 5))
    }

    @Test fun `all pages and blank range mean every page`() {
        assertEquals(PdfPagesPlan.Pages(listOf(0, 1, 2)), InsertPlan.pdfPages(PdfSelection.All, 3))
        assertEquals(PdfPagesPlan.Pages(listOf(0, 1, 2)), InsertPlan.pdfPages(PdfSelection.Range("  "), 3))
    }

    @Test fun `range text becomes zero based pages, Arabic digits included`() {
        assertEquals(PdfPagesPlan.Pages(listOf(0, 1, 2, 4)), InsertPlan.pdfPages(PdfSelection.Range("1-3, 5"), 6))
        assertEquals(PdfPagesPlan.Pages(listOf(0, 1, 2, 4)), InsertPlan.pdfPages(PdfSelection.Range("١-٣، ٥"), 6))
        assertEquals(PdfPagesPlan.Pages(listOf(4, 5)), InsertPlan.pdfPages(PdfSelection.Range("5-"), 6))
    }

    @Test fun `bad ranges name the offending token`() {
        assertEquals(PdfPagesPlan.Invalid("7"), InsertPlan.pdfPages(PdfSelection.Range("1, 7"), 6))
        assertEquals(PdfPagesPlan.Invalid("3-1"), InsertPlan.pdfPages(PdfSelection.Range("3-1"), 6))
        assertEquals(PdfPagesPlan.Invalid("x"), InsertPlan.pdfPages(PdfSelection.Range("x"), 6))
    }

    @Test fun `pdf pages keep size and point at their pdf page`() {
        val pages = InsertPlan.pdfPageModels(nb, "assets/pdf-a.pdf", listOf(1, 4)) { if (it == 1) 612 to 792 else 0 to 0 }
        assertEquals(listOf(1, 4), pages.map { it.pdf!!.pageIndex })
        assertEquals(612f, pages[0].widthPt, 0f)
        assertEquals(792f, pages[0].heightPt, 0f)
        assertEquals(1f, pages[1].widthPt, 0f) // degenerate sizes stay valid
    }

    @Test fun `a photo fills a page as wide as the reference with its proportions`() {
        val fit = InsertPlan.imageFit(4000, 3000, 595f)
        assertEquals(595f, fit.widthPt, 0f)
        assertEquals(446f, fit.heightPt, 0f) // 595 * 0.75 rounded
        assertEquals(fit.widthPt, fit.frameW, 0f)
        assertEquals(fit.heightPt, fit.frameH, 0f)
    }

    @Test fun `an extreme panorama is centered on a limited page, aspect preserved`() {
        val fit = InsertPlan.imageFit(10000, 1000, 600f)
        assertEquals(300f, fit.heightPt, 0f) // limited to 1:2
        assertEquals(600f, fit.frameW, 0f)
        assertEquals(60f, fit.frameH, 0.01f)
    }

    @Test fun `an extreme tall strip fits the page height`() {
        val fit = InsertPlan.imageFit(500, 5000, 600f)
        assertEquals(1500f, fit.heightPt, 0f)
        assertEquals(150f, fit.frameW, 0.01f)
        assertEquals(fit.heightPt, fit.frameH, 0f)
    }

    @Test fun `image page holds one locked centered element and takes the template width`() {
        val template = Page(nb, 3, widthPt = 842f, heightPt = 595f)
        val (page, elements) = InsertPlan.imagePage(nb, template, "assets/img-x.jpg", 2000, 1000, "e1")
        assertEquals(842f, page.widthPt, 0f)
        val image = elements.single() as ImageElement
        assertTrue(image.locked)
        assertEquals(page.widthPt / 2f, image.cx, 0f)
        assertEquals(page.heightPt / 2f, image.cy, 0f)
        assertEquals("assets/img-x.jpg", image.asset)
        assertEquals(Page.A4_WIDTH_PT, InsertPlan.imagePage(nb, null, "assets/i.jpg", 10, 10, "e").first.widthPt, 0f)
    }
}
