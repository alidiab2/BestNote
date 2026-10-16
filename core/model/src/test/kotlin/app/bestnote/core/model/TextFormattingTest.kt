package app.bestnote.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFormattingTest {
    private val bold = RunStyle(bold = true)

    @Test fun `restyle then read back as merged runs`() {
        val r = TextRuns.restyled(emptyList(), 10, 2, 5) { it.copy(bold = true) }
        assertEquals(listOf(TextRun(2, 5, bold)), r)
        val r2 = TextRuns.restyled(r, 10, 5, 7) { it.copy(bold = true) }
        assertEquals(listOf(TextRun(2, 7, bold)), r2)
        val off = TextRuns.restyled(r2, 10, 2, 7) { it.copy(bold = false) }
        assertTrue(off.isEmpty())
    }

    @Test fun `typing inside a styled run continues it and edits shift later runs`() {
        val runs = listOf(TextRun(2, 5, bold))
        assertEquals(listOf(TextRun(2, 7, bold)), TextRuns.edited(runs, 10, at = 4, removed = 0, inserted = 2))
        assertEquals(listOf(TextRun(4, 7, bold)), TextRuns.edited(runs, 10, at = 0, removed = 0, inserted = 2))
        assertEquals(listOf(TextRun(2, 3, bold)), TextRuns.edited(runs, 10, at = 3, removed = 2, inserted = 0))
        assertTrue(TextRuns.edited(runs, 10, at = 1, removed = 6, inserted = 0).isEmpty())
    }

    @Test fun `sanitized clips out of range and drops plain runs`() {
        val r = TextRuns.sanitized(listOf(TextRun(3, 99, bold), TextRun(0, 2, RunStyle.PLAIN)), 6)
        assertEquals(listOf(TextRun(3, 6, bold)), r)
    }

    @Test fun `all reports uniform style over a range`() {
        val runs = listOf(TextRun(0, 3, bold))
        assertTrue(TextRuns.all(runs, 6, 0, 3) { it.bold })
        assertFalse(TextRuns.all(runs, 6, 0, 4) { it.bold })
        assertFalse(TextRuns.all(runs, 6, 2, 2) { it.bold })
    }

    @Test fun `direction follows the first strong letter`() {
        assertTrue(ListNumbering.isRtl("123 مرحبا"))
        assertTrue(ListNumbering.isRtl("שלום world"))
        assertFalse(ListNumbering.isRtl("hello مرحبا"))
        assertFalse(ListNumbering.isRtl("123"))
    }

    @Test fun `arabic indic digits`() {
        assertEquals("١٢٣", ListNumbering.digits(123, Numerals.ArabicIndic))
        assertEquals("12", ListNumbering.digits(12, Numerals.Latin))
    }

    @Test fun `numbered markers are right to left for arabic paragraphs and skip blank lines`() {
        val m = ListNumbering.markers("مرحبا\n\nعالم\nhello", ListStyle.Numbered, Numerals.ArabicIndic)
        assertEquals("‏١.", m[0])
        assertNull(m[1])
        assertEquals("‏٢.", m[2])
        assertEquals("‎٣.", m[3])
        val latin = ListNumbering.markers("a\nb", ListStyle.Numbered, Numerals.Latin)
        assertEquals(listOf("‎1.", "‎2."), latin)
    }

    @Test fun `bullets and no list`() {
        assertEquals(listOf("•", "•"), ListNumbering.markers("a\nب", ListStyle.Bullet, Numerals.Latin))
        assertEquals(listOf(null, null), ListNumbering.markers("a\nb", ListStyle.None, Numerals.Latin))
    }
}
