package app.bestnote.engine.canvas.shape

import app.bestnote.core.model.ShapeInfo
import app.bestnote.core.model.ShapeLink
import app.bestnote.engine.canvas.select.Box
import app.bestnote.engine.canvas.select.SnapGuides
import kotlin.math.abs
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
