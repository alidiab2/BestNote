package app.bestnote.engine.canvas.document

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.Typeface
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.PaperSizes
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.Tool
import app.bestnote.engine.canvas.StrokeBrushes
import app.bestnote.engine.ink.BrushCatalog
import app.bestnote.engine.ink.InkPalette
import app.bestnote.engine.layout.document.DocumentMetrics
import app.bestnote.engine.stylus.HoverButtonGestures
import app.bestnote.engine.stylus.HoverCursorStyle
import app.bestnote.engine.stylus.HoverTracker
import app.bestnote.engine.stylus.InputDecision
import app.bestnote.engine.stylus.PalmRejection
import app.bestnote.engine.stylus.PointerTool
import app.bestnote.engine.stylus.SideButtonTool
import app.bestnote.engine.stylus.StylusAction
import app.bestnote.engine.stylus.StylusEvent
import app.bestnote.engine.stylus.StylusEventMapper
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A notebook as a vertical stack of real pages (CANVAS-07, PAGES-01/02).
 *
 * Layers, bottom to top:
 *  - [PagesLayer]: recycled page sheets (shadow, paper, template, dry ink) on a transparent
 *    background, so the host draws the Atelier desk behind them, then a dashed "+ New page" card;
 *  - wet ink: one androidx.ink [InProgressStrokesView] for the whole viewport. Each stroke starts
 *    with a page-to-view transform, so its inputs are recorded in that page's local points;
 *  - the stylus hover cursor.
 *
 * Input: pens (and fingers when allowed by [PalmRejection]) write on the page they touch down
 * on; fingers that may not write, and any two-finger gesture, scroll with fling; the mouse wheel
 * scrolls. Nothing scrolls while a stroke is in progress. Ink belongs to the [PagedDocument].
 */
@SuppressLint("ViewConstructor")
class PagedDocumentView(context: Context) : FrameLayout(context), InProgressStrokesFinishedListener {

    /** Reports the page the reader is on (0-based) and the page count, e.g. for a "3 / 12" chip. */
    fun interface PageIndicatorListener {
        fun onPageIndicator(current: Int, count: Int)
    }

    var tool: Tool = Tool.Pen
    var penStyle: PenStyle = PenStyle.Fountain
    var colorArgb: Int = InkPalette.BLACK

    /** Brush size in the same units as [BrushCatalog.DEFAULT_SIZE]; converted to page points. */
    var brushSize: Float = BrushCatalog.DEFAULT_SIZE

    var sideButtonTool: Tool?
        get() = sideButton.temporaryTool
        set(value) {
            sideButton.temporaryTool = value
        }

    /** Called with the temporary tool when the side button takes over, and null when it lets go. */
    var temporaryToolListener: ((Tool?) -> Unit)? = null

    /** Called with true when a pen or finger starts inking/erasing and false once every pointer lifted. */
    fun interface WritingListener {
        fun onWritingChanged(writing: Boolean)
    }

    var writingListener: WritingListener? = null

    /** S Pen side button clicked while hovering: open the quick palette at ([x], [y]) in view px. */
    fun interface QuickPaletteListener {
        fun onQuickPalette(x: Float, y: Float)
    }

    var quickPaletteListener: QuickPaletteListener? = null

    /** Setting "Side button click while hovering": false = Off. */
    var quickPaletteEnabled: Boolean
        get() = hoverGestures.enabled
        set(value) {
            hoverGestures.enabled = value
        }

    /** Setting: double-click the side button while hovering = undo. */
    var hoverDoubleClickUndo: Boolean
        get() = hoverGestures.doubleClickUndo
        set(value) {
            hoverGestures.doubleClickUndo = value
        }

    var stylusOnly: Boolean
        get() = palm.stylusOnly
        set(value) {
            palm.stylusOnly = value
        }

    var pageIndicatorListener: PageIndicatorListener? = null
        set(value) {
            field = value
            lastIndicator = -2 to -2
            reportIndicator()
        }

    /** Duration of the scroll that reveals a newly added page; the host passes a Motion token. */
    var revealDurationMs: Int = 0

    var addCardLabel: String
        get() = pages.addCardLabel
        set(value) {
            pages.addCardLabel = value
        }

    var addCardColor: Int
        get() = pages.addCardColor
        set(value) {
            pages.addCardColor = value
        }

