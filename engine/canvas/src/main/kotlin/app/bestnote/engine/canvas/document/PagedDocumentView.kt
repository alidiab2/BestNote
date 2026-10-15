package app.bestnote.engine.canvas.document

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
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
import app.bestnote.engine.canvas.EraserMode
import app.bestnote.engine.canvas.StrokeBrushes
import app.bestnote.engine.canvas.erase.EraserCapsule
import app.bestnote.engine.canvas.erase.InkEraser
import app.bestnote.engine.canvas.WetHighlighter
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
        set(value) {
            if (field == value) return
            field = value
            // Leaving the lasso drops its selection (and any paste offer).
            if (value != Tool.Lasso) selection.clear()
        }

    /** What the eraser (tool, side button or the pen's eraser end) removes. */
    var eraserMode: EraserMode = EraserMode.Stroke
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

    /**
     * The lasso's floating menu (CANVAS-10/11): where to show it, or null to hide it (no selection,
     * or the pen is busy). A [SelectionMenu] with [SelectionMenu.count] 0 offers Paste at a point.
     */
    fun interface SelectionListener {
        fun onSelectionMenu(menu: SelectionMenu?)
    }

    /** The selection's box on screen (view px), or a single point for a paste offer. */
    data class SelectionMenu(val count: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

    var selectionListener: SelectionListener?
        get() = selection.listener
        set(value) {
            selection.listener = value
        }

    /** Lasso and selection color; the host passes a theme color. */
    var selectionColor: Int
        get() = selection.color
        set(value) {
            selection.color = value
        }

    /** Duration of the selection handles' entrance; the host passes a Motion token (0 = none). */
    var selectionAppearMs: Int
        get() = selection.appearDurationMs
        set(value) {
            selection.appearDurationMs = value
        }

    val hasSelection: Boolean get() = selection.hasSelection

    fun clearSelection() = selection.clear()
    fun deleteSelection() = selection.delete()
    fun copySelection() = selection.copy()
    fun cutSelection() = selection.cut()
    fun duplicateSelection() = selection.duplicate()

    /** Pastes the ink clipboard where the pen last touched. @return true if anything was pasted. */
    fun paste(): Boolean = selection.paste()

    /** Gives every selected stroke [colorArgb]'s color (its own opacity stays). One undo step. */
    fun recolorSelection(colorArgb: Int) = selection.recolor(colorArgb)

    /** Makes every selected stroke [factor] times as thick. One undo step. */
    fun thickenSelection(factor: Float) = selection.thicken(factor)

    var pageIndicatorListener: PageIndicatorListener? = null
        set(value) {
            field = value
            lastIndicator = -2 to -2
            reportIndicator()
        }

    /** Duration of the scroll that reveals a newly added page; the host passes a Motion token. */
    var revealDurationMs: Int = 0

    /** Duration of the double-tap fit-to-width zoom; the host passes a Motion token (0 = jump). */
    var zoomDurationMs: Int = 0

    /** Zoom relative to fit-to-width, [ZoomMath.MIN_ZOOM]..[ZoomMath.MAX_ZOOM] (CANVAS-05). */
    val zoom: Float get() = pages.zoom * pages.preview.scale

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
            selection.document = value
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

    /**
     * The highlighter stroke being drawn (one at a time) and its page. It is not drawn by the
     * front-buffered wet view (always on top): its page slot draws it inside its highlight layer,
     * under the ink, from the first frame.
     */
    private var highlighting: Pair<Int, WetHighlighter>? = null
    private val strokePages = HashMap<InProgressStrokeId, Int>()

    /** The brush each stroke was started with (sizes in page points, canonical ink colors). */
    private val strokeBrushes = StrokeBrushes<InProgressStrokeId, Stroke>()

    /** Lasso, selection and its handles (CANVAS-08..11). */
    private val selection = SelectionLayer(context, pages, strokeBrushes)

    /** An eraser gesture on one page: one undo step, diffed against the strokes it started with. */
    private class EraseGesture(val page: Int, val before: List<Stroke>, val mode: EraserMode) {
        var lastX = Float.NaN
        var lastY = Float.NaN
    }

    /** pointerId -> eraser gesture in progress. */
    private val erasing = HashMap<Int, EraseGesture>()
    private val eraser = InkEraser()

    /** Panning/zooming fingers -> last (x, y). */
    private val scrolling = HashMap<Int, Pair<Float, Float>>()

    /** A lone finger that may become a tap: (down x, down y, down time). */
    private var tapCandidate: Triple<Float, Float, Long>? = null

    /** The last tap (x, y, up time), to detect a double-tap. */
    private var lastTap: Triple<Float, Float, Long>? = null
    private val doubleTapSlop = ViewConfiguration.get(context).scaledDoubleTapSlop
    private var zoomAnimator: ValueAnimator? = null

    /** The state the running zoom animation ends in: zoom, (unscaled view point, screen point) anchor. */
    private var zoomTarget: FloatArray? = null

    /** Pointers that touched down on the add-page card -> down position. */
    private val cardTaps = HashMap<Int, Pair<Float, Float>>()

    private val expireHover = Runnable { if (hover.expire(SystemClock.uptimeMillis())) refreshHover() }

    private val documentListener = object : PagedDocument.Listener {
        override fun onPagesChanged() {
            pages.relayout()
            selection.onPagesChanged()
        }

        override fun onPageContentChanged(index: Int) {
            pages.invalidatePage(index)
            selection.onPageContentChanged(index)
        }
    }

    init {
        addView(pages, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(selection, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(cursor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        wet.addFinishedStrokesListener(this)
        // Set up the front-buffered renderer now, not on the first pen-down.
        wet.eagerInit()
        pages.onViewportChanged = {
            reportIndicator()
            selection.onViewportChanged()
        }
        // The pinch preview may draw pages beyond the pages layer's own bounds; clip to ours instead.
        clipChildren = false
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
        val predicted = if (event.actionMasked == MotionEvent.ACTION_MOVE && (inking.isNotEmpty() || highlighting != null)) predictor.predict() else null
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
        val fingersBefore = if (scrolling.isEmpty()) emptyMap() else HashMap(scrolling)
        var scrollMoves = 0
        for (se in StylusEventMapper.map(event)) {
            val id = se.pointerId
            val decision = palm.decide(se)
            // A stylus or a second finger arrived: finger strokes are dropped and become scrolling.
            palm.drainCancels().forEach { cancelled ->
                val wasInking = inking.containsKey(cancelled)
                cancelInk(cancelled, event)
                sideButton.onEnd(cancelled)
                if (wasInking) pointerXY(event, cancelled)?.let { scrolling[cancelled] = it }
            }
            if (hover.onEvent(se)) refreshHover()
            hoverGestures.onEvent(se) // touching abandons a pending side-button click
            when (se.action) {
                StylusAction.Down -> onDown(event, se, decision)
                StylusAction.Move -> {
                    sideButton.onMove(se)?.let { switch ->
                        val page = inking[id]?.first ?: erasing[id]?.page ?: highlighting?.takeIf { it.second.pointerId == id }?.first
                        cancelInk(id, event)
                        finishErase(id)
                        if (page != null) begin(event, se, switch.tool, page)
                    }
                    when {
                        selection.owns(id) -> selectAlong(id, se)
                        id in erasing -> eraseAlong(id, se)
                        id in inking -> wet.addToStroke(event, id, inking.getValue(id).second, predicted)
                        highlighting?.second?.pointerId == id -> highlighting?.let { (page, h) -> h.add(event, predicted); pages.invalidatePage(page) }
                        id in scrolling -> {
                            scrolling[id] = se.latest.x to se.latest.y
                            scrollMoves++
                            tapCandidate?.let { (x0, y0) -> if (abs(se.latest.x - x0) > touchSlop || abs(se.latest.y - y0) > touchSlop) tapCandidate = null }
                        }
                    }
                }
                StylusAction.Up -> {
                    // The UP carries the pen's last position: a quick drag may have no move there.
                    if (selection.owns(id)) selectAlong(id, se)
                    selection.up(id)
                    inking.remove(id)?.let { (_, strokeId) -> wet.finishStroke(event, id, strokeId) }
                    finishHighlight(id, event)
                    finishErase(id)
                    sideButton.onEnd(id)
                    cardTaps.remove(id)?.let { (x, y) -> if (isTapOnCard(x, y, se.latest.x, se.latest.y)) addPage() }
                    if (scrolling.remove(id) != null) {
                        if (scrolling.size < 2) commitPreview()
                        if (scrolling.isEmpty()) {
                            if (!onFingerTap(se)) fling(id)
                        }
                    }
                }
                StylusAction.Cancel -> {
                    selection.cancel(id)
                    cancelInk(id, event)
                    finishErase(id)
                    sideButton.onEnd(id)
                    cardTaps.remove(id)
                    if (scrolling.remove(id) != null && scrolling.size < 2) commitPreview()
                    tapCandidate = null
                }
                else -> Unit
            }
        }
        if (scrollMoves > 0 && inking.isEmpty() && erasing.isEmpty() && highlighting == null && !selection.active) {
            PinchStep.of(fingersBefore, scrolling)?.let(::panZoom)
        }
        notifyTemporaryTool()
        notifyWriting()
    }

    private fun notifyWriting() {
        val now = inking.isNotEmpty() || erasing.isNotEmpty() || highlighting != null || selection.active
        if (now != writing) {
            writing = now
            writingListener?.onWritingChanged(now)
        }
    }

    private fun onDown(event: MotionEvent, se: StylusEvent, decision: InputDecision) {
        val id = se.pointerId
        scroller.forceFinished(true)
        val finger = se.tool == PointerTool.Finger || se.tool == PointerTool.Unknown
        // Nothing moves under the pen: a pen lands on the final, settled layout.
        if (!finger || decision == InputDecision.Draw || decision == InputDecision.Erase) settleZoom()
        val x = se.latest.x
        val y = se.latest.y
        val l = pages.layout
        if (l != null && l.addCard.contains(x + pages.scrollX, y + pages.scrollY)) {
            cardTaps[id] = x to y
            return
        }
        if (decision == InputDecision.Draw && SideButtonTool.resolve(se.tool, se.primaryButtonPressed, tool, sideButton.temporaryTool) == Tool.Lasso && selection.gripAt(x, y) != null) {
            // A selection handle may hang off the page (e.g. the rotate knob above the top edge).
            requestUnbufferedDispatch(event)
            selection.down(id, -1, x, y)
            return
        }
        if (decision == InputDecision.Draw || decision == InputDecision.Erase) {
            val page = l?.pageAt(x + pages.scrollX, y + pages.scrollY) ?: -1
            if (page >= 0) {
                requestUnbufferedDispatch(event)
                begin(event, se, sideButton.onDown(se, tool), page)
                return
            }
            if (!finger) return // A pen on the desk does nothing.
        }
        if (finger) {
            if (scrolling.isEmpty()) {
                tapCandidate = Triple(x, y, event.eventTime)
            } else {
                tapCandidate = null
                lastTap = null
            }
            if (zoomAnimator != null) settleZoom()
            scrolling[id] = x to y
        }
    }

    /** Applies one frame of finger panning (one finger) or pinching (two or more). */
    private fun panZoom(step: PinchStep) {
        if (scrolling.size >= 2 || !pages.preview.isIdentity) {
            val k = ZoomMath.clampFactor(pages.zoom, pages.preview.scale, step.scale)
            pages.preview = pages.preview.scaledBy(k, step.focusX, step.focusY, step.dx, step.dy)
            // Re-lay out (sharp ink, the right pages bound) whenever the preview drifted far from it.
            val s = pages.preview.scale
            if (s < PREVIEW_MIN || s > PREVIEW_MAX) commitPreview(step.focusX, step.focusY)
        } else {
            pages.scrollToXY(pages.scrollX - step.dx.roundToInt(), pages.scrollY - step.dy.roundToInt())
        }
    }

    /** Turns the pinch preview into a real layout at the new zoom, keeping screen point ([fx], [fy]) still. */
    private fun commitPreview(fx: Float = width / 2f, fy: Float = height / 2f) {
        val p = pages.preview
        if (p.isIdentity) return
        pages.preview = PreviewTransform()
        if (p.scale == 1f) {
            pages.scrollToXY(pages.scrollX - p.tx.roundToInt(), pages.scrollY - p.ty.roundToInt())
        } else {
            pages.applyZoom(pages.zoom * p.scale, p.unmapX(fx), p.unmapY(fy), fx, fy)
        }
        refreshHover()
    }

    /** Finishes any zoom animation or pinch preview right now, so input maps onto the final layout. */
    private fun settleZoom() {
        zoomAnimator?.let { a ->
            zoomAnimator = null
            a.removeAllListeners()
            a.cancel()
            finishZoomAnimation()
        }
        commitPreview()
    }

    /** A lone finger lifted: a double-tap zooms to fit the page width. @return true when it was a tap. */
    private fun onFingerTap(se: StylusEvent): Boolean {
        val (x0, y0, t0) = tapCandidate ?: return false
        tapCandidate = null
        val now = se.latest.timeMillis
        if (now - t0 > ViewConfiguration.getTapTimeout() * 2L) return false
        val prev = lastTap
        if (prev != null && now - prev.third <= ViewConfiguration.getDoubleTapTimeout() &&
            abs(x0 - prev.first) < doubleTapSlop && abs(y0 - prev.second) < doubleTapSlop
        ) {
            lastTap = null
            zoomTo(1f, x0, y0)
        } else {
            lastTap = Triple(x0, y0, now)
        }
        return true
    }

    /**
     * Zooms to [target] (relative to fit-to-width) keeping the content under ([fx], [fy]) still,
     * animated with [zoomDurationMs]. A pen touching down finishes the animation at once.
     */
    fun zoomTo(target: Float, fx: Float = width / 2f, fy: Float = height / 2f) {
        settleZoom()
        val z = ZoomMath.clamp(target)
        if (abs(z - pages.zoom) < 1e-3f && pages.scrollX == 0) return
        zoomTarget = floatArrayOf(z, fx, fy)
        if (zoomDurationMs <= 0 || writing) return finishZoomAnimation()
        // Animate a GPU preview to exactly where the focus point will be on the final layout.
        val prev = pages.layout ?: return finishZoomAnimation()
        val next = pages.layoutAt(z) ?: return finishZoomAnimation()
        val (rawX, rawY) = ZoomMath.anchoredScroll(prev, pages.scrollX, pages.scrollY, fx, fy, next, fx, fy)
        val endX = fx + rawX - rawX.coerceIn(0f, (next.contentWidth - width).coerceAtLeast(0).toFloat())
        val endY = fy + rawY - rawY.coerceIn(0f, next.maxScroll(height).toFloat())
        val k = z / pages.zoom
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = zoomDurationMs.toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                val scale = 1f + (k - 1f) * f
                pages.preview = PreviewTransform(scale, (endX - k * fx) * f, (endY - k * fy) * f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (zoomAnimator !== animation) return
                    zoomAnimator = null
                    finishZoomAnimation()
                }
            })
            start()
        }
    }

    private fun finishZoomAnimation() {
        val (z, fx, fy) = zoomTarget ?: return
        zoomTarget = null
        pages.preview = PreviewTransform()
        pages.applyZoom(z, fx, fy, fx, fy)
        refreshHover()
    }

    private fun isTapOnCard(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        val l = pages.layout ?: return false
        return abs(x1 - x0) < touchSlop && abs(y1 - y0) < touchSlop &&
            l.addCard.contains(x1 + pages.scrollX, y1 + pages.scrollY)
    }

    private fun pointerXY(event: MotionEvent, pointerId: Int): Pair<Float, Float>? {
        val index = event.findPointerIndex(pointerId)
        return if (index >= 0) event.getX(index) to event.getY(index) else null
    }

    private fun fling(pointerId: Int) {
        val tracker = velocity ?: return
        tracker.computeCurrentVelocity(1000, maxFling.toFloat())
        val vx = tracker.getXVelocity(pointerId).let { if (pages.maxScrollX > 0) it else 0f }
        val vy = tracker.getYVelocity(pointerId)
        if (abs(vy) < minFling && abs(vx) < minFling) return
        scroller.fling(pages.scrollX, pages.scrollY, -vx.roundToInt(), -vy.roundToInt(), 0, pages.maxScrollX, 0, pages.maxScrollY)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            pages.scrollToXY(scroller.currX, scroller.currY)
            postInvalidateOnAnimation()
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && event.actionMasked == MotionEvent.ACTION_SCROLL &&
            inking.isEmpty() && highlighting == null
        ) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f && (event.metaState and android.view.KeyEvent.META_CTRL_ON) != 0) {
                settleZoom()
                pages.applyZoom(pages.zoom * if (v > 0) WHEEL_ZOOM else 1f / WHEEL_ZOOM, event.x, event.y, event.x, event.y)
                return true
            }
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
        selection.touched(page, se.latest.x, se.latest.y)
        when {
            strokeTool == Tool.Lasso -> selection.down(id, page, se.latest.x, se.latest.y)
            strokeTool == Tool.Eraser -> {
                val doc = document ?: return
                erasing[id] = EraseGesture(page, doc.strokesSnapshot(page), eraserMode)
                eraseAlong(id, se)
            }
            strokeTool.inks -> startInk(event, id, strokeTool, page)
            else -> Unit
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
        if (strokeTool == Tool.Highlighter) return startHighlight(event, pointerId, strokeTool, page, brush, strokeToView, sizePt)
        // World = view pixels; the stroke itself is recorded in page points.
        val strokeId = wet.startStroke(event, pointerId, brush, IDENTITY, strokeToView)
        inking[pointerId] = page to strokeId
        strokePages[strokeId] = page
        StrokeBrushes.capture(strokeTool, penStyle, colorArgb, sizePt)?.let { strokeBrushes.begin(strokeId, it) }
    }

    private fun startHighlight(event: MotionEvent, pointerId: Int, strokeTool: Tool, page: Int, brush: androidx.ink.brush.Brush, strokeToView: Matrix, sizePt: Float) {
        highlighting?.let { cancelHighlight(it.second.pointerId) } // one wet highlight at a time
        val viewToStroke = Matrix().also { strokeToView.invert(it) }
        val h = WetHighlighter(pointerId, brush, event.downTime, viewToStroke)
        highlighting = page to h
        StrokeBrushes.capture(strokeTool, penStyle, colorArgb, sizePt)?.let { strokeBrushes.begin(h.id, it) }
        h.add(event, null)
        pages.setWetHighlight(page, h.stroke, brush.colorIntArgb)
    }

    private fun finishHighlight(pointerId: Int, event: MotionEvent) {
        val (page, h) = highlighting?.takeIf { it.second.pointerId == pointerId } ?: return
        highlighting = null
        val stroke = h.finish(event)
        strokeBrushes.finish(h.id, stroke)
        pages.setWetHighlight(page, null, 0)
        val doc = document
        if (doc != null && page in 0 until doc.size) doc.addStrokes(page, listOf(stroke))
    }

    private fun cancelHighlight(pointerId: Int) {
        val (page, h) = highlighting?.takeIf { it.second.pointerId == pointerId } ?: return
        highlighting = null
        strokeBrushes.cancel(h.id)
        pages.setWetHighlight(page, null, 0)
    }

    private fun cancelInk(pointerId: Int, event: MotionEvent) {
        cancelHighlight(pointerId)
        inking.remove(pointerId)?.let { (_, strokeId) ->
            wet.cancelStroke(strokeId, event)
            strokePages.remove(strokeId)
            strokeBrushes.cancel(strokeId)
        }
    }

    private fun cancelAllInput() {
        highlighting?.let { cancelHighlight(it.second.pointerId) }
        inking.values.forEach { (_, strokeId) ->
            wet.cancelStroke(strokeId)
            strokeBrushes.cancel(strokeId)
        }
        inking.clear()
        strokePages.clear()
        erasing.keys.toList().forEach(::finishErase)
        selection.cancelInput()
        scrolling.clear()
        tapCandidate = null
        settleZoom()
        cardTaps.clear()
        palm.reset()
        notifyWriting()
    }

    /**
     * Erases along the pointer's path since its last sample, on the gesture's page: each step is a
     * capsule (no gaps however fast the pen moves). The radius is [ERASE_RADIUS_DP] on screen, so
     * it covers fewer page points when zoomed in, exactly as the hover cursor shows.
     */
    private fun eraseAlong(pointerId: Int, se: StylusEvent) {
        val g = erasing[pointerId] ?: return
        val doc = document ?: return
        val l = pages.layout ?: return
        if (g.page !in 0 until doc.size) return
        val radius = eraseRadiusPx / l.scale
        val capsules = ArrayList<EraserCapsule>(se.samples.size)
        for (sample in se.samples) {
            val (px, py) = ZoomMath.viewToPage(l, pages.scrollX, pages.scrollY, g.page, sample.x, sample.y)
            val fromX = if (g.lastX.isNaN()) px else g.lastX
            val fromY = if (g.lastY.isNaN()) py else g.lastY
            capsules += EraserCapsule(fromX, fromY, px, py, radius)
            g.lastX = px
            g.lastY = py
        }
        val result = eraser.erase(doc.pages[g.page].strokes, capsules, g.mode)
        if (result.isEmpty) return
        result.replacements.forEach { (original, pieces) -> pieces.forEach { strokeBrushes.inherit(it, original) } }
        doc.eraseStep(g.page, result.removed, result.replacements)
    }

    /** Feeds the lasso or the selection drag every sample of this event (historical ones too). */
    private fun selectAlong(pointerId: Int, se: StylusEvent) {
        val n = se.samples.size
        val xs = FloatArray(n) { se.samples[it].x }
        val ys = FloatArray(n) { se.samples[it].y }
        selection.move(pointerId, xs, ys, n)
    }

    private fun finishErase(pointerId: Int) {
        val g = erasing.remove(pointerId) ?: return
        document?.commitStrokeEdit(g.page, g.before)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clipBounds = Rect(0, 0, w, h)
    }

    override fun onDetachedFromWindow() {
        settleZoom()
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
        private const val WHEEL_ZOOM = 1.1f

        /** The pinch preview re-lays out the pages once it has scaled them beyond this range. */
        private const val PREVIEW_MIN = 0.67f
        private const val PREVIEW_MAX = 1.5f
        private val IDENTITY = Matrix()
    }
}
