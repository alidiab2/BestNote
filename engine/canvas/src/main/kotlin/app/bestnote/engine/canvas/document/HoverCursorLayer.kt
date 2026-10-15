package app.bestnote.engine.canvas.document

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import app.bestnote.engine.stylus.HoverCursorStyle

/** Stylus hover cursor over the document: ring + faint fill + center dot, or the eraser outline. */
internal class HoverCursorLayer(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private var x = 0f
    private var y = 0f
    private var style: HoverCursorStyle? = null

    fun show(x: Float, y: Float, style: HoverCursorStyle) {
        this.x = x
        this.y = y
        this.style = style
        invalidate()
    }

    fun hide() {
        if (style == null) return
        style = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = style ?: return
        fill.color = s.fillArgb
        canvas.drawCircle(x, y, s.ringRadius, fill)
        ring.color = s.colorArgb
        ring.strokeWidth = s.ringStroke
        canvas.drawCircle(x, y, s.ringRadius, ring)
        if (s.dotRadius > 0f) {
            dot.color = s.colorArgb
            canvas.drawCircle(x, y, s.dotRadius, dot)
        }
    }
}
