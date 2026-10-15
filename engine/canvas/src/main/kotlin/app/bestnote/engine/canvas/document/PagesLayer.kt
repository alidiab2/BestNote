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
        clipChildren = true
    }

    private fun bindSlot(slot: PageSlotView, index: Int) {
        val l = layout ?: return
        val doc = document ?: return
        slot.backgrounds = doc.backgrounds
        slot.bind(index, doc.pages[index], l.pageRect(index), l.scale)
        if (index == wetPage) slot.setWet(wetStroke, wetColor)
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

    /** Recomputes page positions (pages added, paper changed, view resized) and rebinds visible pages. */
    fun relayout() {
        val doc = document
        if (width <= 0 || doc == null) {
            layout = null
            window.clear()
            invalidate()
            return
        }
        layout = DocumentLayout(doc.pages.map { it.page.size }, width, metrics)
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

    fun scrollToY(y: Int) {
        val max = layout?.maxScroll(height) ?: 0
        scrollTo(0, y.coerceIn(0, max))
    }

    val maxScrollY: Int get() = layout?.maxScroll(height) ?: 0

    private fun clampScroll() {
        if (scrollY > maxScrollY) scrollTo(0, maxScrollY)
    }

    private fun refreshWindow() {
        val l = layout ?: return
        val count = document?.size ?: 0
        window.update(l.visiblePages(scrollY, height), count)
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
