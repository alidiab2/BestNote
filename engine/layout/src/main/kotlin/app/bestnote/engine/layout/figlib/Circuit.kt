package app.bestnote.engine.layout.figlib

import app.bestnote.core.model.Vec2
import app.bestnote.engine.layout.figlib.Pen.Companion.dir
import app.bestnote.engine.layout.figlib.Pen.Companion.unit
import app.bestnote.engine.layout.figlib.Pen.Role
import kotlin.math.PI
import kotlin.math.max

/**
 * FIG-LIB circuits: a netlist ([type, label, node, node] rows) laid out as a tidy schematic. The
 * first source (battery or cell) goes on the bottom wire; the rest is reduced to series and parallel
 * groups between its terminals and drawn left to right, parallel branches stacked with junction dots.
 * A network that is not series–parallel (a bridge) is drawn with its nodes on a circle instead.
 * Rows without nodes are one series loop in the given order.
 */
internal object Circuit {
    private class Comp(val type: String, val label: String?, var a: String, var b: String, val closed: Boolean)

    private sealed interface Tree
    private class Leaf(val c: Comp, val forward: Boolean) : Tree
    private class Series(val parts: List<Tree>) : Tree
    private class Parallel(val parts: List<Tree>) : Tree

    private class Edge(val a: String, val b: String, val t: Tree)

    private val TYPES = mapOf(
        "r" to "R", "resistor" to "R", "res" to "R", "rheostat" to "R", "c" to "C", "capacitor" to "C", "cap" to "C",
        "l" to "L", "inductor" to "L", "coil" to "L", "bat" to "bat", "battery" to "bat", "e" to "bat", "emf" to "bat", "source" to "bat",
        "cell" to "cell", "v0" to "cell", "sw" to "sw", "switch" to "sw", "k" to "sw", "s" to "sw", "swc" to "swc", "closed" to "swc",
        "bulb" to "bulb", "lamp" to "bulb", "light" to "bulb", "a" to "A", "ammeter" to "A", "v" to "V", "voltmeter" to "V",
        "g" to "G", "galvanometer" to "G", "d" to "D", "diode" to "D", "led" to "D", "wire" to "wire", "w" to "wire",
    )

    fun draw(p: Params, pen: Pen): Boolean {
        val raw = p.rows.filter { it.isNotEmpty() && TYPES.containsKey(it[0].toString().lowercase().filter(Char::isLetterOrDigit)) }
        if (raw.isEmpty()) return false
        // A netlist when any row names two nodes; a row of three is then a part with no label ([bulb, a, b]).
        val withNodes = raw.any { it.size >= 4 }
        val comps = raw.take(MAX_PARTS).mapIndexedNotNull { i, r ->
            val type = TYPES.getValue(r[0].toString().lowercase().filter(Char::isLetterOrDigit))
            val bare = withNodes && r.size == 3
            val label = if (bare) null else Params.textOf(r.getOrNull(1))?.takeIf { it != "-" && it.isNotBlank() }
            val state = r.drop(if (withNodes) 4 else 2).firstOrNull()?.toString()?.lowercase()
            val closed = type == "swc" || state == "closed" || state == "on" || state == "1"
            when {
                bare -> Comp(type, null, Params.textOf(r[1])!!, Params.textOf(r[2])!!, closed)
                withNodes -> if (r.size < 4) null else Comp(type, label, Params.textOf(r[2])!!, Params.textOf(r[3])!!, closed)
                else -> Comp(type, label, "n$i", "n${(i + 1) % raw.size.coerceAtMost(MAX_PARTS)}", closed)
            }
        }.filter { it.a != it.b }
        if (comps.isEmpty()) return false
        val src = comps.firstOrNull { it.type == "bat" || it.type == "cell" } ?: comps.first()
        val rest = comps.filter { it !== src }
        val tree = if (rest.isEmpty()) null else reduce(rest, src.a, src.b)
        if (rest.isNotEmpty() && tree == null) return ring(comps, pen, p)
        // The network on the top row, from the source's first node (left) to its second (right).
        val placedLeaves = ArrayList<Triple<Comp, Vec2, Vec2>>()
        val (w, h, ey) = if (tree != null) size(tree) else Triple(60f, 24f, 12f)
        val top = 0f
        if (tree != null) layout(tree, 0f, top, pen, placedLeaves) else pen.line(Vec2(0f, top), Vec2(w, top))
        val bottom = top - (h - ey) - 34f
        val left = -8f
        val right = w + 8f
        pen.line(Vec2(0f, top), Vec2(left, top))
        pen.line(Vec2(left, top), Vec2(left, bottom))
        pen.line(Vec2(w, top), Vec2(right, top))
        pen.line(Vec2(right, top), Vec2(right, bottom))
        val mid = (left + right) / 2
        val sl = 40f
        pen.line(Vec2(left, bottom), Vec2(mid - sl / 2, bottom))
        pen.line(Vec2(mid + sl / 2, bottom), Vec2(right, bottom))
        // Source: its first node (left side) is the long plate, so it reads right → left.
        symbol(pen, src, Vec2(mid + sl / 2, bottom), Vec2(-1f, 0f), labelSide = -1f)
        placedLeaves += Triple(src, Vec2(mid + sl / 2, bottom), Vec2(mid - sl / 2, bottom))
        currents(p, pen, placedLeaves)
        return true
    }

