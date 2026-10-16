package app.bestnote.engine.canvas.document

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import app.bestnote.core.model.PageRect

/**
 * Marks search matches on one page (REC-03): soft rounded boxes over the ink they cover, drawn in page points
 * so they follow scrolling and zoom. Never hit-tested; [PagedDocumentView] routes input.
 */
@SuppressLint("ViewConstructor")
internal class HighlightLayer(context: Context, private val pages: PagesLayer) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val box = RectF()
    private var page = -1
    private var rects: List<PageRect> = emptyList()

    /** Marker color (ARGB); the box fill uses a fraction of its alpha. */
    var color: Int = DEFAULT_COLOR
        set(value) {
            field = value
            invalidate()
        }

    val isShowing: Boolean get() = rects.isNotEmpty()

    fun show(pageIndex: Int, matches: List<PageRect>) {
        page = pageIndex
        rects = matches
        invalidate()
    }

    fun clear() {
        if (rects.isEmpty()) return
        rects = emptyList()
        page = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (rects.isEmpty()) return
        val layout = pages.layout ?: return
        if (page !in 0 until layout.pageCount) return
        val r = layout.pageRect(page)
        val p = pages.preview
        val s = layout.scale
        val pad = PAD_DP * density
        fill.color = (color and 0x00FFFFFF) or (FILL_ALPHA shl 24)
        line.color = color
        for (m in rects) {
            val l = (r.left - pages.scrollX + m.left * s) * p.scale + p.tx - pad
            val t = (r.top - pages.scrollY + m.top * s) * p.scale + p.ty - pad
            val rr = (r.left - pages.scrollX + m.right * s) * p.scale + p.tx + pad
            val b = (r.top - pages.scrollY + m.bottom * s) * p.scale + p.ty + pad
            box.set(l, t, rr, b)
            canvas.drawRoundRect(box, CORNER_DP * density, CORNER_DP * density, fill)
            canvas.drawRoundRect(box, CORNER_DP * density, CORNER_DP * density, line)
        }
    }

    private companion object {
        const val DEFAULT_COLOR = 0xFFE8A200.toInt()
        const val FILL_ALPHA = 0x44
        const val PAD_DP = 3f
        const val CORNER_DP = 5f
    }
}
