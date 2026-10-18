package app.bestnote.engine.layout.figlib

import app.bestnote.core.model.RectPt
import app.bestnote.core.model.StrokeStyle
import app.bestnote.core.model.Vec2
import app.bestnote.core.model.VectorDiagram
import app.bestnote.core.model.VectorShape
import app.bestnote.engine.layout.NotebookPalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FIG-LIB: the drawing kit every library element draws with. Coordinates are MATHS coordinates (y up,
 * angles counter-clockwise in degrees) in label units: a label's font size is [FONT], so the app can
 * scale a figure so its labels come out at the note's label size. Only the shapes the note's writer
 * turns into editable CANVAS-17 strokes are made (lines, arrows, polylines, polygons, ellipses) plus
 * typeset labels. Labels are placed by [build] beside what they name, never on a stroke or on each
 * other.
 */
class Pen {
    /** What a stroke means; the note's template gives each role its colour. */
    enum class Role(val argb: Long) {
        /** The drawing itself: the pen. */
        Ink(NotebookPalette.INK),

        /** Forces, vectors, rays, curves of interest: the heading colour. */
        Vector(NotebookPalette.EMPHASIS),

        /** Results and stressed parts: the result colour. */
        Accent(NotebookPalette.ESSENTIAL),

        /** Construction, shading, annotations: the margin colour. */
        Note(NotebookPalette.ANNOTATION),
    }

    private class Req(val text: String, val anchor: Vec2, val dir: Vec2?, val gap: Float, val role: Role, val inside: Boolean, val size: Float, val tick: Boolean = false)

    private val shapes = ArrayList<VectorShape>()
    private val reqs = ArrayList<Req>()
    private val keepOut = ArrayList<FloatArray>()
    private val soft = ArrayList<Triple<Vec2, Vec2, Role>>()

    /** The labels as placed by the last [build] (maths coordinates: centre x, y, half width, half height, 1 = written inside a shape), for checks. */
    var placed: List<Pair<String, FloatArray>> = emptyList()
        private set

    val isEmpty: Boolean get() = shapes.isEmpty() && reqs.isEmpty() && soft.isEmpty()

    private fun style(role: Role, dashed: Boolean) = StrokeStyle(colorArgb = role.argb, dashed = dashed)

    private fun ok(vararg p: Vec2) = p.all { it.x.isFinite() && it.y.isFinite() }

    fun line(a: Vec2, b: Vec2, role: Role = Role.Ink, dashed: Boolean = false) {
        if (ok(a, b) && a.dist(b) > EPS) shapes += VectorShape.Line(a, b, style(role, dashed))
    }

    fun arrow(from: Vec2, to: Vec2, role: Role = Role.Vector, dashed: Boolean = false, head: Float = HEAD) {
        if (ok(from, to) && from.dist(to) > EPS) shapes += VectorShape.Arrow(from, to, head, style(role, dashed))
    }

    /** A dimension or two-way arrow: two arrows from the middle (one editable shape each). */
    fun doubleArrow(a: Vec2, b: Vec2, role: Role = Role.Ink, dashed: Boolean = false) {
        val m = mid(a, b)
        arrow(m, a, role, dashed)
        arrow(m, b, role, dashed)
    }

    fun poly(points: List<Vec2>, closed: Boolean = false, role: Role = Role.Ink, dashed: Boolean = false) {
        val p = points.filter { ok(it) }
        if (p.size >= 2) shapes += VectorShape.Polyline(p, closed, style(role, dashed))
    }

    fun circle(c: Vec2, r: Float, role: Role = Role.Ink, dashed: Boolean = false) = ellipse(c, r, r, role, dashed)

    fun ellipse(c: Vec2, rx: Float, ry: Float, role: Role = Role.Ink, dashed: Boolean = false) {
        if (ok(c) && rx > EPS && ry > EPS) shapes += VectorShape.Ellipse(RectPt(c.x - rx, c.y - ry, c.x + rx, c.y + ry), style(role, dashed))
    }

    /** A filled dot (a junction, a point, an electron): a tiny circle with one inside it, so the pen fills it. */
    fun dot(c: Vec2, r: Float = 1.6f, role: Role = Role.Ink) {
        circle(c, r, role)
        circle(c, r * 0.45f, role)
    }

    /** An arc of a circle from [a0] to [a1] degrees (counter-clockwise). */
    fun arc(c: Vec2, r: Float, a0: Float, a1: Float, role: Role = Role.Ink, dashed: Boolean = false) {
        poly(arcPoints(c, r, r, a0, a1), role = role, dashed = dashed)
    }

