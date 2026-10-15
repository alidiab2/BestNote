package app.bestnote.feature.smartboard

import app.bestnote.ai.vision.DemoVisionProvider
import app.bestnote.core.model.PageBlock
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.layout.NotebookPalette
import app.bestnote.engine.layout.RuledPaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardToInkTest {
    private val paper = RuledPaper.of(PaperTemplate.Ruled, PageSize.A4)
    private var n = 0
    private val pipeline = BoardToInk { "b${n++}" }

    @Test fun `sample lecture becomes handwriting on one ruled page`() {
        val ink = pipeline.write(DemoVisionProvider.ENGLISH, rtlHint = false, paper = paper)
        assertEquals(0, ink.dropped)
        assertEquals(1, ink.pages.pages.size)
        assertFalse(ink.rtl)
        val blocks = ink.blocks.map { it.second }
        assertTrue(blocks.first() is PageBlock.Heading)
        assertTrue(blocks.any { it is PageBlock.Diagram })
        val strokes = ink.pages.pages[0]
        assertTrue(strokes.size > 150)
        assertTrue(strokes.any { it.stroke.brush.colorArgb == NotebookPalette.ESSENTIAL.toInt() })
        assertTrue(strokes.any { it.stroke.brush.colorArgb == NotebookPalette.ANNOTATION.toInt() })
        for (s in strokes) for (p in s.stroke.samples) {
            assertTrue(p.x in 0f..paper.width && p.y in 0f..paper.height)
        }
        // A full page writes itself in natural time well under four minutes.
        assertTrue("took ${ink.pages.durationMs(0)} ms", ink.pages.durationMs(0) in 5_000L..240_000L)
    }

    @Test fun `arabic board is detected and written right to left`() {
        val ink = pipeline.write(DemoVisionProvider.ARABIC, rtlHint = false, paper = paper)
        assertTrue(ink.rtl)
        val title = ink.pages.pages[0].filter { it.block == ink.blocks.first().second.id.value }
        assertTrue(title.first().stroke.samples.first().x > paper.width / 2)
    }
}
