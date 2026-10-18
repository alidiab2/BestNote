package app.bestnote.engine.handwriting

import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.layout.BoardFigure
import app.bestnote.engine.layout.FigureKind
import app.bestnote.engine.layout.FigureSource
import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteNormalizer
import app.bestnote.engine.layout.NoteTemplates
import app.bestnote.engine.layout.RuledPaper
import app.bestnote.engine.layout.figlib.FigExamples
import app.bestnote.engine.layout.figlib.FigureLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIG-LIB gallery: every library kind laid out in a real note (template look, ruled paper) as
 * editable shape strokes and typeset labels; pages to build/figure-gallery/ for eyeballing.
 */
class FigureGalleryTest {
    @Test fun `every library kind is laid out as editable shapes with its labels`() {
        val paper = RuledPaper.of(PaperTemplate.Grid, PageSize.A4)
        val template = NoteTemplates.byId("classic")
        val blocks = ArrayList<NoteBlock>()
        blocks += NoteBlock(NoteElement.Title, text = "Figure library")
        for (s in FigExamples.all) {
            blocks += NoteBlock(NoteElement.Heading, text = s.kind)
            blocks += NoteBlock(NoteElement.Diagram, head = s.kind, diagram = FigureLibrary.render(s)!!.diagram, figure = BoardFigure(FigureKind.Diagram, spec = s, source = FigureSource.FromData))
        }
        val pages = NoteLayout.typeset(NoteNormalizer.normalize(NoteDocument(blocks), template), template, paper, AwtMeasure)
        pages.pages.pages.forEachIndexed { i, p -> FontPreview.png(paper, p, "gallery-$i", dir = "build/figure-gallery") }
        val figs = pages.placed.map { it.second }.filter { it.block.element == NoteElement.Diagram }
        assertEquals(FigExamples.all.size, figs.size)
        // Every drawing became shape strokes (CANVAS-17 metadata), inside its column.
        for ((pi, placed) in pages.placed) {
            if (placed.block.element != NoteElement.Diagram) continue
            val page = pages.pages.pages[pi]
            val shapes = page.ink.filter { it.brush.shape != null && it.samples.all { q -> q.y in placed.frame.top - 1f..placed.frame.bottom + 1f && q.x in placed.frame.left - 1f..placed.frame.right + 1f } }
            assertTrue("${placed.block.head}: shapes", shapes.isNotEmpty())
        }
    }
}
