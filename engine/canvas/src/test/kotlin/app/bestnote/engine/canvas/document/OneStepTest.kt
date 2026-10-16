package app.bestnote.engine.canvas.document

import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.TextElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TPL-01: a Smart Board follow-up (re-written pages + pages added) undoes and redoes as one step. */
class OneStepTest {
    private val nid = NotebookId("nb")

    private fun text(id: String) = TextElement(id, 100f, 100f, 50f, 20f, id)

    @Test fun relayoutAndNewPagesAreOneUndoStep() {
        val d = PagedDocument().apply { reset(List(2) { Page(nid, it) }, listOf("p0", "p1")) }
        d.replaceElements(0, listOf(text("user"), text("sb-old")))
        d.asOneStep {
            d.editContent(0, elements = listOf(text("user"), text("sb-new-1")))
            d.insertPages(1, listOf(Page(nid, 0) to emptyList()), listOf(listOf(text("sb-new-2"))))
        }
        assertEquals(3, d.size)
        assertEquals(listOf("user", "sb-new-1"), d.pages[0].elements.map { it.id })
        d.undo()
        assertEquals(2, d.size)
        assertEquals(listOf("user", "sb-old"), d.pages[0].elements.map { it.id })
        assertTrue(d.canUndo) // the earlier edit is still its own step
        d.redo()
        assertEquals(3, d.size)
        assertEquals("sb-new-2", d.pages[1].elements.single().id)
        d.undo()
        d.undo()
        assertFalse(d.canUndo)
    }
}
