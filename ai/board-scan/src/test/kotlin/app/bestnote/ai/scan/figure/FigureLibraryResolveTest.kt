package app.bestnote.ai.scan.figure

import app.bestnote.engine.layout.BoardFigure
import app.bestnote.engine.layout.FigureBox
import app.bestnote.engine.layout.FigureKind
import app.bestnote.engine.layout.FigureSource
import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.figlib.FigSpec
import app.bestnote.engine.layout.figlib.FigureLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** FIG-LIB: a drawing the AI recognised is drawn from its parameters, ahead of fitting the photo's strokes. */
class FigureLibraryResolveTest {
    private fun doc(spec: FigSpec?) = NoteDocument(listOf(NoteBlock(NoteElement.Diagram, figure = BoardFigure(FigureKind.Drawing, FigureBox(0.1f, 0.1f, 0.5f, 0.5f), spec = spec))))

    private val incline = FigSpec("incline", listOf(listOf("a", 37.0), listOf("f", "N", "n")))

    @Test fun `a recognised kind is drawn from its parameters`() {
        val b = FigureRedraw.resolve(doc(incline), board = null).elements.single()
        assertEquals(FigureSource.FromData, b.figure!!.source)
        assertEquals(FigureLibrary.render(incline)!!.diagram, b.diagram)
    }

    @Test fun `a low-confidence kind is still drawn when nothing better is found`() {
        val b = FigureRedraw.resolve(doc(incline.copy(confidence = 0.2f)), board = null).elements.single()
        assertEquals(FigureSource.FromData, b.figure!!.source)
        assertNotNull(b.diagram)
    }

    @Test fun `a spec that cannot be drawn leaves the old path`() {
        val b = FigureRedraw.resolve(doc(FigSpec("incline", emptyList<List<Any>>().plus(listOf(listOf("zz", 1.0)))).copy(kind = "nothing")), board = null).elements.single()
        assertEquals(FigureSource.Pending, b.figure!!.source)
    }
}
