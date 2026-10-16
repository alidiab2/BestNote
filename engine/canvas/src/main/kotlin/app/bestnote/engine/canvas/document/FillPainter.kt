package app.bestnote.engine.canvas.document

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import app.bestnote.engine.canvas.StrokeFills

/**
 * INK-11: draws the fill of closed strokes (the shape their centerline outlines) in page points.
 * Call it before the ink so fills sit under every stroke. One per thread (it reuses a path).
 */
internal class FillPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val input = StrokeInput()

    /** Fills every stroke of [strokes] that has a fill, in order; the fill color is made readable on [paper]. */
    fun draw(canvas: Canvas, strokes: List<Stroke>, paper: PaperColors) {
        for (stroke in strokes) {
            val fill = StrokeFills.of(stroke) ?: continue
            val inputs = stroke.inputs
            if (inputs.size < 3) continue
            path.rewind()
            for (i in 0 until inputs.size) {
                inputs.populate(i, input)
                if (i == 0) path.moveTo(input.x, input.y) else path.lineTo(input.x, input.y)
            }
            path.close()
            paint.color = PaperInk.colorOn(fill, paper)
            canvas.drawPath(path, paint)
        }
    }
}
