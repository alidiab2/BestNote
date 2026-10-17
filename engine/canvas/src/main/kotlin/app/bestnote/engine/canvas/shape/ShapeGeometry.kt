package app.bestnote.engine.canvas.shape

import app.bestnote.core.model.ShapeInfo
import app.bestnote.core.model.ShapeLink
import app.bestnote.engine.canvas.select.Affine
import app.bestnote.engine.canvas.select.Box
import app.bestnote.engine.recognition.RecognizedShape
import app.bestnote.engine.recognition.ShapeKind
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/** Where on a shape something can glue: a corner, the middle of an edge, the center, or a line's end. */
enum class AnchorType { Corner, Midpoint, Center, End }

/** A glue point of a shape, page points; [at] is its place as a [ShapeLink.at]. */
data class Anchor(val x: Float, val y: Float, val at: Float, val type: AnchorType)

/** The point on a shape's outline nearest to a query point. */
data class OutlineHit(val x: Float, val y: Float, val at: Float, val distance: Float)

/**
 * CANVAS-17: the geometry of a stored clean shape ([ShapeInfo]): its glue points, its outline, places on it
 * ([ShapeLink.at]) that survive moving, resizing and turning, and how it moves with its stroke. Pure Kotlin.
 */
object ShapeGeometry {
    const val LINE = "Line"
    const val ARROW = "Arrow"
    const val CIRCLE = "Circle"
    const val ELLIPSE = "Ellipse"
    const val POLYLINE = "Polyline"

    private const val ELLIPSE_SAMPLES = 72

    /** The record stored for clean [shape] under [id]. */
    fun infoOf(shape: RecognizedShape, id: String, start: ShapeLink? = null, end: ShapeLink? = null): ShapeInfo = when (shape) {
        is RecognizedShape.Line -> ShapeInfo(id, LINE, listOf(shape.x0, shape.y0, shape.x1, shape.y1), start, end)
        is RecognizedShape.Arrow -> ShapeInfo(
            id, ARROW, listOf(shape.x0, shape.y0, shape.x1, shape.y1) + shape.barbs.flatMap { listOf(it.first, it.second) }, start, end,
        )
        is RecognizedShape.Ellipse -> {
            val c = cos(shape.rotationRad); val s = sin(shape.rotationRad)
            ShapeInfo(
                id, if (shape.kind == ShapeKind.Circle) CIRCLE else ELLIPSE,
                listOf(shape.cx, shape.cy, shape.cx + shape.rx * c, shape.cy + shape.rx * s, shape.cx - shape.ry * s, shape.cy + shape.ry * c),
            )
        }
        is RecognizedShape.Polygon -> ShapeInfo(
            id, if (shape.closed) shape.kind.name else POLYLINE, shape.points.flatMap { listOf(it.first, it.second) },
        )
    }

    fun isConnector(info: ShapeInfo) = info.kind == LINE || info.kind == ARROW

    fun isEllipse(info: ShapeInfo) = (info.kind == CIRCLE || info.kind == ELLIPSE) && info.pointCount == 3

    fun isClosedPolygon(info: ShapeInfo) = !isConnector(info) && !isEllipse(info) && info.kind != POLYLINE

    private fun x(info: ShapeInfo, i: Int) = info.points[2 * i]
    private fun y(info: ShapeInfo, i: Int) = info.points[2 * i + 1]

    /** The corners of a polygon outline, or a connector's shaft ends (the barbs are decoration). */
    private fun vertexCount(info: ShapeInfo) = if (isConnector(info)) 2 else info.pointCount

    /** Edges along the outline: closed polygons wrap around. */
    private fun edgeCount(info: ShapeInfo) = if (isClosedPolygon(info)) info.pointCount else vertexCount(info) - 1

    /** [info] moved by [m] (links unchanged). */
    fun transformed(info: ShapeInfo, m: Affine): ShapeInfo {
        val p = info.points
        val out = ArrayList<Float>(p.size)
        for (i in 0 until info.pointCount) {
            out += m.mapX(p[2 * i], p[2 * i + 1]); out += m.mapY(p[2 * i], p[2 * i + 1])
        }
        return info.copy(points = out)
    }

