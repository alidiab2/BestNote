package app.bestnote.engine.canvas.document

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.Tool
import app.bestnote.engine.canvas.StrokeBrushes
import app.bestnote.engine.canvas.StrokeFills
import app.bestnote.engine.canvas.select.PenType
import app.bestnote.engine.canvas.select.StrokeEdits
import app.bestnote.engine.ink.BrushCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** INK-11 / CANVAS-10 on real androidx.ink strokes: restyle keeps the inputs, fill and restyle undo as one step. */
@RunWith(AndroidJUnit4::class)
class FillRestyleInstrumentedTest {
    private val nid = NotebookId("nb")

    private fun square(): Stroke {
        val batch = MutableStrokeInputBatch()
        val pts = listOf(10f to 10f, 90f to 10f, 90f to 90f, 10f to 90f, 10f to 10f)
        pts.forEachIndexed { i, (x, y) -> batch.add(InputToolType.STYLUS, x, y, i * 20L, StrokeInput.NO_STROKE_UNIT_LENGTH, 0.4f + i * 0.1f, 0.3f, 1f) }
        return Stroke(BrushCatalog.brush(Tool.Pen, PenStyle.Fountain, 0xFF1E5AA8.toInt(), 5f)!!, batch)
    }

    private fun inputsOf(s: Stroke): List<List<Float>> {
        val i = StrokeInput()
        return List(s.inputs.size) { n -> s.inputs.populate(n, i); listOf(i.x, i.y, i.pressure, i.tiltRadians, i.orientationRadians, i.elapsedTimeMillis.toFloat()) }
    }

    @Test fun restyleKeepsInputsAndColorForEveryPen() {
        val original = square()
        val brushes = StrokeBrushes<Int, Stroke>()
        val stored = BrushCatalog.modelBrush(original.brush)
        for (type in PenType.entries) {
            val brush = StrokeEdits.restyled(stored, type)
            val styled = StrokeEdits.withBrush(original, brush)
            assertEquals(inputsOf(original), inputsOf(styled))
            assertEquals(type.tool == Tool.Highlighter, BrushCatalog.isHighlighter(styled.brush))
            assertEquals(0x1E5AA8, styled.brush.colorIntArgb and 0xFFFFFF)
            brushes.put(styled, brush)
            assertEquals(brush, brushes.brushOf(styled))
        }
    }

    @Test fun restyleAndFillAreOneUndoStepEach() {
        val doc = PagedDocument().apply { reset(listOf(Page(nid, 0)), listOf("p0")) }
        val shape = square()
        doc.editContent(0, addedStrokes = listOf(shape))
        val brushes = StrokeBrushes<Int, Stroke>().apply { put(shape, BrushCatalog.modelBrush(shape.brush)) }

        val filled = StrokeEdits.refilled(shape)
        brushes.put(filled, brushes.brushOf(shape)!!.copy(fillArgb = 0x80FFD54F.toInt()))
        doc.editContent(0, replacements = mapOf(shape to filled))
        assertSame(filled, doc.pages[0].strokes.single())
        assertEquals(0x80FFD54F.toInt(), StrokeFills.of(doc.pages[0].strokes.single()))

        val brush = StrokeEdits.restyled(brushes.brushOf(filled)!!, PenType.Brush)
        val styled = StrokeEdits.withBrush(filled, brush)
        brushes.put(styled, brush)
        doc.editContent(0, replacements = mapOf(filled to styled))
        assertEquals(0x80FFD54F.toInt(), StrokeFills.of(doc.pages[0].strokes.single()))

        doc.undo()
        assertSame(filled, doc.pages[0].strokes.single())
        doc.undo()
        assertSame(shape, doc.pages[0].strokes.single())
        assertNull(StrokeFills.of(shape))
        doc.redo()
        assertTrue(StrokeFills.of(doc.pages[0].strokes.single()) != null)
        assertTrue(doc.canRedo)
    }
}