    /** Series–parallel reduction between the terminals [s] and [t]; null when the network is not series–parallel (or not connected). */
    private fun reduce(comps: List<Comp>, s: String, t: String): Tree? {
        var edges = comps.map { Edge(it.a, it.b, Leaf(it, true)) }.toMutableList()
        var changed = true
        var guard = 0
        while (changed && guard++ < 500) {
            changed = false
            // Parallel: edges between the same two nodes.
            val groups = edges.groupBy { setOf(it.a, it.b) }
            if (groups.any { it.value.size > 1 }) {
                edges = groups.map { (_, es) ->
                    if (es.size == 1) es[0] else {
                        val a = es[0].a
                        val b = es[0].b
                        Edge(a, b, Parallel(es.map { e -> if (e.a == a) e.t else rev(e.t) }))
                    }
                }.toMutableList()
                changed = true
                continue
            }
            // Series: an inner node with exactly two edges.
            val degree = HashMap<String, Int>()
            for (e in edges) { degree.merge(e.a, 1, Int::plus); degree.merge(e.b, 1, Int::plus) }
            val v = degree.entries.firstOrNull { it.value == 2 && it.key != s && it.key != t }?.key
            if (v != null) {
                val (e1, e2) = edges.filter { it.a == v || it.b == v }
                edges.remove(e1)
                edges.remove(e2)
                val u = if (e1.a == v) e1.b else e1.a
                val w = if (e2.a == v) e2.b else e2.a
                val t1 = if (e1.b == v) e1.t else rev(e1.t) // u → v
                val t2 = if (e2.a == v) e2.t else rev(e2.t) // v → w
                edges.add(Edge(u, w, Series(flat(t1) + flat(t2))))
                changed = true
                continue
            }
            // Dangling parts (an end with one edge that is not a terminal) are drawn in series at their node: drop them.
            val dangling = degree.entries.firstOrNull { it.value == 1 && it.key != s && it.key != t }?.key
            if (dangling != null) {
                edges.removeAll { it.a == dangling || it.b == dangling }
                changed = true
            }
        }
        if (edges.size != 1) return null
        val e = edges[0]
        return when {
            e.a == s && e.b == t -> e.t
            e.a == t && e.b == s -> rev(e.t)
            else -> null
        }
    }

    private fun flat(t: Tree): List<Tree> = if (t is Series) t.parts else listOf(t)

    private fun rev(t: Tree): Tree = when (t) {
        is Leaf -> Leaf(t.c, !t.forward)
        is Series -> Series(t.parts.reversed().map(::rev))
        is Parallel -> Parallel(t.parts.map(::rev))
    }

    private const val LEAF_W = 44f
    private const val LEAF_H = 30f
    private const val BRANCH_GAP = 8f
    private const val RAIL = 8f

