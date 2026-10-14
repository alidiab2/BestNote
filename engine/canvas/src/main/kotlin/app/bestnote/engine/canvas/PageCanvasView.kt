/**
 * :engine:canvas — the page drawing surface.
 *
 * [PageCanvasView] stacks two layers:
 *  - **wet ink**: androidx.ink [InProgressStrokesView], which draws the stroke being written
 *    through androidx.graphics' front-buffered renderer, fed with unbuffered MotionEvents
 *    and [MotionEventPredictor] samples for minimum perceived latency;
 *  - **dry ink**: a regular View drawing the paper template and finished strokes with
 *    [CanvasStrokeRenderer].
 * Input is classified by :engine:stylus ([StylusEventMapper] + [PalmRejection]); brushes come
 * from :engine:ink. Edits (add / erase) go through an [UndoStack].
 */
package app.bestnote.engine.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.Tool
import app.bestnote.engine.ink.BrushCatalog
import app.bestnote.engine.stylus.InputDecision
import app.bestnote.engine.stylus.PalmRejection
import app.bestnote.engine.stylus.StylusAction
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
    var colorArgb: Int = 0xFF1F1F1F.toInt()
    var brushSize: Float = BrushCatalog.DEFAULT_SIZE

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
    private val predictor = MotionEventPredictor.newInstance(this)

    /** pointerId -> wet stroke being drawn. */
    private val inking = HashMap<Int, InProgressStrokeId>()

    /** Pointers currently erasing, with the strokes they removed so far (one undo step per gesture). */
    private val erasing = HashMap<Int, MutableList<Stroke>>()

    init {
        addView(dry, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
            palm.drainCancels().forEach { cancelInk(it, event) }
            when (stylusEvent.action) {
                StylusAction.Down -> when {
                    decision == InputDecision.Erase || (decision == InputDecision.Draw && tool == Tool.Eraser) -> {
                        erasing[pointerId] = ArrayList()
                        eraseAt(pointerId, stylusEvent.latest.x, stylusEvent.latest.y)
                    }
                    decision == InputDecision.Draw -> startInk(event, pointerId)
                }
                StylusAction.Move -> when {
                    pointerId in erasing -> stylusEvent.samples.forEach { eraseAt(pointerId, it.x, it.y) }
                    pointerId in inking -> wet.addToStroke(event, pointerId, inking.getValue(pointerId), predicted)
                }
                StylusAction.Up -> {
                    inking.remove(pointerId)?.let { wet.finishStroke(event, pointerId, it) }
                    finishErase(pointerId)
                }
                StylusAction.Cancel -> {
                    cancelInk(pointerId, event)
                    finishErase(pointerId)
                }
                else -> Unit
            }
        }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val hover = StylusEventMapper.map(event).firstOrNull() ?: return super.onHoverEvent(event)
        dry.hover = if (hover.action == StylusAction.HoverExit) null else hover.latest.x to hover.latest.y
        return true
    }

    private fun startInk(event: MotionEvent, pointerId: Int) {
        val brush = BrushCatalog.brush(tool, penStyle, colorArgb, brushSize) ?: return
        // Deliver every digitizer sample as it arrives instead of batching per vsync.
        requestUnbufferedDispatch(event)
        inking[pointerId] = wet.startStroke(event, pointerId, brush, IDENTITY, IDENTITY)
    }

    private fun cancelInk(pointerId: Int, event: MotionEvent) {
        inking.remove(pointerId)?.let { wet.cancelStroke(it, event) }
    }

    /** Whole-stroke eraser: removes strokes whose bounds (plus a small radius) contain the point. */
    private fun eraseAt(pointerId: Int, x: Float, y: Float) {
        val hits = dry.strokes.filter { stroke ->
            val box = stroke.shape.computeBoundingBox() ?: return@filter false
            x >= box.xMin - ERASE_RADIUS && x <= box.xMax + ERASE_RADIUS &&
                y >= box.yMin - ERASE_RADIUS && y <= box.yMax + ERASE_RADIUS
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
        dry.add(strokes.values)
        wet.removeFinishedStrokes(strokes.keys)
        history.push(CanvasEdit.Added(strokes.values.toList()))
        notifyHistory()
    }

    private fun notifyHistory() = historyListener?.onHistoryChanged(history.canUndo, history.canRedo)

    /** Paper template + finished strokes + stylus hover cursor. */
    private class DryLayer(context: Context) : View(context) {
        private val renderer = CanvasStrokeRenderer.create()
        val strokes = ArrayList<Stroke>()
        private val density = resources.displayMetrics.density
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332D5BFF; strokeWidth = density }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x552D5BFF }
        private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000; style = Paint.Style.STROKE; strokeWidth = density }

        var template = PaperTemplate.Ruled
            set(value) {
                field = value
                invalidate()
            }

        var hover: Pair<Float, Float>? = null
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
            strokes.forEach { renderer.draw(canvas, it, IDENTITY) }
            hover?.let { (x, y) -> canvas.drawCircle(x, y, 4 * density, hoverPaint) }
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

    private companion object {
        val IDENTITY = Matrix()
        const val ERASE_RADIUS = 8f
    }
}
