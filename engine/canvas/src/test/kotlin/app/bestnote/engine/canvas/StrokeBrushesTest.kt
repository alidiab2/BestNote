package app.bestnote.engine.canvas

import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrokeBrushesTest {
    private class Stroke

    @Test fun brushIsTheOneCapturedAtPenDownEvenIfTheToolChangesMidStroke() {
        val brushes = StrokeBrushes<Int, Stroke>()
        var tool = Tool.Pen
        var style = PenStyle.Brush
        var color = 0xFF112233.toInt()
        var size = 7f
        val atPenDown = StrokeBrushes.capture(tool, style, color, size)!!
        brushes.begin(1, atPenDown)
        // Mid-stroke: the user (or the side button) switches everything.
        tool = Tool.Highlighter
        style = PenStyle.Ball
        color = 0xFFFFD54F.toInt()
        size = 30f
        val stroke = Stroke()
        brushes.finish(1, stroke)
        assertEquals(InkBrush(Tool.Pen, PenStyle.Brush, 0xFF112233.toInt(), 7f, "brush-pen.v1"), brushes.brushOf(stroke))
        // The next stroke picks up the new settings.
        brushes.begin(2, StrokeBrushes.capture(tool, style, color, size)!!)
        val next = Stroke()
        brushes.finish(2, next)
        assertEquals(Tool.Highlighter, brushes.brushOf(next)!!.tool)
        assertEquals("highlighter-chisel.v1", brushes.brushOf(next)!!.family)
    }

    @Test fun cancelledAndUnknownStrokesHaveNoBrush() {
        val brushes = StrokeBrushes<Int, Stroke>()
        brushes.begin(1, StrokeBrushes.capture(Tool.Pencil, PenStyle.Fountain, 0, 5f)!!)
        brushes.cancel(1)
        val s = Stroke()
        brushes.finish(1, s)
        assertNull(brushes.brushOf(s))
        assertNull(StrokeBrushes.capture(Tool.Eraser, PenStyle.Fountain, 0, 5f))
    }

    @Test fun fillFollowsCopiesAndIsDroppedFromErasedPieces() {
        val brushes = StrokeBrushes<Int, Stroke>()
        val shape = Stroke()
        brushes.put(shape, InkBrush(Tool.Pen, PenStyle.Ball, 0xFF000000.toInt(), 4f, "ballpoint.v1", fillArgb = 0x80336699.toInt()))
        assertEquals(0x80336699.toInt(), StrokeFills.of(shape))
        val moved = Stroke()
        brushes.derive(moved, shape) { it }
        assertEquals(0x80336699.toInt(), StrokeFills.of(moved))
        assertEquals(0x80336699.toInt(), brushes.brushOf(moved)!!.fillArgb)
        val cleared = Stroke()
        brushes.derive(cleared, moved) { it.copy(fillArgb = null) }
        assertNull(StrokeFills.of(cleared))
        val piece = Stroke()
        brushes.inherit(piece, shape)
        assertNull(StrokeFills.of(piece))
        assertNull(brushes.brushOf(piece)!!.fillArgb)
        // A copy of a stroke the brushes never saw still takes its fill along.
        val loaded = Stroke().also { StrokeFills.set(it, 0x40112233) }
        val loadedCopy = Stroke()
        brushes.derive(loadedCopy, loaded) { it }
        assertEquals(0x40112233, StrokeFills.of(loadedCopy))
    }
}
