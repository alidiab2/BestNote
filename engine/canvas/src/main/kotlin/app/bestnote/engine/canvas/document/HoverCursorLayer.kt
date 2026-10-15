package app.bestnote.engine.canvas.document

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View
import app.bestnote.engine.stylus.HoverCursorStyle

/** Stylus hover cursor over the document: ring + faint fill + center dot, or the dashed eraser ring. */
internal class HoverCursorLayer(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private var x = 0f
    private var y = 0f
    private var style: HoverCursorStyle? = null
    private var dashEffect: DashPathEffect? = null
    private var dashKey = 0f to 0f

    private fun dash(on: Float, off: Float): DashPathEffect {
        val key = on to off
        return dashEffect?.takeIf { dashKey == key } ?: DashPathEffect(floatArrayOf(on, off), 0f).also {
            dashEffect = it
            dashKey = key
        }
    }

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
        ring.pathEffect = if (s.dashPx > 0f) dash(s.dashPx, s.gapPx) else null
        canvas.drawCircle(x, y, s.ringRadius, ring)
        if (s.dotRadius > 0f) {
            dot.color = s.colorArgb
            canvas.drawCircle(x, y, s.dotRadius, dot)
        }
    }
}
