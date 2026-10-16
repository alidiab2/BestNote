package app.bestnote.feature.editor

import app.bestnote.ai.vision.AiBlock
import app.bestnote.ai.vision.AiDocument
import app.bestnote.core.model.PageRect
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.TextElement
import app.bestnote.engine.handwriting.TextMeasure
import app.bestnote.engine.layout.HandFont
import app.bestnote.engine.layout.RuledPaper
import org.junit.Assert.assertTrue
import org.junit.Test

class TidyWriterTest {
    /** Half an em per character: enough to check placement without Android's Paint. */
    private object Fake : TextMeasure {
        override fun width(text: String, font: HandFont, sizePt: Float) = text.length * sizePt * 0.5f
        override fun ascent(font: HandFont) = 0.8f
        override fun descent(font: HandFont) = 0.25f
    }

    private val page = PageSize.A4
    private var n = 0

    private fun write(text: String, box: PageRect) = TidyWriter.write(
        AiDocument(blocks = listOf(AiBlock("paragraph", order = 0, text = text))), box, PaperTemplate.Ruled, page, 0xFF1D3C8A.toInt(), Fake,
    ) { "t${n++}" }

    @Test fun `tidied text replaces the selection on the page's ruled lines in the ink colour`() {
        val box = PageRect(120f, 300f, 460f, 360f)
        val out = write("neat notes keep their place on the page", box)
        val texts = out.elements.filterIsInstance<TextElement>()
        assertTrue(texts.isNotEmpty())
        val paper = RuledPaper.of(PaperTemplate.Ruled, page)
        for (t in texts) {
            assertTrue("left ${t.cx - t.width / 2}", t.cx - t.width / 2 >= box.left - 6f)
            assertTrue("right ${t.cx + t.width / 2}", t.cx + t.width / 2 <= box.right + 6f)
            assertTrue("top ${t.cy - t.height / 2}", t.cy - t.height / 2 >= box.top - paper.lineGap)
            assertTrue(t.colorArgb == 0xFF1D3C8A.toInt())
            assertTrue(t.font != null)
        }
    }

    @Test fun `hebrew and arabic are written from the right edge of the selection`() {
        for (text in listOf("ملاحظات مرتبة", "הערות מסודרות")) {
            val box = PageRect(100f, 200f, 480f, 240f)
            val t = write(text, box).elements.filterIsInstance<TextElement>().first()
            assertTrue("$text starts at ${t.cx}", t.cx > (box.left + box.right) / 2)
        }
    }
}
