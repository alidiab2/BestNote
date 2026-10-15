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
}
