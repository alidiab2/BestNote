/**
 * :engine:canvas — the page drawing surface.
 *
 * [PageCanvasView] stacks two layers:
 *  - **wet ink**: androidx.ink [InProgressStrokesView], which draws the stroke being written
 *    through androidx.graphics' front-buffered renderer, fed with unbuffered MotionEvents
 *    and [MotionEventPredictor] samples for minimum perceived latency;
 *  - **dry ink**: a regular View drawing the paper template and finished strokes with
 *    :engine:ink's [InkRenderer] (highlighters under ink);
 *  - **cursor**: a tiny View on top drawing the stylus hover cursor, so hovering never redraws
 *    the page (cost stays constant however many strokes the page holds).
 * Input is classified by :engine:stylus ([StylusEventMapper] + [PalmRejection]); the S Pen side
 * button picks a temporary tool per stroke ([SideButtonTool]); brushes come from :engine:ink.
 * Edits (add / erase) go through an [UndoStack].
 */
package app.bestnote.engine.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.Tool
import app.bestnote.engine.ink.BrushCatalog
import app.bestnote.engine.ink.InkPalette
import app.bestnote.engine.ink.InkRenderer
import app.bestnote.engine.stylus.HoverCursorStyle
import app.bestnote.engine.stylus.HoverTracker
import app.bestnote.engine.stylus.InputDecision
import app.bestnote.engine.stylus.PalmRejection
import app.bestnote.engine.stylus.PointerTool
import app.bestnote.engine.stylus.SampleRateMeter
import app.bestnote.engine.stylus.SideButtonTool
import app.bestnote.engine.stylus.StylusAction
import app.bestnote.engine.stylus.StylusEvent
import app.bestnote.engine.stylus.StylusEventMapper

/** A reversible change to the page's finished strokes. */
sealed interface CanvasEdit {
    data class Added(val strokes: List<Stroke>) : CanvasEdit
    data class Erased(val strokes: List<Stroke>) : CanvasEdit
}

@SuppressLint("ViewConstructor")
class PageCanvasView(context: Context) : FrameLayout(context), InProgressStrokesFinishedListener {

