package app.bestnote.engine.mathtype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexPaletteTest {
    private fun item(template: String) = PaletteItem(template, template, template)

    @Test
    fun fractionWithNothingSelectedPutsTheCaretInTheNumerator() {
        val e = LatexPalette.insert(LatexEdit("x=", 2), item("\\frac{#}{@}"))
        assertEquals("x=\\frac{}{}", e.text)
        assertEquals(8, e.start)
    }

    @Test
    fun selectionGoesIntoTheFirstSlotAndTheCaretToTheNext() {
        val e = LatexPalette.insert(LatexEdit("a+b=c", 0, 3), item("\\frac{#}{@}"))
        assertEquals("\\frac{a+b}{}=c", e.text)
        assertEquals(11, e.start)
    }

    @Test
    fun powerAttachesToWhatIsBeforeTheCaret() {
        val e = LatexPalette.insert(LatexEdit("x", 1), item("#^{@}"))
        assertEquals("x^{}", e.text)
        assertEquals(3, e.start)
    }

    @Test
    fun rootWrapsTheSelection() {
        val e = LatexPalette.insert(LatexEdit("2+y", 2, 3), item("\\sqrt{#}"))
        assertEquals("2+\\sqrt{y}", e.text)
        assertEquals(10, e.start)
    }

    @Test
    fun commandsNeverRunIntoLetters() {
        val e = LatexPalette.insert(LatexEdit("x", 0), item("\\alpha"))
        assertEquals("\\alpha x", e.text)
        val f = LatexPalette.insert(LatexEdit("\\pi", 3), item("r"))
        assertEquals("\\pi r", f.text)
    }

    @Test
    fun nextSlotFindsEmptyBraces() {
        val e = LatexEdit("\\frac{a}{}", 7)
        assertEquals(9, LatexPalette.nextSlot(e)!!.start)
        assertEquals(null, LatexPalette.nextSlot(LatexEdit("x", 0)))
    }

    @Test
    fun everyPaletteItemTypesetsWithoutErrors() {
        val tex = MathTypesetter()
        LatexPalette.groups.flatMap { it.items }.forEach { item ->
            val label = tex.typeset(item.label, 20f)
            assertTrue("${item.label}: ${label.errors}", label.errors.isEmpty())
            val inserted = LatexPalette.insert(LatexEdit("", 0), item)
            assertTrue("${item.template}: ${tex.typeset(inserted.text, 20f).errors}", tex.typeset(inserted.text, 20f).errors.isEmpty())
        }
    }
}
