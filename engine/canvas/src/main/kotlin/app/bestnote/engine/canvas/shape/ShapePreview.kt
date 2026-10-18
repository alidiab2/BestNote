package app.bestnote.engine.canvas.shape

import app.bestnote.core.model.ShapeInfo
import app.bestnote.core.model.ShapeLink
import app.bestnote.engine.canvas.select.Affine
import app.bestnote.engine.recognition.GeometricShapeRecognizer
import app.bestnote.engine.recognition.RecognizedShape
import app.bestnote.engine.recognition.ShapeKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the ghost's label calls a shape (a square is a rectangle with equal sides; a polyline an open polygon). */
enum class ShapeLabel { Line, Arrow, Circle, Ellipse, Rectangle, Square, Triangle, Polygon, Diamond, Star, Polyline }

/** Snapping the ghost shows: a perfect circle, right angles, a level / upright / 45° line, an end glued to a shape. */
enum class ShapeHint { PerfectCircle, RightAngles, Horizontal, Vertical, Diagonal, Glued }

/**
 * CANVAS-17: the live "this is what you'll get" ghost of hold-to-shape. When the pen pauses, [begin] recognizes the
 * stroke so far and snaps it (angles, squares, glue to nearby shapes; a line-like stroke that is no shape straightens,
 * INK-07 [Straighten]); while the pen stays down, [move] adjusts it
 * (a line's end follows the pen, a closed shape grows and shrinks about its center) and keeps it glued; moving far
 * away or shaking the pen cancels it, so the stroke stays free ink. Lifting commits exactly [ghost].
 * Pure Kotlin, page points; cheap enough to run per pen event on the main thread (no lag under the pen).
 */
class ShapePreview(private val recognizer: GeometricShapeRecognizer = GeometricShapeRecognizer()) {

    /** The previewed shape, its label and hints, the glue markers to draw and how its ends are attached. */
    class Ghost(
        val shape: RecognizedShape,
        val label: ShapeLabel,
        val hints: Set<ShapeHint>,
        val markers: List<ShapeGlue.Marker>,
        val guideX: Float?,
        val guideY: Float?,
        val start: ShapeLink?,
        val end: ShapeLink?,
    ) {
        /** The record stored with the committed stroke. */
        fun info(id: String): ShapeInfo = ShapeGeometry.infoOf(shape, id, start, end)
    }

    var ghost: Ghost? = null
        private set

    /** The pen moved away or shook after a ghost showed: this stroke stays free ink. */
    var cancelled: Boolean = false
        private set

    private var base: RecognizedShape? = null
    private var holdX = 0f
    private var holdY = 0f
    private var targets: List<ShapeInfo> = emptyList()
    private var tolerance = 0f
    private var cancelDistance = 0f
    private var startGlue: ShapeGlue.EndSnap? = null

    // Shake detection: the last direction of travel and when it reversed.
    private var lastX = 0f
    private var lastY = 0f
    private var dirX = 0f
    private var dirY = 0f
    private var shakeStep = 0f
    private val reversals = ArrayDeque<Long>()

    val active: Boolean get() = ghost != null

    /**
     * The pen paused at ([penX], [penY]) after drawing [xs], [ys] ([count] points): recognize and show a ghost.
     * [targets] are the other shapes on the page; [tolerance] the glue distance and [minCancel] the least distance
     * that cancels, page points. @return the ghost, or null when the stroke isn't a shape (or was cancelled).
     */
    fun begin(xs: FloatArray, ys: FloatArray, count: Int, penX: Float, penY: Float, targets: List<ShapeInfo>, tolerance: Float, minCancel: Float): Ghost? {
        if (cancelled) return null
        // INK-07: a wobbly line the recognizer doesn't call a line is still straightened by the hold.
        val shape = Straighten.resolve(recognizer.match(xs.copyOf(count), ys.copyOf(count))?.shape, xs, ys, count)
            ?: return null.also { ghost = null }
        start(shape, penX, penY, targets, tolerance, minCancel)
        ghost = build(penX, penY)
        return ghost
    }

    /** [shape] (recognized without a preview, e.g. by the Shapes tool) snapped and glued like a ghost would be. */
    fun settle(shape: RecognizedShape, targets: List<ShapeInfo>, tolerance: Float): Ghost {
        reset()
        start(shape, 0f, 0f, targets, tolerance, 0f)
        return build(0f, 0f).also { reset() }
    }

    private fun start(shape: RecognizedShape, penX: Float, penY: Float, targets: List<ShapeInfo>, tolerance: Float, minCancel: Float) {
        base = shape
        holdX = penX; holdY = penY
        lastX = penX; lastY = penY
        dirX = 0f; dirY = 0f
        reversals.clear()
        this.targets = targets
        this.tolerance = tolerance
        shakeStep = minCancel / SHAKE_STEP_DIV
        val connector = shape is RecognizedShape.Line || shape is RecognizedShape.Arrow
        cancelDistance = max(minCancel, sizeOf(shape) * if (connector) LINE_CANCEL_SHARE else CLOSED_CANCEL_SHARE)
        startGlue = when (shape) {
            is RecognizedShape.Line -> ShapeGlue.snapEnd(shape.x0, shape.y0, targets, tolerance)
            is RecognizedShape.Arrow -> ShapeGlue.snapEnd(shape.x0, shape.y0, targets, tolerance)
            else -> null
        }
    }

