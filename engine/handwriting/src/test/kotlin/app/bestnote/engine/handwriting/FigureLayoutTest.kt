package app.bestnote.engine.handwriting

import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.RectPt
import app.bestnote.core.model.StrokeStyle
import app.bestnote.core.model.Vec2
import app.bestnote.core.model.VectorDiagram
import app.bestnote.core.model.VectorShape
import app.bestnote.engine.layout.BoardFigure
import app.bestnote.engine.layout.FigureBox
import app.bestnote.engine.layout.FigureKind
import app.bestnote.engine.layout.FigureRaster
import app.bestnote.engine.layout.FigureSource
import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteNormalizer
import app.bestnote.engine.layout.NoteTemplates
import app.bestnote.engine.layout.NotebookPalette
import app.bestnote.engine.layout.RuledPaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI-12: a redrawn board figure in the laid-out note — after the text it belongs to, inside its column, as editable shapes. */
class FigureLayoutTest {
    private val paper = RuledPaper.of(PaperTemplate.Ruled, PageSize.A4)

    private val incline = VectorDiagram(
        RectPt(0f, 0f, 520f, 400f),
        listOf(
            VectorShape.Line(Vec2(40f, 360f), Vec2(480f, 360f)),
            VectorShape.Line(Vec2(480f, 360f), Vec2(480f, 30f)),
            VectorShape.Line(Vec2(40f, 360f), Vec2(480f, 30f)),
            VectorShape.Line(Vec2(200f, 300f), Vec2(400f, 300f), StrokeStyle(dashed = true)),
            VectorShape.Arrow(Vec2(250f, 200f), Vec2(250f, 340f), style = StrokeStyle(colorArgb = NotebookPalette.ESSENTIAL)),
            VectorShape.Polyline(listOf(Vec2(100f, 360f), Vec2(98f, 340f), Vec2(92f, 322f))),
            VectorShape.Label(Vec2(262f, 350f), "mg", latex = true),
        ),
    )

    private fun doc(figure: NoteBlock) = NoteDocument(
        listOf(
            NoteBlock(NoteElement.Title, text = "Incline"),
            NoteBlock(NoteElement.Paragraph, text = "A block on a slope at \\(37^\\circ\\)."),
            figure,
            NoteBlock(NoteElement.Formula, math = "N = mg\\cos 37^\\circ"),
        ),
    )

    @Test fun `a redrawn figure is placed between its text neighbours as clean shape strokes`() {
        for (template in listOf(NoteTemplates.byId("classic"), NoteTemplates.byId(NoteTemplates.STUDY_COLUMNS))) {
            val block = NoteBlock(NoteElement.Diagram, head = "Forces", diagram = incline, figure = BoardFigure(FigureKind.Diagram, FigureBox(0.3f, 0.2f, 0.6f, 0.6f), source = FigureSource.Redrawn))
            val pages = NoteLayout.typeset(NoteNormalizer.normalize(doc(block), template), template, paper, AwtMeasure)
            val placed = pages.placed.map { it.second }
            val fig = placed.first { it.block.element == NoteElement.Diagram }
            val para = placed.first { it.block.text?.startsWith("A block") == true } // study-columns makes it a Note
            val formula = placed.first { it.block.element == NoteElement.Formula }
            assertTrue("${template.id}: figure after its paragraph", fig.frame.top >= para.frame.top)
            assertTrue("${template.id}: formula after the figure", formula.frame.top >= fig.frame.top || formula.frame.left != fig.frame.left)
            val page = pages.pages.pages[pages.placed.first { it.second === fig }.first]
            val shapes = page.ink.filter { it.brush.shape != null }
            assertEquals("${template.id}: one stroke per shape", 6, shapes.size)
            assertEquals(setOf("Line", "Arrow", "Polyline"), shapes.map { it.brush.shape!!.kind }.toSet())
            assertEquals(1, shapes.count { it.brush.dash == app.bestnote.core.model.LineDash.Dashed })
            // Inside the figure's column, and the arrow in the template's result colour (not the board's red).
            for (s in shapes) for (p in s.samples) assertTrue("${template.id}: inside the column", p.x >= fig.frame.left - 1f && p.x <= fig.frame.right + 1f)
            val arrow = shapes.first { it.brush.shape!!.kind == "Arrow" }
            val resultColor = template.look.role(app.bestnote.engine.layout.NoteRole.Result).color?.toInt() ?: FontComposer.INK
            assertEquals(resultColor, arrow.brush.colorArgb)
            // The base stays exactly horizontal (no hand tremor on a redrawn figure).
            val base = shapes.first { it.brush.shape!!.kind == "Line" }
            assertTrue(base.samples.maxOf { it.y } - base.samples.minOf { it.y } < 0.01f)
            // The label is typeset.
            assertTrue(page.runs.any { it.block == fig.key && it.text.contains("m") })
        }
    }

    @Test fun `a unit is bracketed once, however the AI wrote it`() {
        for (u in listOf("m", "(m)", "[m]", "\\((m)\\)", " (m/s) ")) assertEquals(u, u.trim().let { if (it.contains("m/s")) "m/s" else "m" }, bareUnit(u))
        assertEquals("(m)(s)", bareUnit("(m)(s)"))
    }

    @Test fun `a label with words is written without its LaTeX`() {
        assertEquals("מצב B", plainLabel("\\text{מצב } B"))
        assertEquals("v = 10 (m/s)", plainLabel("v = 10\\,(m/s)"))
    }

    @Test fun `a figure kept as a picture becomes an image on the page`() {
        val template = NoteTemplates.byId("classic")
        val block = NoteBlock(NoteElement.Diagram, figure = BoardFigure(FigureKind.Drawing, FigureBox(0.1f, 0.1f, 0.5f, 0.4f), raster = FigureRaster("assets/img-x.png", 400, 300), source = FigureSource.Picture))
        val pages = NoteLayout.typeset(NoteNormalizer.normalize(doc(block), template), template, paper, AwtMeasure)
        val img = pages.pages.pages.flatMap { it.images }.single()
        assertEquals("assets/img-x.png", img.asset)
        assertEquals(400f / 300f, img.width / img.height, 0.01f)
        assertTrue(img.left >= 0f && img.left + img.width <= paper.width)
    }
}
