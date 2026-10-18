package app.bestnote.engine.handwriting

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pen-drawn shapes: ruler lines, arrows, boxes, ellipses and underlines, with the slight bow and
 * tremor of a hand (port of tools/handwriting/hw/shapes.py). Page units.
 */
internal class ShapeWriter(private val hand: Hand, private val ink: InkBuilder) {
    private val mm get() = hand.unitsPerMm
    private val rnd get() = hand.rnd

    /** A hand-drawn straight line. [ruler] keeps it almost perfectly straight, as along a ruler. */
    /**
     * @param outward a side of a box drawn clockwise: it bows only away from the box's inside, and only a
     *   little, so it never sags into the writing it encloses or far into what is around it.
     */
    fun linePath(a: P, b: P, bow: Float = 0.012f, ruler: Boolean = false, outward: Boolean = false): Poly {
        val len = Geom.dist(a, b)
        if (len < 1e-3f) return listOf(a)
        val n = max(4, (len / (2f * mm)).toInt())
        val nx = -(b.y - a.y) / len
        val ny = (b.x - a.x) / len
        val drawn = rnd.gauss(0f, if (ruler) bow * 0.25f else bow) * len
        // (+ offsets along the left normal: for a clockwise box on the page that is inwards.)
        val bowAmt = if (outward) -min(abs(drawn), 0.8f * mm) else drawn
        val pts = (0..n).map { i ->
            val u = i.toFloat() / n
            val off = bowAmt * sin(PI.toFloat() * u)
            P(a.x + (b.x - a.x) * u + nx * off, a.y + (b.y - a.y) * u + ny * off)
        }
        val dense = Geom.catmullRom(pts, 0.1f * mm)
        return Geom.wobble(dense, rnd, (if (ruler) 0.02f else 0.045f) * mm, 9f * mm)
    }

    fun line(a: P, b: P, color: Int, gap: Gap = Gap.Shape, ruler: Boolean = true, width: Float = 1f) {
        ink.add(linePath(a, b, ruler = ruler), if (ruler) Gap.Ruler else gap, color, speed = if (ruler) 0.8f else 1f, width = width)
    }

    fun dashed(a: P, b: P, color: Int, ruler: Boolean = true) {
        val len = Geom.dist(a, b)
        val dash = 2.2f * mm
        val n = max(1, (len / (dash * 1.8f)).toInt())
        for (i in 0 until n) {
            val u0 = i.toFloat() / n
            val u1 = u0 + 0.55f / n
            val p0 = P(a.x + (b.x - a.x) * u0, a.y + (b.y - a.y) * u0)
            val p1 = P(a.x + (b.x - a.x) * u1, a.y + (b.y - a.y) * u1)
            ink.add(linePath(p0, p1, ruler = ruler), if (i == 0) Gap.Ruler else Gap.Letter, color)
        }
    }

    fun arrowHead(tip: P, angle: Float, size: Float, color: Int) {
        val spread = Math.toRadians(rnd.uniform(24f, 32f).toDouble()).toFloat()
        val l = size * rnd.uniform(0.9f, 1.1f)
        val a = P(tip.x - cos(angle - spread) * l, tip.y - sin(angle - spread) * l)
        val l2 = l * rnd.uniform(0.9f, 1.1f)
        val b = P(tip.x - cos(angle + spread) * l2, tip.y - sin(angle + spread) * l2)
        val pts = Geom.resample(listOf(a, tip), 0.3f * mm) + Geom.resample(listOf(tip, b), 0.3f * mm).drop(1)
        ink.add(Geom.wobble(Geom.catmullRom(pts, 0.08f * mm), rnd, 0.03f * mm, 6f * mm), Gap.Letter, color)
    }

    fun arrow(a: P, b: P, color: Int, ruler: Boolean = true, head: Float = 3f * mm) {
        ink.add(linePath(a, b, bow = if (ruler) 0.012f else 0.03f, ruler = ruler), if (ruler) Gap.Ruler else Gap.Shape, color)
        arrowHead(b, atan2(b.y - a.y, b.x - a.x), head, color)
    }

    /** A curved arrow from [a] to [b] that bows to one side: the margin-note pointer. */
    fun curvedArrow(a: P, b: P, color: Int, bend: Float = 0.25f) {
        val mid = P((a.x + b.x) / 2, (a.y + b.y) / 2)
        val len = Geom.dist(a, b)
        val nx = -(b.y - a.y) / max(len, 1e-3f)
        val ny = (b.x - a.x) / max(len, 1e-3f)
        val c = P(mid.x + nx * len * bend, mid.y + ny * len * bend)
        val path = Geom.quad(a, c, b, 0.1f * mm)
        ink.add(Geom.wobble(path, rnd, 0.04f * mm, 8f * mm), Gap.Shape, color)
        val tail = path[max(0, path.size - 6)]
        arrowHead(b, atan2(b.y - tail.y, b.x - tail.x), 2.4f * mm, color)
    }

