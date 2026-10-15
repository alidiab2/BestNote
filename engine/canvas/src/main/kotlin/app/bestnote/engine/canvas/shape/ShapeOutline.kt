package app.bestnote.engine.canvas.shape

import app.bestnote.engine.recognition.RecognizedShape
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A polyline as parallel coordinate arrays. */
class Polyline(val xs: FloatArray, val ys: FloatArray) {
    val size: Int get() = xs.size

    fun length(): Float {
        var l = 0f
        for (i in 1 until size) l += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        return l
    }

    /** [n] points evenly spaced by arc length, ends included. */
    fun resample(n: Int): Polyline {
        val ox = FloatArray(n)
        val oy = FloatArray(n)
        if (size == 1 || n == 1) {
            ox.fill(xs[0]); oy.fill(ys[0])
            return Polyline(ox, oy)
        }
        val cum = FloatArray(size)
        for (i in 1 until size) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        val total = cum.last()
        var j = 0
        for (i in 0 until n) {
            val s = total * i / (n - 1)
            while (j < size - 2 && cum[j + 1] < s) j++
            val seg = cum[j + 1] - cum[j]
            val u = if (seg > 0f) ((s - cum[j]) / seg).coerceIn(0f, 1f) else 0f
            ox[i] = xs[j] + (xs[j + 1] - xs[j]) * u
            oy[i] = ys[j] + (ys[j + 1] - ys[j]) * u
        }
        return Polyline(ox, oy)
    }

    companion object {
        /** Point-by-point blend: [t] 0 gives [from], 1 gives [to]; both must have the same size. */
        fun lerp(from: Polyline, to: Polyline, t: Float): Polyline {
            require(from.size == to.size)
            return Polyline(
                FloatArray(to.size) { from.xs[it] + (to.xs[it] - from.xs[it]) * t },
                FloatArray(to.size) { from.ys[it] + (to.ys[it] - from.ys[it]) * t },
            )
        }
    }
}

/**
 * INK-08: the pen path that draws a clean [RecognizedShape], as a dense polyline in the shape's
 * coordinates. Closed shapes start where the hand-drawn stroke started (([startX], [startY])) and
 * go the same way round, so the snap morph moves every part of the stroke the shortest way.
 * Pure Kotlin.
 */
object ShapeOutline {
    fun of(shape: RecognizedShape, startX: Float, startY: Float, spacing: Float = DEFAULT_SPACING): Polyline {
        val out = Builder(spacing)
        when (shape) {
            is RecognizedShape.Line -> { out.moveTo(shape.x0, shape.y0); out.lineTo(shape.x1, shape.y1) }
            is RecognizedShape.Arrow -> {
                out.moveTo(shape.x0, shape.y0)
                out.lineTo(shape.x1, shape.y1)
                shape.barbs.forEachIndexed { i, (bx, by) ->
                    if (i > 0) out.lineTo(shape.x1, shape.y1)
                    out.lineTo(bx, by)
                }
            }
            is RecognizedShape.Ellipse -> ellipse(shape, out)
            is RecognizedShape.Polygon -> if (shape.closed) closedPolygon(shape.points, startX, startY, out) else {
                shape.points.forEachIndexed { i, (x, y) -> if (i == 0) out.moveTo(x, y) else out.lineTo(x, y) }
            }
        }
        return out.build()
    }

    private fun ellipse(e: RecognizedShape.Ellipse, out: Builder) {
        // Ramanujan's perimeter, for the point count.
        val a = e.rx.toDouble(); val b = e.ry.toDouble()
        val h = ((a - b) * (a - b)) / ((a + b) * (a + b))
        val perimeter = PI * (a + b) * (1 + 3 * h / (10 + sqrt(4 - 3 * h)))
        val n = max(MIN_ELLIPSE_POINTS, ceil(perimeter / out.spacing).toInt())
        val c = cos(e.rotationRad.toDouble()); val s = sin(e.rotationRad.toDouble())
        val dir = if (e.clockwise) 1.0 else -1.0
        for (i in 0..n) {
            val t = e.startAngleRad + dir * 2 * PI * i / n
            val u = a * cos(t); val v = b * sin(t)
            out.add((e.cx + u * c - v * s).toFloat(), (e.cy + u * s + v * c).toFloat())
        }
    }

    /** Around the polygon from the boundary point nearest the start, back to it. */
    private fun closedPolygon(points: List<Pair<Float, Float>>, sx: Float, sy: Float, out: Builder) {
        val m = points.size
        var bestEdge = 0
        var bestU = 0f
        var best = Float.MAX_VALUE
        for (k in 0 until m) {
            val (ax, ay) = points[k]
            val (bx, by) = points[(k + 1) % m]
            val dx = bx - ax; val dy = by - ay
            val l2 = dx * dx + dy * dy
            val u = if (l2 > 0f) (((sx - ax) * dx + (sy - ay) * dy) / l2).coerceIn(0f, 1f) else 0f
            val d = hypot(ax + dx * u - sx, ay + dy * u - sy)
            if (d < best) { best = d; bestEdge = k; bestU = u }
        }
        val (ax, ay) = points[bestEdge]
        val (bx, by) = points[(bestEdge + 1) % m]
        val px = ax + (bx - ax) * bestU
        val py = ay + (by - ay) * bestU
        out.moveTo(px, py)
        for (i in 1..m) {
            val (x, y) = points[(bestEdge + i) % m]
            out.lineTo(x, y)
        }
        out.lineTo(px, py)
    }

    private class Builder(val spacing: Float) {
        private val xs = ArrayList<Float>()
        private val ys = ArrayList<Float>()

        fun add(x: Float, y: Float) {
            if (xs.isNotEmpty() && xs.last() == x && ys.last() == y) return
            xs += x; ys += y
        }

        fun moveTo(x: Float, y: Float) = add(x, y)

        fun lineTo(x: Float, y: Float) {
            if (xs.isEmpty()) return add(x, y)
            val x0 = xs.last(); val y0 = ys.last()
            val steps = max(1, ceil(hypot(x - x0, y - y0) / spacing).toInt())
            for (i in 1..steps) add(x0 + (x - x0) * i / steps, y0 + (y - y0) * i / steps)
        }

        fun build() = Polyline(xs.toFloatArray(), ys.toFloatArray())
    }

    /** Page points between outline samples. */
    const val DEFAULT_SPACING = 2f
    private const val MIN_ELLIPSE_POINTS = 48
}