    /** Width, height and the entry wire's depth below the top. */
    private fun size(t: Tree): Triple<Float, Float, Float> = when (t) {
        is Leaf -> Triple(LEAF_W, LEAF_H, LEAF_H / 2)
        is Series -> {
            val s = t.parts.map(::size)
            val ey = s.maxOf { it.third }
            Triple(s.sumOf { it.first.toDouble() }.toFloat(), ey + s.maxOf { it.second - it.third }, ey)
        }
        is Parallel -> {
            val s = t.parts.map(::size)
            Triple(s.maxOf { it.first } + 2 * RAIL, s.sumOf { it.second.toDouble() }.toFloat() + BRANCH_GAP * (s.size - 1), s.first().third)
        }
    }

    /** Draws [t] with its entry wire at height [y], starting at [x]. */
    private fun layout(t: Tree, x: Float, y: Float, pen: Pen, out: MutableList<Triple<Comp, Vec2, Vec2>>) {
        when (t) {
            is Leaf -> {
                val a = Vec2(x, y)
                val b = Vec2(x + LEAF_W, y)
                if (t.forward) symbol(pen, t.c, a, Vec2(1f, 0f)) else symbol(pen, t.c, b, Vec2(-1f, 0f), labelSide = -1f)
                out += if (t.forward) Triple(t.c, a, b) else Triple(t.c, b, a)
            }
            is Series -> {
                var cx = x
                for (part in t.parts) {
                    layout(part, cx, y, pen, out)
                    cx += size(part).first
                }
            }
            is Parallel -> {
                val (w, _, _) = size(t)
                var top = y + size(t.parts.first()).third
                val rows = ArrayList<Float>()
                for (part in t.parts) {
                    val (pw, ph, pey) = size(part)
                    val by = top - pey
                    rows += by
                    // Centred in the branch, wires out to both rails.
                    val px = x + RAIL + (w - 2 * RAIL - pw) / 2
                    pen.line(Vec2(x, by), Vec2(px, by))
                    layout(part, px, by, pen, out)
                    pen.line(Vec2(px + pw, by), Vec2(x + w, by))
                    top -= ph + BRANCH_GAP
                }
                pen.line(Vec2(x, rows.first()), Vec2(x, rows.last()))
                pen.line(Vec2(x + w, rows.first()), Vec2(x + w, rows.last()))
                for (r in rows.dropLast(1)) {
                    pen.dot(Vec2(x, r))
                    pen.dot(Vec2(x + w, r))
                }
            }
        }
    }

    /** One component from [a] along [t] (LEAF_W long), its label on the [labelSide] (+1 = left of t). */
    private fun symbol(pen: Pen, c: Comp, a: Vec2, t0: Vec2, labelSide: Float = 1f, len: Float = LEAF_W) {
        val t = unit(t0)
        val n = t.rot90()
        fun f(x: Float, y: Float) = a + t * x + n * y
        val body = 24f
        val l0 = (len - body) / 2
        val l1 = l0 + body
        val m = len / 2
        fun leads(inner0: Float = l0, inner1: Float = l1) {
            pen.line(f(0f, 0f), f(inner0, 0f))
            pen.line(f(inner1, 0f), f(len, 0f))
        }
        var labelAt = f(m, 9f)
        when (c.type) {
            "R" -> { leads(); pen.zigzag(f(l0, 0f), f(l1, 0f), 4, 4.5f) }
            "C" -> {
                leads(m - 2.5f, m + 2.5f)
                pen.line(f(m - 2.5f, -9f), f(m - 2.5f, 9f))
                pen.line(f(m + 2.5f, -9f), f(m + 2.5f, 9f))
                labelAt = f(m, 11f)
            }
            "L" -> {
                leads()
                val k = 4
                val r = body / k / 2
                val pts = ArrayList<Vec2>()
                for (i in 0 until k) for (j in 0..10) {
                    val ang = PI.toFloat() * (1f - j / 10f)
                    pts += f(l0 + r + 2 * r * i + r * kotlin.math.cos(ang), r * kotlin.math.sin(ang) * 1.4f)
                }
                pen.poly(pts)
            }
            "bat", "cell" -> {
                val cells = if (c.type == "bat") 2 else 1
                val gap = 5f
                val span = gap * (2 * cells - 1)
                val s0 = m - span / 2
                leads(s0, s0 + span)
                for (i in 0 until cells) {
                    val x = s0 + i * 2 * gap
                    pen.line(f(x, -10f), f(x, 10f))
                    pen.line(f(x + gap, -5f), f(x + gap, 5f))
                    if (i < cells - 1) pen.line(f(x + gap, 0f), f(x + 2 * gap, 0f), dashed = true)
                }
                labelAt = f(m, 13f)
            }
            "sw", "swc" -> {
                leads(m - 9f, m + 9f)
                pen.dot(f(m - 9f, 0f), 1.4f)
                pen.dot(f(m + 9f, 0f), 1.4f)
                if (c.closed) pen.line(f(m - 9f, 0f), f(m + 9f, 0f)) else pen.line(f(m - 9f, 0f), f(m + 8f, 8f))
                labelAt = f(m, 11f)
            }
            "bulb", "A", "V", "G" -> {
                val r = 8f
                leads(m - r, m + r)
                pen.circle(f(m, 0f), r)
                if (c.type == "bulb") {
                    val d = r * 0.7f
                    pen.line(f(m - d, -d), f(m + d, d))
                    pen.line(f(m - d, d), f(m + d, -d))
                } else {
                    pen.label(c.type, f(m, 0f), inside = true, size = Pen.FONT * 0.9f)
                }
                labelAt = f(m, r + 2f)
            }
            "D" -> {
                leads(m - 6f, m + 6f)
                pen.poly(listOf(f(m - 6f, -7f), f(m - 6f, 7f), f(m + 6f, 0f)), closed = true)
                pen.line(f(m + 6f, -7f), f(m + 6f, 7f))
            }
            else -> pen.line(f(0f, 0f), f(len, 0f))
        }
        val side = if (labelSide >= 0f) n else n * -1f
        if (c.label != null) pen.label(c.label, if (labelSide >= 0f) labelAt else a + t * m - n * (labelAt - (a + t * m)).dot(n), side, gap = 1.5f)
    }