    /** The pen moved to ([penX], [penY]) at [t] ms while a ghost shows. @return the updated ghost, or null once cancelled. */
    fun move(penX: Float, penY: Float, t: Long): Ghost? {
        if (ghost == null || cancelled) return null
        if (hypot(penX - holdX, penY - holdY) > cancelDistance || shook(penX, penY, t)) {
            cancel()
            return null
        }
        ghost = build(penX, penY)
        return ghost
    }

    fun cancel() {
        cancelled = true
        ghost = null
        base = null
    }

    /** A new stroke. */
    fun reset() {
        cancelled = false
        ghost = null
        base = null
        targets = emptyList()
        reversals.clear()
    }

    private fun shook(x: Float, y: Float, t: Long): Boolean {
        val dx = x - lastX; val dy = y - lastY
        val d = hypot(dx, dy)
        if (d < shakeStep) return false
        lastX = x; lastY = y
        val ux = dx / d; val uy = dy / d
        if (ux * dirX + uy * dirY < -REVERSAL_COS) reversals.addLast(t)
        dirX = ux; dirY = uy
        while (reversals.isNotEmpty() && t - reversals.first() > SHAKE_WINDOW_MS) reversals.removeFirst()
        return reversals.size >= SHAKE_REVERSALS
    }

    private fun build(penX: Float, penY: Float): Ghost {
        val b = base!!
        val dx = penX - holdX; val dy = penY - holdY
        val hints = HashSet<ShapeHint>()
        val markers = ArrayList<ShapeGlue.Marker>()
        return when (b) {
            is RecognizedShape.Line, is RecognizedShape.Arrow -> {
                val e = ShapeGeometry.ends(ShapeGeometry.infoOf(b, ""))
                val sg = startGlue
                val x0 = sg?.x ?: e[0]
                val y0 = sg?.y ?: e[1]
                var x1 = e[2] + dx
                var y1 = e[3] + dy
                // Level, upright or 45° when close.
                val a = atan2(y1 - y0, x1 - x0)
                val k = (a / (PI / 4)).roundToInt()
                val snapped = (k * PI / 4).toFloat()
                if (abs(a - snapped) <= ANGLE_SNAP) {
                    val len = hypot(x1 - x0, y1 - y0)
                    x1 = x0 + cos(snapped) * len; y1 = y0 + sin(snapped) * len
                }
                val eg = ShapeGlue.snapEnd(x1, y1, targets, tolerance)
                if (eg != null) { x1 = eg.x; y1 = eg.y; markers += eg.marker; hints += ShapeHint.Glued }
                if (sg != null) { markers += sg.marker; hints += ShapeHint.Glued }
                val fa = atan2(y1 - y0, x1 - x0)
                val fk = (fa / (PI / 4)).roundToInt()
                if (abs(fa - fk * PI / 4) < EXACT) hints += when (((fk % 4) + 4) % 4) {
                    0 -> ShapeHint.Horizontal
                    2 -> ShapeHint.Vertical
                    else -> ShapeHint.Diagonal
                }
                val shape = ShapeGeometry.withEnds(b, x0, y0, x1, y1)
                Ghost(shape, if (b is RecognizedShape.Arrow) ShapeLabel.Arrow else ShapeLabel.Line, hints, markers, null, null, sg?.link, eg?.link)
            }
            else -> {
                val (cx, cy) = centerOf(b)
                val r0 = hypot(holdX - cx, holdY - cy)
                val scale = if (r0 < MIN_RADIUS) 1f else (hypot(penX - cx, penY - cy) / r0).coerceIn(MIN_SCALE, MAX_SCALE)
                var shape = transform(b, Affine.scaleAbout(scale, cx, cy))
                var label = labelOf(shape)
                if (label == ShapeLabel.Rectangle) squared(shape as RecognizedShape.Polygon)?.let { shape = it; label = ShapeLabel.Square }
                if (label == ShapeLabel.Circle) hints += ShapeHint.PerfectCircle
                if (label == ShapeLabel.Rectangle || label == ShapeLabel.Square) hints += ShapeHint.RightAngles
                val info = ShapeGeometry.infoOf(shape, "")
                val move = ShapeGlue.movingOf(listOf(info))?.let { (anchors, box) -> ShapeGlue.snapMove(anchors, box, targets, tolerance) } ?: ShapeGlue.Move.NONE
                if (move.snapped) {
                    shape = transform(shape, Affine.translate(move.dx, move.dy))
                    hints += ShapeHint.Glued
                }
                Ghost(shape, label, hints, move.markers, move.guideX, move.guideY, null, null)
            }
        }
    }

