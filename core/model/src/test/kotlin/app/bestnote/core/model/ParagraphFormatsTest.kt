package app.bestnote.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphFormatsTest {
    private val title = ParagraphFormat(ParagraphStyle.Title)
    private val quote = ParagraphFormat(ParagraphStyle.Quote)
    private val bullet = ParagraphFormat(list = ListStyle.Bullet)

    @Test fun indexAndRanges() {
        val t = "ab\n\ncd"
        assertEquals(0, Paragraphs.indexAt(t, 2))
        assertEquals(1, Paragraphs.indexAt(t, 3))
        assertEquals(2, Paragraphs.indexAt(t, 6))
        assertEquals(listOf(0 until 2, 3 until 3, 4 until 6), Paragraphs.ranges(t))
        assertEquals(3, Paragraphs.count(t))
    }

    @Test fun normalizedPadsAndTrims() {
        assertEquals(listOf(title), Paragraphs.normalized(listOf(title, ParagraphFormat.PLAIN, ParagraphFormat.PLAIN), "a\nb\nc"))
        assertEquals(listOf(ParagraphFormat.PLAIN, title), Paragraphs.normalized(listOf(ParagraphFormat.PLAIN, title, quote), "a\nb"))
        assertTrue(Paragraphs.normalized(emptyList(), "x").isEmpty())
    }

    @Test fun enterAtEndOfHeadingStartsBody() {
        val out = Paragraphs.edited(listOf(title), "Hello", 5, 0, "\n")
        assertEquals(listOf(title, ParagraphFormat.PLAIN), out)
    }

    @Test fun enterInsideHeadingKeepsHeadingAndQuotesContinue() {
        assertEquals(listOf(title, title), Paragraphs.edited(listOf(title), "Hello", 2, 0, "\n"))
        assertEquals(listOf(quote, quote), Paragraphs.edited(listOf(quote), "Hi", 2, 0, "\n"))
        assertEquals(listOf(bullet, bullet), Paragraphs.edited(listOf(bullet), "Hi", 2, 0, "\n"))
    }

    @Test fun deletingALineBreakMergesIntoTheFirst() {
        val out = Paragraphs.edited(listOf(title, quote, bullet), "a\nb\nc", 1, 1, "")
        assertEquals(listOf(title, bullet), out)
    }

    @Test fun replacingAcrossParagraphs() {
        // "a\nb\nc" -> select "\nb\n" and type "x\ny": three paragraphs become two.
        val out = Paragraphs.edited(listOf(title, quote, bullet), "a\nb\nc", 1, 3, "x\ny")
        assertEquals(2, out.size)
        assertEquals(title.style, out[0].style)
    }

    @Test fun numberingRestartsAndNests() {
        val n = ParagraphFormat(list = ListStyle.Numbered)
        val deep = ParagraphFormat(list = ListStyle.Numbered, indent = 1)
        val m = Paragraphs.markers("one\ntwo\nsub\nsub2\nthree\nbreak\nagain", ListStyle.None, listOf(n, n, deep, deep, n, ParagraphFormat.PLAIN, n), Numerals.Latin)
        assertEquals(listOf("1.", "2.", "1.", "2.", "3.", null, "1."), m.map { it?.drop(1) })
    }

    @Test fun boxListAppliesUnlessOverridden() {
        val m = Paragraphs.markers("a\nb", ListStyle.Bullet, listOf(ParagraphFormat(list = ListStyle.None)), Numerals.Latin)
        assertNull(m[0])
        assertEquals(ListNumbering.BULLET.toString(), m[1])
    }

    @Test fun headingScalesAreOrdered() {
        assertTrue(ParagraphStyle.Title.sizeScale > ParagraphStyle.Heading.sizeScale)
        assertTrue(ParagraphStyle.Heading.sizeScale > ParagraphStyle.Subheading.sizeScale)
        assertTrue(ParagraphStyle.Subheading.sizeScale > ParagraphStyle.Body.sizeScale)
        assertTrue(ParagraphStyle.Code.mono)
    }
}