    /** A rectangle centred at [c], turned by [rot] degrees, as one closed polygon. */
    fun rect(c: Vec2, w: Float, h: Float, rot: Float = 0f, role: Role = Role.Ink, dashed: Boolean = false): List<Vec2> {
        val u = dir(rot)
        val v = dir(rot + 90f)
        val pts = listOf(c - u * (w / 2) - v * (h / 2), c + u * (w / 2) - v * (h / 2), c + u * (w / 2) + v * (h / 2), c - u * (w / 2) + v * (h / 2))
        poly(pts, closed = true, role = role, dashed = dashed)
        return pts
    }

    /** Ground, wall or ceiling: the surface [a]→[b] with short slanted hatch strokes on its [side] (+1 = left of a→b, −1 = right). */
    fun surface(a: Vec2, b: Vec2, side: Int = -1, spacing: Float = 6f, depth: Float = 5f, role: Role = Role.Ink) {
        line(a, b, role)
        val len = a.dist(b)
        if (len < EPS) return
        val t = (b - a) * (1f / len)
        val n = Vec2(-t.y, t.x) * side.toFloat()
        val slant = (n * depth) - t * (depth * 0.8f)
        var s = spacing * 0.5f
        while (s < len - spacing * 0.2f) {
            val p = a + t * s
            line(p, p + slant, role)
            s += spacing
        }
    }

    /** Hatching inside [polygon] (shading of an area or region), lines at [angle] degrees, [spacing] apart. */
    fun hatch(polygon: List<Vec2>, spacing: Float = 4.5f, angle: Float = 45f, role: Role = Role.Note) {
        for ((a, b) in hatchSegments(polygon, spacing, angle)) shade(a, b, role)
    }

    /** A shading stroke: labels may sit on it, and it is cut around them when the drawing is built. */
    fun shade(a: Vec2, b: Vec2, role: Role = Role.Note) {
        if (ok(a, b) && a.dist(b) > EPS) soft += Triple(a, b, role)
    }

    /** Right-angle square at [vertex] between the directions [d1] and [d2] (degrees). */
    fun rightAngle(vertex: Vec2, d1: Float, d2: Float, size: Float = 5f, role: Role = Role.Ink) {
        val p1 = vertex + dir(d1) * size
        val p2 = vertex + dir(d2) * size
        poly(listOf(p1, p1 + dir(d2) * size, p2), role = role)
    }

    /** An angle arc at [vertex] from direction [from] to [to] (degrees, the short way round) with its [label]. */
    fun angle(vertex: Vec2, from: Float, to: Float, r: Float = 12f, label: String? = null, role: Role = Role.Ink, arcs: Int = 1) {
        var a0 = norm360(from)
        var sweep = norm360(to - from)
        if (sweep > 180f) {
            a0 = norm360(to)
            sweep = 360f - sweep
        }
        if (abs(sweep - 90f) < 0.5f && label == null) return rightAngle(vertex, a0, a0 + 90f, r * 0.45f, role)
        var radius = r
        if (label != null) {
            val m = a0 + sweep / 2
            // A narrow angle: out along the bisector until the wedge is wide enough for the label, the arc just inside it.
            val (w, h) = extent(label)
            val d = dir(m)
            val reach = abs(d.x) * w / 2 + abs(d.y) * h / 2
            val half = sin(Math.toRadians((sweep / 2).toDouble()).toFloat()).coerceAtLeast(0.05f)
            val centre = max(r + 2f + reach, (hypot(w, h) / 2 + 2.5f) / half)
            if (centre <= 2.6f * r + reach) {
                radius = max(r, centre - hypot(w, h) / 2 - 2.5f)
                label(label, vertex + d * (centre - reach - 1.5f), d, gap = 1.5f, role = role)
            } else {
                // Too narrow to hold it: beside the arc, wherever it is free.
                label(label, vertex + d * (r + 2f), d, gap = 1.5f, role = role)
            }
        }
        for (i in 0 until arcs.coerceIn(1, 3)) arc(vertex, radius + i * 2.2f, a0, a0 + sweep, role)
    }

    /** Equal-length tick marks ([n] short strokes across the middle of a→b). */
    fun ticks(a: Vec2, b: Vec2, n: Int, role: Role = Role.Ink) {
        val t = unit(b - a)
        val nn = Vec2(-t.y, t.x)
        val m = mid(a, b)
        for (i in 0 until n.coerceIn(1, 4)) {
            val c = m + t * ((i - (n - 1) / 2f) * 2.2f)
            line(c - nn * 3f, c + nn * 3f, role)
        }
    }

