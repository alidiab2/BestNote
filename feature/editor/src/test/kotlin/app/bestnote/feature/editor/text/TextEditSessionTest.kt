package app.bestnote.feature.editor.text

import app.bestnote.core.model.ListStyle
import app.bestnote.core.model.ParagraphFormat
import app.bestnote.core.model.ParagraphStyle
import app.bestnote.core.model.Paragraphs
import app.bestnote.core.model.TextElement
import app.bestnote.core.model.TextRuns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEditSessionTest {
    private var now = 0L
    private fun session(text: String = "") = TextEditSession(TextElement("t", 100f, 100f, 200f, 30f, text), clock = { now })

    /** Types [chars] one at a time at the caret, like a keyboard. */
    private fun TextEditSession.typeEach(chars: String) = chars.forEach { type(it.toString()) }

    private fun TextEditSession.backspace() {
        val d = draft
        onInput(d.text.removeRange(d.min - 1, d.min), d.min - 1, d.min - 1)
    }

    private fun TextEditSession.formatAt(i: Int) = Paragraphs.formatOf(draft.box.paragraphs, i)

    @Test fun hashSpaceMakesATitleAndStripsTheMarker() {
        val s = session()
        s.typeEach("# ")
        assertEquals("", s.draft.text)
        assertEquals(ParagraphStyle.Title, s.formatAt(0).style)
        s.typeEach("Hello")
        assertEquals("Hello", s.draft.text)
    }

    @Test fun markdownMarkersAtLineStart() {
        val cases = mapOf(
            "## " to ParagraphFormat(ParagraphStyle.Heading),
            "### " to ParagraphFormat(ParagraphStyle.Subheading),
            "> " to ParagraphFormat(ParagraphStyle.Quote),
            "``` " to ParagraphFormat(ParagraphStyle.Code),
            "- " to ParagraphFormat(list = ListStyle.Bullet),
            "* " to ParagraphFormat(list = ListStyle.Bullet),
            "1. " to ParagraphFormat(list = ListStyle.Numbered),
            "١. " to ParagraphFormat(list = ListStyle.Numbered),
        )
        for ((marker, format) in cases) {
            val s = session("first\n")
            s.typeEach(marker)
            assertEquals(marker, "first\n", s.draft.text)
            assertEquals(marker, format, s.formatAt(1))
            assertEquals(ParagraphFormat.PLAIN, s.formatAt(0))
        }
    }

    @Test fun markersOnlyAtTheStartOfALine() {
        val s = session("a")
        s.typeEach("# ")
        assertEquals("a# ", s.draft.text)
        assertEquals(ParagraphFormat.PLAIN, s.formatAt(0))
    }

    @Test fun oneUndoOrBackspaceBringsTheMarkerBack() {
        val s = session()
        s.typeEach("- ")
        assertEquals(ListStyle.Bullet, s.formatAt(0).list)
        assertTrue(s.undo())
        assertEquals("- ", s.draft.text)
        assertNull(s.formatAt(0).list)

        val t = session("x\n")
        t.typeEach("# ")
        t.backspace() // deletes the line break before the caret... but restores the marker instead
        assertEquals("x\n# ", t.draft.text)
        assertEquals(ParagraphStyle.Body, t.formatAt(1).style)

        val u = session()
        u.typeEach("> ")
        assertTrue(u.backspaceAtStart())
        assertEquals("> ", u.draft.text)
        assertFalse(u.backspaceAtStart())
    }

    @Test fun inlineCodeBecomesMonospaced() {
        val s = session()
        s.typeEach("run `ls` now")
        assertEquals("run ls now", s.draft.text)
        val styles = TextRuns.toStyles(s.draft.box.runs, s.draft.text.length)
        assertEquals(TextElement.FONT_MONO, styles[4].font)
        assertEquals(TextElement.FONT_MONO, styles[5].font)
        assertNull(styles[3].font)
        assertNull(styles[7].font) // typed after the code is plain again
        assertTrue(s.undo()) // " now"
        assertEquals("run ls", s.draft.text)
        assertTrue(s.undo()) // the autoformat
        assertEquals("run `ls`", s.draft.text)
    }

    @Test fun noAutoformatWhileComposing() {
        val s = session()
        assertFalse(s.onInput("# ", 2, 2, composing = true))
        assertEquals("# ", s.draft.text)
    }

    @Test fun enterAfterHeadingStartsBodyAndListsContinue() {
        val s = session()
        s.setStyle(ParagraphStyle.Heading)
        s.typeEach("Plan\n")
        assertEquals(ParagraphStyle.Heading, s.formatAt(0).style)
        assertEquals(ParagraphStyle.Body, s.formatAt(1).style)
        s.typeEach("- milk\neggs")
        assertEquals(ListStyle.Bullet, s.formatAt(1).list)
        assertEquals(ListStyle.Bullet, s.formatAt(2).list)
    }

    @Test fun enterOnAnEmptyItemEndsTheList() {
        val s = session()
        s.typeEach("- a\n")
        assertEquals(ListStyle.Bullet, s.formatAt(1).list)
        s.typeEach("\n")
        assertEquals("a\n", s.draft.text)
        assertEquals(ListStyle.None, s.formatAt(1).list)
    }

    @Test fun tabIndentsOnlyInLists() {
        val s = session("plain")
        assertFalse(s.indent(1))
        s.toggleList(ListStyle.Numbered)
        assertTrue(s.indent(1))
        assertEquals(1, s.formatAt(0).indent)
        assertTrue(s.indent(-1))
        assertEquals(0, s.formatAt(0).indent)
    }

    @Test fun ctrlBAtTheCaretStylesWhatIsTypedNext() {
        val s = session("ab")
        s.toggle(Flag.Bold)
        assertTrue(s.isOn(Flag.Bold))
        s.typeEach("CD")
        val styles = TextRuns.toStyles(s.draft.box.runs, 4)
        assertFalse(styles[1].bold)
        assertTrue(styles[2].bold && styles[3].bold)
    }

    @Test fun boldOnASelection() {
        val s = session("hello world")
        s.onInput("hello world", 0, 5)
        s.toggle(Flag.Bold)
        assertTrue(s.isOn(Flag.Bold))
        s.toggle(Flag.Bold)
        assertFalse(s.isOn(Flag.Bold))
    }

    @Test fun paragraphStyleIsOneTapAndTogglesBack() {
        val s = session("a\nb")
        s.onInput("a\nb", 0, 3)
        s.setStyle(ParagraphStyle.Quote)
        assertEquals(ParagraphStyle.Quote, s.formatAt(0).style)
        assertEquals(ParagraphStyle.Quote, s.formatAt(1).style)
        s.setStyle(ParagraphStyle.Quote)
        assertEquals(ParagraphStyle.Body, s.formatAt(1).style)
    }

    @Test fun typingIsGroupedIntoWordsForUndo() {
        val s = session()
        s.typeEach("hello")
        now += 100
        s.typeEach(" world")
        assertTrue(s.undo())
        assertEquals("hello", s.draft.text)
        assertTrue(s.undo())
        assertEquals("", s.draft.text)
        assertTrue(s.redo())
        assertEquals("hello", s.draft.text)
    }

    @Test fun resultTrimsAndKeepsFormatsAligned() {
        val s = session()
        s.typeEach("# Title\nbody\n\n")
        val r = s.result()
        assertEquals("Title\nbody", r.text)
        assertEquals(listOf(ParagraphFormat(ParagraphStyle.Title)), r.paragraphs)
    }
}
