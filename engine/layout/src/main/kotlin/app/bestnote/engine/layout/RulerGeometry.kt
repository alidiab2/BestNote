package app.bestnote.engine.layout

import app.bestnote.core.model.RectPt
import app.bestnote.core.model.Vec2
import app.bestnote.core.model.VectorDiagram
import app.bestnote.core.model.VectorShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Redraws a hand-drawn board diagram "with a ruler and compass".
 *
 * The vision model already returns vector shapes, but its coordinates carry the lecturer's wobble.
 * This pass is deterministic and runs after the model, so the result is clean even when the model
 * is sloppy:
 *
 * - **Weld:** line, arrow and polyline endpoints that almost touch become one shared point.
 * - **Square up:** near-horizontal/vertical segments become exact (within [angleTolDeg]);
 *   near-45° and near-15° diagonals snap with tighter tolerances, so deliberate angles survive.
 *   Shared points move together, so corners stay closed.
 * - **Close:** polylines whose ends almost meet become closed polygons.
 * - **Regularise:** near-circles become circles, nearly-unrotated rectangles become axis aligned.
 * - **Anchor:** a loose arrow end near a box or circle lands exactly on its outline.
 * - **Grid:** every coordinate rounds to half a point.
 */
class RulerGeometry(
    private val angleTolDeg: Float = 6f,
    private val weldFraction: Float = 0.03f,
    private val circleTolerance: Float = 0.1f,
) {
    fun straighten(diagram: VectorDiagram): VectorDiagram {
        val box = diagram.viewBox
        val tol = hypot(box.width, box.height) * weldFraction
        val graph = VertexGraph(tol)

        // 1. Register every segment endpoint so welding can share them.
        val refs = diagram.shapes.map { shape ->
            when (shape) {
                is VectorShape.Line -> listOf(graph.add(shape.from), graph.add(shape.to))
                is VectorShape.Arrow -> listOf(graph.add(shape.from), graph.add(shape.to))
                is VectorShape.Polyline -> shape.points.map(graph::add)
                else -> emptyList()
            }
        }
        graph.weld()

        // 2. Square up every segment through the shared vertex table.
        diagram.shapes.mapIndexed { i, shape ->
            val r = refs[i]
            val segments = when (shape) {
                is VectorShape.Polyline -> r.zipWithNext() + (if (shape.closed && r.size > 2) listOf(r.last() to r.first()) else emptyList())
                is VectorShape.Line, is VectorShape.Arrow -> listOf(r[0] to r[1])
                else -> emptyList()
            }
            segments.forEach { (a, b) -> graph.connect(a, b) }
            segments
        }.flatten().forEach { (a, b) -> graph.snapSegment(a, b, angleTolDeg) }

        // 3. Rebuild shapes from the cleaned vertices.
        val rebuilt = diagram.shapes.mapIndexed { i, shape ->
            val r = refs[i]
            when (shape) {
                is VectorShape.Line -> shape.copy(from = graph[r[0]], to = graph[r[1]])
                is VectorShape.Arrow -> shape.copy(from = graph[r[0]], to = graph[r[1]])
                is VectorShape.Polyline -> closePolyline(shape, r.map(graph::get), tol)
                is VectorShape.Rect -> shape.copy(rotationDeg = snapAngle(shape.rotationDeg, angleTolDeg))
                is VectorShape.Ellipse -> shape.copy(bounds = roundIfCircle(shape.bounds))
                else -> shape
            }
        }

        // 4. Anchor loose arrow ends onto nearby outlines, then 5. snap to the half-point grid.
        val outlines = rebuilt.filter { it is VectorShape.Rect || it is VectorShape.Ellipse }
        val anchored = rebuilt.mapIndexed { i, shape ->
            if (shape !is VectorShape.Arrow) return@mapIndexed shape
            val r = refs[i]
            shape.copy(
                from = if (graph.isShared(r[0])) shape.from else anchor(shape.from, outlines, tol),
                to = if (graph.isShared(r[1])) shape.to else anchor(shape.to, outlines, tol),
            )
        }
        return VectorDiagram(box, anchored.map(::toGrid))
    }

    private fun closePolyline(shape: VectorShape.Polyline, pts: List<Vec2>, tol: Float): VectorShape.Polyline {
        if (shape.closed || pts.size < 4 || (pts.first() - pts.last()).length() > tol) return shape.copy(points = pts)
        return shape.copy(points = pts.dropLast(1), closed = true)
    }

    private fun roundIfCircle(b: RectPt): RectPt {
        val big = maxOf(b.width, b.height)
        if (big <= 0f || abs(b.width - b.height) > big * circleTolerance) return b
        val d = (b.width + b.height) / 2f
        val c = b.center
        return RectPt(c.x - d / 2f, c.y - d / 2f, c.x + d / 2f, c.y + d / 2f)
    }

    /** Projects [p] onto the closest outline within [tol]; unchanged if nothing is close. */
    private fun anchor(p: Vec2, outlines: List<VectorShape>, tol: Float): Vec2 =
        outlines.mapNotNull { shape ->
            when (shape) {
                is VectorShape.Rect -> if (shape.rotationDeg == 0f) nearestOnRect(p, shape.rect) else null
                is VectorShape.Ellipse -> nearestOnEllipse(p, shape.bounds)
                else -> null
            }
        }.filter { (it - p).length() <= tol }.minByOrNull { (it - p).length() } ?: p

    private fun nearestOnRect(p: Vec2, r: RectPt): Vec2 {
        val x = p.x.coerceIn(r.left, r.right)
        val y = p.y.coerceIn(r.top, r.bottom)
        if (!r.contains(p)) return Vec2(x, y)
        // Inside: push to the closest edge.
        val d = listOf(p.x - r.left, r.right - p.x, p.y - r.top, r.bottom - p.y)
        return when (d.indexOf(d.min())) {
            0 -> Vec2(r.left, p.y)
            1 -> Vec2(r.right, p.y)
            2 -> Vec2(p.x, r.top)
            else -> Vec2(p.x, r.bottom)
        }
    }

    private fun nearestOnEllipse(p: Vec2, b: RectPt): Vec2 {
        val c = b.center
        val a = atan2((p.y - c.y) * b.width, (p.x - c.x) * b.height)
        return Vec2(c.x + b.width / 2f * cos(a), c.y + b.height / 2f * sin(a))
    }

    private fun toGrid(shape: VectorShape): VectorShape = when (shape) {
        is VectorShape.Line -> shape.copy(from = grid(shape.from), to = grid(shape.to))
        is VectorShape.Arrow -> shape.copy(from = grid(shape.from), to = grid(shape.to))
        is VectorShape.Polyline -> shape.copy(points = shape.points.map(::grid))
        is VectorShape.Rect -> shape.copy(rect = grid(shape.rect))
        is VectorShape.Ellipse -> shape.copy(bounds = grid(shape.bounds))
        is VectorShape.Label -> shape.copy(at = grid(shape.at))
        is VectorShape.Path -> shape
    }

    private fun grid(v: Vec2) = Vec2(half(v.x), half(v.y))
    private fun grid(r: RectPt) = RectPt(half(r.left), half(r.top), half(r.right), half(r.bottom))
    private fun half(f: Float) = (f * 2f).roundToInt() / 2f

    /** Endpoint table with union-find welding. */
    private class VertexGraph(private val tol: Float) {
        private val points = ArrayList<Vec2>()
        private val parent = ArrayList<Int>()
        private val uses = ArrayList<Int>()
        private val degree = HashMap<Int, Int>()

        fun add(p: Vec2): Int { points += p; parent += parent.size; uses += 0; return points.size - 1 }

        /** Records a segment so diagonal snapping knows which ends other segments depend on. */
        fun connect(a: Int, b: Int) {
            degree.merge(find(a), 1, Int::plus)
            degree.merge(find(b), 1, Int::plus)
        }

        operator fun get(i: Int) = points[find(i)]

        fun isShared(i: Int) = uses[find(i)] > 1

        fun weld() {
            for (i in points.indices) for (j in i + 1 until points.size) {
                if ((points[i] - points[j]).length() <= tol) union(i, j)
            }
            // Each welded group moves to its average position.
            val groups = points.indices.groupBy(::find)
            for ((root, members) in groups) {
                points[root] = Vec2(members.map { points[it].x }.average().toFloat(), members.map { points[it].y }.average().toFloat())
                uses[root] = members.size
            }
        }

        fun snapSegment(ia: Int, ib: Int, tolDeg: Float) {
            val a = find(ia); val b = find(ib)
            if (a == b) return
            val pa = points[a]; val pb = points[b]
            val deg = Math.toDegrees(atan2((pb.y - pa.y).toDouble(), (pb.x - pa.x).toDouble())).toFloat()
            val target = snapAngle(deg, tolDeg)
            when {
                target == deg -> return
                // Horizontal / vertical: align the coordinate on both ends so shared corners stay square.
                isMultipleOf(target, 180f) -> { val y = (pa.y + pb.y) / 2f; points[a] = pa.copy(y = y); points[b] = pb.copy(y = y) }
                isMultipleOf(target + 90f, 180f) -> { val x = (pa.x + pb.x) / 2f; points[a] = pa.copy(x = x); points[b] = pb.copy(x = x) }
                // Diagonal: rotate a free end. If both ends are corners of other segments, moving
                // either would bend those, so the segment keeps the angle its corners give it.
                else -> {
                    val da = degree[a] ?: 0
                    val db = degree[b] ?: 0
                    if (da > 1 && db > 1) return
                    val len = (pb - pa).length()
                    val rad = target * PI.toFloat() / 180f
                    if (db <= da) points[b] = Vec2(pa.x + len * cos(rad), pa.y + len * sin(rad))
                    else points[a] = Vec2(pb.x - len * cos(rad), pb.y - len * sin(rad))
                }
            }
        }

        private fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            parent[i] = r
            return r
        }

        private fun union(i: Int, j: Int) {
            val a = find(i); val b = find(j)
            if (a != b) parent[maxOf(a, b)] = minOf(a, b)
        }

        private fun isMultipleOf(v: Float, step: Float) = abs(v / step - (v / step).roundToInt()) < 1e-4f
    }

    private companion object {
        /**
         * Horizontal/vertical within [tol]; 45° diagonals within 2/3 of it; other 15° steps within
         * 1/3 of it. Anything else is a deliberate angle and is left unchanged.
         */
        fun snapAngle(deg: Float, tol: Float): Float {
            for ((step, t) in listOf(90f to tol, 45f to tol * 2f / 3f, 15f to tol / 3f)) {
                val target = (deg / step).roundToInt() * step
                if (abs(deg - target) <= t) return target
            }
            return deg
        }
    }
}