    /** One stroke clockwise from the top-left corner, with a small overshoot where it closes. */
    fun box(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        val j = { rnd.gauss(0f, 0.18f * mm) }
        val c = listOf(P(left + j(), top + j()), P(right + j(), top + j()), P(right + j(), bottom + j()), P(left + j(), bottom + j()))
        val pts = ArrayList<P>()
        for (i in 0 until 4) {
            val seg = linePath(c[i], c[(i + 1) % 4], outward = true)
            if (pts.isEmpty()) pts += seg else pts += seg.drop(1)
        }
        pts += linePath(c[0], P(c[0].x + rnd.uniform(1f, 2.5f) * mm, c[0].y + j() * 0.3f)).drop(1)
        ink.add(pts, Gap.Shape, color)
    }

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        val start = Math.toRadians(rnd.uniform(-120f, -60f).toDouble()).toFloat()
        val sweep = (2 * PI).toFloat() + Math.toRadians(rnd.uniform(6f, 20f).toDouble()).toFloat()
        val r = max(rx, ry)
        val noise = SmoothNoise(rnd, (2 * PI).toFloat(), 0.025f)
        val n = max(24, (r * sweep / (0.15f * mm)).toInt())
        val pts = (0..n).map { i ->
            val a = start - sweep * i / n
            val k = 1 + noise(a) - 0.03f * i / n
            P(cx + cos(a) * rx * k, cy + sin(a) * ry * k)
        }
        ink.add(Geom.wobble(pts, rnd, 0.03f * mm, 8f * mm), Gap.Shape, color)
    }

    fun polyline(points: List<P>, closed: Boolean, color: Int, ruler: Boolean = true) {
        if (points.size < 2) return
        val corners = if (closed) points + points[0] else points
        val pts = ArrayList<P>()
        for (i in 0 until corners.size - 1) {
            val seg = linePath(corners[i], corners[i + 1], ruler = ruler)
            if (pts.isEmpty()) pts += seg else pts += seg.drop(1)
        }
        ink.add(pts, Gap.Ruler, color, speed = 0.85f)
    }

    /** A quick hand underline under a title, slightly longer than the words. */
    fun underline(x0: Float, x1: Float, y: Float, color: Int) {
        val over = 0.6f * hand.xh
        val a = P(x0 - over * rnd.uniform(0.2f, 0.8f), y + rnd.gauss(0f, 0.15f * mm))
        val b = P(x1 + over * rnd.uniform(0.2f, 1f), y + rnd.gauss(0f, 0.25f * mm))
        ink.add(linePath(a, b, bow = 0.006f), Gap.Shape, color, speed = 1.25f)
    }

    /** Points of a cubic Bézier from SVG-ish path data (M/L/C/Q/Z, absolute), already scaled. */
    fun path(d: String, map: (Float, Float) -> P, color: Int) {
        val tokens = Regex("[MmLlCcQqZzHhVv]|-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?").findAll(d).map { it.value }.toList()
        var i = 0
        var cmd = 'M'
        var cur = P(0f, 0f)
        var start = cur
        val pts = ArrayList<P>()
        fun num() = tokens[i++].toFloat()
        fun flush() {
            if (pts.size > 1) ink.add(Geom.wobble(Geom.catmullRom(pts.map { map(it.x, it.y) }, 0.1f * mm), rnd, 0.03f * mm, 8f * mm), Gap.Shape, color)
            pts.clear()
        }
        try {
            while (i < tokens.size) {
                val t = tokens[i]
                if (t[0].isLetter()) {
                    cmd = t[0]
                    i++
                }
                when (cmd) {
                    'M', 'm' -> {
                        flush()
                        cur = if (cmd == 'M') P(num(), num()) else cur + P(num(), num())
                        start = cur
                        pts += cur
                        cmd = if (cmd == 'M') 'L' else 'l'
                    }
                    'L', 'l' -> {
                        cur = if (cmd == 'L') P(num(), num()) else cur + P(num(), num())
                        pts += cur
                    }
                    'H', 'h' -> { cur = if (cmd == 'H') P(num(), cur.y) else P(cur.x + num(), cur.y); pts += cur }
                    'V', 'v' -> { cur = if (cmd == 'V') P(cur.x, num()) else P(cur.x, cur.y + num()); pts += cur }
                    'C', 'c' -> {
                        val o = if (cmd == 'c') cur else P(0f, 0f)
                        val c1 = o + P(num(), num())
                        val c2 = o + P(num(), num())
                        val e = o + P(num(), num())
                        pts += Geom.cubic(cur, c1, c2, e, hypot(e.x - cur.x, e.y - cur.y) / 12f + 1e-3f).drop(1)
                        cur = e
                    }
                    'Q', 'q' -> {
                        val o = if (cmd == 'q') cur else P(0f, 0f)
                        val c = o + P(num(), num())
                        val e = o + P(num(), num())
                        pts += Geom.quad(cur, c, e, hypot(e.x - cur.x, e.y - cur.y) / 10f + 1e-3f).drop(1)
                        cur = e
                    }
                    'Z', 'z' -> {
                        pts += start
                        cur = start
                        flush()
                        cmd = 'M' // stray numbers after Z start a new subpath instead of looping
                    }
                    else -> i++
                }
            }
        } catch (_: RuntimeException) {
            // Malformed path data: draw what was parsed.
        }
        flush()
    }
}