    companion object {
        private val ANGLE_SNAP = Math.toRadians(6.0).toFloat()
        private const val EXACT = 1e-3
        private const val LINE_CANCEL_SHARE = 1.0f
        private const val CLOSED_CANCEL_SHARE = 0.75f
        private const val SHAKE_STEP_DIV = 6f
        private const val REVERSAL_COS = 0.5f
        private const val SHAKE_WINDOW_MS = 700L
        private const val SHAKE_REVERSALS = 3
        private const val MIN_RADIUS = 1e-2f
        private const val MIN_SCALE = 0.2f
        private const val MAX_SCALE = 5f

        /** A rectangle whose sides differ by less than this share becomes a square. */
        const val SQUARE_TOLERANCE = 0.1f

        fun labelOf(shape: RecognizedShape): ShapeLabel = when (shape) {
            is RecognizedShape.Line -> ShapeLabel.Line
            is RecognizedShape.Arrow -> ShapeLabel.Arrow
            is RecognizedShape.Ellipse -> if (shape.kind == ShapeKind.Circle) ShapeLabel.Circle else ShapeLabel.Ellipse
            is RecognizedShape.Polygon -> when {
                !shape.closed -> ShapeLabel.Polyline
                shape.kind == ShapeKind.Rectangle -> if (isSquare(shape)) ShapeLabel.Square else ShapeLabel.Rectangle
                shape.kind == ShapeKind.Triangle -> ShapeLabel.Triangle
                shape.kind == ShapeKind.Diamond -> ShapeLabel.Diamond
                shape.kind == ShapeKind.Star -> ShapeLabel.Star
                else -> ShapeLabel.Polygon
            }
        }

        private fun isSquare(p: RecognizedShape.Polygon): Boolean {
            if (p.points.size != 4) return false
            val w = hypot(p.points[1].first - p.points[0].first, p.points[1].second - p.points[0].second)
            val h = hypot(p.points[2].first - p.points[1].first, p.points[2].second - p.points[1].second)
            return abs(w - h) <= 1e-3f * max(w, h)
        }

        /** [p] (a rectangle) as an exact square about its center when its sides are within [SQUARE_TOLERANCE], else null. */
        fun squared(p: RecognizedShape.Polygon): RecognizedShape.Polygon? {
            if (p.points.size != 4) return null
            val (x0, y0) = p.points[0]; val (x1, y1) = p.points[1]; val (x2, y2) = p.points[2]
            val w = hypot(x1 - x0, y1 - y0); val h = hypot(x2 - x1, y2 - y1)
            if (w <= 0f || h <= 0f || abs(w - h) > SQUARE_TOLERANCE * max(w, h)) return null
            val s = (w + h) / 2
            val ux = (x1 - x0) / w; val uy = (y1 - y0) / w
            val vx = (x2 - x1) / h; val vy = (y2 - y1) / h
            val cx = p.points.sumOf { it.first.toDouble() }.toFloat() / 4
            val cy = p.points.sumOf { it.second.toDouble() }.toFloat() / 4
            val ax = cx - (ux + vx) * s / 2; val ay = cy - (uy + vy) * s / 2
            val pts = listOf(ax to ay, ax + ux * s to ay + uy * s, ax + (ux + vx) * s to ay + (uy + vy) * s, ax + vx * s to ay + vy * s)
            return RecognizedShape.Polygon(pts, closed = true, kind = ShapeKind.Rectangle)
        }

        fun centerOf(shape: RecognizedShape): Pair<Float, Float> = ShapeGeometry.center(ShapeGeometry.infoOf(shape, ""))

        /** Diagonal of the shape's bounds. */
        fun sizeOf(shape: RecognizedShape): Float = ShapeGeometry.bounds(ShapeGeometry.infoOf(shape, "")).let { hypot(it.width, it.height) }

        /** [shape] moved by [m] (a translation or a uniform scale, possibly turned). */
        fun transform(shape: RecognizedShape, m: Affine): RecognizedShape = when (shape) {
            is RecognizedShape.Line -> RecognizedShape.Line(m.mapX(shape.x0, shape.y0), m.mapY(shape.x0, shape.y0), m.mapX(shape.x1, shape.y1), m.mapY(shape.x1, shape.y1))
            is RecognizedShape.Arrow -> RecognizedShape.Arrow(
                m.mapX(shape.x0, shape.y0), m.mapY(shape.x0, shape.y0), m.mapX(shape.x1, shape.y1), m.mapY(shape.x1, shape.y1),
                shape.barbs.map { (x, y) -> m.mapX(x, y) to m.mapY(x, y) },
            )
            is RecognizedShape.Ellipse -> {
                val k = m.lengthScale
                shape.copy(cx = m.mapX(shape.cx, shape.cy), cy = m.mapY(shape.cx, shape.cy), rx = shape.rx * k, ry = shape.ry * k)
            }
            is RecognizedShape.Polygon -> shape.copy(points = shape.points.map { (x, y) -> m.mapX(x, y) to m.mapY(x, y) })
        }
    }
}
