package app.bestnote.feature.editor.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoformatTest {
    @Test fun lineMarkersNeedASpaceAndTheLineStart() {
        assertTrue(Autoformat.detect("# ", 2) is Autoformat.Paragraph)
        assertNull(Autoformat.detect("#", 1))
        assertNull(Autoformat.detect("a # ", 4))
        assertNull(Autoformat.detect("#### ", 5))
        val m = Autoformat.detect("x\n12) ", 6) as Autoformat.Paragraph
        assertEquals(2, m.start)
        assertEquals(4, m.length)
    }

    @Test fun inlineCodeNeedsSomethingBetweenTheBackticks() {
        assertEquals(Autoformat.InlineCode(2, 5), Autoformat.detect("a `bc`", 6))
        assertNull(Autoformat.detect("``", 2))
        assertNull(Autoformat.detect("```", 3))
        // Backticks on another line do not pair.
        assertNull(Autoformat.detect("`a\nb`", 5))
    }
}
