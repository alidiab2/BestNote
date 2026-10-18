package app.bestnote.engine.canvas.document

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.Stroke
import app.bestnote.core.model.LineDash
import app.bestnote.engine.canvas.shape.AnchorType
import app.bestnote.engine.canvas.shape.Polyline
import app.bestnote.engine.canvas.shape.ShapeHint
import app.bestnote.engine.canvas.shape.ShapeLabel
import app.bestnote.engine.canvas.shape.ShapeOutline
import app.bestnote.engine.canvas.shape.ShapePreview
import app.bestnote.engine.ink.InkRenderer
import app.bestnote.engine.recognition.RecognizedShape
import kotlin.math.hypot
import kotlin.math.max

/** The words on the ghost's label (the host passes translated ones). */
class ShapeGhostText(
    val names: Map<ShapeLabel, String> = ShapeLabel.entries.associateWith { it.name },
    val hints: Map<ShapeHint, String> = mapOf(
        ShapeHint.PerfectCircle to "perfect",
        ShapeHint.RightAngles to "90°",
        ShapeHint.Horizontal to "horizontal",
        ShapeHint.Vertical to "vertical",
        ShapeHint.Diagonal to "45°",
        ShapeHint.Glued to "snapped",
    ),
)

/**
 * CANVAS-17: draws the hold-to-shape ghost above the wet ink layer. While it shows, the raw stroke is taken off
 * the wet layer, so what is on screen is exactly what lifting commits: the clean outline in the full pen color
 * and width on a soft contrasting halo (readable on light and dark paper), a faint fill, its snapping (right-angle
 * ticks, glue markers, alignment guides) and a small label ("Circle · perfect"). A cancelled ghost hands back the
 * raw ink, drawn here until the pen lifts. No animation: it changes exactly when the pen moves. Never hit-tested.
 */
@SuppressLint("ViewConstructor")
internal class ShapeGhostLayer(context: Context) : View(context) {
    var text = ShapeGhostText()

    /** Glue markers and guides (the host passes the theme's accent). */
    var accent: Int = DEFAULT_ACCENT

