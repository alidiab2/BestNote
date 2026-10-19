package app.bestnote.engine.canvas.shape

import app.bestnote.core.model.ShapeInfo
import app.bestnote.core.model.ShapeLink
import app.bestnote.engine.canvas.select.Affine
import app.bestnote.engine.canvas.select.Box
import app.bestnote.engine.canvas.select.SnapGuides
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * CANVAS-17 "glue": shapes snap magnetically onto each other. A line's end snaps to another shape's corners, edge
 * middles and center (or anywhere on its edge, a little less eagerly) and is then attached there; a shape being
 * drawn or moved snaps its own glue points onto the others', or lines its box up edge-to-edge with theirs so shapes
 * tile. Pure Kotlin: page points in, corrections and the markers to draw out.
 */
object ShapeGlue {
    /** Edges pull from this share of the snap distance; points (corners, middles, centers) from all of it. */
    const val EDGE_SHARE = 0.6f

    /** A side turns onto a nearby edge's angle when within this much of it (radians, 15°). */
    val FLUSH_MAX_TURN = Math.toRadians(15.0).toFloat()

    /** A visible snap: where to draw the marker, and what was hit. */
    data class Marker(val x: Float, val y: Float, val type: AnchorType?, val attached: Boolean)

    /** A connector end that snapped to ([x], [y]) and is now glued by [link]. [type] null = somewhere on an edge. */
    data class EndSnap(val x: Float, val y: Float, val link: ShapeLink, val type: AnchorType?) {
        val marker get() = Marker(x, y, type, attached = true)
    }

    /**
     * Where the end of a line at ([x], [y]) glues among [targets], within [tolerance]: the nearest glue point,
     * else the nearest edge within [EDGE_SHARE] of it, else null.
     */
    fun snapEnd(x: Float, y: Float, targets: List<ShapeInfo>, tolerance: Float, exclude: String? = null): EndSnap? {
        var best: EndSnap? = null
        var bestD = tolerance
        for (t in targets) {
            if (t.id == exclude) continue
            for (a in ShapeGeometry.anchors(t)) {
                val d = hypot(a.x - x, a.y - y)
                if (d <= bestD) { bestD = d; best = EndSnap(a.x, a.y, ShapeLink(t.id, a.at), a.type) }
            }
        }
        if (best != null) return best
        bestD = tolerance * EDGE_SHARE
        for (t in targets) {
            if (t.id == exclude) continue
            val hit = ShapeGeometry.nearestOnOutline(t, x, y)
            if (hit.distance <= bestD) { bestD = hit.distance; best = EndSnap(hit.x, hit.y, ShapeLink(t.id, hit.at), null) }
        }
        return best
    }

    /** A move correction ([dx], [dy]) and what to draw for it: point markers and guide lines (x or y, page points). */
    data class Move(val dx: Float, val dy: Float, val markers: List<Marker>, val guideX: Float?, val guideY: Float?) {
        val snapped: Boolean get() = markers.isNotEmpty() || guideX != null || guideY != null

        companion object {
            val NONE = Move(0f, 0f, emptyList(), null, null)
        }
    }

    /**
     * Where moving glue points [moving] (and their [box]) snap among [targets]: the closest pair of glue points within
     * [tolerance] (corner on corner, middle on middle, center on center…); otherwise the box's edges and center lines
     * onto the other shapes' (edge-to-edge tiling and alignment), each axis on its own.
     */
    fun snapMove(moving: List<Anchor>, box: Box, targets: List<ShapeInfo>, tolerance: Float): Move {
        var best = tolerance
        var hit: Pair<Anchor, Anchor>? = null
        for (t in targets) for (b in ShapeGeometry.anchors(t)) for (a in moving) {
            // Like with like (corner on corner, middle on middle, center on center); a polyline's ends on anything.
            if (a.type != b.type && a.type != AnchorType.End && b.type != AnchorType.End) continue
            val d = hypot(b.x - a.x, b.y - a.y)
            if (d < best) { best = d; hit = a to b }
        }
        hit?.let { (a, b) -> return Move(b.x - a.x, b.y - a.y, listOf(Marker(b.x, b.y, b.type, attached = false)), null, null) }
        if (targets.isEmpty()) return Move.NONE
        val xs = ArrayList<Float>(targets.size * 3); val ys = ArrayList<Float>(targets.size * 3)
        for (t in targets) {
            val b = ShapeGeometry.bounds(t)
            xs += b.minX; xs += b.centerX; xs += b.maxX
            ys += b.minY; ys += b.centerY; ys += b.maxY
        }
        val s = SnapGuides.snap(box, SnapGuides.Targets(xs.toFloatArray(), ys.toFloatArray()), tolerance)
        if (!s.snapped) return Move.NONE
        return Move(s.dx, s.dy, emptyList(), s.guideX, s.guideY)
    }

