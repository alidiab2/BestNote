package app.bestnote.engine.layout.document

import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperOrientation
import app.bestnote.core.model.PaperSizePreset
import app.bestnote.core.model.PaperSizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentLayoutTest {
    private val metrics = DocumentMetrics(topPad = 40, bottomPad = 60, sidePad = 50, gap = 24, addCardHeight = 120)
    private val a4 = PageSize.A4

    @Test fun widestPageFitsTheViewportAndPagesStack() {
        val layout = DocumentLayout(List(3) { a4 }, viewportWidth = 1290, metrics = metrics)
        assertEquals(2f, layout.scale, 1e-4f) // (1290 - 100) / 595
        val p0 = layout.pageRect(0)
        assertEquals(PxRect(50, 40, 1240, 40 + 1684), p0)
        val p1 = layout.pageRect(1)
        assertEquals(p0.bottom + 24, p1.top)
        assertEquals(layout.pageRect(2).bottom + 24, layout.addCard.top)
        assertEquals(120, layout.addCard.height)
        assertEquals(layout.addCard.bottom + 60, layout.contentHeight)
    }

    @Test fun mixedSizesShareOneScaleAndAreCentered() {
        val a5Landscape = PaperSizes.of(PaperSizePreset.A5, PaperOrientation.Landscape) // 595 x 420
        val a5 = PaperSizes.A5 // 420 x 595
        val layout = DocumentLayout(listOf(a5, a5Landscape), viewportWidth = 1290, metrics = metrics)
        assertEquals(2f, layout.scale, 1e-4f)
        val narrow = layout.pageRect(0)
        assertEquals(840, narrow.width)
        assertEquals((1290 - 840) / 2, narrow.left)
        assertEquals(840, layout.pageRect(1).height)
    }

    @Test fun zoomWidensContent() {
        val layout = DocumentLayout(listOf(a4), viewportWidth = 1290, metrics = metrics, zoom = 2f)
        assertEquals(4f, layout.scale, 1e-4f)
        assertEquals(2380 + 100, layout.contentWidth)
    }

    @Test fun visiblePagesAndCurrentPage() {
        val layout = DocumentLayout(List(300) { a4 }, viewportWidth = 1290, metrics = metrics)
        val pitch = 1684 + 24
        assertEquals(0..0, layout.visiblePages(0, 1000))
        assertEquals(0..1, layout.visiblePages(1000, 1000))
        assertEquals(0, layout.currentPage(0, 1000))
        assertEquals(1, layout.currentPage(40 + pitch - 200, 1000)) // page 2 now fills most of the screen
        assertEquals(299..299, layout.visiblePages(layout.pageRect(299).top, 1000))
        // Only the add card on screen: indicator stays on the last page.
        assertTrue(layout.visiblePages(layout.addCard.top, 100).isEmpty())
        assertEquals(299, layout.currentPage(layout.addCard.top, 100))
        assertEquals(layout.maxScroll(1000), layout.contentHeight - 1000)
    }

    @Test fun visibleRangeMatchesBruteForceEverywhere() {
        val sizes = List(57) { i -> if (i % 3 == 0) PaperSizes.of(PaperSizePreset.A5, PaperOrientation.Landscape) else a4 }
        val layout = DocumentLayout(sizes, viewportWidth = 800, metrics = metrics)
        val h = 900
        var y = 0
        while (y < layout.contentHeight) {
            val expected = sizes.indices.filter { val r = layout.pageRect(it); r.bottom > y && r.top < y + h }
            val actual = layout.visiblePages(y, h).toList()
            assertEquals("scrollY=$y", expected, actual)
            y += 37
        }
    }

    @Test fun hitTesting() {
        val layout = DocumentLayout(List(3) { a4 }, viewportWidth = 1290, metrics = metrics)
        val p1 = layout.pageRect(1)
        assertEquals(1, layout.pageAt(p1.left + 1f, p1.top + 1f))
        assertEquals(-1, layout.pageAt(p1.left + 1f, p1.top - 5f)) // the gap
        assertEquals(-1, layout.pageAt(10f, p1.top + 10f)) // the desk
        assertEquals(-1, layout.pageAt(600f, layout.addCard.top + 5f))
        assertEquals(p1.top - 40, layout.scrollYFor(1, 1000))
    }

    @Test fun emptyDocumentStillHasTheAddCard() {
        val layout = DocumentLayout(emptyList(), viewportWidth = 1000, metrics = metrics)
        assertEquals(40, layout.addCard.top)
        assertEquals(-1, layout.currentPage(0, 500))
        assertTrue(layout.visiblePages(0, 500).isEmpty())
    }
}