    /** Parallel marks (small arrowheads) on a→b. */
    fun parallelMark(a: Vec2, b: Vec2, n: Int, role: Role = Role.Ink) {
        val t = unit(b - a)
        val nn = Vec2(-t.y, t.x)
        val m = mid(a, b)
        for (i in 0 until n.coerceIn(1, 3)) {
            val c = m + t * (i * 2.5f)
            poly(listOf(c - t * 2.5f + nn * 2.5f, c, c - t * 2.5f - nn * 2.5f), role = role)
        }
    }

    /** A dimension line beside a→b, [offset] to its left (negative: right), with extension lines and a centred label. */
    fun dimension(a: Vec2, b: Vec2, label: String?, offset: Float = 8f, role: Role = Role.Ink) {
        val t = unit(b - a)
        val n = Vec2(-t.y, t.x) * offset
        val a1 = a + n
        val b1 = b + n
        if (abs(offset) > 2f) {
            val e = unit(n) * 2f
            line(a + unit(n) * 1.5f, a1 + e, role, dashed = false)
            line(b + unit(n) * 1.5f, b1 + e, role, dashed = false)
        }
        doubleArrow(a1, b1, role)
        if (label != null) label(label, mid(a1, b1), unit(n).takeIf { it.length() > 0f } ?: t.rot90(), gap = 1.5f, role = role)
    }

    /** A curly brace from [a] to [b], bulging to the left of a→b, with [label] at its tip. */
    fun brace(a: Vec2, b: Vec2, label: String?, depth: Float = 5f, role: Role = Role.Ink) {
        val t = unit(b - a)
        val n = Vec2(-t.y, t.x)
        val len = a.dist(b)
        val pts = ArrayList<Vec2>()
        for (i in 0..40) {
            val s = i / 40f
            // Two quarter-wave humps with a cusp in the middle.
            val h = depth * (if (s < 0.5f) curl(s * 2) else curl((1 - s) * 2))
            pts += a + t * (len * s) + n * h
        }
        poly(pts, role = role)
        if (label != null) label(label, a + t * (len / 2) + n * (depth + 1f), n, gap = 1.5f, role = role)
    }

    private fun curl(s: Float): Float = if (s < 0.15f) s / 0.15f * 0.5f else if (s < 0.85f) 0.5f else 0.5f + (s - 0.85f) / 0.15f * 0.5f

    /** A spring (helix seen from the side) from [a] to [b]: [coils] loops of [radius], short straight ends. */
    fun spring(a: Vec2, b: Vec2, coils: Int, radius: Float, role: Role = Role.Ink) {
        val len = a.dist(b)
        if (len < EPS) return
        val t = unit(b - a)
        val n = Vec2(-t.y, t.x)
        val lead = min(len * 0.12f, 6f)
        val body = len - 2 * lead
        val k = coils.coerceIn(2, 30)
        val pts = arrayListOf(a, a + t * lead)
        val steps = k * 16
        // A prolate cycloid: loops that overlap a little, like a coil drawn from the side.
        val adv = body / (2 * PI.toFloat() * k)
        val loop = radius * 0.6f
        for (i in 0..steps) {
            val th = i.toFloat() / steps * k * 2 * PI.toFloat()
            val end = if (i == 0 || i == steps) 0f else 1f
            val along = adv * th - loop * sin(th) * end
            pts += a + t * (lead + along) - n * (radius * cos(th) * end)
        }
        pts += b
        poly(pts, role = role)
    }

    /** A zigzag (resistor, wavy spring) from [a] to [b] with [n] teeth. */
    fun zigzag(a: Vec2, b: Vec2, n: Int, amp: Float, role: Role = Role.Ink) {
        val t = unit(b - a)
        val nn = Vec2(-t.y, t.x)
        val len = a.dist(b)
        val pts = arrayListOf(a)
        val k = n.coerceIn(2, 20)
        for (i in 0 until 2 * k) pts += a + t * (len * (i + 0.5f) / (2 * k)) + nn * (if (i % 2 == 0) amp else -amp)
        pts += b
        poly(pts, role = role)
    }