    private val density = resources.displayMetrics.density
    private var ghost: ShapePreview.Ghost? = null
    private val toView = Matrix()
    private var color = 0
    private var widthPt = 1f
    private var startX = 0f
    private var startY = 0f
    private var shapeOutline: Polyline? = null
    private val bounds = RectF()

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pillEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = PILL_EDGE_DP * density
        color = PILL_EDGE
    }
    private val ink = InkRenderer()
    private var raw: Stroke? = null
    private val rawToView = Matrix()
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = MARK_DP * density
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = GUIDE_DP * density
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = LABEL_SP * resources.displayMetrics.scaledDensity
        color = LABEL_TEXT
    }
    private val path = Path()
    private val pts = FloatArray(4)
    private val rect = RectF()

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Shows [g] (null hides) on a page drawn through [pageToView], in pen [colorArgb], [strokeWidthPt] wide. */
    fun show(g: ShapePreview.Ghost?, pageToView: Matrix?, colorArgb: Int, strokeWidthPt: Float, startX: Float, startY: Float) {
        if (g == null && ghost == null) return
        ghost = g
        if (pageToView != null) toView.set(pageToView)
        color = colorArgb
        widthPt = strokeWidthPt
        this.startX = startX
        this.startY = startY
        shapeOutline = g?.let { ShapeOutline.of(it.shape, startX, startY) }
        invalidate()
    }

    val showing: Boolean get() = ghost != null

    private var wet: InProgressStroke? = null
    private var wetDash = LineDash.Solid
    private val wetToView = Matrix()

    /**
     * INK-07: draws [stroke], a dashed or dotted pen stroke in progress (page points, through [pageToView]),
     * clipped to its dashes; null stops. The caller invalidates this view as the stroke grows.
     */
    fun showWet(stroke: InProgressStroke?, pageToView: Matrix?, dash: LineDash) {
        if (stroke == null && wet == null) return
        wet = stroke
        wetDash = dash
        if (pageToView != null) wetToView.set(pageToView)
        invalidate()
    }

    /** Draws [stroke] (page points, through [pageToView]) as the pen's raw ink; null stops. */
    fun showRaw(stroke: Stroke?, pageToView: Matrix?) {
        if (stroke == null && raw == null) return
        raw = stroke
        if (pageToView != null) rawToView.set(pageToView)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        wet?.let {
            val save = canvas.save()
            canvas.concat(wetToView)
            ink.drawWet(canvas, it, wetToView, wetDash)
            canvas.restoreToCount(save)
        }
        raw?.let { ink.draw(canvas, listOf(it), rawToView) }
        val g = ghost ?: return
        val o = shapeOutline ?: return
        if (o.size < 2) return
        bounds.set(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until o.size) {
            map(o.xs[i], o.ys[i])
            bounds.union(pts[0], pts[1])
        }
        path.rewind()
        path.moveTo(o.xs[0], o.ys[0])
        for (i in 1 until o.size) path.lineTo(o.xs[i], o.ys[i])
        path.transform(toView)
        val closed = g.shape is RecognizedShape.Ellipse || (g.shape as? RecognizedShape.Polygon)?.closed == true
        if (closed) {
            fill.color = withAlpha(color, FILL_ALPHA)
            canvas.drawPath(path, fill)
        }
        val w = max(MIN_OUTLINE_DP * density, widthPt * toView.mapRadius(1f))
        halo.color = withAlpha(haloFor(color), HALO_ALPHA)
        halo.strokeWidth = w + 2 * HALO_DP * density
        canvas.drawPath(path, halo)
        outline.color = color
        outline.strokeWidth = w
        canvas.drawPath(path, outline)
        drawRightAngles(canvas, g)
        drawGuides(canvas, g)
        for (m in g.markers) {
            map(m.x, m.y)
            val r = MARKER_DP * density
            mark.color = accent
            canvas.drawCircle(pts[0], pts[1], r, mark)
            dot.color = accent
            canvas.drawCircle(pts[0], pts[1], if (m.attached || m.type == AnchorType.Center) r * 0.45f else r * 0.25f, dot)
        }
        drawLabel(canvas, g)
    }

    /** Small squares in a rectangle's corners. */
    private fun drawRightAngles(canvas: Canvas, g: ShapePreview.Ghost) {
        if (ShapeHint.RightAngles !in g.hints) return
        val p = (g.shape as? RecognizedShape.Polygon)?.points ?: return
        if (p.size != 4) return
        mark.color = color
        val s = TICK_DP * density
        for (k in 0 until 4) {
            val (cx, cy) = p[k]; val (ax, ay) = p[(k + 3) % 4]; val (bx, by) = p[(k + 1) % 4]
            map(cx, cy); val vx = pts[0]; val vy = pts[1]
            map(ax, ay); val ux1 = pts[0] - vx; val uy1 = pts[1] - vy
            map(bx, by); val ux2 = pts[0] - vx; val uy2 = pts[1] - vy
            val l1 = hypot(ux1, uy1); val l2 = hypot(ux2, uy2)
            if (l1 < 3 * s || l2 < 3 * s) continue
            val ax1 = ux1 / l1 * s; val ay1 = uy1 / l1 * s; val bx1 = ux2 / l2 * s; val by1 = uy2 / l2 * s
            path.rewind()
            path.moveTo(vx + ax1, vy + ay1)
            path.lineTo(vx + ax1 + bx1, vy + ay1 + by1)
            path.lineTo(vx + bx1, vy + by1)
            canvas.drawPath(path, mark)
        }
    }

    private fun drawGuides(canvas: Canvas, g: ShapePreview.Ghost) {
        guide.color = accent
        val ext = GUIDE_EXTENT_DP * density
        val minX = bounds.left; val maxX = bounds.right; val minY = bounds.top; val maxY = bounds.bottom
        g.guideX?.let { x ->
            map(x, 0f)
            canvas.drawLine(pts[0], minY - ext, pts[0], maxY + ext, guide)
        }
        g.guideY?.let { y ->
            map(0f, y)
            canvas.drawLine(minX - ext, pts[1], maxX + ext, pts[1], guide)
        }
    }

    /** "Rectangle · 90°" in a pill just above the shape (below it near the top of the screen). */
    private fun drawLabel(canvas: Canvas, g: ShapePreview.Ghost) {
        val name = text.names[g.label] ?: g.label.name
        val hints = g.hints.sortedBy { it.ordinal }.mapNotNull { text.hints[it] }
        val s = (listOf(name) + hints).joinToString(" · ")
        val minX = bounds.left; val maxX = bounds.right; val minY = bounds.top; val maxY = bounds.bottom
        val pad = PILL_PAD_DP * density
        val w = label.measureText(s) + 2 * pad
        val h = label.textSize + 1.2f * pad
        val gap = LABEL_GAP_DP * density
        var top = minY - gap - h
        if (top < 0) top = maxY + gap
        val left = ((minX + maxX) / 2 - w / 2).coerceIn(0f, max(0f, width - w))
        rect.set(left, top, left + w, top + h)
        pill.color = LABEL_BACKGROUND
        canvas.drawRoundRect(rect, h / 2, h / 2, pill)
        canvas.drawRoundRect(rect, h / 2, h / 2, pillEdge)
        canvas.drawText(s, left + pad, top + h / 2 - (label.descent() + label.ascent()) / 2, label)
    }

    private fun map(x: Float, y: Float) {
        pts[0] = x; pts[1] = y
        toView.mapPoints(pts, 0, pts, 0, 1)
    }

    /** White behind dark ink, near-black behind light ink (dark paper). */
    private fun haloFor(argb: Int): Int {
        val r = (argb shr 16) and 0xFF; val gr = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return if (0.299 * r + 0.587 * gr + 0.114 * b < LIGHT_INK) HALO_LIGHT else HALO_DARK
    }

    private fun withAlpha(argb: Int, alpha: Int) = (argb and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private companion object {
        const val HALO_ALPHA = 0x99
        const val HALO_DP = 3f
        const val HALO_LIGHT = 0xFFFFFFFF.toInt()
        const val HALO_DARK = 0xFF15171C.toInt()
        const val LIGHT_INK = 140.0
        const val PILL_EDGE_DP = 1f
        const val PILL_EDGE = 0x99FFFFFF.toInt()
        const val FILL_ALPHA = 0x1C
        const val MIN_OUTLINE_DP = 2f
        const val MARK_DP = 1.5f
        const val GUIDE_DP = 1f
        const val GUIDE_EXTENT_DP = 48f
        const val MARKER_DP = 7f
        const val TICK_DP = 7f
        const val LABEL_SP = 12f
        const val PILL_PAD_DP = 8f
        const val LABEL_GAP_DP = 10f
        const val LABEL_BACKGROUND = 0xE6262A33.toInt()
        const val LABEL_TEXT = 0xFFFFFFFF.toInt()
        const val DEFAULT_ACCENT = 0xFF2F6FED.toInt()
    }
}
