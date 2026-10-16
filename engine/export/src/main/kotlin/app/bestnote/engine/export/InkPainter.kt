package app.bestnote.engine.export

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import app.bestnote.core.model.InkStroke
import app.bestnote.engine.canvas.document.PaperColors
import app.bestnote.engine.canvas.document.PaperInk
import app.bestnote.engine.ink.BrushCatalog
import app.bestnote.engine.ink.InkFamily

/**
 * Draws stored strokes onto any [Canvas] in page points (origin top-left): a PNG bitmap or a PDF
 * page. Everything is a stroked path, so on a PDF canvas the ink stays vector and sharp at any zoom.
 * Highlighters go first, under the ink.
 */
internal class InkPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    fun draw(canvas: Canvas, strokes: List<InkStroke>, paper: PaperColors) {
        strokes.forEach { drawFill(canvas, it, paper) }
        val (marker, ink) = strokes.partition { StrokeGeometry.familyOf(it.brush) == InkFamily.Highlighter }
        marker.forEach { drawHighlighter(canvas, it, paper) }
        ink.forEach { drawInk(canvas, it, paper) }
    }

    /** INK-11: the filled shape of a closed stroke, under all ink. */
    private fun drawFill(canvas: Canvas, stroke: InkStroke, paper: PaperColors) {
        val argb = stroke.brush.fillArgb ?: return
        val s = stroke.samples
        if (s.size < 3) return
        fill.color = PaperInk.colorOn(argb, paper)
        path.rewind()
        path.moveTo(s[0].x, s[0].y)
        for (i in 1 until s.size) path.lineTo(s[i].x, s[i].y)
        path.close()
        canvas.drawPath(path, fill)
    }

    private fun colorOf(stroke: InkStroke, paper: PaperColors): Int {
        val spec = requireNotNull(BrushCatalog.spec(stroke.brush.tool, stroke.brush.penStyle))
        return BrushCatalog.applyOpacity(PaperInk.colorOn(stroke.brush.colorArgb, paper), spec.opacity)
    }

    private fun drawHighlighter(canvas: Canvas, stroke: InkStroke, paper: PaperColors) {
        paint.color = colorOf(stroke, paper)
        paint.strokeWidth = StrokeGeometry.baseWidth(stroke.brush)
        paint.strokeCap = Paint.Cap.BUTT
        path.rewind()
        val s = stroke.samples
        path.moveTo(s[0].x, s[0].y)
        if (s.size == 1) path.lineTo(s[0].x + 0.01f, s[0].y)
        for (i in 1 until s.size) path.lineTo(s[i].x, s[i].y)
        canvas.drawPath(path, paint)
        paint.strokeCap = Paint.Cap.ROUND
    }

    private fun drawInk(canvas: Canvas, stroke: InkStroke, paper: PaperColors) {
        val color = colorOf(stroke, paper)
        val segments = StrokeGeometry.segments(stroke)
        if (segments.size == 1 && segments[0].x0 == segments[0].x1 && segments[0].y0 == segments[0].y1) {
            fill.color = color
            canvas.drawCircle(segments[0].x0, segments[0].y0, segments[0].width / 2f, fill)
            return
        }
        paint.color = color
        for (run in StrokeGeometry.runs(segments)) {
            paint.strokeWidth = run.width
            path.rewind()
            path.moveTo(run.points[0], run.points[1])
            var i = 2
            while (i < run.points.size) {
                path.lineTo(run.points[i], run.points[i + 1])
                i += 2
            }
            canvas.drawPath(path, paint)
        }
    }
}