    /**
     * A label beside [anchor], in direction [dir] (null: wherever it is free, above-right first), [gap]
     * from it. [inside]: written exactly at the anchor (a block's mass), still kept clear of later labels.
     */
    fun label(text: String?, anchor: Vec2, dir: Vec2? = null, gap: Float = 2f, role: Role = Role.Ink, inside: Boolean = false, size: Float = FONT, tick: Boolean = false) {
        val t = text?.trim()?.take(FigSpec.MAX_TEXT)?.takeIf { it.isNotEmpty() } ?: return
        if (!ok(anchor)) return
        if (reqs.any { it.text == t && it.anchor.dist(anchor) < 1f }) return
        reqs += Req(t, anchor, dir?.let(::unit)?.takeIf { it.length() > 0.5f }, gap, role, inside, size, tick)
    }

    /** An area labels must avoid (maths coordinates). */
    fun keepClear(r: RectPt) {
        keepOut += floatArrayOf(r.left, r.top, r.right, r.bottom)
    }

    /**
     * The drawing: labels placed, y flipped to the page's downward axis, a small margin around
     * everything. Null when nothing was drawn.
     */
    fun build(): VectorDiagram? {
        if (shapes.isEmpty() && reqs.isEmpty() && soft.isEmpty()) return null
        val obstacles = ArrayList<Vec2>()
        for (s in shapes) obstacles += samples(s)
        val boxes = ArrayList<FloatArray>(keepOut)
        val out = ArrayList<Pair<String, FloatArray>>()
        val labels = ArrayList<VectorShape.Label>()
        for (r in reqs) {
            val (w, h) = extent(r.text, r.size)
            val c = if (r.inside) r.anchor else place(r, w, h, obstacles, boxes)
            val box = floatArrayOf(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)
            boxes += box
            out += r.text to floatArrayOf(c.x, c.y, w / 2, h / 2, if (r.inside) 1f else 0f)
            labels += VectorShape.Label(Vec2(c.x, -c.y), r.text, latex = true, style = style(r.role, false))
        }
        placed = out
        // Shading is cut around the labels written on it.
        for ((a, b, role) in soft) for ((p, q) in cut(a, b, out.map { it.second })) shapes += VectorShape.Line(p, q, style(role, false))
        val flipped = shapes.map(::flip) + labels
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var rr = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        fun grow(x: Float, y: Float) {
            l = min(l, x); rr = max(rr, x); t = min(t, y); b = max(b, y)
        }
        for (s in flipped) if (s !is VectorShape.Label) samples(s).forEach { grow(it.x, it.y) }
        for ((_, p) in out) {
            grow(p[0] - p[2], -p[1] - p[3]); grow(p[0] + p[2], -p[1] + p[3])
        }
        if (!l.isFinite() || rr - l < EPS && b - t < EPS) return null
        val m = MARGIN
        return VectorDiagram(RectPt(l - m, t - m, max(rr, l + 1f) + m, max(b, t + 1f) + m), flipped)
    }

    private fun place(r: Req, w: Float, h: Float, obstacles: List<Vec2>, boxes: List<FloatArray>): Vec2 {
        fun hits(c: Vec2): Int {
            val pad = 1.2f
            val x0 = c.x - w / 2 - pad
            val x1 = c.x + w / 2 + pad
            val y0 = c.y - h / 2 - pad
            val y1 = c.y + h / 2 + pad
            var n = obstacles.count { it.x in x0..x1 && it.y in y0..y1 }
            n += 50 * boxes.count { o -> x0 < o[2] && o[0] < x1 && y0 < o[3] && o[1] < y1 }
            return n
        }
        val base = r.dir ?: Vec2(0.7071f, 0.7071f)
        val baseAngle = atan2(base.y, base.x) * 180f / PI.toFloat()
        // A tick's number stays at its tick: this side of the axis or the other, never wandering off.
        if (r.tick && r.dir != null) {
            val d = r.dir
            val reach = abs(d.x) * w / 2 + abs(d.y) * h / 2
            val near = r.anchor + d * (reach + r.gap)
            val far = r.anchor - d * (reach + r.gap + 4f)
            return if (hits(near) == 0 || hits(near) <= hits(far)) near else far
        }
        val offsets = if (r.dir != null) listOf(0f, 20f, -20f, 40f, -40f, 65f, -65f, 90f, -90f, 120f, -120f, 150f, -150f, 180f) else (0 until 12).map { it * 30f }
        var best: Vec2? = null
        var bestHits = Int.MAX_VALUE
        // Nearest first: a small turn or a little farther out costs about the same.
        val cands = ArrayList<Pair<Float, Vec2>>()
        for ((ki, k) in listOf(1f, 1.7f, 2.6f, 3.8f).withIndex()) for (o in offsets) {
            val d = dir(baseAngle + o)
            // Distance from the anchor to the box's edge along d, plus the gap.
            val reach = abs(d.x) * w / 2 + abs(d.y) * h / 2
            cands += (abs(o) / 20f + ki * 1.1f) to (r.anchor + d * (reach + r.gap * k + (k - 1f) * 3f))
        }
        for ((_, c) in cands.sortedBy { it.first }) {
            val n = hits(c)
            if (n == 0) return c
            if (n < bestHits) { bestHits = n; best = c }
        }
        return best ?: r.anchor
    }

