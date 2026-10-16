package app.bestnote.engine.handwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeHandTest {
    @Test fun `hebrew is not in the stroke hand so it goes to the fonts instead of question marks`() {
        assertFalse(StrokeHand.canWrite("חישוב גבול בעזרת כלל לופיטל"))
        assertTrue(StrokeHand.canWrite("limits at infinity"))
        assertTrue(StrokeHand.canWrite("المتتاليات الحسابية"))
        assertFalse(StrokeHand.canWriteProse("גבול \\( x^2 \\)"))
        assertTrue(StrokeHand.canWriteProse("limit \\( x^2 \\) and \$\\frac{1}{2}\$"))
    }

    @Test fun `common macros are known and unknown ones are reported, never written with a backslash`() {
        assertEquals(emptySet<String>(), StrokeHand.unknownCommands("30 \\cdot 29 \\dotsm 1 = 30! \\quad \\lim_{x \\to \\infty} \\dfrac{a}{b} \\left( \\text{ok} \\right) \\operatorname{arg}"))
        assertEquals(setOf("foo"), StrokeHand.unknownCommands("\\foo x"))
        // \dotsm is written as an ellipsis, not "dotsm".
        val nodes = MathParser.parse("30\\cdot 29 \\dotsm 1").flatten()
        val text = nodes.filterIsInstance<MathParser.Text>().joinToString("") { it.s }
        assertTrue(text, "…" in text && "dotsm" !in text)
    }

    @Test fun `inline maths in every delimiter real answers use`() {
        val table = ArrayList<String>()
        val encoded = InlineMath.encode("צורה \$\\frac{\\infty}{\\infty}\$ ו־\$\$x^2\$\$ and \\[ y \\] or \\( z \\)", table)
        assertEquals(listOf("\\frac{\\infty}{\\infty}", "x^2", "y", "z"), table)
        assertFalse(encoded.contains('$'))
    }
}