    var addCardTypeface: Typeface?
        get() = pages.addCardTypeface
        set(value) {
            pages.addCardTypeface = value
        }

    var document: PagedDocument? = null
        set(value) {
            if (field === value) return
            cancelAllInput()
            field?.removeListener(documentListener)
            field = value
            value?.addListener(documentListener)
            pages.document = value
        }

    /** The page the reader is on, or -1 for an empty document. */
    val currentPage: Int get() = pages.layout?.currentPage(pages.scrollY, pages.height) ?: -1

    /** Height / width of the area one page can fill: the shape of a "fit to screen" page. */
    val viewportAspect: Float
        get() {
            val w = width - 2 * metrics.sidePad
            val h = height - metrics.topPad - metrics.gap
            return if (w > 0 && h > 0) h.toFloat() / w else PaperSizes.A4.heightPt / PaperSizes.A4.widthPt
        }

    /** Page slot views currently alive (for tests and diagnostics): stays flat however long the notebook is. */
    val pageSlotCount: Int get() = pages.slotCount

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).roundToInt()
    private val metrics = DocumentMetrics(
        topPad = dp(28f),
        bottomPad = dp(88f), // clears the floating page indicator
        sidePad = dp(if (resources.configuration.smallestScreenWidthDp >= 600) 32f else 12f),
        gap = dp(24f),
        addCardHeight = dp(96f),
    )
    private val sheet = SheetStyle(shadowRadius = 10f * density, shadowDy = 3f * density, shadowColor = 0x33503C28)

    private val pages = PagesLayer(context, sheet, metrics)
    private val wet = InProgressStrokesView(context)
    private val cursor = HoverCursorLayer(context)
    private val palm = PalmRejection()
    private val sideButton = SideButtonTool()
    private val hover = HoverTracker()
    private val hoverGestures = HoverButtonGestures()
    private val resolveHoverClick = Runnable { hoverGestures.onTick(SystemClock.uptimeMillis())?.let(::onHoverGesture) }
    private var writing = false
    private val predictor = MotionEventPredictor.newInstance(this)
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val eraseRadiusPx = ERASE_RADIUS_DP * density
    private var temporaryShown: Tool? = null
    private var lastIndicator = -2 to -2

    /** pointerId -> (page, wet stroke). */
    private val inking = HashMap<Int, Pair<Int, InProgressStrokeId>>()
    private val strokePages = HashMap<InProgressStrokeId, Int>()

    /** The brush each stroke was started with (sizes in page points, canonical ink colors). */
    private val strokeBrushes = StrokeBrushes<InProgressStrokeId, Stroke>()

    /** pointerId -> (page, strokes erased so far): one undo step per gesture. */
    private val erasing = HashMap<Int, Pair<Int, MutableList<Stroke>>>()

    /** Scrolling pointers -> last y. */
    private val scrolling = HashMap<Int, Float>()

    /** Pointers that touched down on the add-page card -> down position. */
    private val cardTaps = HashMap<Int, Pair<Float, Float>>()

    private val expireHover = Runnable { if (hover.expire(SystemClock.uptimeMillis())) refreshHover() }

    private val documentListener = object : PagedDocument.Listener {
        override fun onPagesChanged() {
            pages.relayout()
        }

        override fun onPageContentChanged(index: Int) {
            pages.invalidatePage(index)
        }
    }

    init {
        addView(pages, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(cursor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        wet.addFinishedStrokesListener(this)
        // Set up the front-buffered renderer now, not on the first pen-down.
        wet.eagerInit()
        pages.onViewportChanged = ::reportIndicator
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** Appends a page with the same paper as the last one and scrolls it into view. @return its index. */
    fun addPage(): Int {
        val doc = document ?: return -1
        val index = doc.addPage()
        if (index >= 0) revealPage(index)
        return index
    }

    /** Smoothly scrolls so page [index] starts near the top of the viewport. */
    fun revealPage(index: Int) {
        val l = pages.layout ?: return
        val target = l.scrollYFor(index, pages.height)
        scroller.forceFinished(true)
        if (revealDurationMs <= 0) {
            pages.scrollToY(target)
        } else {
            scroller.startScroll(0, pages.scrollY, 0, target - pages.scrollY, revealDurationMs)
            postInvalidateOnAnimation()
        }
    }

    /**
     * The brush [stroke] was drawn with, captured at pen-down: what storage should save. Its color is
     * the palette color even on dark paper (where the stroke itself shows the light twin). Null for
     * strokes this view didn't draw (e.g. loaded from disk).
     */
    fun brushOf(stroke: Stroke): InkBrush? = strokeBrushes.brushOf(stroke)

    /** Jumps to page [index] without animation. */
    fun scrollToPage(index: Int) {
        val l = pages.layout ?: return
        scroller.forceFinished(true)
        pages.scrollToY(l.scrollYFor(index, pages.height))
    }

    // ---------------------------------------------------------------- input

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tracker = velocity ?: VelocityTracker.obtain().also { velocity = it }
        tracker.addMovement(event)
        predictor.record(event)
        val predicted = if (event.actionMasked == MotionEvent.ACTION_MOVE && inking.isNotEmpty()) predictor.predict() else null
        try {
            handle(event, predicted)
        } finally {
            predicted?.recycle()
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            velocity?.recycle()
            velocity = null
        }
        return true
    }

    private fun handle(event: MotionEvent, predicted: MotionEvent?) {
        var scrollDy = 0f
        var scrollMoves = 0
        for (se in StylusEventMapper.map(event)) {
            val id = se.pointerId
            val decision = palm.decide(se)
            // A stylus or a second finger arrived: finger strokes are dropped and become scrolling.
            palm.drainCancels().forEach { cancelled ->
                val wasInking = inking.containsKey(cancelled)
                cancelInk(cancelled, event)
                sideButton.onEnd(cancelled)
                if (wasInking) pointerY(event, cancelled)?.let { scrolling[cancelled] = it }
            }
            if (hover.onEvent(se)) refreshHover()
            hoverGestures.onEvent(se) // touching abandons a pending side-button click
            when (se.action) {
                StylusAction.Down -> onDown(event, se, decision)
                StylusAction.Move -> {
                    sideButton.onMove(se)?.let { switch ->
                        val page = inking[id]?.first ?: erasing[id]?.first
                        cancelInk(id, event)
                        finishErase(id)
                        if (page != null) begin(event, se, switch.tool, page)
                    }
                    when {
                        id in erasing -> se.samples.forEach { eraseAt(id, it.x, it.y) }
                        id in inking -> wet.addToStroke(event, id, inking.getValue(id).second, predicted)
                        id in scrolling -> {
                            val y = se.latest.y
                            scrollDy += y - scrolling.getValue(id)
                            scrollMoves++
                            scrolling[id] = y
                        }
                    }
                }
                StylusAction.Up -> {
                    inking.remove(id)?.let { (_, strokeId) -> wet.finishStroke(event, id, strokeId) }
                    finishErase(id)
                    sideButton.onEnd(id)
                    cardTaps.remove(id)?.let { (x, y) -> if (isTapOnCard(x, y, se.latest.x, se.latest.y)) addPage() }
                    if (scrolling.remove(id) != null && scrolling.isEmpty()) fling(id)
                }
                StylusAction.Cancel -> {
                    cancelInk(id, event)
                    finishErase(id)
                    sideButton.onEnd(id)
                    cardTaps.remove(id)
                    scrolling.remove(id)
                }
                else -> Unit
            }
        }
        if (scrollMoves > 0 && inking.isEmpty() && erasing.isEmpty()) {
            pages.scrollToY(pages.scrollY - (scrollDy / scrollMoves).roundToInt())
        }
        notifyTemporaryTool()
        notifyWriting()
    }

    private fun notifyWriting() {
        val now = inking.isNotEmpty() || erasing.isNotEmpty()
        if (now != writing) {
            writing = now
            writingListener?.onWritingChanged(now)
        }
    }

    private fun onDown(event: MotionEvent, se: StylusEvent, decision: InputDecision) {
        val id = se.pointerId
        scroller.forceFinished(true)
        val x = se.latest.x
        val y = se.latest.y
        val l = pages.layout
        if (l != null && l.addCard.contains(x + pages.scrollX, y + pages.scrollY)) {
            cardTaps[id] = x to y
            return
        }
        val finger = se.tool == PointerTool.Finger || se.tool == PointerTool.Unknown
        if (decision == InputDecision.Draw || decision == InputDecision.Erase) {
            val page = l?.pageAt(x + pages.scrollX, y + pages.scrollY) ?: -1
            if (page >= 0) {
                requestUnbufferedDispatch(event)
                begin(event, se, sideButton.onDown(se, tool), page)
                return
            }
            if (!finger) return // A pen on the desk does nothing.
        }
        if (finger) scrolling[id] = y
    }

    private fun isTapOnCard(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        val l = pages.layout ?: return false
        return abs(x1 - x0) < touchSlop && abs(y1 - y0) < touchSlop &&
            l.addCard.contains(x1 + pages.scrollX, y1 + pages.scrollY)
    }

    private fun pointerY(event: MotionEvent, pointerId: Int): Float? {
        val index = event.findPointerIndex(pointerId)
        return if (index >= 0) event.getY(index) else null
    }

    private fun fling(pointerId: Int) {
        val tracker = velocity ?: return
        tracker.computeCurrentVelocity(1000, maxFling.toFloat())
        val vy = tracker.getYVelocity(pointerId)
        if (abs(vy) < minFling) return
        scroller.fling(0, pages.scrollY, 0, -vy.roundToInt(), 0, 0, 0, pages.maxScrollY)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            pages.scrollToY(scroller.currY)
            postInvalidateOnAnimation()
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && event.actionMasked == MotionEvent.ACTION_SCROLL &&
            inking.isEmpty()
        ) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                scroller.forceFinished(true)
                pages.scrollToY(pages.scrollY - (v * WHEEL_STEP_DP * density).roundToInt())
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /** Start a stroke for [se]'s pointer with [strokeTool] on page [page]. */
    private fun begin(event: MotionEvent, se: StylusEvent, strokeTool: Tool, page: Int) {
        val id = se.pointerId
        when {
            strokeTool == Tool.Eraser -> {
                erasing[id] = page to ArrayList()
                se.samples.forEach { eraseAt(id, it.x, it.y) }
            }
            strokeTool.inks -> startInk(event, id, strokeTool, page)
            else -> Unit // Lasso: CANVAS-08.
        }
    }

    private fun pageToView(page: Int, out: Matrix): Boolean {
        val l = pages.layout ?: return false
        val r = l.pageRect(page)
        out.setScale(l.scale, l.scale)
        out.postTranslate((r.left - pages.scrollX).toFloat(), (r.top - pages.scrollY).toFloat())
        return true
    }

    private fun startInk(event: MotionEvent, pointerId: Int, strokeTool: Tool, page: Int) {
        val doc = document ?: return
        val paper = PaperColors.of(doc.pages[page].page.paperColor)
        val sizePt = brushSize * PT_PER_BRUSH_UNIT
        val brush = BrushCatalog.brush(strokeTool, penStyle, PaperInk.colorOn(colorArgb, paper), sizePt) ?: return
        val strokeToView = Matrix()
        if (!pageToView(page, strokeToView)) return
        // World = view pixels; the stroke itself is recorded in page points.
        val strokeId = wet.startStroke(event, pointerId, brush, IDENTITY, strokeToView)
        inking[pointerId] = page to strokeId
        strokePages[strokeId] = page
        StrokeBrushes.capture(strokeTool, penStyle, colorArgb, sizePt)?.let { strokeBrushes.begin(strokeId, it) }
    }

    private fun cancelInk(pointerId: Int, event: MotionEvent) {
        inking.remove(pointerId)?.let { (_, strokeId) ->
            wet.cancelStroke(strokeId, event)
            strokePages.remove(strokeId)
            strokeBrushes.cancel(strokeId)
        }
    }

    private fun cancelAllInput() {
        inking.values.forEach { (_, strokeId) ->
            wet.cancelStroke(strokeId)
            strokeBrushes.cancel(strokeId)
        }
        inking.clear()
        strokePages.clear()
        erasing.keys.toList().forEach(::finishErase)
        scrolling.clear()
        cardTaps.clear()
        palm.reset()
        notifyWriting()
    }

    /** Whole-stroke eraser on the gesture's page: strokes whose bounds (plus a radius) contain the point. */
    private fun eraseAt(pointerId: Int, x: Float, y: Float) {
        val (page, removed) = erasing[pointerId] ?: return
        val doc = document ?: return
        val l = pages.layout ?: return
        val r = l.pageRect(page)
        val px = (x + pages.scrollX - r.left) / l.scale
        val py = (y + pages.scrollY - r.top) / l.scale
        val radius = eraseRadiusPx / l.scale
        val hits = doc.pages[page].strokes.filter { stroke ->
            val box = stroke.shape.computeBoundingBox() ?: return@filter false
            px >= box.xMin - radius && px <= box.xMax + radius && py >= box.yMin - radius && py <= box.yMax + radius
        }
        if (hits.isEmpty()) return
        doc.removeStrokes(page, hits)
        removed += hits
    }

    private fun finishErase(pointerId: Int) {
        val (page, removed) = erasing.remove(pointerId) ?: return
        document?.commitErase(page, removed)
    }

    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        val doc = document
        strokes.forEach { (id, stroke) -> strokeBrushes.finish(id, stroke) }
        // Hand off wet -> dry in the same frame so nothing flickers.
        strokes.entries.groupBy({ strokePages.remove(it.key) ?: -1 }, { it.value }).forEach { (page, list) ->
            if (doc != null && page in 0 until doc.size) doc.addStrokes(page, list)
        }
        wet.removeFinishedStrokes(strokes.keys)
    }

    // ---------------------------------------------------------------- hover

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val events = StylusEventMapper.map(event)
        if (events.isEmpty()) return super.onHoverEvent(event)
        var changed = false
        for (hoverEvent in events) {
            palm.decide(hoverEvent)
            changed = hover.onEvent(hoverEvent) || changed
            hoverGestures.onEvent(hoverEvent)?.let(::onHoverGesture)
        }
        removeCallbacks(resolveHoverClick)
        hoverGestures.deadline?.let { postDelayed(resolveHoverClick, (it - SystemClock.uptimeMillis()).coerceAtLeast(0L)) }
        if (changed) refreshHover()
        removeCallbacks(expireHover)
        if (hover.position != null) postDelayed(expireHover, HoverTracker.STALE_MS)
        notifyTemporaryTool()
        return true
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        val type = event.getToolType(pointerIndex)
        return if (type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER) {
            PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
        } else {
            super.onResolvePointerIcon(event, pointerIndex)
        }
    }

    private fun onHoverGesture(gesture: HoverButtonGestures.Gesture) {
        when (gesture) {
            is HoverButtonGestures.Gesture.QuickPalette -> quickPaletteListener?.onQuickPalette(gesture.x, gesture.y)
            HoverButtonGestures.Gesture.Undo -> document?.undo()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(expireHover)
        removeCallbacks(resolveHoverClick)
        scroller.forceFinished(true)
        super.onDetachedFromWindow()
    }

    private fun refreshHover() {
        val at = hover.position ?: return cursor.hide()
        val hoverTool = SideButtonTool.resolve(at.pointer, at.buttonPressed, tool, sideButton.temporaryTool)
        val scale = pages.layout?.scale ?: 1f
        val width = (BrushCatalog.brush(hoverTool, penStyle, colorArgb, brushSize * PT_PER_BRUSH_UNIT)?.size ?: brushSize) * scale
        cursor.show(at.x, at.y, HoverCursorStyle.of(hoverTool, colorArgb, width, eraseRadiusPx, density))
    }

    private fun notifyTemporaryTool() {
        val held = sideButton.temporaryActive || hover.position?.let { it.pointer == PointerTool.Stylus && it.buttonPressed } == true
        val now = if (held) sideButton.temporaryTool else null
        if (now != temporaryShown) {
            temporaryShown = now
            temporaryToolListener?.invoke(now)
        }
    }

    private fun reportIndicator() {
        val count = document?.size ?: 0
        val now = currentPage to count
        if (now == lastIndicator) return
        lastIndicator = now
        pageIndicatorListener?.onPageIndicator(now.first, count)
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility != View.VISIBLE) cancelAllInput()
    }

    companion object {
        /** Page points per brush-size unit: the default size 5 becomes a 1.5 pt (0.5 mm) line. */
        const val PT_PER_BRUSH_UNIT = 0.3f

        /** Eraser hit radius around a stroke's bounds; the hover cursor previews exactly this. */
        const val ERASE_RADIUS_DP = 10f

        private const val WHEEL_STEP_DP = 64f
        private val IDENTITY = Matrix()
    }
}