    /** The parts of a→b outside every label box (centre, half extents), each box grown by a little air. */
    private fun cut(a: Vec2, b: Vec2, boxes: List<FloatArray>): List<Pair<Vec2, Vec2>> {
        var pieces = listOf(0f to 1f)
        val d = b - a
        for (bx in boxes) {
            val pad = 1.2f
            // Slab clipping: the t-range of a→b inside the box.
            var t0 = 0f
            var t1 = 1f
            var inside = true
            for ((pa, da, lo, hi) in listOf(arrayOf(a.x, d.x, bx[0] - bx[2] - pad, bx[0] + bx[2] + pad), arrayOf(a.y, d.y, bx[1] - bx[3] - pad, bx[1] + bx[3] + pad))) {
                if (abs(da) < EPS) {
                    if (pa < lo || pa > hi) inside = false
                } else {
                    val u = (lo - pa) / da
                    val v = (hi - pa) / da
                    t0 = max(t0, min(u, v))
                    t1 = min(t1, max(u, v))
                }
            }
            if (!inside || t0 >= t1) continue
            pieces = pieces.flatMap { (s, e) -> listOf(s to min(e, t0), max(s, t1) to e).filter { it.second - it.first > 1e-3f } }
        }
        return pieces.map { (s, e) -> lerp(a, b, s) to lerp(a, b, e) }.filter { it.first.dist(it.second) > 0.8f }
    }

