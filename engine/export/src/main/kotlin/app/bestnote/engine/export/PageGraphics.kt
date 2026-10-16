package app.bestnote.engine.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.Page
import app.bestnote.core.model.PageElement
import app.bestnote.engine.canvas.document.ElementImages
import app.bestnote.engine.canvas.document.ElementPainter
import app.bestnote.engine.canvas.document.PaperInk
import app.bestnote.engine.canvas.document.PaperColors
import app.bestnote.engine.canvas.document.PaperPainter
import app.bestnote.engine.layout.paper.LineRole
import app.bestnote.engine.layout.paper.PaperGeometry
import kotlin.math.max
import kotlin.math.roundToInt

/** Draws one page (paper, template, ink) for export. */
internal object PageGraphics {
    private val ink = InkPainter()
    private val elementPainter = ElementPainter()
    private val photo = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * A PNG-ready bitmap of [page], [scale] pixels per point (2 = about 144 dpi), capped so the long
     * side stays within [MAX_SIDE_PX]. The caller owns (and recycles) it.
     */
    fun bitmap(page: Page, strokes: List<InkStroke>, scale: Float = DEFAULT_PNG_SCALE, background: Bitmap? = null, elements: List<PageElement> = emptyList(), images: ElementImages? = null): Bitmap {
        val s = scale.coerceAtMost(MAX_SIDE_PX / max(page.widthPt, page.heightPt))
        val w = (page.widthPt * s).roundToInt().coerceAtLeast(1)
        val h = (page.heightPt * s).roundToInt().coerceAtLeast(1)
        val bitmap = createBitmap(w, h)
        val canvas = Canvas(bitmap)
        if (background != null) canvas.drawBitmap(background, null, Rect(0, 0, w, h), photo) else PaperPainter.draw(canvas, page, s, w, h)
        canvas.scale(s, s)
        val colors = PaperColors.of(page.paperColor)
        drawElements(canvas, elements, s, images, colors)
        ink.draw(canvas, strokes, colors)
        return bitmap
    }

    /**
     * Draws [page] into a PDF page canvas (units are points). Paper lines are hairline vector
     * strokes rather than the screen renderer's pixel-snapped ones.
     */
    fun drawVector(canvas: Canvas, page: Page, strokes: List<InkStroke>, background: Bitmap? = null, elements: List<PageElement> = emptyList(), images: ElementImages? = null) {
        val colors = PaperColors.of(page.paperColor)
        if (background != null) {
            // An imported PDF page: its rendering is the paper; the ink on top stays vector.
            canvas.drawBitmap(background, null, RectF(0f, 0f, page.widthPt, page.heightPt), photo)
            drawElements(canvas, elements, PDF_ELEMENT_SCALE, images, PaperColors.White)
            ink.draw(canvas, strokes, PaperColors.White)
            return
        }
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
        drawElements(canvas, elements, PDF_ELEMENT_SCALE, images, colors)
        ink.draw(canvas, strokes, colors)
    }

    /** Text boxes and pictures (TEXT-01, MEDIA-01) lie on the paper, under the ink. [pxPerPt] picks picture sizes. */
    private fun drawElements(canvas: Canvas, elements: List<PageElement>, pxPerPt: Float, images: ElementImages?, colors: PaperColors) {
        if (elements.isEmpty()) return
        elementPainter.draw(canvas, elements, pxPerPt, images, Runnable { }) { PaperInk.colorOn(it, colors) }
    }

    /** A PDF page is in points but pictures print at about 2.8 px per point. */
    private const val PDF_ELEMENT_SCALE = 2.8f
    const val DEFAULT_PNG_SCALE = 2f
    const val MAX_SIDE_PX = 4096f
    private const val PDF_LINE_PT = 0.6f
}
