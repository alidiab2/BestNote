package app.bestnote.engine.canvas.document

import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PaperTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PAGES-03/04/05: page order, count and paper changes are single undoable steps with stable ids. */
class PageStructureTest {
    private val nid = NotebookId("nb")

    private fun document(count: Int = 4) = PagedDocument().apply {
        reset(List(count) { Page(nid, it) }, List(count) { "p$it" })
    }

    private fun PagedDocument.ids() = pages.map { it.id }
    private fun PagedDocument.indices() = pages.map { it.page.index }

    @Test fun moveReordersAndUndoRestores() {
        val d = document()
        d.movePage(0, 2)
        assertEquals(listOf("p1", "p2", "p0", "p3"), d.ids())
        assertEquals(listOf(0, 1, 2, 3), d.indices())
        d.undo()
        assertEquals(listOf("p0", "p1", "p2", "p3"), d.ids())
        d.redo()
        assertEquals(listOf("p1", "p2", "p0", "p3"), d.ids())
    }

    @Test fun deleteKeepsAtLeastOnePageAndUndoBringsPagesBack() {
        val d = document()
        assertEquals(2, d.deletePages(listOf(1, 2)))
        assertEquals(listOf("p0", "p3"), d.ids())
        assertEquals(listOf(0, 1), d.indices())
        d.undo()
        assertEquals(listOf("p0", "p1", "p2", "p3"), d.ids())
        assertEquals(3, d.deletePages(listOf(0, 1, 2, 3))) // never empties the notebook
        assertEquals(1, d.size)
        assertEquals("p0", d.ids().single())
        d.undo()
        assertEquals(4, d.size)
    }

    @Test fun duplicateInsertsCopiesAfterOriginalsWithNewIds() {
        val d = document(3)
        val copies = d.duplicatePages(listOf(0, 2))
        assertEquals(5, d.size)
        assertEquals(listOf(1, 4), copies)
        assertEquals("p0", d.ids()[0])
        assertEquals("p2", d.ids()[3])
        assertEquals(5, d.ids().toSet().size)
        d.undo()
        assertEquals(listOf("p0", "p1", "p2"), d.ids())
    }

    @Test fun rotateSwapsPaperSidesAndUndoes() {
        val d = document(2)
        val before = d.pages[1].page
        d.rotatePages(listOf(1))
        assertEquals(before.heightPt, d.pages[1].page.widthPt, 0f)
        assertEquals(before.widthPt, d.pages[1].page.heightPt, 0f)
        d.undo()
        assertEquals(before, d.pages[1].page)
    }

    @Test fun insertWithTemplateIsUndoable() {
        val d = document(2)
        val at = d.insertPage(1, Page(nid, 0, PaperTemplate.Grid))
        assertEquals(1, at)
        assertEquals(PaperTemplate.Grid, d.pages[1].page.template)
        assertEquals(listOf("p0", "p1"), d.ids().filter { it.startsWith("p") && it.length == 2 })
        assertEquals(3, d.size)
        assertTrue(d.canUndo)
        d.undo()
        assertEquals(listOf("p0", "p1"), d.ids())
        assertFalse(d.canUndo)
    }

    @Test fun paperChangesAreUndoable() {
        val d = document(1)
        d.updatePage(0) { it.copy(template = PaperTemplate.Dotted) }
        assertEquals(PaperTemplate.Dotted, d.pages[0].page.template)
        d.undo()
        assertEquals(PaperTemplate.Ruled, d.pages[0].page.template)
    }
}