    /** A side laid flush on another shape's edge: [m] turns and moves the shape; [markers] show where. */
    data class Flush(val m: Affine, val markers: List<Marker>)

    /**
     * REQ-007: a closed polygon ([points], in order) drawn against another shape's straight edge (a block on an incline)
     * lies on it: the nearest of its sides that runs within [FLUSH_MAX_TURN] of a target edge, with its middle within
     * [tolerance] of that edge's line and over the edge, is turned to the edge's angle and moved onto its line, so the
     * two share one line (no second, slightly offset line beside it). Then it slides along the edge so a corner meets
     * the edge's end when that is within [tolerance]. Null when no side is close.
     */
    fun snapFlush(points: List<Pair<Float, Float>>, targets: List<ShapeInfo>, tolerance: Float): Flush? {
        val n = points.size
        if (n < 3) return null
        var best: Flush? = null
        var bestScore = Float.MAX_VALUE
        for (t in targets) for (e in ShapeGeometry.edges(t)) {
            val (cx, cy, dx, dy) = e
            val el = hypot(dx - cx, dy - cy)
            if (el <= 0f) continue
            val ux = (dx - cx) / el; val uy = (dy - cy) / el
            val edgeAngle = atan2(uy, ux)
            for (k in 0 until n) {
                val (ax, ay) = points[k]
                val (bx, by) = points[(k + 1) % n]
                val sl = hypot(bx - ax, by - ay)
                if (sl <= 0f) continue
                // The turn that makes this side parallel to the edge (either direction), in -90°..90°.
                var turn = edgeAngle - atan2(by - ay, bx - ax)
                while (turn > PI / 2) turn -= PI.toFloat()
                while (turn <= -PI / 2) turn += PI.toFloat()
                if (abs(turn) > FLUSH_MAX_TURN) continue
                val mx = (ax + bx) / 2; val my = (ay + by) / 2
                val along = (mx - cx) * ux + (my - cy) * uy
                if (along < 0f || along > el) continue
                val off = (mx - cx) * -uy + (my - cy) * ux
                if (abs(off) > tolerance) continue
                val score = abs(off) + tolerance * abs(turn) / FLUSH_MAX_TURN
                if (score >= bestScore) continue
                bestScore = score
                // Turn about the side's middle, then move it onto the edge's line.
                var m = Affine.rotateAbout(turn, mx, my).then(Affine.translate(uy * off, -ux * off))
                val markers = arrayListOf(Marker(cx + ux * along, cy + uy * along, null, attached = false))
                // Slide along the edge so a corner meets one of the edge's ends.
                var slide = Float.MAX_VALUE
                var corner: Marker? = null
                for ((px, py) in listOf(ax to ay, bx to by)) {
                    val qx = m.mapX(px, py); val qy = m.mapY(px, py)
                    for ((ex, ey) in listOf(cx to cy, dx to dy)) {
                        val s = (ex - qx) * ux + (ey - qy) * uy
                        if (abs(s) <= tolerance && abs(s) < abs(slide)) { slide = s; corner = Marker(ex, ey, AnchorType.Corner, attached = false) }
                    }
                }
                if (corner != null) {
                    m = m.then(Affine.translate(ux * slide, uy * slide))
                    markers[0] = corner
                }
                best = Flush(m, markers)
            }
        }
        return best
    }

    /** The glue points of [infos] and their box, as [snapMove] takes them. */
    fun movingOf(infos: List<ShapeInfo>): Pair<List<Anchor>, Box>? {
        if (infos.isEmpty()) return null
        val anchors = infos.flatMap { ShapeGeometry.anchors(it) }
        val box = Box.union(infos.map(ShapeGeometry::bounds)) ?: return null
        return anchors to box
    }

    /** True when ([x], [y]) is still where [link] glues it on [target] (within [tolerance]). */
    fun stillAttached(x: Float, y: Float, target: ShapeInfo, link: ShapeLink, tolerance: Float): Boolean {
        val (px, py) = ShapeGeometry.pointAt(target, link.at)
        return abs(px - x) <= tolerance && abs(py - y) <= tolerance
    }
}