    private fun currents(p: Params, pen: Pen, leaves: List<Triple<Comp, Vec2, Vec2>>) {
        for (r in p.all("i", "current")) {
            val name = Params.textOf(r.getOrNull(0)) ?: "I"
            val na = Params.textOf(r.getOrNull(1))
            val nb = Params.textOf(r.getOrNull(2))
            val hit = leaves.firstOrNull { (c, _, _) -> na != null && nb != null && setOf(c.a, c.b) == setOf(na, nb) } ?: leaves.firstOrNull() ?: continue
            val (c, a, b) = hit
            val forward = na == null || c.a == na
            val t = unit(b - a) * (if (forward) 1f else -1f)
            val start = if (forward) a else b
            val p0 = start + t * 1f
            pen.arrow(p0, p0 + t * 7f, Role.Accent, head = 4.5f)
            pen.label(name, p0 + t * 4f, t.rot90() * -1f, gap = 3f, role = Role.Accent)
        }
    }

    /** Not series–parallel: nodes on a circle, each part on its chord; junction dots where three or more meet. */
    private fun ring(comps: List<Comp>, pen: Pen, p: Params): Boolean {
        val nodes = comps.flatMap { listOf(it.a, it.b) }.distinct()
        val r = max(60f, nodes.size * 22f)
        val at = nodes.mapIndexed { i, n -> n to dir(90f + 360f * i / nodes.size) * r }.toMap()
        val leaves = ArrayList<Triple<Comp, Vec2, Vec2>>()
        for (c in comps) {
            val a = at.getValue(c.a)
            val b = at.getValue(c.b)
            val len = a.dist(b)
            val pad = (len - LEAF_W) / 2
            val t = unit(b - a)
            if (pad > 0f) {
                pen.line(a, a + t * pad)
                pen.line(b - t * pad, b)
            }
            symbol(pen, c, a + t * pad.coerceAtLeast(0f), t, labelSide = if (t.rot90().dot(Pen.mid(a, b)) >= 0f) 1f else -1f)
            leaves += Triple(c, a, b)
        }
        val degree = comps.flatMap { listOf(it.a, it.b) }.groupingBy { it }.eachCount()
        for ((n, d) in degree) if (d >= 3) pen.dot(at.getValue(n))
        currents(p, pen, leaves)
        return true
    }

    private const val MAX_PARTS = 24
}
