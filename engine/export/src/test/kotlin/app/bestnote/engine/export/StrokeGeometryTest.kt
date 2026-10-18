package app.bestnote.engine.export

import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.StylusSample
import app.bestnote.core.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeGeometryTest {
    private fun stroke(tool: Tool = Tool.Pen, style: PenStyle = PenStyle.Fountain, n: Int, pressure: (Int) -> Float = { 0.6f }) = InkStroke(
        InkBrush(tool, style, 0xFF000000.toInt(), 4f),
        List(n) { i -> StylusSample(10f + i * 3f, 20f + (i % 3), pressure(i), 0f, 0f, i * 8L) },
    )

    @Test fun `a single sample is a dot`() {
        val seg = StrokeGeometry.segments(stroke(n = 1)).single()
        assertEquals(seg.x0, seg.x1, 0f)
        assertTrue("dots are bold", seg.width > StrokeGeometry.baseWidth(stroke(n = 1).brush))
    }

    @Test fun `a long stroke has one segment per step and ends thinner than its middle`() {
        val segs = StrokeGeometry.segments(stroke(n = 60))
        assertEquals(59, segs.size)
        val mid = segs[30].width
        assertTrue("start tapers", segs.first().width < mid)
        assertTrue("end tapers", segs.last().width < mid)
    }

    @Test fun `harder pressure draws wider`() {
        val soft = StrokeGeometry.segments(stroke(style = PenStyle.Ball, n = 40) { 0.1f })[20].width
        val hard = StrokeGeometry.segments(stroke(style = PenStyle.Ball, n = 40) { 0.9f })[20].width
        assertTrue(hard > soft)
    }

    @Test fun `runs merge similar widths and split on big changes`() {
        val steady = StrokeGeometry.runs(StrokeGeometry.segments(stroke(style = PenStyle.Ball, n = 80)))
        assertEquals(1, steady.size)
        assertEquals(80 * 2, steady[0].points.size)
        val varying = StrokeGeometry.runs(StrokeGeometry.segments(stroke(style = PenStyle.Ball, n = 80) { if (it < 40) 0.1f else 1f }))
        assertTrue(varying.size >= 2)
    }

    @Test fun `highlighter uses its own family and is much wider than a pen`() {
        val hl = stroke(tool = Tool.Highlighter, n = 5)
        assertEquals(app.bestnote.engine.ink.InkFamily.Highlighter, StrokeGeometry.familyOf(hl.brush))
        assertTrue(StrokeGeometry.baseWidth(hl.brush) > 5 * StrokeGeometry.baseWidth(stroke(n = 5).brush))
    }

    private fun pencil(n: Int, tilt: (Int) -> Float, pressure: (Int) -> Float = { 0.6f }) = InkStroke(
        InkBrush(Tool.Pencil, PenStyle.Fountain, 0xFF333333.toInt(), 4f, family = "pencil.v1"),
        List(n) { i -> StylusSample(10f + i * 3f, 20f, pressure(i), tilt(i), 0f, i * 8L) },
    )

    @Test fun `tilting the pencil widens and lightens the exported stroke`() {
        val upright = StrokeGeometry.segments(pencil(60, { 0.7f }))[30]
        val flat = StrokeGeometry.segments(pencil(60, { 1.45f }))[30]
        assertEquals(upright.width * app.bestnote.engine.ink.BrushTuning.Pencil.TILT_MAX_WIDTH, flat.width, 1e-3f)
        assertTrue(flat.opacity < upright.opacity)
    }

    @Test fun `pencil pressure sets darkness and runs split where it changes`() {
        val segs = StrokeGeometry.segments(pencil(80, { 0.7f }) { i -> if (i < 40) 0.15f else 0.95f })
        assertTrue(segs[60].opacity > segs[20].opacity + 0.3f)
        val runs = StrokeGeometry.runs(segs)
        assertTrue(runs.size >= 2)
        assertTrue(runs.last().opacity > runs.first().opacity)
        // Other pens are never see-through.
        assertTrue(StrokeGeometry.segments(stroke(n = 30)).all { it.opacity == 1f })
    }
}
