package app.bestnote.engine.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.Page
import app.bestnote.engine.canvas.document.PaperColors
import app.bestnote.engine.canvas.document.PaperPainter
import app.bestnote.engine.layout.paper.LineRole
import app.bestnote.engine.layout.paper.PaperGeometry
import kotlin.math.max
import kotlin.math.roundToInt

/** Draws one page (paper, template, ink) for export. */
internal object PageGraphics {
    private val ink = InkPainter()

    /**
     * A PNG-ready bitmap of [page], [scale] pixels per point (2 = about 144 dpi), capped so the long
     * side stays within [MAX_SIDE_PX]. The caller owns (and recycles) it.
     */
    fun bitmap(page: Page, strokes: List<InkStroke>, scale: Float = DEFAULT_PNG_SCALE): Bitmap {
        val s = scale.coerceAtMost(MAX_SIDE_PX / max(page.widthPt, page.heightPt))
        val w = (page.widthPt * s).roundToInt().coerceAtLeast(1)
        val h = (page.heightPt * s).roundToInt().coerceAtLeast(1)
        val bitmap = createBitmap(w, h)
        val canvas = Canvas(bitmap)
        PaperPainter.draw(canvas, page, s, w, h)
        canvas.scale(s, s)
        ink.draw(canvas, strokes, PaperColors.of(page.paperColor))
        return bitmap
    }

    /**
     * Draws [page] into a PDF page canvas (units are points). Paper lines are hairline vector
     * strokes rather than the screen renderer's pixel-snapped ones.
     */
    fun drawVector(canvas: Canvas, page: Page, strokes: List<InkStroke>) {
        val colors = PaperColors.of(page.paperColor)
        canvas.drawColor(colors.page)
        val geometry = PaperGeometry.of(page.template, page.size)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = PDF_LINE_PT }
        for (role in LineRole.entries) {
            line.color = colors.line(role)
            for (l in geometry.lines) if (l.role == role) canvas.drawLine(l.x0, l.y0, l.x1, l.y1, line)
        }
        geometry.dots?.let { grid ->
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = colors.dot }
            val r = max(0.6f, grid.spacing * 0.04f)
            for (row in 0 until grid.rows) for (col in 0 until grid.cols) {
                canvas.drawCircle(grid.left + col * grid.spacing, grid.top + row * grid.spacing, r, dot)
            }
        }
        ink.draw(canvas, strokes, colors)
    }

    const val DEFAULT_PNG_SCALE = 2f
    const val MAX_SIDE_PX = 4096f
    private const val PDF_LINE_PT = 0.6f
}
