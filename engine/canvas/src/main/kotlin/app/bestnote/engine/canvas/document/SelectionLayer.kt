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
import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.PageElement
import app.bestnote.core.model.ShapeInfo
import app.bestnote.core.model.PageRect
import app.bestnote.core.model.TextElement
import app.bestnote.engine.canvas.StrokeBrushes
import app.bestnote.engine.canvas.StrokeFills
import app.bestnote.engine.canvas.select.Affine
import app.bestnote.engine.canvas.select.Box
import app.bestnote.engine.canvas.select.ElementTransforms
import app.bestnote.engine.canvas.select.Grip
import app.bestnote.engine.canvas.select.SnapGuides
import app.bestnote.engine.canvas.select.FillGeometry
import app.bestnote.engine.canvas.select.InkClipboard
import app.bestnote.engine.canvas.select.PenType
import app.bestnote.engine.canvas.select.LassoPolygon
import app.bestnote.engine.canvas.select.ObjectOrder
import app.bestnote.engine.canvas.select.PastePlacement
import app.bestnote.engine.canvas.select.SpatialGrid
import app.bestnote.engine.canvas.select.StrokeEdits
import app.bestnote.engine.canvas.select.StrokeOutlines
import app.bestnote.engine.canvas.select.TransformGesture
import app.bestnote.engine.canvas.shape.Anchor
import app.bestnote.engine.canvas.shape.ShapeEdits
import app.bestnote.engine.canvas.shape.ShapeGeometry
import app.bestnote.engine.canvas.shape.ShapeGlue
import app.bestnote.engine.canvas.shape.ShapeStroke
import app.bestnote.engine.ink.BrushCatalog
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
    private val elementPainter: ElementPainter,
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

    /** A tap with the lasso selected this (unlocked) element on its own, e.g. to open an equation's editor (MATH-01). */
    var elementTapListener: ((PageElement) -> Unit)? = null

    val hasSelection: Boolean get() = selection != null

    /** A lasso or a transform is under the pen. */
    val active: Boolean get() = draft != null || gesture != null

    private val density = resources.displayMetrics.density
    private val outlines = StrokeOutlines()
    private val indexes = WeakHashMap<DocumentPage, Pair<Int, SpatialGrid<Stroke>>>()
    private val ink = InkRenderer()
    private val fills = FillPainter()

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

    private class Selected(val page: DocumentPage, val strokes: List<Stroke>, val elements: List<PageElement>, val box: Box)

    private var draft: Draft? = null
    private var selection: Selected? = null
    private var gesture: TransformGesture? = null
    private var gesturePointer = -1
    private var gestureBox: Box? = null
    private var previewStrokes: List<Stroke> = emptyList()
    private var previewElements: List<PageElement> = emptyList()
    private val imageReady = Runnable { invalidate() }
    private var lastSnap: Int? = null

    /** TEXT-04: where a moving text box / picture snaps (page lines), and the guides it lines up on right now. */
    private var snapTargets: SnapGuides.Targets? = null
    private var guides: SnapGuides.Snap? = null

    /** CANVAS-17: the moving shapes' glue points and box, the shapes they glue to, and the current glue snap. */
    private var glue: Pair<List<Anchor>, Box>? = null
    private var glueTargets: List<ShapeInfo> = emptyList()
    private var glueMove: ShapeGlue.Move? = null

    /** Connectors (not selected) glued to the selected shapes, and how they look following the drag right now. */
    private var followers: List<Pair<Stroke, ShapeInfo>> = emptyList()
    private var selectedShapes: List<ShapeInfo> = emptyList()
    private var followPreview: List<Stroke> = emptyList()

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
        // Only locked pictures or text selected: nothing can move, so the box is not grabbable.
        if (sel.strokes.isEmpty() && sel.elements.all { it.locked }) return null
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
            guides = null
            // Moving boxes (not plain ink) snap to the page's margins and center and to the other boxes.
            snapTargets = if (grip == Grip.Move && sel.elements.isNotEmpty()) {
                val ids = sel.elements.mapTo(HashSet()) { it.id }
                SnapGuides.targets(sel.page.page.widthPt, sel.page.page.heightPt, sel.page.elements.filter { it.id !in ids }.map { ElementTransforms.bounds(it) })
            } else {
                null
            }
            // Moving shapes glue onto the page's other shapes (corners, edge middles, centers, edges).
            val infos = sel.strokes.mapNotNull { brushes.brushOf(it)?.shape }
            glue = if (grip == Grip.Move) ShapeGlue.movingOf(infos) else null
            glueTargets = if (glue != null) shapesOn(sel.page, infos.mapTo(HashSet()) { it.id }) else emptyList()
            glueMove = null
            selectedShapes = infos
            followers = connectorsOf(sel, infos.mapTo(HashSet()) { it.id })
            followPreview = emptyList()
            previewStrokes = readable(sel.page, sel.strokes)
            pages.setHidden(selIndex, identitySet(sel.strokes + followers.map { it.first }))
            previewElements = sel.elements.filter { !it.locked }
            pages.setHiddenElements(selIndex, previewElements.mapTo(HashSet()) { it.id })
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
        snapTargets?.let { targets ->
            val m = g.transform
            val b = sel.box
            val scale = pages.layout?.scale ?: 1f
            val snapped = SnapGuides.snap(Box(b.minX + m.tx, b.minY + m.ty, b.maxX + m.tx, b.maxY + m.ty), targets, SNAP_DP * density / scale)
            if (snapped.snapped) g.update(px + snapped.dx, py + snapped.dy)
            // A light tick when the box catches a new line (the same feedback as rotation snapping).
            val was = guides
            if (snapped.snapped && (snapped.guideX != was?.guideX || snapped.guideY != was?.guideY)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            guides = snapped.takeIf { it.snapped }
        }
        glue?.let { (anchors, box) ->
            val m = g.transform
            val moved = anchors.map { it.copy(x = it.x + m.tx, y = it.y + m.ty) }
            val scale = pages.layout?.scale ?: 1f
            val snapped = ShapeGlue.snapMove(moved, Box(box.minX + m.tx, box.minY + m.ty, box.maxX + m.tx, box.maxY + m.ty), glueTargets, GLUE_DP * density / scale)
            if (snapped.snapped) {
                g.update(px + snapped.dx, py + snapped.dy)
                val was = glueMove
                if (was == null || was.markers != snapped.markers || was.guideX != snapped.guideX || was.guideY != snapped.guideY) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            glueMove = snapped.takeIf { it.snapped }
        }
        if (followers.isNotEmpty()) {
            val m = g.transform
            val moved = selectedShapes.associate { it.id to ShapeGeometry.transformed(it, m) }
            val follow = ShapeGeometry.follow(followers.map { it.second }, moved)
            followPreview = readable(sel.page, followers.map { (stroke, info) ->
                follow[info.id]?.let { ShapeStroke.build(it.first, stroke, ShapeStroke.samplesOf(stroke)).stroke } ?: stroke
            })
        }
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
            // A tap on a text box or picture selects it.
            val hit = d.page.elements.lastOrNull { ElementTransforms.contains(it, d.xs[0], d.ys[0]) }
            if (hit != null) {
                // A locked picture is selected alone (so it can be unlocked); otherwise its group comes with it.
                if (hit.locked) {
                    select(d.page, emptyList(), listOf(hit))
                } else {
                    val (strokes, elements) = withGroupMates(d.page, emptyList(), listOf(hit))
                    select(d.page, strokes, elements)
                    if (strokes.isEmpty() && elements.size == 1) elementTapListener?.invoke(hit)
                }
                return invalidate()
            }
            // Otherwise nothing to select. With ink on the clipboard, offer Paste right here.
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
        // Locked pictures and text ignore the lasso: only a tap on one selects it.
        val chosenElements = d.page.elements.filter { ElementTransforms.lassoPicks(poly, it) }
        if (chosen.isNotEmpty() || chosenElements.isNotEmpty()) {
            val (strokes, elements) = withGroupMates(d.page, chosen, chosenElements)
            select(d.page, strokes, elements)
        } else {
            notifyMenu()
        }
        invalidate()
    }

    private fun finishGesture(commit: Boolean) {
        val g = gesture ?: return
        gesture = null
        gesturePointer = -1
        previewStrokes = emptyList()
        previewElements = emptyList()
        snapTargets = null
        guides = null
        glue = null
        glueTargets = emptyList()
        glueMove = null
        followers = emptyList()
        selectedShapes = emptyList()
        followPreview = emptyList()
        val sel = selection
        val m = g.transform
        if (commit && sel != null && !nearlyIdentity(m)) {
            val k = m.lengthScale
            val moveShape = shapeMover(sel, m)
            replace(sel, { s ->
                StrokeEdits.transformed(s, m).also { n -> brushes.derive(n, s) { moveShape(if (k != 1f) StrokeEdits.resized(it, k) else it) } }
            }) { e ->
                ElementTransforms.transformed(e, m).let { if (it is TextElement && it !== e) elementPainter.fitted(it) else it }
            }
        }
        pages.setHidden(-1, emptySet())
        pages.setHiddenElements(-1, emptySet())
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

    fun selectedInk(): PagedDocumentView.SelectedInk? {
        val sel = selection ?: return null
        val index = indexOf(sel.page)
        if (index < 0 || sel.strokes.isEmpty()) return null
        val b = sel.box
        return PagedDocumentView.SelectedInk(index, sel.strokes, PageRect(b.minX, b.minY, b.maxX, b.maxY))
    }

    fun delete() {
        val sel = selection ?: return
        val gone = sel.elements.mapTo(HashSet()) { it.id }
        edit { document?.editContent(indexOf(sel.page), removedStrokes = sel.strokes, elements = sel.page.elements.filter { it.id !in gone }) }
        clear()
    }

    fun copy() {
        val sel = selection ?: return
        InkClipboard.set(sel.strokes.map { InkClipboard.Item(it, brushes.brushOf(it)) }, sel.box, sel.elements)
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
        val groups = ObjectOrder.Regrouper()
        val renamed = ShapeEdits.renamer(sel.strokes.mapNotNull { brushes.brushOf(it)?.shape?.id })
        val copies = sel.strokes.map { s ->
            StrokeEdits.transformed(s, m).also { n -> brushes.derive(n, s) { ShapeEdits.copied(it.copy(group = groups.of(it.group)), renamed, m) } }
        }
        val shapes = sel.elements.map { it.withId(PageElement.newId()).withGroup(groups.of(it.group)).placed(cx = it.cx + PastePlacement.DUPLICATE_OFFSET, cy = it.cy + PastePlacement.DUPLICATE_OFFSET) }
        edit { document?.editContent(index, addedStrokes = copies, elements = sel.page.elements + shapes) }
        select(sel.page, copies, shapes)
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
        val groups = ObjectOrder.Regrouper()
        val renamed = ShapeEdits.renamer(InkClipboard.items.mapNotNull { it.brush?.shape?.id })
        val pasted = InkClipboard.items.map { item ->
            StrokeEdits.transformed(item.stroke, m).also { n -> item.brush?.let { brushes.put(n, ShapeEdits.copied(it.copy(group = groups.of(it.group)), renamed, m)) } }
        }
        pasteOffer = null
        val shapes = InkClipboard.elements.map { it.withId(PageElement.newId()).withGroup(groups.of(it.group)).placed(cx = it.cx + dx, cy = it.cy + dy) }
        edit { doc.editContent(index, addedStrokes = pasted, elements = page.elements + shapes) }
        select(page, pasted, shapes)
        return true
    }

    /** The stored group of [stroke], or null. */
    private fun groupOf(stroke: Stroke): String? = brushes.brushOf(stroke)?.group

    /** [strokes] and [elements] plus everything on [page] grouped with them (never a locked element). */
    private fun withGroupMates(page: DocumentPage, strokes: List<Stroke>, elements: List<PageElement>): Pair<List<Stroke>, List<PageElement>> {
        val groups = strokes.mapNotNullTo(HashSet(), ::groupOf) + elements.mapNotNull { it.group }
        if (groups.isEmpty()) return strokes to elements
        return ObjectOrder.withGroupMates(strokes, page.strokes, ::groupOf) to
            ObjectOrder.withGroupMates(elements, page.elements, { it.group }, { !it.locked })
    }

    /** Can the selection be grouped (two or more things, not already exactly one group)? */
    private fun canGroup(sel: Selected): Boolean {
        if (sel.strokes.size + sel.elements.size < 2 || sel.elements.any { it.locked }) return false
        val ids = sel.strokes.map(::groupOf) + sel.elements.map { it.group }
        return ids.any { it == null } || ids.toSet().size > 1
    }

    private fun isGrouped(sel: Selected) = sel.strokes.any { groupOf(it) != null } || sel.elements.any { it.group != null }

    /** Gives every selected thing the same group id, so the lasso picks them up together. One undo step. */
    fun group() {
        val sel = selection?.takeIf(::canGroup) ?: return
        setGroup(sel, ObjectOrder.newGroupId())
    }

    /** Takes every selected thing out of its group. One undo step. */
    fun ungroup() {
        val sel = selection?.takeIf(::isGrouped) ?: return
        setGroup(sel, null)
    }

    private fun setGroup(sel: Selected, id: String?) {
        replace(sel, { s ->
            if (groupOf(s) == id) s else StrokeEdits.refilled(s).also { n -> brushes.put(n, brushFor(s).copy(group = id)) }
        }) { e -> if (e.group == id) e else e.withGroup(id) }
        notifyMenu()
    }

    /** Draws the selection above everything else of its kind on the page (strokes among strokes, pictures among pictures). One undo step. */
    fun toFront() = restack(toFront = true)

    /** Draws the selection below everything else of its kind on the page. One undo step. */
    fun toBack() = restack(toFront = false)

    private fun restack(toFront: Boolean) {
        val sel = selection ?: return
        val index = indexOf(sel.page)
        val doc = document ?: return
        if (index < 0 || sel.elements.any { it.locked }) return
        val moveStrokes = ObjectOrder.wouldMove(sel.page.strokes, sel.strokes, toFront)
        val moveElements = ObjectOrder.wouldMove(sel.page.elements, sel.elements, toFront)
        if (!moveStrokes && !moveElements) return
        val picked = identitySet(sel.strokes)
        val copies = LinkedHashMap<Stroke, Stroke>()
        if (moveStrokes) for (s in sel.page.strokes) if (s in picked) copies[s] = StrokeEdits.refilled(s).also { n -> brushes.derive(n, s) { it } }
        val elements = if (moveElements) ObjectOrder.reordered(sel.page.elements, sel.elements, toFront) else sel.page.elements
        edit {
            doc.asOneStep {
                if (copies.isNotEmpty()) doc.restackStrokes(index, copies, toFront)
                if (moveElements) doc.replaceElements(index, elements)
            }
        }
        val strokes = sel.strokes.map { copies[it] ?: it }
        selection = Selected(sel.page, strokes, sel.elements, boxOf(strokes, sel.elements) ?: sel.box)
        notifyMenu()
        invalidate()
    }

    fun recolor(colorArgb: Int) {
        val sel = selection ?: return
        replace(sel, { s -> StrokeEdits.recolored(s, colorArgb).also { n -> brushes.derive(n, s) { StrokeEdits.recolored(it, colorArgb) } } }) { e ->
            when (e) {
                is TextElement -> e.copy(colorArgb = colorArgb)
                is app.bestnote.core.model.EquationElement -> e.copy(colorArgb = colorArgb)
                else -> e
            }
        }
        notifyMenu()
    }

    fun thicken(factor: Float) {
        val sel = selection ?: return
        if (sel.strokes.isEmpty()) return
        replace(sel, { s -> StrokeEdits.thickened(s, factor).also { n -> brushes.derive(n, s) { StrokeEdits.resized(it, factor) } } })
        notifyMenu()
    }

    /**
     * INK-11: gives the closed shapes among the selected strokes the fill [argb] (alpha = opacity), or
     * takes their fill away for null. Open strokes stay as they are. One undo step.
     */
    fun fill(argb: Int?) {
        val sel = selection ?: return
        replace(sel, { s ->
            if (!isClosed(s)) {
                s
            } else {
                StrokeEdits.refilled(s).also { n -> brushes.put(n, brushFor(s).copy(fillArgb = argb)) }
            }
        })
        notifyMenu()
    }

    /** CANVAS-10: draws every selected stroke with another pen, keeping geometry, color and size setting. One undo step. */
    fun restyle(type: PenType) {
        val sel = selection ?: return
        replace(sel, { s ->
            val brush = StrokeEdits.restyled(brushFor(s), type)
            if (brush == brushFor(s)) s else StrokeEdits.withBrush(s, brush).also { n -> brushes.put(n, brush) }
        })
        notifyMenu()
    }

    /** The stored brush of [stroke]: the one captured at pen-down, else read back from the stroke itself. */
    private fun brushFor(stroke: Stroke) =
        (brushes.brushOf(stroke) ?: BrushCatalog.modelBrush(stroke.brush)).let { b -> StrokeFills.of(stroke)?.let { b.copy(fillArgb = it) } ?: b }

    private val closedCache = WeakHashMap<Stroke, Boolean>()

    /** True when [stroke] outlines a closed shape (cached). */
    private fun isClosed(stroke: Stroke): Boolean = closedCache.getOrPut(stroke) {
        val o = outlines.of(stroke)
        FillGeometry.isClosed(o.xs, o.ys, stroke.brush.size)
    }

    /** Swaps every selected stroke for [make]'s copy as one undo step, and selects the copies. A stroke [make] returns unchanged is left alone. */
    private inline fun replace(sel: Selected, make: (Stroke) -> Stroke, makeElement: (PageElement) -> PageElement = { it }) {
        val index = indexOf(sel.page)
        if (index < 0) return
        val mapping = LinkedHashMap<Stroke, Stroke>()
        val after = ArrayList<Stroke>(sel.strokes.size)
        for (s in sel.strokes) {
            val n = make(s)
            if (n !== s) mapping[s] = n
            after += n
        }
        val changed = LinkedHashMap<String, PageElement>()
        for (e in sel.elements) changed[e.id] = makeElement(e)
        if (mapping.isEmpty() && changed.all { (id, e) -> sel.elements.first { it.id == id } === e }) return
        // Connectors glued to shapes that moved follow them, in the same undo step (CANVAS-17).
        val all = LinkedHashMap(mapping).apply { putAll(followers(sel, mapping)) }
        edit { document?.editContent(index, replacements = all, elements = sel.page.elements.map { changed[it.id] ?: it }) }
        val shapes = changed.values.toList()
        selection = Selected(sel.page, after, shapes, boxOf(after, shapes) ?: sel.box)
        invalidate()
    }

    /** The shapes on [page] (except [skip] ids), as their stored records. */
    private fun shapesOn(page: DocumentPage, skip: Set<String> = emptySet()): List<ShapeInfo> =
        page.strokes.mapNotNull { brushes.brushOf(it)?.shape?.takeIf { s -> s.id !in skip } }

    /** How the stored shape of a stroke in [sel] changes when it moves by [m] (connector ends re-glue where they land). */
    private fun shapeMover(sel: Selected, m: Affine): (InkBrush) -> InkBrush {
        val ids = sel.strokes.mapNotNullTo(HashSet()) { brushes.brushOf(it)?.shape?.id }
        if (ids.isEmpty()) return { it }
        val others = shapesOn(sel.page, ids)
        val tolerance = GLUE_DP * density / (pages.layout?.scale ?: 1f) * RELINK_SHARE
        return { b -> ShapeEdits.moved(b, m, ids, others, tolerance) }
    }

    /** Connector strokes on [sel]'s page, not selected, with an end glued to one of the shapes [ids]. */
    private fun connectorsOf(sel: Selected, ids: Set<String>): List<Pair<Stroke, ShapeInfo>> {
        if (ids.isEmpty()) return emptyList()
        val picked = identitySet(sel.strokes)
        return sel.page.strokes.mapNotNull { s ->
            val info = brushes.brushOf(s)?.shape ?: return@mapNotNull null
            if (s in picked || !ShapeGeometry.isConnector(info)) return@mapNotNull null
            if (info.start?.targetId in ids || info.end?.targetId in ids) s to info else null
        }
    }

    /** Re-drawn connectors (not in [sel]) whose glued ends must follow the shapes [mapping] moved. */
    private fun followers(sel: Selected, mapping: Map<Stroke, Stroke>): Map<Stroke, Stroke> {
        val moved = HashMap<String, ShapeInfo>()
        for ((old, new) in mapping) {
            val before = brushes.brushOf(old)?.shape ?: continue
            val after = brushes.brushOf(new)?.shape ?: continue
            if (before.points != after.points) moved[after.id] = after
        }
        if (moved.isEmpty()) return emptyMap()
        val picked = identitySet(sel.strokes)
        val byId = HashMap<String, Stroke>()
        val connectors = ArrayList<ShapeInfo>()
        for (s in sel.page.strokes) {
            if (s in picked) continue
            val info = brushes.brushOf(s)?.shape ?: continue
            if (!ShapeGeometry.isConnector(info)) continue
            if (info.start?.targetId !in moved && info.end?.targetId !in moved) continue
            connectors += info
            byId[info.id] = s
        }
        val out = LinkedHashMap<Stroke, Stroke>()
        for ((id, redrawn) in ShapeGeometry.follow(connectors, moved)) {
            val old = byId[id] ?: continue
            val clean = ShapeStroke.build(redrawn.first, old, ShapeStroke.samplesOf(old)).stroke
            brushes.derive(clean, old) { it.copy(shape = redrawn.second) }
            out[old] = clean
        }
        return out
    }

    /**
     * Moves the selection by ([dx], [dy]) page points as one undo step (arrow-key nudging, TEXT-04). Nothing moves
     * when a locked picture or text box is part of it. @return true when something moved.
     */
    fun nudge(dx: Float, dy: Float): Boolean {
        val sel = selection ?: return false
        if (gesture != null || sel.elements.any { it.locked }) return false
        val m = Affine.translate(dx, dy)
        val moveShape = shapeMover(sel, m)
        replace(sel, { s -> StrokeEdits.transformed(s, m).also { n -> brushes.derive(n, s, moveShape) } }) { e -> ElementTransforms.transformed(e, m) }
        notifyMenu()
        return true
    }

    /** The selected element when it is the only thing selected (a picture or a text box), else null. */
    val singleElement: PageElement? get() = selection?.takeIf { it.strokes.isEmpty() && it.elements.size == 1 }?.elements?.first()

    /** Selects the element [id] of page [pageIndex] (a freshly inserted text box or picture). */
    fun selectElement(pageIndex: Int, id: String) {
        val page = document?.pages?.getOrNull(pageIndex) ?: return
        val element = page.elements.firstOrNull { it.id == id } ?: return
        clear()
        select(page, emptyList(), listOf(element))
    }

    /** Applies [change] to the single selected element as one undo step (crop, turn, lock, text edit). */
    fun updateSingleElement(change: (PageElement) -> PageElement) {
        val sel = selection?.takeIf { it.strokes.isEmpty() && it.elements.size == 1 } ?: return
        replace(sel, { it }, change)
        notifyMenu()
    }

    private fun boxOf(strokes: Collection<Stroke>, elements: List<PageElement>): Box? =
        Box.union(listOfNotNull(outlines.boxOf(strokes)) + elements.map { ElementTransforms.bounds(it) })

    private inline fun edit(block: () -> Unit) {
        editing = true
        try {
            block()
        } finally {
            editing = false
        }
    }

    private fun select(page: DocumentPage, strokes: List<Stroke>, elements: List<PageElement> = emptyList()) {
        val box = boxOf(strokes, elements) ?: return
        selection = Selected(page, strokes, elements, box)
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

    fun onPageElementsChanged(index: Int) {
        if (editing) return
        val sel = selection ?: return
        val page = document?.pages?.getOrNull(index) ?: return
        if (page !== sel.page) return
        // Undo, redo or another change touched this page's elements: keep the selection only if all of it is still there as selected.
        if (!sel.elements.all { it in page.elements }) clear() else invalidate()
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
            return l.onSelectionMenu(
                PagedDocumentView.SelectionMenu(
                    sel.strokes.size + sel.elements.size, pts[0], pts[1], pts[2], pts[3],
                    strokes = sel.strokes.size, element = singleElement, texts = sel.elements.count { it is TextElement || it is app.bestnote.core.model.EquationElement },
                    fillable = sel.strokes.count { isClosed(it) },
                    filled = sel.strokes.count { StrokeFills.of(it) != null },
                    canGroup = canGroup(sel),
                    grouped = isGrouped(sel),
                    onlyLocked = sel.strokes.isEmpty() && sel.elements.all { it.locked },
                ),
            )
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
        if (g != null && (previewStrokes.isNotEmpty() || previewElements.isNotEmpty())) {
            val full = Matrix(matrix).apply { preConcat(m.toMatrix()) }
            canvas.save()
            canvas.concat(full)
            if (previewElements.isNotEmpty()) {
                val colors = PaperColors.of(sel.page.page.paperColor)
                val pxPerPt = (pages.layout?.scale ?: 1f) * pages.preview.scale * m.lengthScale
                // Moving text keeps its size; the final box is re-fitted on release.
                for (e in previewElements) elementPainter.drawOne(canvas, e, pxPerPt, document?.images, imageReady) { PaperInk.colorOn(it, colors) }
            }
            if (previewStrokes.isNotEmpty()) {
                fills.draw(canvas, previewStrokes, PaperColors.of(sel.page.page.paperColor))
                ink.draw(canvas, previewStrokes, full)
            }
            canvas.restore()
            if (followPreview.isNotEmpty()) ink.draw(canvas, followPreview, matrix)
        }
        drawBox(canvas, gestureBox.takeIf { g != null } ?: padded(sel.box), m, showHandles = g == null)
        if (g != null) guides?.let { drawGuides(canvas, it, sel.page) }
        if (g != null) glueMove?.let { drawGlue(canvas, it, sel.page) }
    }

    /** The snap guides across the whole page, thin and in the selection color. */
    private fun drawGuides(canvas: Canvas, snap: SnapGuides.Snap, page: DocumentPage) {
        val w = page.page.widthPt
        val h = page.page.heightPt
        line.color = color
        val pts = FloatArray(4)
        snap.guideX?.let { x ->
            pts[0] = x; pts[1] = 0f; pts[2] = x; pts[3] = h
            matrix.mapPoints(pts)
            canvas.drawLine(pts[0], pts[1], pts[2], pts[3], line)
        }
        snap.guideY?.let { y ->
            pts[0] = 0f; pts[1] = y; pts[2] = w; pts[3] = y
            matrix.mapPoints(pts)
            canvas.drawLine(pts[0], pts[1], pts[2], pts[3], line)
        }
    }

    /** Glue markers (rings on the points the shapes snapped together at) and alignment guides. */
    private fun drawGlue(canvas: Canvas, move: ShapeGlue.Move, page: DocumentPage) {
        line.color = color
        val p = FloatArray(4)
        for (mk in move.markers) {
            p[0] = mk.x; p[1] = mk.y
            matrix.mapPoints(p, 0, p, 0, 1)
            canvas.drawCircle(p[0], p[1], GLUE_MARK_DP * density, line)
        }
        if (move.guideX != null || move.guideY != null) {
            drawGuides(canvas, SnapGuides.Snap(0f, 0f, move.guideX, move.guideY), page)
        }
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
            if (want == s.brush.colorIntArgb) s else s.copy(s.brush.copyWithColorIntArgb(want)).also { StrokeFills.copy(it, s) }
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

        /** A moving box catches a snap line this close (screen dp, so it feels the same at every zoom). */
        private const val SNAP_DP = 6f

        /** CANVAS-17: moving shapes glue onto others this close (screen dp); a moved line's end re-glues within a share of it. */
        private const val GLUE_DP = 14f
        private const val RELINK_SHARE = 0.35f
        private const val GLUE_MARK_DP = 7f
        private const val MIN_STEP_DP = 1.5f
        private const val TAP_DP = 12f
        private const val MOVE_EPS = 0.5f
        private const val INITIAL_POINTS = 256
    }
}