    /** Called after any change to the history (draw, erase, undo, redo). */
    fun interface HistoryListener {
        fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean)
    }

    var historyListener: HistoryListener? = null
    var tool: Tool = Tool.Pen
    var penStyle: PenStyle = PenStyle.Fountain
    var colorArgb: Int = InkPalette.BLACK
    var brushSize: Float = BrushCatalog.DEFAULT_SIZE

    /** Tool used while the S Pen side button is held (default eraser); null = button does nothing. */
    var sideButtonTool: Tool?
        get() = sideButton.temporaryTool
        set(value) {
            sideButton.temporaryTool = value
        }

    /** Called with the temporary tool when the side button takes over, and null when it lets go. */
    var temporaryToolListener: ((Tool?) -> Unit)? = null

    /** When true, fingers never draw. */
    var stylusOnly: Boolean
        get() = palm.stylusOnly
        set(value) {
            palm.stylusOnly = value
        }

    var template: PaperTemplate
        get() = dry.template
        set(value) {
            dry.template = value
        }

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    private val palm = PalmRejection()
    private val history = UndoStack<CanvasEdit>()
    private val wet = InProgressStrokesView(context)
    private val dry = DryLayer(context)
    private val cursor = CursorLayer(context)
    private val predictor = MotionEventPredictor.newInstance(this)
    private val sideButton = SideButtonTool()
    private val hover = HoverTracker()
    private val density = resources.displayMetrics.density
    private val eraseRadius = ERASE_RADIUS_DP * density
    private var temporaryShown: Tool? = null

    /** Stylus pointerId -> sample-rate meter for the stroke (logged on lift, SPEN-01). */
    private val rates = HashMap<Int, SampleRateMeter>()

    /** Fallback for a missed HOVER_EXIT: no ghost cursor once hover samples stop. */
    private val expireHover = Runnable { if (hover.expire(SystemClock.uptimeMillis())) refreshHover() }

    /** pointerId -> wet stroke being drawn. */
    private val inking = HashMap<Int, InProgressStrokeId>()

    /** The brush each stroke was started with (captured at pen-down, kept for storage). */
    private val strokeBrushes = StrokeBrushes<InProgressStrokeId, Stroke>()

    /** Pointers currently erasing, with the strokes they removed so far (one undo step per gesture). */
    private val erasing = HashMap<Int, MutableList<Stroke>>()

    init {
        addView(dry, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(cursor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        wet.addFinishedStrokesListener(this)
    }

    fun undo() {
        when (val edit = history.undo() ?: return) {
            is CanvasEdit.Added -> dry.remove(edit.strokes)
            is CanvasEdit.Erased -> dry.add(edit.strokes)
        }
        notifyHistory()
    }

    fun redo() {
        when (val edit = history.redo() ?: return) {
            is CanvasEdit.Added -> dry.add(edit.strokes)
            is CanvasEdit.Erased -> dry.remove(edit.strokes)
        }
        notifyHistory()
    }

    fun clear() {
        if (dry.strokes.isEmpty()) return
        val all = dry.strokes.toList()
        dry.remove(all)
        history.push(CanvasEdit.Erased(all))
        notifyHistory()
    }

    /** The page's finished strokes in drawing order (read on the main thread). */
    val strokes: List<Stroke> get() = dry.strokes

    /** The exact brush [stroke] was drawn with, captured at pen-down; null for strokes set via [setStrokes]. */
    fun brushOf(stroke: Stroke): InkBrush? = strokeBrushes.brushOf(stroke)

    /** Replaces the page's strokes (e.g. loaded from disk) and starts a fresh undo history. */
    fun setStrokes(strokes: Collection<Stroke>) {
        dry.remove(dry.strokes.toList())
        dry.add(strokes)
        history.clear()
        notifyHistory()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        predictor.record(event)
        val predicted = if (event.actionMasked == MotionEvent.ACTION_MOVE && inking.isNotEmpty()) predictor.predict() else null
        try {
            handle(event, predicted)
        } finally {
            predicted?.recycle()
        }
        return true
    }

    private fun handle(event: MotionEvent, predicted: MotionEvent?) {
        for (stylusEvent in StylusEventMapper.map(event)) {
            val pointerId = stylusEvent.pointerId
            val decision = palm.decide(stylusEvent)
            palm.drainCancels().forEach { cancelInk(it, event); sideButton.onEnd(it) }
            if (hover.onEvent(stylusEvent)) refreshHover() // touching hides the hover cursor
            when (stylusEvent.action) {
                StylusAction.Down -> if (decision == InputDecision.Draw || decision == InputDecision.Erase) {
                    // Deliver every digitizer sample as it arrives instead of batching per vsync.
                    requestUnbufferedDispatch(event)
                    begin(event, stylusEvent, sideButton.onDown(stylusEvent, tool))
                    if (stylusEvent.tool == PointerTool.Stylus || stylusEvent.tool == PointerTool.Eraser) {
                        rates.getOrPut(pointerId) { SampleRateMeter() }.apply { start(); add(stylusEvent.latest.timeMillis) }
                    }
                }
                StylusAction.Move -> {
                    sideButton.onMove(stylusEvent)?.let { switch ->
                        // Button reported a few samples late: drop the stray ink, redo the stroke.
                        cancelInk(pointerId, event)
                        finishErase(pointerId)
                        begin(event, stylusEvent, switch.tool)
                    }
                    when {
                        pointerId in erasing -> stylusEvent.samples.forEach { eraseAt(pointerId, it.x, it.y) }
                        pointerId in inking -> wet.addToStroke(event, pointerId, inking.getValue(pointerId), predicted)
                    }
                    rates[pointerId]?.let { meter -> stylusEvent.samples.forEach { meter.add(it.timeMillis) } }
                }
                StylusAction.Up -> {
                    inking.remove(pointerId)?.let { wet.finishStroke(event, pointerId, it) }
                    finishErase(pointerId)
                    sideButton.onEnd(pointerId)
                    rates.remove(pointerId)?.let { logRate(it) }
                }
                StylusAction.Cancel -> {
                    cancelInk(pointerId, event)
                    finishErase(pointerId)
                    sideButton.onEnd(pointerId)
                    rates.remove(pointerId)
                }
                else -> Unit
            }
        }
        notifyTemporaryTool()
    }

    /** Start a stroke for [stylusEvent]'s pointer with [strokeTool]. */
    private fun begin(event: MotionEvent, stylusEvent: StylusEvent, strokeTool: Tool) {
        val pointerId = stylusEvent.pointerId
        when {
            strokeTool == Tool.Eraser -> {
                erasing[pointerId] = ArrayList()
                stylusEvent.samples.forEach { eraseAt(pointerId, it.x, it.y) }
            }
            strokeTool.inks -> startInk(event, pointerId, strokeTool)
            else -> Unit // Lasso: CANVAS-08.
        }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val events = StylusEventMapper.map(event)
        if (events.isEmpty()) return super.onHoverEvent(event)
        var changed = false
        for (hoverEvent in events) {
            palm.decide(hoverEvent) // a hovering pen keeps fingers from inking
            changed = hover.onEvent(hoverEvent) || changed
        }
        if (changed) refreshHover()
        removeCallbacks(expireHover)
        if (hover.position != null) postDelayed(expireHover, HoverTracker.STALE_MS)
        notifyTemporaryTool()
        return true
    }

    /** Hide the system / Air View hover dot for pens: we draw our own cursor. */
    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        val type = event.getToolType(pointerIndex)
        return if (type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER) {
            PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
        } else {
            super.onResolvePointerIcon(event, pointerIndex)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(expireHover)
        super.onDetachedFromWindow()
    }

    private fun refreshHover() {
        val at = hover.position ?: return cursor.hide()
        val hoverTool = SideButtonTool.resolve(at.pointer, at.buttonPressed, tool, sideButton.temporaryTool)
        val width = BrushCatalog.brush(hoverTool, penStyle, colorArgb, brushSize)?.size ?: brushSize
        cursor.show(at.x, at.y, HoverCursorStyle.of(hoverTool, colorArgb, width, eraseRadius, density))
    }

    private fun notifyTemporaryTool() {
        val held = sideButton.temporaryActive || hover.position?.let { it.pointer == PointerTool.Stylus && it.buttonPressed } == true
        val now = if (held) sideButton.temporaryTool else null
        if (now != temporaryShown) {
            temporaryShown = now
            temporaryToolListener?.invoke(now)
        }
    }

    private fun logRate(meter: SampleRateMeter) {
        val hz = meter.hz ?: return
        Log.d(TAG, "stylus stroke: ${meter.samples} samples in ${meter.durationMillis} ms = ${hz.toInt()} Hz")
    }

    private fun startInk(event: MotionEvent, pointerId: Int, strokeTool: Tool) {
        val brush = BrushCatalog.brush(strokeTool, penStyle, colorArgb, brushSize) ?: return
        val id = wet.startStroke(event, pointerId, brush, IDENTITY, IDENTITY)
        inking[pointerId] = id
        StrokeBrushes.capture(strokeTool, penStyle, colorArgb, brushSize)?.let { strokeBrushes.begin(id, it) }
    }

    private fun cancelInk(pointerId: Int, event: MotionEvent) {
        inking.remove(pointerId)?.let {
            wet.cancelStroke(it, event)
            strokeBrushes.cancel(it)
        }
    }

    /** Whole-stroke eraser: removes strokes whose bounds (plus a small radius) contain the point. */
    private fun eraseAt(pointerId: Int, x: Float, y: Float) {
        val hits = dry.strokes.filter { stroke ->
            val box = stroke.shape.computeBoundingBox() ?: return@filter false
            x >= box.xMin - eraseRadius && x <= box.xMax + eraseRadius &&
                y >= box.yMin - eraseRadius && y <= box.yMax + eraseRadius
        }
        if (hits.isEmpty()) return
        dry.remove(hits)
        erasing[pointerId]?.addAll(hits)
    }

    private fun finishErase(pointerId: Int) {
        val removed = erasing.remove(pointerId) ?: return
        if (removed.isNotEmpty()) {
            history.push(CanvasEdit.Erased(removed))
            notifyHistory()
        }
    }

    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        // Hand off wet -> dry in the same frame so nothing flickers.
        strokes.forEach { (id, stroke) -> strokeBrushes.finish(id, stroke) }
        dry.add(strokes.values)
        wet.removeFinishedStrokes(strokes.keys)
        history.push(CanvasEdit.Added(strokes.values.toList()))
        notifyHistory()
    }

    private fun notifyHistory() = historyListener?.onHistoryChanged(history.canUndo, history.canRedo)

    /** Paper template + finished strokes. */
    private class DryLayer(context: Context) : View(context) {
        private val renderer = InkRenderer()
        val strokes = ArrayList<Stroke>()
        private val density = resources.displayMetrics.density
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332D5BFF; strokeWidth = density }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x552D5BFF }

        var template = PaperTemplate.Ruled
            set(value) {
                field = value
                invalidate()
            }

        fun add(more: Collection<Stroke>) {
            strokes += more
            invalidate()
        }

        fun remove(gone: Collection<Stroke>) {
            val set = gone.toHashSet()
            strokes.removeAll { it in set }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(if (template == PaperTemplate.Blank) 0xFFFFFFFF.toInt() else 0xFFFFFDF7.toInt())
            drawTemplate(canvas)
            renderer.draw(canvas, strokes, IDENTITY)
        }

        private fun drawTemplate(canvas: Canvas) {
            val gap = 32 * density
            val w = width.toFloat()
            val h = height.toFloat()
            when (template) {
                PaperTemplate.Blank -> Unit
                PaperTemplate.Ruled -> {
                    var y = gap * 2
                    while (y < h) { canvas.drawLine(0f, y, w, y, linePaint); y += gap }
                }
                PaperTemplate.Grid -> {
                    var y = gap
                    while (y < h) { canvas.drawLine(0f, y, w, y, linePaint); y += gap }
                    var x = gap
                    while (x < w) { canvas.drawLine(x, 0f, x, h, linePaint); x += gap }
                }
                PaperTemplate.Dotted -> {
                    var y = gap
                    while (y < h) {
                        var x = gap
                        while (x < w) { canvas.drawCircle(x, y, 1.5f * density, dotPaint); x += gap }
                        y += gap
                    }
                }
            }
        }
    }

    /** Stylus hover cursor: ring + faint fill + center dot, or the eraser outline. */
    private class CursorLayer(context: Context) : View(context) {
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

    private companion object {
        const val TAG = "BestNote/Stylus"
        val IDENTITY = Matrix()

        /** Eraser hit radius around a stroke's bounds; the hover cursor previews exactly this. */
        const val ERASE_RADIUS_DP = 10f
    }
}
