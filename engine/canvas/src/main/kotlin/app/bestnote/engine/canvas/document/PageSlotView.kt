package app.bestnote.engine.canvas.document

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.View
import androidx.core.graphics.withTranslation
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.Stroke
import app.bestnote.engine.ink.InkRenderer
import app.bestnote.engine.layout.document.PxRect
import java.util.WeakHashMap

/**
 * One recyclable page sheet (CANVAS-07): soft shadow, paper, template and the page's ink, in a
 * View sized to the page plus room for the shadow. While bound, it only redraws when its page's
 * ink or paper changes; scrolling moves its RenderNode without re-recording anything.
 */
@SuppressLint("ViewConstructor")
internal class PageSlotView(context: Context, private val style: SheetStyle) : View(context) {
    /** Bound page index, or -1 while pooled. */
    var index: Int = -1
        private set

    private var page: DocumentPage? = null
    private var scale = 1f
    private val paper = PaperRenderer()
    private val ink = InkRenderer()
    private val density = resources.displayMetrics.density
    private val sheet = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inkMatrix = Matrix()
    private val tinted = WeakHashMap<Stroke, Stroke>()
    private val drawList = ArrayList<Stroke>()
    private var wet: InProgressStroke? = null
    private var wetColor = 0

    init {
        // Never hit-tested: the document view routes all input.
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun bind(index: Int, page: DocumentPage, rect: PxRect, scale: Float) {
        this.index = index
        this.page = page
        this.scale = scale
        val pad = style.shadowPad
        val l = rect.left - pad
        val t = rect.top - pad
        val r = rect.right + pad
        val b = rect.bottom + pad
        if (l != left || t != top || r != right || b != bottom) {
            measure(MeasureSpec.makeMeasureSpec(r - l, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(b - t, MeasureSpec.EXACTLY))
            layout(l, t, r, b)
        }
        visibility = VISIBLE
        invalidate()
    }

    /**
     * A highlighter stroke in progress on this page (in page points, started with an opaque brush),
     * drawn inside its highlight layer under the ink; [colorArgb] is its real color. Null clears it.
     */
    fun setWet(stroke: InProgressStroke?, colorArgb: Int) {
        if (stroke == null && wet == null) return
        wet = stroke
        wetColor = colorArgb
        invalidate()
    }

    fun unbind() {
        index = -1
        page = null
        wet = null
        drawList.clear()
        visibility = INVISIBLE
    }

    override fun onDraw(canvas: Canvas) {
        val p = page ?: return
        val pad = style.shadowPad.toFloat()
        val w = width - 2 * style.shadowPad
        val h = height - 2 * style.shadowPad
        if (w <= 0 || h <= 0) return
        val colors = PaperColors.of(p.page.paperColor)
        sheet.color = colors.page
        sheet.setShadowLayer(style.shadowRadius, 0f, style.shadowDy, style.shadowColor)
        canvas.drawRect(pad, pad, pad + w, pad + h, sheet)

        val s = scale
        val dpi = density // Canvas has its own `density`: read ours outside the receiver lambda.
        canvas.withTranslation(pad, pad) {
            clipRect(0, 0, w, h)
            paper.draw(this, p.page, s, w, h, dpi)
            scale(s, s)
            // The renderer needs the stroke-to-pixel scale for crisp anti-aliasing (translation doesn't matter).
            inkMatrix.setScale(s, s)
            ink.draw(this, readable(p.strokes, colors), inkMatrix, wet, PaperInk.colorOn(wetColor, colors))
        }
    }

    /** [strokes] with every ink color made readable on [paper] ([PaperInk]); unchanged strokes are reused. */
    private fun readable(strokes: List<Stroke>, paper: PaperColors): List<Stroke> {
        drawList.clear()
        for (s in strokes) {
            val color = s.brush.colorIntArgb
            val want = PaperInk.colorOn(color, paper)
            if (want == color) {
                drawList += s
            } else {
                val cached = tinted[s]
                drawList += if (cached != null && cached.brush.colorIntArgb == want) {
                    cached
                } else {
                    s.copy(s.brush.copyWithColorIntArgb(want)).also { tinted[s] = it }
                }
            }
        }
        return drawList
    }
}

/** Sheet chrome shared by all slots, in pixels. */
internal data class SheetStyle(val shadowRadius: Float, val shadowDy: Float, val shadowColor: Int) {
    val shadowPad: Int get() = (shadowRadius + shadowDy).toInt() + 1
}
