package app.bestnote.feature.editor

import app.bestnote.ai.vision.AiBlock
import app.bestnote.ai.vision.AiDocument
import app.bestnote.core.model.PageRect
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.layout.RuledPaper
import org.junit.Assert.assertTrue
import org.junit.Test

class TidyWriterTest {
    private val page = PageSize.A4
    private var n = 0

    private fun write(text: String, box: PageRect) = TidyWriter.write(
        AiDocument(blocks = listOf(AiBlock("paragraph", order = 0, text = text))), box, PaperTemplate.Ruled, page, 0xFF1D3C8A.toInt(),
    ) { "t${n++}" }

    @Test fun `tidied writing replaces the selection on the page's ruled lines`() {
        val box = PageRect(120f, 300f, 460f, 360f)
        val strokes = write("neat notes keep their place on the page", box)
        assertTrue(strokes.isNotEmpty())
        val xs = strokes.flatMap { s -> s.samples.map { it.x } }
        val ys = strokes.flatMap { s -> s.samples.map { it.y } }
        assertTrue("left ${xs.min()}", xs.min() >= box.left - 4f)
        assertTrue("right ${xs.max()}", xs.max() <= box.right + 4f)
        val paper = RuledPaper.of(PaperTemplate.Ruled, page)
        assertTrue("top ${ys.min()}", ys.min() >= box.top - paper.lineGap)
        assertTrue(strokes.all { it.brush.colorArgb == 0xFF1D3C8A.toInt() })
    }

    @Test fun `arabic is written from the right edge of the selection`() {
        val box = PageRect(100f, 200f, 480f, 240f)
        val strokes = write("ملاحظات مرتبة", box)
        val first = strokes.first().samples.first()
        assertTrue("starts at ${first.x}", first.x > (box.left + box.right) / 2)
    }
}
