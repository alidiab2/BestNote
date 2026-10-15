package app.bestnote.engine.layout.document

import app.bestnote.core.model.PageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageWindowTest {
    private class Slot(val id: Int) {
        var page: Int? = null
    }

    private var binds = 0
    private val window = PageWindow(
        overscan = 1,
        create = { Slot(0) },
        bind = { slot, page -> slot.page = page; binds++ },
        unbind = { it.page = null },
    )

    @Test fun targetAddsOverscanAndClamps() {
        assertEquals(0..2, window.target(0..1, 300))
        assertEquals(9..13, window.target(10..12, 300))
        assertEquals(298..299, window.target(299..299, 300))
        assertTrue(window.target(IntRange.EMPTY, 300).isEmpty())
    }

    @Test fun scrollingThrough300PagesKeepsSlotsFlat() {
        val metrics = DocumentMetrics(40, 60, 50, 24, 120)
        val layout = DocumentLayout(List(300) { PageSize.A4 }, 1290, metrics)
        val viewport = 1800
        var y = 0
        var maxBound = 0
        while (y <= layout.maxScroll(viewport)) {
            val visible = layout.visiblePages(y, viewport)
            window.update(visible, 300)
            for (p in visible) assertEquals(p, window.slotFor(p)!!.page) // every visible page is drawn
            assertEquals(window.target(visible, 300).toSet(), window.boundPages)
            maxBound = maxOf(maxBound, window.boundPages.size)
            y += 97 // ~120 fps fling step
        }
        // A viewport shows at most 3 pages here; plus 1 overscan each side.
        assertTrue("bound $maxBound", maxBound <= 5)
        assertTrue("created ${window.created}", window.created <= 5)
        // Each page is bound once per pass (no rebinding while it stays in range).
        assertTrue("binds $binds", binds <= 300 + 5)
    }

    @Test fun jumpRecyclesEverySlot() {
        window.update(1..3, 300) // binds 0..4
        val before = window.created
        window.update(150..152, 300)
        assertEquals(before, window.created)
        assertNull(window.slotFor(0))
        assertEquals(setOf(149, 150, 151, 152, 153), window.boundPages)
        window.clear()
        assertTrue(window.boundPages.isEmpty())
        window.update(0..0, 1)
        assertEquals(setOf(0), window.boundPages)
        assertEquals(before, window.created)
    }
}