    companion object {
        /** A label's font size in drawing units (the app scales the drawing so this is the note's label size). */
        const val FONT = 12f

        /** Default arrowhead length. */
        const val HEAD = 6f

        const val MARGIN = 3f
        private const val EPS = 1e-3f

        /** A label's box (width, height) in drawing units: LaTeX commands count as one letter, scripts as smaller letters. */
        fun extent(text: String, size: Float = FONT): Pair<Float, Float> {
            var t = text.replace(Regex("""\\(text|mathrm|mathbf|vec|overline|hat|bar)\s*\{([^}]*)\}"""), "$2")
                .replace(Regex("""\^\{?\\circ\}?"""), "°").replace(Regex("""\\(vec|hat|bar)\s+"""), "")
            val scripts = Regex("""[_^](\{[^}]*\}|\\[a-zA-Z]+|.)""").findAll(t).sumOf { m -> m.value.drop(1).trim('{', '}').replace(Regex("""\\[a-zA-Z]+"""), "a").length }
            t = t.replace(Regex("""[_^](\{[^}]*\}|\\[a-zA-Z]+|.)"""), "")
            t = t.replace(Regex("""\\(circ|degree)"""), "°").replace(Regex("""\\[a-zA-Z]+"""), "a").replace(Regex("""[{}\\]"""), "")
            val n = t.length + scripts * 0.65f
            val w = max(size * 0.6f, n * size * 0.55f + 1f)
            val h = size * (if (scripts > 0) 1.25f else 1.0f)
            return w to h
        }

        fun dir(deg: Float): Vec2 = Vec2(cos(deg * PI / 180).toFloat(), sin(deg * PI / 180).toFloat())

        fun unit(v: Vec2): Vec2 = v.length().let { if (it < EPS) Vec2(0f, 0f) else v * (1f / it) }

        fun mid(a: Vec2, b: Vec2) = Vec2((a.x + b.x) / 2, (a.y + b.y) / 2)

        fun norm360(a: Float): Float = ((a % 360f) + 360f) % 360f

        fun angleOf(v: Vec2): Float = atan2(v.y, v.x) * 180f / PI.toFloat()

        fun arcPoints(c: Vec2, rx: Float, ry: Float, a0: Float, a1: Float): List<Vec2> {
            val n = max(6, (abs(a1 - a0) / 6f).toInt())
            return (0..n).map { i -> val a = (a0 + (a1 - a0) * i / n) * PI / 180; Vec2(c.x + rx * cos(a).toFloat(), c.y + ry * sin(a).toFloat()) }
        }

        /** Points along a shape every ~2 units (for label avoidance and checks); maths or page coordinates alike. */
        fun samples(s: VectorShape): List<Vec2> = when (s) {
            is VectorShape.Line -> seg(s.from, s.to)
            is VectorShape.Arrow -> seg(s.from, s.to)
            is VectorShape.Polyline -> (if (s.closed) s.points + s.points.first() else s.points).zipWithNext().flatMap { (a, b) -> seg(a, b) }
            is VectorShape.Ellipse -> {
                val c = s.bounds.center
                val rx = s.bounds.width / 2
                val ry = s.bounds.height / 2
                val n = max(12, (PI * (rx + ry) / 2f).toInt())
                (0 until n).map { i -> val a = i * 2 * PI / n; Vec2(c.x + rx * cos(a).toFloat(), c.y + ry * sin(a).toFloat()) }
            }
            is VectorShape.Rect -> seg(Vec2(s.rect.left, s.rect.top), Vec2(s.rect.right, s.rect.top)) + seg(Vec2(s.rect.right, s.rect.top), Vec2(s.rect.right, s.rect.bottom)) +
                seg(Vec2(s.rect.right, s.rect.bottom), Vec2(s.rect.left, s.rect.bottom)) + seg(Vec2(s.rect.left, s.rect.bottom), Vec2(s.rect.left, s.rect.top))
            else -> emptyList()
        }

        private fun seg(a: Vec2, b: Vec2): List<Vec2> {
            val n = max(1, (a.dist(b) / 2f).toInt())
            return (0..n).map { i -> Vec2(a.x + (b.x - a.x) * i / n, a.y + (b.y - a.y) * i / n) }
        }

        private fun flip(s: VectorShape): VectorShape {
            fun f(p: Vec2) = Vec2(p.x, -p.y)
            return when (s) {
                is VectorShape.Line -> s.copy(from = f(s.from), to = f(s.to))
                is VectorShape.Arrow -> s.copy(from = f(s.from), to = f(s.to))
                is VectorShape.Polyline -> s.copy(points = s.points.map(::f))
                is VectorShape.Ellipse -> s.copy(bounds = RectPt(s.bounds.left, -s.bounds.bottom, s.bounds.right, -s.bounds.top))
                is VectorShape.Label -> s.copy(at = f(s.at))
                is VectorShape.Rect -> s.copy(rect = RectPt(s.rect.left, -s.rect.bottom, s.rect.right, -s.rect.top))
                is VectorShape.Path -> s
            }
        }

        /** Hatch segments clipped to [polygon] (even-odd), lines at [angle] degrees. */
        fun hatchSegments(polygon: List<Vec2>, spacing: Float, angle: Float): List<Pair<Vec2, Vec2>> {
            if (polygon.size < 3) return emptyList()
            val u = dir(angle)
            val n = Vec2(-u.y, u.x)
            // Work in the rotated frame: rows along u, offsets along n.
            val proj = polygon.map { Vec2(it.x * u.x + it.y * u.y, it.x * n.x + it.y * n.y) }
            val lo = proj.minOf { it.y }
            val hi = proj.maxOf { it.y }
            val out = ArrayList<Pair<Vec2, Vec2>>()
            var off = lo + spacing / 2
            while (off < hi) {
                val xs = ArrayList<Float>()
                for (i in proj.indices) {
                    val a = proj[i]
                    val b = proj[(i + 1) % proj.size]
                    if ((a.y <= off) != (b.y <= off)) xs += a.x + (off - a.y) / (b.y - a.y) * (b.x - a.x)
                }
                xs.sort()
                var i = 0
                while (i + 1 < xs.size) {
                    if (xs[i + 1] - xs[i] > 0.8f) {
                        fun back(x: Float) = Vec2(x * u.x + off * n.x, x * u.y + off * n.y)
                        out += back(xs[i] + 0.4f) to back(xs[i + 1] - 0.4f)
                    }
                    i += 2
                }
                off += spacing
            }
            return out
        }
    }
}

fun Vec2.dist(o: Vec2): Float = hypot(x - o.x, y - o.y)

internal fun Vec2.dot(o: Vec2): Float = x * o.x + y * o.y

internal fun Vec2.rot90(): Vec2 = Vec2(-y, x)

internal fun lerp(a: Vec2, b: Vec2, t: Float) = Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

internal fun sq(x: Float) = x * x

internal fun sqrtf(x: Float) = sqrt(x)
