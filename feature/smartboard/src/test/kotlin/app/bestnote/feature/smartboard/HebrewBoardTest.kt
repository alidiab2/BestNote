package app.bestnote.feature.smartboard

import app.bestnote.ai.vision.AiDocumentParser
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.handwriting.TextMeasure
import app.bestnote.engine.layout.HandFont
import app.bestnote.engine.layout.RuledPaper
import app.bestnote.engine.layout.StyleTemplates
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's "?????" page: Gemini answered in Hebrew with $…$ maths and \dotsm. */
class HebrewBoardTest {
    private object Fake : TextMeasure {
        override fun width(text: String, font: HandFont, sizePt: Float) = text.length * sizePt * 0.45f
        override fun ascent(font: HandFont) = 0.8f
        override fun descent(font: HandFont) = 0.25f
    }

    private val answer = """{"title":"חישוב גבול בעזרת כלל לופיטל","blocks":[
        {"type":"paragraph","order":0,"text":"מקבלים צורה ${'$'}\\frac{\\infty}{\\infty}${'$'} ולכן נשתמש בכלל לופיטל"},
        {"type":"math","id":"m","order":1,"latex":"\\lim_{x \\to \\infty} \\frac{x^{30}}{e^x+x^2+10} = \\lim \\frac{30\\cdot 29 \\dotsm 1}{e^x} = 0","boxed":true},
        {"type":"margin_note","order":2,"annotates":"m","text":"אקספוננט מנצח"},
        {"type":"paragraph","order":3,"text":"ובקיצור lim _(x→∞)(x↑(30))/(e↑x+x↑2+10) = 0"}]}"""

    @Test fun `hebrew answer is written with fonts, right to left, with real maths and no question marks`() {
        val doc = AiDocumentParser.parse(answer).document
        // The stroke hand cannot write Hebrew: the page must go to the font writer.
        assertFalse(BoardToInk.strokesCanWrite(doc))
        var n = 0
        val text = BoardToInk { "b${n++}" }.typeset(doc, rtlHint = false, paper = RuledPaper.of(PaperTemplate.Ruled, PageSize.A4), style = StyleTemplates.Classic, measure = Fake)
        assertTrue(text.rtl)
        val runs = text.pages.pages.flatMap { it.runs }
        assertTrue(runs.any { it.text.contains("חישוב") && it.rtl })
        assertTrue(runs.none { '?' in it.text || "dotsm" in it.text || "rac{" in it.text || '↑' in it.text || '$' in it.text })
        assertTrue(runs.any { "∞" in it.text })
        assertTrue(runs.any { "…" in it.text })
        // Nothing unknown in this answer: \dotsm and friends are known macros.
        assertTrue(text.dropReasons.toString(), text.dropReasons.none { "unknown LaTeX" in it })
    }
}
