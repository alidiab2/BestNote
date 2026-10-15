package app.bestnote.engine.canvas.document

import android.graphics.Canvas
import android.graphics.Paint
import app.bestnote.core.model.Page
import app.bestnote.engine.layout.paper.LineRole
import app.bestnote.engine.layout.paper.PaperGeometry
import app.bestnote.engine.layout.paper.PixelSnap
import app.bestnote.engine.layout.paper.TemplateGeometry
import kotlin.math.max

/**
 * Prints a page's paper (color + template) in device pixels. Lines are snapped to pixel centers and
 * drawn 1 px wide without anti-aliasing, so they are exactly one physical pixel at every zoom
 * (PAGES-01). Line coordinates are computed once per page size/zoom and reused for every redraw.
 */
internal class PaperRenderer {
    private val linePaint = Paint().apply {
        isAntiAlias = false
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    private var key: Key? = null
    private var batches: Map<LineRole, FloatArray> = emptyMap()
    private var dots: FloatArray = FloatArray(0)
    private var dotDiameter = 0f

    private data class Key(val template: Any, val widthPt: Float, val heightPt: Float, val scale: Float, val w: Int, val h: Int)

    /**
     * Draws [page] filling `(0, 0, widthPx, heightPx)` of [canvas] (which must be pixel-aligned there).
     * [scale] is pixels per point; [density] sizes the dots.
     */
    fun draw(canvas: Canvas, page: Page, scale: Float, widthPx: Int, heightPx: Int, density: Float) {
        val colors = PaperColors.of(page.paperColor)
        canvas.drawColor(colors.page)
        prepare(page, scale, widthPx, heightPx, density)
        for ((role, pts) in batches) {
            linePaint.color = colors.line(role)
            canvas.drawLines(pts, linePaint)
        }
        if (dots.isNotEmpty()) {
            dotPaint.color = colors.dot
            dotPaint.strokeWidth = dotDiameter
            canvas.drawPoints(dots, dotPaint)
        }
    }

    private fun prepare(page: Page, scale: Float, w: Int, h: Int, density: Float) {
        val k = Key(page.template, page.widthPt, page.heightPt, scale, w, h)
        if (k == key) return
        key = k
        val geometry: TemplateGeometry = PaperGeometry.of(page.template, page.size)
        batches = geometry.lines.groupBy { it.role }.mapValues { (_, lines) ->
            val out = FloatArray(lines.size * 4)
            var i = 0
            for (l in lines) {
                if (l.horizontal) {
                    val y = PixelSnap.centerIn(l.y0, scale, h)
                    out[i++] = (l.x0 * scale); out[i++] = y; out[i++] = (l.x1 * scale); out[i++] = y
                } else {
                    val x = PixelSnap.centerIn(l.x0, scale, w)
                    out[i++] = x; out[i++] = (l.y0 * scale); out[i++] = x; out[i++] = (l.y1 * scale)
                }
            }
            out
        }
        val grid = geometry.dots
        if (grid == null || grid.count == 0) {
            dots = FloatArray(0)
        } else {
            // Dots scale with the page but never vanish below ~1.5 physical px.
            dotDiameter = max(1.5f, max(0.9f * density, grid.spacing * 0.08f * scale)).coerceAtMost(grid.spacing * scale * 0.4f)
            dots = FloatArray(grid.count * 2)
            var i = 0
            for (r in 0 until grid.rows) {
                val y = PixelSnap.center(grid.top + r * grid.spacing, scale)
                for (c in 0 until grid.cols) {
                    dots[i++] = PixelSnap.center(grid.left + c * grid.spacing, scale)
                    dots[i++] = y
                }
            }
        }
    }
}

/** Prints a page's paper (color + template) into `(0, 0, widthPx, heightPx)`, e.g. for thumbnails. */
object PaperPainter {
    fun draw(canvas: Canvas, page: Page, scale: Float, widthPx: Int, heightPx: Int, density: Float = 1f) =
        PaperRenderer().draw(canvas, page, scale, widthPx, heightPx, density)

    /** The page color [page] is printed on, as ARGB. */
    fun pageColor(page: Page): Int = PaperColors.of(page.paperColor).page
}
