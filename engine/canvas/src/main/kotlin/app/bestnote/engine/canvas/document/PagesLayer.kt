package app.bestnote.engine.canvas.document

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.ViewGroup
import androidx.ink.strokes.InProgressStroke
import app.bestnote.engine.layout.document.DocumentLayout
import app.bestnote.engine.layout.document.DocumentMetrics
import app.bestnote.engine.layout.document.PageWindow

/**
 * The scrolling content of [PagedDocumentView]: recycled [PageSlotView]s for the pages near the
 * viewport (a [PageWindow]) and the dashed "+ New page" card after the last page. Scrolling uses
 * the View scroll offset, so it only moves already-recorded page RenderNodes.
 */
@SuppressLint("ViewConstructor")
internal class PagesLayer(context: Context, private val sheet: SheetStyle, var metrics: DocumentMetrics) : ViewGroup(context) {
    var document: PagedDocument? = null
        set(value) {
            field = value
            relayout()
        }

    /** Null until the view has a width. */
    var layout: DocumentLayout? = null
        private set

    /** Called after the scroll position or the layout changed. */
    var onViewportChanged: (() -> Unit)? = null

    var addCardLabel: String = "+ New page"
        set(value) {
            field = value
            invalidate()
        }
    var addCardColor: Int = 0x8C6A6F7C.toInt()
        set(value) {
            field = value
            invalidate()
        }
    var addCardTypeface: Typeface? = null
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(7f * density, 5f * density), 0f)
    }
    private val cardText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 16f * density
    }
    private val cardRect = RectF()

    private val window = PageWindow(
        overscan = 1,
        create = { PageSlotView(context, sheet).also { addViewInLayout(it, -1, generateDefaultLayoutParams(), true) } },
        bind = { slot, i -> bindSlot(slot, i) },
        unbind = { it.unbind() },
    )

    init {
        setWillNotDraw(false)
        // Pages outside this view's bounds stay visible while a pinch preview scales it down;
        // the document view still clips to its own bounds.
        clipChildren = false
    }

    private fun bindSlot(slot: PageSlotView, index: Int) {
        val l = layout ?: return
        val doc = document ?: return
        slot.backgrounds = doc.backgrounds
        slot.images = doc.images
        slot.bind(index, doc.pages[index], l.pageRect(index), l.scale)
        if (index == wetPage) slot.setWet(wetStroke, wetColor)
        slot.setHidden(if (index == hiddenPage) hidden else emptySet())
        if (index == morphPage) slot.setMorph(morphTarget, morphFrame) else slot.setMorph(null, null)
        slot.setHiddenElements(if (index == hiddenElementsPage) hiddenElements else emptySet())
    }

    private var morphPage = -1
    private var morphTarget: androidx.ink.strokes.Stroke? = null
    private var morphFrame: androidx.ink.strokes.Stroke? = null

    /** INK-08: draws [frame] instead of [target] on page [index] while a snapped shape morphs; nulls stop it. */
    fun setMorph(index: Int, target: androidx.ink.strokes.Stroke?, frame: androidx.ink.strokes.Stroke?) {
        if (morphPage >= 0 && morphPage != index) window.slotFor(morphPage)?.setMorph(null, null)
        morphPage = if (target != null) index else -1
        morphTarget = target
        morphFrame = frame
        if (index >= 0) window.slotFor(index)?.setMorph(target, frame)
    }

    private var hiddenElementsPage = -1
    private var hiddenElements: Set<String> = emptySet()

    /** Leaves the elements [ids] out of page [index]'s drawing (the selection layer draws them while they move); empty restores. */
    fun setHiddenElements(index: Int, ids: Set<String>) {
        if (hiddenElementsPage >= 0 && hiddenElementsPage != index) window.slotFor(hiddenElementsPage)?.setHiddenElements(emptySet())
        hiddenElementsPage = if (ids.isEmpty()) -1 else index
        hiddenElements = ids
        if (index >= 0) window.slotFor(index)?.setHiddenElements(ids)
    }

    private var hiddenPage = -1
    private var hidden: Set<androidx.ink.strokes.Stroke> = emptySet()

    /** Leaves [strokes] out of page [index]'s drawing (the selection layer draws them while they move); empty restores. */
    fun setHidden(index: Int, strokes: Set<androidx.ink.strokes.Stroke>) {
        if (hiddenPage >= 0 && hiddenPage != index) window.slotFor(hiddenPage)?.setHidden(emptySet())
        hiddenPage = if (strokes.isEmpty()) -1 else index
        hidden = strokes
        if (index >= 0) window.slotFor(index)?.setHidden(strokes)
    }

    private var wetPage = -1
    private var wetStroke: InProgressStroke? = null
    private var wetColor = 0

    /** Shows [stroke], a highlighter stroke in progress, under the ink of page [index]; null clears it. */
    fun setWetHighlight(index: Int, stroke: InProgressStroke?, colorArgb: Int) {
        if (wetPage >= 0 && wetPage != index) window.slotFor(wetPage)?.setWet(null, 0)
        wetPage = if (stroke != null) index else -1
        wetStroke = stroke
        wetColor = colorArgb
        window.slotFor(index)?.setWet(stroke, colorArgb)
    }

    /** The layout the pages would have at [zoom], or null before the view has a width. */
    fun layoutAt(zoom: Float): DocumentLayout? {
        val doc = document ?: return null
        if (width <= 0) return null
        return DocumentLayout(doc.pages.map { it.page.size }, width, metrics, ZoomMath.clamp(zoom))
    }

    /** Recomputes page positions (pages added, paper changed, view resized) and rebinds visible pages. */
    fun relayout() {
        val doc = document
        if (width <= 0 || doc == null) {
            layout = null
            window.clear()
            invalidate()
            return
        }
        layout = DocumentLayout(doc.pages.map { it.page.size }, width, metrics, zoom)
        window.clear()
        clampScroll()
        refreshWindow()
        invalidate()
        onViewportChanged?.invoke()
    }

    fun invalidatePage(index: Int) {
        window.slotFor(index)?.invalidate()
    }

    /** Number of page slots that exist (bound or pooled); flat however long the document is. */
    val slotCount: Int get() = childCount

    fun scrollToY(y: Int) = scrollToXY(scrollX, y)

    fun scrollToXY(x: Int, y: Int) {
        scrollTo(x.coerceIn(0, maxScrollX), y.coerceIn(0, maxScrollY))
    }

    val maxScrollY: Int get() = layout?.maxScroll(height) ?: 0

    /** Horizontal room when zoomed in past the viewport width; 0 at fit-to-width and below. */
    val maxScrollX: Int get() = ((layout?.contentWidth ?: 0) - width).coerceAtLeast(0)

    /** Zoom relative to fit-to-width (CANVAS-05); change it with [applyZoom]. */
    var zoom: Float = 1f
        private set

    /**
     * Lays the pages out at [newZoom] and scrolls so the content at screen point ([sx], [sy]), which
     * showed unscaled view point ([vx], [vy]) before, stays there. Pages re-record at the new scale,
     * so ink and paper are vector-sharp at every zoom.
     */
    fun applyZoom(newZoom: Float, vx: Float, vy: Float, sx: Float, sy: Float) {
        val prev = layout
        zoom = ZoomMath.clamp(newZoom)
        if (prev == null) return relayout()
        val (oldX, oldY) = scrollX to scrollY
        relayout()
        val next = layout ?: return
        val (x, y) = ZoomMath.anchoredScroll(prev, oldX, oldY, vx, vy, next, sx, sy)
        scrollToXY(Math.round(x), Math.round(y))
    }

    /** The pinch preview: the recorded pages scaled by the GPU until the gesture settles. */
    var preview: PreviewTransform = PreviewTransform()
        set(value) {
            field = value
            pivotX = 0f
            pivotY = 0f
            scaleX = value.scale
            scaleY = value.scale
            translationX = value.tx
            translationY = value.ty
            if (value.scale < 1f) refreshWindow()
            onViewportChanged?.invoke()
        }

    private fun clampScroll() {
        if (scrollY > maxScrollY || scrollX > maxScrollX) scrollTo(scrollX.coerceAtMost(maxScrollX), scrollY.coerceAtMost(maxScrollY))
    }

    private fun refreshWindow() {
        val l = layout ?: return
        val count = document?.size ?: 0
        // While a pinch zooms out, the scaled-down pages reveal more of the document: bind those too.
        val s = preview.scale
        val top = if (s < 1f) (scrollY - preview.ty / s).toInt() else scrollY
        val h = if (s < 1f) (height / s).toInt() else height
        window.update(l.visiblePages(top.coerceAtLeast(0), h), count)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        refreshWindow()
        onViewportChanged?.invoke()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    /** Reading position to restore after a resize: page and fraction scrolled into it. */
    private var anchor: Pair<Int, Float>? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val old = layout ?: return
        val page = old.currentPage(scrollY, oldh)
        if (page >= 0) anchor = page to (scrollY - old.pageRect(page).top).toFloat() / old.pageRect(page).height
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val current = layout
        if (current == null || current.viewportWidth != width) {
            relayout()
            val (page, fraction) = anchor ?: return
            anchor = null
            val laid = layout ?: return
            if (page < (document?.size ?: 0)) {
                val rect = laid.pageRect(page)
                scrollToY(rect.top + (fraction * rect.height).toInt())
            }
        } else if (changed) {
            anchor = null
            clampScroll()
            refreshWindow()
            onViewportChanged?.invoke()
        }
        // Slots position themselves when bound.
    }

    override fun onDraw(canvas: Canvas) {
        val card = layout?.addCard ?: return
        if (card.height <= 0) return
        val inset = cardStroke.strokeWidth / 2
        cardRect.set(card.left + inset, card.top + inset, card.right - inset, card.bottom - inset)
        cardStroke.color = addCardColor
        val radius = 18f * density
        canvas.drawRoundRect(cardRect, radius, radius, cardStroke)
        cardText.color = addCardColor
        cardText.typeface = addCardTypeface
        val baseline = cardRect.centerY() - (cardText.descent() + cardText.ascent()) / 2
        canvas.drawText(addCardLabel, cardRect.centerX(), baseline, cardText)
    }
}
