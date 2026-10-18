package app.bestnote.feature.smartboard

import app.bestnote.ai.vision.FontSlot
import app.bestnote.ai.vision.LookPatch
import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefineSessionTest {
    private fun doc(t: String) = NoteDocument(listOf(NoteBlock(NoteElement.Paragraph, text = t)))

    @Test fun `each refine is one undo step, newest first`() {
        val s = RefineSession()
        assertFalse(s.canUndo)
        s.push(RefineSession.Snapshot(doc("a"), LookPatch(), "classic"))
        s.push(RefineSession.Snapshot(doc("b"), LookPatch(fonts = mapOf(FontSlot.Latin to "caveat")), "cornell"))
        assertTrue(s.canUndo)
        assertEquals("cornell", s.pop()!!.templateId)
        assertEquals("a", s.pop()!!.notes.elements.single().text)
        assertNull(s.pop())
    }

    @Test fun `undo depth and request history are bounded`() {
        val s = RefineSession(maxUndo = 2, maxRequests = 2)
        repeat(3) { s.push(RefineSession.Snapshot(doc("$it"), LookPatch(), "t")) }
        assertEquals("2", s.pop()!!.notes.elements.single().text)
        assertEquals("1", s.pop()!!.notes.elements.single().text)
        assertNull(s.pop())
        s.remember("make headings red")
        s.remember("font caveat")
        s.remember("make headings red")
        s.remember("  ")
        assertEquals(listOf("make headings red", "font caveat"), s.requests)
    }
}
