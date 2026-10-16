package app.bestnote.ai.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsciiMathTest {
    @Test fun `the owner's pseudo-ascii limit becomes inline latex`() {
        val out = AsciiMath.inline("נחשב lim _(x→∞)(x↑(30))/(e↑x+x↑2+10) בעזרת לופיטל")
        assertEquals("נחשב \\( \\lim_{x \\to \\infty}\\frac{x^{30}}{e^x+x^2+10} \\) בעזרת לופיטל", out)
    }

    @Test fun `ordinary words and existing latex are left alone`() {
        val s = "rate of 5% per year, see \\( a^2 \\) and \$b_1\$."
        assertEquals(s, AsciiMath.inline(s))
        assertEquals("area \\( x^2 \\).", AsciiMath.inline("area x↑2."))
    }

    @Test fun `gemini hebrew answer with dollar maths keeps its formula`() {
        // The owner's Gemini 3.8 Flash answer: Hebrew text, inline maths in $…$.
        val doc = AiDocumentParser.parse(
            """{"title":"חישוב גבול בעזרת כלל לופיטל","blocks":[
               {"type":"paragraph","order":0,"text":"מקבלים צורה ${'$'}\\frac{\\infty}{\\infty}${'$'} ולכן נשתמש בכלל לופיטל"},
               {"type":"paragraph","order":1,"text":"לאחר 30 גזירות: 30·29\\dotsm 1"}]}""",
        ).document
        assertEquals("חישוב גבול בעזרת כלל לופיטל", doc.title)
        assertTrue(doc.blocks[0].text!!.contains("\$\\frac{\\infty}{\\infty}\$"))
        assertTrue(doc.blocks[1].text!!, doc.blocks[1].text!!.contains("\\dotsm"))
    }
}
