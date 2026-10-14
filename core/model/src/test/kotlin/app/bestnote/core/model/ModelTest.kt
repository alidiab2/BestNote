package app.bestnote.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    @Test fun inkingTools() {
        assertEquals(setOf(Tool.Pen, Tool.Pencil, Tool.Highlighter), Tool.entries.filter { it.inks }.toSet())
        assertFalse(Tool.Eraser.inks)
    }

    @Test fun defaultPageIsA4Portrait() {
        val page = Page(NotebookId("n1"), index = 0)
        assertTrue(page.aspectRatio < 1f)
        assertEquals(595f / 842f, page.aspectRatio, 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankIdRejected() {
        NotebookId(" ")
    }
}
