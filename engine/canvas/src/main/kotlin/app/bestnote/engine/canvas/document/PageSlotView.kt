package app.bestnote.engine.canvas.document

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
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

    /** Source of PDF page rasters (PDF-01); null for plain notebooks. */
    var backgrounds: PageBackgrounds? = null
    private val backgroundReady = Runnable { if (index >= 0) invalidate() }
    private val backgroundSrc = Rect()
    private val backgroundDst = Rect()
    private val backgroundPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var scale = 1f

    /** Source of the pictures of image elements (MEDIA-01). */
    var images: ElementImages? = null
    private val imageReady = Runnable { if (index >= 0) invalidate() }
    private val elementPainter = ElementPainter()
    private val paper = PaperRenderer()
    private val ink = InkRenderer()
    private val density = resources.displayMetrics.density
    private val sheet = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inkMatrix = Matrix()
    private val tinted = WeakHashMap<Stroke, Stroke>()
    private val drawList = ArrayList<Stroke>()
    private var wet: InProgressStroke? = null
    private var wetColor = 0

    /** INK-08 snap morph: [morphFrame] is drawn in place of [morphTarget] while the shape settles. */
    private var morphTarget: Stroke? = null
    private var morphFrame: Stroke? = null

    /** Strokes the selection layer is dragging (CANVAS-09): drawn there, so left out here. */
    private var hidden: Set<Stroke> = emptySet()

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

    /** Elements the selection layer is dragging: drawn there, so left out here. */
    private var hiddenElements: Set<String> = emptySet()

    fun setHiddenElements(ids: Set<String>) {
        if (ids == hiddenElements) return
        hiddenElements = ids
        invalidate()
    }

    /** Strokes drawn faded: ink written after the audio playhead (MEDIA-05). */
    private var dimmed: Set<Stroke> = emptySet()

    fun setDimmed(strokes: Set<Stroke>) {
        if (strokes == dimmed) return
        dimmed = strokes
        invalidate()
    }

    fun setHidden(strokes: Set<Stroke>) {
        if (strokes === hidden) return
        hidden = strokes
        invalidate()
    }

    fun setMorph(target: Stroke?, frame: Stroke?) {
        if (target == null && morphTarget == null) return
        morphTarget = target
        morphFrame = frame
        invalidate()
    }

    fun unbind() {
        index = -1
        page = null
        wet = null
        morphTarget = null
        morphFrame = null
        hidden = emptySet()
        hiddenElements = emptySet()
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
            val raster = if (p.page.pdf != null) backgrounds?.background(p.page, w, backgroundReady) else null
            if (p.page.pdf != null) {
                // A PDF page is its own paper: white until the first raster arrives, no template lines.
                drawColor(colors.page)
                if (raster != null) {
                    backgroundSrc.set(0, 0, raster.width, raster.height)
                    backgroundDst.set(0, 0, w, h)
                    drawBitmap(raster, backgroundSrc, backgroundDst, backgroundPaint)
                }
            } else {
                paper.draw(this, p.page, s, w, h, dpi)
            }
            scale(s, s)
            // Text and pictures lie on the paper, under the handwriting.
            if (p.elements.isNotEmpty()) elementPainter.draw(this, p.elements, s, images, imageReady, hiddenElements) { PaperInk.colorOn(it, colors) }
            // The renderer needs the stroke-to-pixel scale for crisp anti-aliasing (translation doesn't matter).
            inkMatrix.setScale(s, s)
            ink.draw(this, readable(p.strokes, colors), inkMatrix, wet, PaperInk.colorOn(wetColor, colors))
        }
    }

    /** [strokes] with every ink color made readable on [paper] ([PaperInk]); unchanged strokes are reused. */
    private fun readable(strokes: List<Stroke>, paper: PaperColors): List<Stroke> {
        drawList.clear()
        for (stroke in strokes) {
            if (stroke in hidden) continue
            val s = if (stroke === morphTarget) morphFrame ?: stroke else stroke
            val color = s.brush.colorIntArgb
            var want = PaperInk.colorOn(color, paper)
            if (s in dimmed) want = (want and 0x00FFFFFF) or ((want ushr 24) * DIM_ALPHA / 255 shl 24)
            if (want == color) {
                drawList += s
            } else {
                val cached = tinted[s]
                drawList += if (cached != null && cached.brush.colorIntArgb == want) {
                    cached
                } else if (s === morphFrame) {
                    s.copy(s.brush.copyWithColorIntArgb(want)) // a frame is drawn once: don't cache it
                } else {
                    s.copy(s.brush.copyWithColorIntArgb(want)).also { tinted[s] = it }
                }
            }
        }
        return drawList
    }
}

/** Ink written after the audio playhead keeps this share of its opacity. */
private const val DIM_ALPHA = 56

/** Sheet chrome shared by all slots, in pixels. */
internal data class SheetStyle(val shadowRadius: Float, val shadowDy: Float, val shadowColor: Int) {
    val shadowPad: Int get() = (shadowRadius + shadowDy).toInt() + 1
}