    fun center(info: ShapeInfo): Pair<Float, Float> {
        if (isEllipse(info)) return x(info, 0) to y(info, 0)
        val n = vertexCount(info)
        var sx = 0f; var sy = 0f
        for (i in 0 until n) { sx += x(info, i); sy += y(info, i) }
        return sx / n to sy / n
    }

    /** The point at place [at] (see [ShapeLink.at]); negative = the center. */
    fun pointAt(info: ShapeInfo, at: Float): Pair<Float, Float> {
        if (at < 0f) return center(info)
        if (isEllipse(info)) {
            val t = at * PI.toFloat() / 2
            val cx = x(info, 0); val cy = y(info, 0)
            val ax = x(info, 1) - cx; val ay = y(info, 1) - cy
            val bx = x(info, 2) - cx; val by = y(info, 2) - cy
            return (cx + cos(t) * ax + sin(t) * bx) to (cy + cos(t) * ay + sin(t) * by)
        }
        val edges = edgeCount(info)
        if (edges <= 0) return x(info, 0) to y(info, 0)
        val clamped = if (isClosedPolygon(info)) ((at % edges) + edges) % edges else at.coerceIn(0f, edges.toFloat())
        val k = floor(clamped).toInt().coerceAtMost(edges - 1)
        val f = clamped - k
        val n = vertexCount(info)
        val q = (k + 1) % n
        return (x(info, k) + (x(info, q) - x(info, k)) * f) to (y(info, k) + (y(info, q) - y(info, k)) * f)
    }

    /** Glue points: corners and edge middles (stars: the points only), centers, a line's ends and middle. */
    fun anchors(info: ShapeInfo): List<Anchor> {
        val out = ArrayList<Anchor>()
        if (isEllipse(info)) {
            for (k in 0 until 4) pointAt(info, k.toFloat()).let { (px, py) -> out += Anchor(px, py, k.toFloat(), AnchorType.Midpoint) }
            center(info).let { (cx, cy) -> out += Anchor(cx, cy, -1f, AnchorType.Center) }
            return out
        }
        val connector = isConnector(info)
        val n = vertexCount(info)
        for (i in 0 until n) {
            val type = if (connector || (info.kind == POLYLINE && (i == 0 || i == n - 1))) AnchorType.End else AnchorType.Corner
            out += Anchor(x(info, i), y(info, i), i.toFloat(), type)
        }
        if (info.kind != ShapeKind.Star.name) {
            for (k in 0 until edgeCount(info)) pointAt(info, k + 0.5f).let { (px, py) -> out += Anchor(px, py, k + 0.5f, AnchorType.Midpoint) }
        }
        if (!connector && info.kind != POLYLINE) center(info).let { (cx, cy) -> out += Anchor(cx, cy, -1f, AnchorType.Center) }
        return out
    }

    /** The nearest point on [info]'s outline to ([px], [py]). */
    fun nearestOnOutline(info: ShapeInfo, px: Float, py: Float): OutlineHit {
        var best = OutlineHit(0f, 0f, 0f, Float.MAX_VALUE)
        if (isEllipse(info)) {
            // Sample, then refine between the neighbours of the best sample.
            var bestAt = 0f
            for (i in 0 until ELLIPSE_SAMPLES) {
                val at = 4f * i / ELLIPSE_SAMPLES
                val (ex, ey) = pointAt(info, at)
                val d = hypot(ex - px, ey - py)
                if (d < best.distance) { best = OutlineHit(ex, ey, at, d); bestAt = at }
            }
            val step = 4f / ELLIPSE_SAMPLES
            for (j in -10..10) {
                val at = ((bestAt + step * j / 10f) % 4f + 4f) % 4f
                val (ex, ey) = pointAt(info, at)
                val d = hypot(ex - px, ey - py)
                if (d < best.distance) best = OutlineHit(ex, ey, at, d)
            }
            return best
        }
        val n = vertexCount(info)
        for (k in 0 until edgeCount(info)) {
            val ax = x(info, k); val ay = y(info, k)
            val q = (k + 1) % n
            val bx = x(info, q); val by = y(info, q)
            val dx = bx - ax; val dy = by - ay
            val l2 = dx * dx + dy * dy
            val f = if (l2 > 0f) (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f) else 0f
            val hx = ax + dx * f; val hy = ay + dy * f
            val d = hypot(hx - px, hy - py)
            if (d < best.distance) best = OutlineHit(hx, hy, k + f, d)
        }
        return best
    }

