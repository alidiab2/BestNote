package app.bestnote.engine.canvas.document

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.ink.strokes.Stroke
import app.bestnote.engine.canvas.StrokeBrushes
import app.bestnote.engine.canvas.select.Affine
import app.bestnote.engine.canvas.select.Box
import app.bestnote.engine.canvas.select.Grip
import app.bestnote.engine.canvas.select.InkClipboard
import app.bestnote.engine.canvas.select.LassoPolygon
import app.bestnote.engine.canvas.select.PastePlacement
import app.bestnote.engine.canvas.select.SpatialGrid
import app.bestnote.engine.canvas.select.StrokeEdits
import app.bestnote.engine.canvas.select.StrokeOutlines
import app.bestnote.engine.canvas.select.TransformGesture
import app.bestnote.engine.ink.InkRenderer
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlin.math.hypot

/**
 * The lasso tool (CANVAS-08..11), drawn over the pages: the freehand lasso while it is drawn, then
 * the selection's box with resize corners and a rotate knob. Owns the selection, its transforms
 * (each one undo step), restyling and the clipboard actions. Selections live in page points, so
 * they stay put through scrolling and zoom. Never hit-tested itself: [PagedDocumentView] routes input.
 */
@SuppressLint("ViewConstructor")
internal class SelectionLayer(
    context: Context,
    private val pages: PagesLayer,
    private val brushes: StrokeBrushes<*, Stroke>,
) : View(context) {

    var document: PagedDocument? = null
        set(value) {
            if (field === value) return
            field = value
            cancelInput()
            clear()
        }

    /** Lasso line, box and handle color (the host passes a theme color). */
    var color: Int = DEFAULT_COLOR
        set(value) {
            field = value
            invalidate()
        }

    /** How long the handles take to appear; the host passes a Motion token (0 = at once). */
    var appearDurationMs: Int = 0

    var listener: PagedDocumentView.SelectionListener? = null

    val hasSelection: Boolean get() = selection != null

    /** A lasso or a transform is under the pen. */
    val active: Boolean get() = draft != null || gesture != null

    private val density = resources.displayMetrics.density
    private val outlines = StrokeOutlines()
    private val indexes = WeakHashMap<DocumentPage, Pair<Int, SpatialGrid<Stroke>>>()
    private val ink = InkRenderer()

    private class Draft(val pointerId: Int, val page: DocumentPage) {
        var xs = FloatArray(INITIAL_POINTS)
        var ys = FloatArray(INITIAL_POINTS)
        var count = 0
        var screenLength = 0f

        fun add(x: Float, y: Float) {
            if (count == xs.size) {
                xs = xs.copyOf(count * 2)
                ys = ys.copyOf(count * 2)
            }
            xs[count] = x
            ys[count] = y
            count++
        }
    }

    private class Selected(val page: DocumentPage, val strokes: List<Stroke>, val box: Box)

    private var draft: Draft? = null
    private var selection: Selected? = null
    private var gesture: TransformGesture? = null
    private var gesturePointer = -1
    private var gestureBox: Box? = null
    private var previewStrokes: List<Stroke> = emptyList()
    private var lastSnap: Int? = null

    /** Where the pen last touched a page (page, x, y in page points): where paste lands. */
    private var lastTouch: Triple<DocumentPage, Float, Float>? = null

    /** A tap with the lasso while the clipboard has ink: offer Paste there. */
    private var pasteOffer: Triple<DocumentPage, Float, Float>? = null

    /** Our own edits change page content too: don't treat those as the selection going stale. */
    private var editing = false

    private var appear = 1f
    private var appearAnimator: ValueAnimator? = null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LINE_DP * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dashed = Paint(line).apply { pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = HANDLE_FILL
    }
    private val path = Path()
    private val matrix = Matrix()
    private val inverse = Matrix()
    private val pts = FloatArray(8)

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // ------------------------------------------------------------ input (view px)

    /** True when this pointer is drawing a lasso or dragging the selection. */
    fun owns(pointerId: Int) = draft?.pointerId == pointerId || (gesture != null && gesturePointer == pointerId)

    /** The handle or body of the selection under ([x], [y]), or null. */
    fun gripAt(x: Float, y: Float): Grip? {
        val sel = selection ?: return null
        val index = indexOf(sel.page)
        if (index < 0 || !pageToScreen(index, matrix)) return null
        val b = padded(sel.box)
        pts[0] = b.minX; pts[1] = b.minY; pts[2] = b.maxX; pts[3] = b.maxY
        matrix.mapPoints(pts, 0, pts, 0, 2)
        val (l, t, r, bt) = listOf(pts[0], pts[1], pts[2], pts[3])
        val hit = HIT_DP * density
        val knobY = t - KNOB_DP * density
        val cx = (l + r) / 2
        return when {
            hypot(x - cx, y - knobY) <= hit -> Grip.Rotate
            hypot(x - l, y - t) <= hit -> Grip.TopLeft
            hypot(x - r, y - t) <= hit -> Grip.TopRight
            hypot(x - r, y - bt) <= hit -> Grip.BottomRight
            hypot(x - l, y - bt) <= hit -> Grip.BottomLeft
            x in l..r && y in t..bt -> Grip.Move
            else -> null
        }
    }

    /** Any tool touched page [index] at ([x], [y]): remembered as where a paste lands. */
    fun touched(index: Int, x: Float, y: Float) {
        val page = document?.pages?.getOrNull(index) ?: return
        val (px, py) = toPage(index, x, y) ?: return
        lastTouch = Triple(page, px, py)
    }

    /**
     * The lasso pen touched down at ([x], [y]) on page [index] (-1: off the pages, only valid on a
     * handle). On the selection it grabs it; elsewhere it drops it and starts a new lasso.
     */
    fun down(pointerId: Int, index: Int, x: Float, y: Float): Boolean {
        if (active) return false
        finishAppear()
        pasteOffer = null
        val grip = gripAt(x, y)
        val sel = selection
        if (grip != null && sel != null) {
            val selIndex = indexOf(sel.page)
            val (px, py) = toPage(selIndex, x, y) ?: return false
            val box = padded(sel.box)
            gesture = TransformGesture(grip, box, px, py)
            gesturePointer = pointerId
            gestureBox = box
            lastSnap = null
            previewStrokes = readable(sel.page, sel.strokes)
            pages.setHidden(selIndex, identitySet(sel.strokes))
            listener?.onSelectionMenu(null)
            invalidate()
            return true
        }
        val page = document?.pages?.getOrNull(index) ?: return false
        clear()
        val (px, py) = toPage(index, x, y) ?: return false
        draft = Draft(pointerId, page).also { it.add(px, py) }
        listener?.onSelectionMenu(null)
        invalidate()
        return true
    }

    /** The pointer moved through [xs], [ys] (view px, oldest first). */
    fun move(pointerId: Int, xs: FloatArray, ys: FloatArray, count: Int) {
        draft?.takeIf { it.pointerId == pointerId }?.let { d ->
            val index = indexOf(d.page)
            if (index < 0 || !pageToScreen(index, matrix) || !matrix.invert(inverse)) return
            val minStep = MIN_STEP_DP * density
            for (i in 0 until count) {
                pts[0] = xs[i]; pts[1] = ys[i]
                inverse.mapPoints(pts, 0, pts, 0, 1)
                val lx = d.xs[d.count - 1]
                val ly = d.ys[d.count - 1]
                pts[2] = lx; pts[3] = ly
                matrix.mapPoints(pts, 4, pts, 2, 1)
                val step = hypot(xs[i] - pts[4], ys[i] - pts[5])
                if (step < minStep) continue
                d.screenLength += step
                d.add(pts[0], pts[1])
            }
            invalidate()
            return
        }
        val g = gesture ?: return
        if (gesturePointer != pointerId || count == 0) return
        val sel = selection ?: return
        val (px, py) = toPage(indexOf(sel.page), xs[count - 1], ys[count - 1]) ?: return
        g.update(px, py)
        val snap = g.snappedDeg
        if (snap != null && snap != lastSnap) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        lastSnap = snap
        invalidate()
    }

    fun up(pointerId: Int) {
        draft?.takeIf { it.pointerId == pointerId }?.let { d ->
            draft = null
            finishLasso(d)
            return
        }
        if (gesture != null && gesturePointer == pointerId) finishGesture(commit = true)
    }

    fun cancel(pointerId: Int) {
        if (draft?.pointerId == pointerId) {
            draft = null
            invalidate()
        }
        if (gesture != null && gesturePointer == pointerId) finishGesture(commit = false)
    }

    fun cancelInput() {
        draft = null
        if (gesture != null) finishGesture(commit = false)
    }

    private fun finishLasso(d: Draft) {
        val index = indexOf(d.page)
        if (index < 0) return invalidate()
        if (d.screenLength < TAP_DP * density) {
            // A tap: nothing to select. With ink on the clipboard, offer Paste right here.
            if (InkClipboard.hasContent) pasteOffer = Triple(d.page, d.xs[0], d.ys[0])
            notifyMenu()
            return invalidate()
        }
        val poly = LassoPolygon(d.xs.copyOf(d.count), d.ys.copyOf(d.count))
        val bounds = poly.bounds ?: return invalidate()
        val chosen = index(d.page).query(bounds).filter { s ->
            val o = outlines.of(s)
            poly.selects(o.xs, o.ys)
        }
        if (chosen.isNotEmpty()) select(d.page, chosen) else notifyMenu()
        invalidate()
    }

    private fun finishGesture(commit: Boolean) {
        val g = gesture ?: return
        gesture = null
        gesturePointer = -1
        previewStrokes = emptyList()
        val sel = selection
        val m = g.transform
        if (commit && sel != null && !nearlyIdentity(m)) {
            val k = m.lengthScale
            replace(sel) { s ->
                StrokeEdits.transformed(s, m).also { n -> brushes.derive(n, s) { if (k != 1f) StrokeEdits.resized(it, k) else it } }
            }
        }
        pages.setHidden(-1, emptySet())
        notifyMenu()
        invalidate()
    }

    private fun nearlyIdentity(m: Affine): Boolean {
        val e = 1e-4f
        return kotlin.math.abs(m.a - 1f) < e && kotlin.math.abs(m.d - 1f) < e && kotlin.math.abs(m.b) < e && kotlin.math.abs(m.c) < e &&
            kotlin.math.abs(m.tx) < MOVE_EPS && kotlin.math.abs(m.ty) < MOVE_EPS
    }

    // ------------------------------------------------------------ actions

    fun clear() {
        val had = selection != null || pasteOffer != null
        selection = null
        pasteOffer = null
        appearAnimator?.cancel()
        appearAnimator = null
        if (had) {
            notifyMenu()
            invalidate()
        }
    }

    fun delete() {
        val sel = selection ?: return
        edit { document?.deleteStrokes(indexOf(sel.page), sel.strokes) }
        clear()
    }

    fun copy() {
        val sel = selection ?: return
        InkClipboard.set(sel.strokes.map { InkClipboard.Item(it, brushes.brushOf(it)) }, sel.box)
    }

    fun cut() {
        copy()
        delete()
    }

    fun duplicate() {
        val sel = selection ?: return
        val index = indexOf(sel.page)
        if (index < 0) return
        val m = Affine.translate(PastePlacement.DUPLICATE_OFFSET, PastePlacement.DUPLICATE_OFFSET)
        val copies = sel.strokes.map { s -> StrokeEdits.transformed(s, m).also { n -> brushes.derive(n, s) { it } } }
        edit { document?.addStrokes(index, copies) }
        select(sel.page, copies)
    }

    /** Pastes the clipboard centered where the pen last touched (the offered spot, if any). @return true if pasted. */
    fun paste(): Boolean {
        val doc = document ?: return false
        val box = InkClipboard.box ?: return false
        if (!InkClipboard.hasContent) return false
        val at = pasteOffer ?: lastTouch?.takeIf { indexOf(it.first) >= 0 } ?: centerOfView() ?: return false
        val (page, x, y) = at
        val index = doc.pages.indexOf(page)
        if (index < 0) return false
        val (dx, dy) = PastePlacement.offset(box, x, y, page.page.widthPt, page.page.heightPt)
        val m = Affine.translate(dx, dy)
        val pasted = InkClipboard.items.map { item ->
            StrokeEdits.transformed(item.stroke, m).also { n -> item.brush?.let { brushes.put(n, it) } }
        }
        pasteOffer = null
        edit { doc.addStrokes(index, pasted) }
        select(page, pasted)
        return true
    }

    fun recolor(colorArgb: Int) {
        val sel = selection ?: return
        replace(sel) { s -> StrokeEdits.recolored(s, colorArgb).also { n -> brushes.derive(n, s) { StrokeEdits.recolored(it, colorArgb) } } }
        notifyMenu()
    }

    fun thicken(factor: Float) {
        val sel = selection ?: return
        replace(sel) { s -> StrokeEdits.thickened(s, factor).also { n -> brushes.derive(n, s) { StrokeEdits.resized(it, factor) } } }
        notifyMenu()
    }

    /** Swaps every selected stroke for [make]'s copy as one undo step, and selects the copies. */
    private inline fun replace(sel: Selected, make: (Stroke) -> Stroke) {
        val index = indexOf(sel.page)
        if (index < 0) return
        val mapping = LinkedHashMap<Stroke, Stroke>()
        for (s in sel.strokes) mapping[s] = make(s)
        edit { document?.replaceStrokes(index, mapping) }
        selection = Selected(sel.page, mapping.values.toList(), outlines.boxOf(mapping.values) ?: sel.box)
        invalidate()
    }

    private inline fun edit(block: () -> Unit) {
        editing = true
        try {
            block()
        } finally {
            editing = false
        }
    }

    private fun select(page: DocumentPage, strokes: List<Stroke>) {
        val box = outlines.boxOf(strokes) ?: return
        selection = Selected(page, strokes, box)
        pasteOffer = null
        startAppear()
        notifyMenu()
        invalidate()
    }

    // ------------------------------------------------------------ document changes

    fun onPageContentChanged(index: Int) {
        if (editing) return
        val sel = selection ?: return
        val page = document?.pages?.getOrNull(index) ?: return
        if (page !== sel.page) return
        // Undo, redo or an eraser changed this page: keep the selection only if all of it is still there.
        val present = identitySet(page.strokes)
        if (!sel.strokes.all { it in present }) clear()
    }

    fun onPagesChanged() {
        val doc = document ?: return clear()
        selection?.let { if (it.page !in doc.pages) clear() }
        if (lastTouch?.first?.let { it !in doc.pages } == true) lastTouch = null
        onViewportChanged()
    }

    fun onViewportChanged() {
        if (selection != null || pasteOffer != null) {
            invalidate()
            if (!active) notifyMenu()
        }
    }

    // ------------------------------------------------------------ menu

    private fun notifyMenu() {
        val l = listener ?: return
        if (active) return l.onSelectionMenu(null)
        val sel = selection
        if (sel != null) {
            val index = indexOf(sel.page)
            if (index < 0 || !pageToScreen(index, matrix)) return l.onSelectionMenu(null)
            val b = padded(sel.box)
            pts[0] = b.minX; pts[1] = b.minY; pts[2] = b.maxX; pts[3] = b.maxY
            matrix.mapPoints(pts, 0, pts, 0, 2)
            return l.onSelectionMenu(PagedDocumentView.SelectionMenu(sel.strokes.size, pts[0], pts[1], pts[2], pts[3]))
        }
        val offer = pasteOffer
        if (offer != null) {
            val index = indexOf(offer.first)
            if (index >= 0 && pageToScreen(index, matrix)) {
                pts[0] = offer.second; pts[1] = offer.third
                matrix.mapPoints(pts, 0, pts, 0, 1)
                return l.onSelectionMenu(PagedDocumentView.SelectionMenu(0, pts[0], pts[1], pts[0], pts[1]))
            }
        }
        l.onSelectionMenu(null)
    }

    // ------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        draft?.let { drawDraft(canvas, it) }
        val sel = selection ?: return
        val index = indexOf(sel.page)
        if (index < 0 || !pageToScreen(index, matrix)) return
        val g = gesture
        val m = g?.transform ?: Affine.IDENTITY
        if (g != null && previewStrokes.isNotEmpty()) {
            val full = Matrix(matrix).apply { preConcat(m.toMatrix()) }
            canvas.save()
            canvas.concat(full)
            ink.draw(canvas, previewStrokes, full)
            canvas.restore()
        }
        drawBox(canvas, gestureBox.takeIf { g != null } ?: padded(sel.box), m, showHandles = g == null)
    }

    private fun drawDraft(canvas: Canvas, d: Draft) {
        val index = indexOf(d.page)
        if (index < 0 || d.count < 2 || !pageToScreen(index, matrix)) return
        path.rewind()
        path.moveTo(d.xs[0], d.ys[0])
        for (i in 1 until d.count) path.lineTo(d.xs[i], d.ys[i])
        path.transform(matrix)
        fill.color = withAlpha(color, FILL_ALPHA)
        canvas.drawPath(path, fill)
        dashed.color = color
        canvas.drawPath(path, dashed)
    }

    private fun drawBox(canvas: Canvas, b: Box, m: Affine, showHandles: Boolean) {
        val corners = floatArrayOf(
            m.mapX(b.minX, b.minY), m.mapY(b.minX, b.minY),
            m.mapX(b.maxX, b.minY), m.mapY(b.maxX, b.minY),
            m.mapX(b.maxX, b.maxY), m.mapY(b.maxX, b.maxY),
            m.mapX(b.minX, b.maxY), m.mapY(b.minX, b.maxY),
        )
        matrix.mapPoints(corners)
        path.rewind()
        path.moveTo(corners[0], corners[1])
        for (i in 1 until 4) path.lineTo(corners[2 * i], corners[2 * i + 1])
        path.close()
        fill.color = withAlpha(color, FILL_ALPHA)
        canvas.drawPath(path, fill)
        dashed.color = color
        canvas.drawPath(path, dashed)
        if (!showHandles) return
        val a = appear
        val r = HANDLE_DP * density * a
        line.color = withAlpha(color, (255 * a).toInt())
        handleFill.alpha = (255 * a).toInt()
        // Rotate knob: straight "up" from the top edge's middle, in the box's own frame.
        val tx = (corners[0] + corners[2]) / 2
        val ty = (corners[1] + corners[3]) / 2
        val ex = corners[2] - corners[0]
        val ey = corners[3] - corners[1]
        val len = hypot(ex, ey).coerceAtLeast(1f)
        val nx = ey / len
        val ny = -ex / len
        val knob = KNOB_DP * density
        val kx = tx + nx * knob
        val ky = ty + ny * knob
        canvas.drawLine(tx, ty, kx, ky, line)
        canvas.drawCircle(kx, ky, r, handleFill)
        canvas.drawCircle(kx, ky, r, line)
        for (i in 0 until 4) {
            canvas.drawCircle(corners[2 * i], corners[2 * i + 1], r, handleFill)
            canvas.drawCircle(corners[2 * i], corners[2 * i + 1], r, line)
        }
    }

    private fun startAppear() {
        appearAnimator?.cancel()
        if (appearDurationMs <= 0) {
            appear = 1f
            return
        }
        appear = 0f
        appearAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = appearDurationMs.toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                appear = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Nothing animates under an active pen: a touch ends the handles' entrance at once. */
    private fun finishAppear() {
        appearAnimator?.cancel()
        appearAnimator = null
        appear = 1f
    }

    // ------------------------------------------------------------ geometry

    private fun indexOf(page: DocumentPage): Int = document?.pages?.indexOf(page) ?: -1

    /** Page [index]'s points -> this view's pixels, including any pinch preview. */
    private fun pageToScreen(index: Int, out: Matrix): Boolean {
        val l = pages.layout ?: return false
        if (index !in 0 until (document?.size ?: 0)) return false
        val r = l.pageRect(index)
        val p = pages.preview
        out.setScale(l.scale, l.scale)
        out.postTranslate((r.left - pages.scrollX).toFloat(), (r.top - pages.scrollY).toFloat())
        out.postScale(p.scale, p.scale)
        out.postTranslate(p.tx, p.ty)
        return true
    }

    private fun toPage(index: Int, x: Float, y: Float): Pair<Float, Float>? {
        if (!pageToScreen(index, matrix) || !matrix.invert(inverse)) return null
        pts[0] = x; pts[1] = y
        inverse.mapPoints(pts, 0, pts, 0, 1)
        return pts[0] to pts[1]
    }

    /** The selection box with a little air around the ink (a fixed size on screen). */
    private fun padded(b: Box): Box = b.inflate(PAD_DP * density / (pages.layout?.scale ?: 1f))

    private fun centerOfView(): Triple<DocumentPage, Float, Float>? {
        val doc = document ?: return null
        val l = pages.layout ?: return null
        val index = l.currentPage(pages.scrollY, pages.height).takeIf { it >= 0 } ?: return null
        val page = doc.pages.getOrNull(index) ?: return null
        val (px, py) = toPage(index, width / 2f, height / 2f) ?: return null
        return Triple(page, px.coerceIn(0f, page.page.widthPt), py.coerceIn(0f, page.page.heightPt))
    }

    private fun index(page: DocumentPage): SpatialGrid<Stroke> {
        indexes[page]?.let { (version, grid) -> if (version == page.version) return grid }
        val grid = SpatialGrid<Stroke>()
        for (s in page.strokes) grid.insert(s, outlines.of(s).box)
        indexes[page] = page.version to grid
        return grid
    }

    /** [strokes] with colors made readable on [page]'s paper, as the page itself draws them. */
    private fun readable(page: DocumentPage, strokes: List<Stroke>): List<Stroke> {
        val paper = PaperColors.of(page.page.paperColor)
        return strokes.map { s ->
            val want = PaperInk.colorOn(s.brush.colorIntArgb, paper)
            if (want == s.brush.colorIntArgb) s else s.copy(s.brush.copyWithColorIntArgb(want))
        }
    }

    private fun Affine.toMatrix() = Matrix().apply { setValues(floatArrayOf(a, c, tx, b, d, ty, 0f, 0f, 1f)) }

    private fun withAlpha(argb: Int, alpha: Int) = (argb and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun <T> identitySet(items: Collection<T>): Set<T> =
        Collections.newSetFromMap(IdentityHashMap<T, Boolean>(items.size * 2)).apply { addAll(items) }

    companion object {
        private const val DEFAULT_COLOR = 0xFF3E6AE1.toInt()
        private const val HANDLE_FILL = 0xFFFFFFFF.toInt()
        private const val FILL_ALPHA = 20
        private const val LINE_DP = 1.5f
        private const val HANDLE_DP = 7f
        private const val HIT_DP = 22f
        private const val KNOB_DP = 28f
        private const val PAD_DP = 6f
        private const val MIN_STEP_DP = 1.5f
        private const val TAP_DP = 12f
        private const val MOVE_EPS = 0.5f
        private const val INITIAL_POINTS = 256
    }
}