    fun bounds(info: ShapeInfo): Box {
        if (isEllipse(info)) {
            val cx = x(info, 0); val cy = y(info, 0)
            val ax = x(info, 1) - cx; val ay = y(info, 1) - cy
            val bx = x(info, 2) - cx; val by = y(info, 2) - cy
            val hw = hypot(ax, bx); val hh = hypot(ay, by)
            return Box(cx - hw, cy - hh, cx + hw, cy + hh)
        }
        val xs = FloatArray(info.pointCount) { x(info, it) }
        val ys = FloatArray(info.pointCount) { y(info, it) }
        return Box.of(xs, ys)!!
    }

    /** A connector's shaft ends: start x, y, end x, y. */
    fun ends(info: ShapeInfo): FloatArray = floatArrayOf(x(info, 0), y(info, 0), x(info, 1), y(info, 1))

    /** The clean shape of a connector record (to redraw it), or null for other shapes. */
    fun connectorShape(info: ShapeInfo): RecognizedShape? = when {
        info.kind == LINE && info.pointCount >= 2 -> RecognizedShape.Line(x(info, 0), y(info, 0), x(info, 1), y(info, 1))
        info.kind == ARROW && info.pointCount >= 2 -> RecognizedShape.Arrow(
            x(info, 0), y(info, 0), x(info, 1), y(info, 1), (2 until info.pointCount).map { x(info, it) to y(info, it) },
        )
        else -> null
    }

    /**
     * A line or arrow moved so its shaft runs ([x0], [y0]) → ([x1], [y1]); an arrow's head keeps its size and opening
     * and turns with the shaft. Other shapes come back unchanged.
     */
    fun withEnds(shape: RecognizedShape, x0: Float, y0: Float, x1: Float, y1: Float): RecognizedShape = when (shape) {
        is RecognizedShape.Line -> RecognizedShape.Line(x0, y0, x1, y1)
        is RecognizedShape.Arrow -> {
            val turn = atan2(y1 - y0, x1 - x0) - atan2(shape.y1 - shape.y0, shape.x1 - shape.x0)
            val c = cos(turn); val s = sin(turn)
            val barbs = shape.barbs.map { (bx, by) ->
                val dx = bx - shape.x1; val dy = by - shape.y1
                (x1 + dx * c - dy * s) to (y1 + dx * s + dy * c)
            }
            RecognizedShape.Arrow(x0, y0, x1, y1, barbs)
        }
        else -> shape
    }

    /**
     * Connectors that must follow after the shapes in [moved] (id → its new record) changed: for every connector in
     * [connectors] not itself in [moved] whose start or end is glued to one of them, its re-drawn shape and record.
     * Ends glued to shapes that didn't move stay put.
     */
    fun follow(connectors: List<ShapeInfo>, moved: Map<String, ShapeInfo>): Map<String, Pair<RecognizedShape, ShapeInfo>> {
        val out = HashMap<String, Pair<RecognizedShape, ShapeInfo>>()
        for (c in connectors) {
            if (!isConnector(c) || c.id in moved) continue
            val s = c.start?.let { moved[it.targetId]?.let { t -> pointAt(t, it.at) } }
            val e = c.end?.let { moved[it.targetId]?.let { t -> pointAt(t, it.at) } }
            if (s == null && e == null) continue
            val shape = connectorShape(c) ?: continue
            val ends = ends(c)
            val clean = withEnds(shape, s?.first ?: ends[0], s?.second ?: ends[1], e?.first ?: ends[2], e?.second ?: ends[3])
            out[c.id] = clean to infoOf(clean, c.id, c.start, c.end)
        }
        return out
    }
}
