package app.bestnote.engine.layout.figlib

import app.bestnote.core.model.Vec2
import app.bestnote.engine.layout.figlib.Pen.Companion.dir
import app.bestnote.engine.layout.figlib.Pen.Companion.mid
import app.bestnote.engine.layout.figlib.Pen.Companion.unit
import app.bestnote.engine.layout.figlib.Pen.Role
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** FIG-LIB computer science: flowcharts, trees, graphs, arrays, linked lists, state machines. */
internal object Cs {
    val all: List<FigElement> by lazy {
        listOf(
            FigElement("flowchart", Subject.ComputerScience, "n=[id, start|end|process|decision|io, text]…; e=[from, to, label]…", listOf("flow", "flowdiagram", "algorithm"), ::flowchart),
            FigElement("tree", Subject.ComputerScience, "l|r=[parent, child]… (binary) or e=[parent, child]…", listOf("binarytree", "bst", "heap", "trie", "datatree"), ::tree),
            FigElement("network", Subject.ComputerScience, "n=[id, label]; e=[a, b, weight]…; directed", listOf("graphtheory", "nodes", "digraph", "weightedgraph", "nodesedges"), ::graph),
            FigElement("array", Subject.ComputerScience, "a=[v1, v2, …]; ptr=[name, index]…; hl=[index]", listOf("arrays", "stack", "queue", "memory", "cells"), ::array),
            FigElement("list", Subject.ComputerScience, "n=[v1, v2, …]; head=[name]; double (doubly linked)", listOf("linkedlist", "linked", "nodeslist"), ::list),
            FigElement("fsm", Subject.ComputerScience, "s=[name, start|accept…]…; t=[from, to, label]…", listOf("statemachine", "automaton", "dfa", "nfa", "states"), ::fsm),
        )
    }

    private fun txt(v: Any?) = Params.textOf(v)

    /** A box of text: width and height for [text] with padding. */
    private fun size(text: String, padX: Float = 9f): Pair<Float, Float> {
        val (w, h) = Pen.extent(text)
        return (w + 2 * padX) to (h + 10f)
    }

    private fun flowchart(p: Params, pen: Pen): Boolean {
        class N(val id: String, val type: String, val text: String, var w: Float = 0f, var h: Float = 0f, var c: Vec2 = Vec2(0f, 0f), var rank: Int = 0)
        val nodes = LinkedHashMap<String, N>()
        for (r in p.all("n", "node", "step")) {
            val id = txt(r.getOrNull(0)) ?: continue
            val type = (txt(r.getOrNull(1)) ?: "process").lowercase().let { t ->
                when {
                    t.startsWith("s") || t.startsWith("b") -> "start"
                    t.startsWith("e") || t.startsWith("stop") || t.startsWith("t") -> "end"
                    t.startsWith("d") || t.startsWith("if") || t.startsWith("q") || t == "?" -> "decision"
                    t.startsWith("i") || t.startsWith("o") || t.startsWith("in") || t.startsWith("out") -> "io"
                    else -> "process"
                }
            }
            if (nodes.size < 20) nodes[id] = N(id, type, txt(r.getOrNull(2)) ?: id)
        }
        if (nodes.isEmpty()) return false
        val edges = p.all("e", "edge", "link").mapNotNull { r ->
            val a = txt(r.getOrNull(0)) ?: return@mapNotNull null
            val b = txt(r.getOrNull(1)) ?: return@mapNotNull null
            if (a in nodes && b in nodes) Triple(a, b, txt(r.getOrNull(2))) else null
        }.ifEmpty { nodes.keys.zipWithNext().map { Triple(it.first, it.second, null) } }
        for (n in nodes.values) {
            val (w, h) = size(n.text)
            when (n.type) {
                "decision" -> { n.w = w * 1.45f; n.h = h * 1.7f }
                "io" -> { n.w = w + 12f; n.h = h }
                else -> { n.w = w; n.h = h }
            }
        }
        val gapY = 26f
        val placed = Layered.layout(nodes.values.map { Layered.Node(it.id, it.w) }, edges.map { it.first to it.second }, gap = 28f)
        val rowH = HashMap<Int, Float>()
        for (n in nodes.values) { n.rank = placed.getValue(n.id).second; rowH[n.rank] = max(rowH[n.rank] ?: 0f, n.h) }
        val rowY = HashMap<Int, Float>()
        var y = 0f
        for (rk in rowH.keys.sorted()) { rowY[rk] = y - rowH.getValue(rk) / 2; y -= rowH.getValue(rk) + gapY }
        for (n in nodes.values) n.c = Vec2(placed.getValue(n.id).first, rowY.getValue(n.rank))
        for (n in nodes.values) {
            val c = n.c
            val hw = n.w / 2
            val hh = n.h / 2
            when (n.type) {
                "decision" -> pen.poly(listOf(c + Vec2(0f, hh), c + Vec2(hw, 0f), c + Vec2(0f, -hh), c + Vec2(-hw, 0f)), closed = true)
                "io" -> pen.poly(listOf(c + Vec2(-hw + 6f, hh), c + Vec2(hw + 6f, hh), c + Vec2(hw - 6f, -hh), c + Vec2(-hw - 6f, -hh)), closed = true)
                "start", "end" -> {
                    val r = hh
                    val pts = Pen.arcPoints(c + Vec2(hw - r, 0f), r, r, -90f, 90f) + Pen.arcPoints(c + Vec2(-hw + r, 0f), r, r, 90f, 270f)
                    pen.poly(pts, closed = true)
                }
                else -> pen.rect(c, n.w, n.h)
            }
            pen.label(n.text, c, inside = true)
        }
        val right = nodes.values.maxOf { it.c.x + it.w / 2 } + 14f
        val left = nodes.values.minOf { it.c.x - it.w / 2 } - 14f
        var detour = 0
        val outCount = HashMap<String, Int>()
        for ((a0, b0, label) in edges) {
            val a = nodes.getValue(a0)
            val b = nodes.getValue(b0)
            val k = outCount.merge(a0, 1, Int::plus)!!
            val pts = ArrayList<Vec2>()
            if (b.rank > a.rank) {
                val side = b.c.x - a.c.x
                val start = if (abs(side) > a.w / 2 || (a.type == "decision" && k > 1)) a.c + Vec2(if (side >= 0f) a.w / 2 else -a.w / 2, 0f) else a.c + Vec2(0f, -a.h / 2)
                val end = b.c + Vec2(0f, b.h / 2)
                pts += start
                if (start.y == a.c.y) pts += Vec2(if (abs(side) < 1f) start.x + (if (side >= 0f) 12f else -12f) else end.x, start.y)
                if (abs(pts.last().x - end.x) > 0.5f) {
                    val my = (pts.last().y + end.y) / 2
                    pts += Vec2(pts.last().x, my)
                    pts += Vec2(end.x, my)
                }
                pts += end
            } else {
                // A loop back: out on the outer side of the source, along the edge of the chart, and in from that side.
                val s = if (a.c.x < 0f) -1f else 1f
                val off = (if (s > 0f) right else left) + s * 10f * detour++
                pts += a.c + Vec2(s * a.w / 2, 0f)
                pts += Vec2(off, a.c.y)
                pts += Vec2(off, b.c.y)
                pts += b.c + Vec2(s * b.w / 2, 0f)
            }
            if (pts.size > 2) pen.poly(pts.dropLast(1))
            pen.arrow(pts[pts.size - 2], pts.last(), Role.Ink, head = 5f)
            if (label != null) pen.label(label, pts[0] + unit(pts[1] - pts[0]) * 6f, unit(pts[1] - pts[0]).rot90().let { if (it.y < 0f) it * -1f else it }, gap = 1f, size = Pen.FONT * 0.85f)
        }
        return true
    }

    private fun tree(p: Params, pen: Pen): Boolean {
        val label = LinkedHashMap<String, String>()
        val parent = HashMap<String, String>()
        val side = HashMap<String, Int>()
        for (r in p.all("n", "node")) {
            val id = txt(r.getOrNull(0)) ?: continue
            label[id] = txt(r.getOrNull(1)) ?: id
            txt(r.getOrNull(2))?.takeIf { it != id && it != "-" && it.isNotBlank() }?.let { parent[id] = it }
        }
        for ((key, s) in listOf("e" to 0, "edge" to 0, "l" to -1, "left" to -1, "r" to 1, "right" to 1)) for (r in p.all(key)) {
            val a = txt(r.getOrNull(0)) ?: continue
            val b = txt(r.getOrNull(1)) ?: continue
            if (a == b) continue
            label.putIfAbsent(a, a)
            label.putIfAbsent(b, b)
            parent[b] = a
            if (s != 0) side[b] = s
        }
        if (label.size < 1 || label.size > 40) return false
        for (k in parent.keys.toList()) if (parent[k] !in label) parent.remove(k)
        // Break cycles: a node whose parent chain returns to it loses its parent.
        for (k in label.keys) {
            var v = parent[k]
            var n = 0
            while (v != null && v != k && n++ < 50) v = parent[v]
            if (v == k) parent.remove(k)
        }
        val kids = label.keys.associateWith { k -> label.keys.filter { parent[it] == k }.sortedBy { side[it] ?: 0 } }
        val roots = label.keys.filter { it !in parent }
        val x = HashMap<String, Float>()
        val depth = HashMap<String, Int>()
        var slot = 0f
        val step = max(28f, label.values.maxOf { Pen.extent(it).first } + 12f)
        fun place(v: String, d: Int) {
            depth[v] = d
            val ks = kids.getValue(v)
            if (ks.isEmpty()) { x[v] = slot * step; slot++; return }
            ks.forEach { place(it, d + 1) }
            x[v] = if (ks.size == 1 && side[ks[0]] != null) x.getValue(ks[0]) - side.getValue(ks[0]) * step / 2 else (x.getValue(ks.first()) + x.getValue(ks.last())) / 2
        }
        if (side.isNotEmpty()) {
            // A binary tree: in-order positions, so no two nodes ever share a column.
            fun inorder(v: String, d: Int) {
                depth[v] = d
                val ks = kids.getValue(v)
                ks.filter { (side[it] ?: -1) < 0 }.forEach { inorder(it, d + 1) }
                x[v] = slot * step * 0.6f
                slot++
                ks.filter { (side[it] ?: -1) > 0 }.forEach { inorder(it, d + 1) }
            }
            roots.forEach { inorder(it, 0); slot += 0.5f }
        } else {
            roots.forEach { place(it, 0); slot += 0.5f }
        }
        val r = max(10f, label.values.maxOf { Pen.extent(it).first } / 2 + 4f)
        fun c(v: String) = Vec2(x.getValue(v), -depth.getValue(v) * (2 * r + 18f))
        for ((v, pv) in parent) {
            val a = c(pv)
            val b = c(v)
            val t = unit(b - a)
            pen.line(a + t * r, b - t * r)
        }
        for (v in label.keys) {
            pen.circle(c(v), r)
            pen.label(label.getValue(v), c(v), inside = true)
        }
        return true
    }

    private fun graph(p: Params, pen: Pen): Boolean {
        val label = LinkedHashMap<String, String>()
        for (r in p.all("n", "node", "v")) txt(r.getOrNull(0))?.let { label[it] = txt(r.getOrNull(1)) ?: it }
        val edges = p.all("e", "edge").mapNotNull { r ->
            val a = txt(r.getOrNull(0)) ?: return@mapNotNull null
            val b = txt(r.getOrNull(1)) ?: return@mapNotNull null
            label.putIfAbsent(a, a)
            label.putIfAbsent(b, b)
            Triple(a, b, txt(r.getOrNull(2)))
        }
        if (label.isEmpty() || label.size > 24) return false
        val directed = p.flag("directed", "digraph", "dir")
        val ids = label.keys.toList()
        val r = max(10f, label.values.maxOf { Pen.extent(it).first } / 2 + 4f)
        val pos = if (ids.size <= 8) {
            val rad = max(45f, ids.size * 15f)
            ids.mapIndexed { i, id -> id to dir(90f + 360f * i / ids.size) * rad }.toMap()
        } else spring(ids, edges.map { it.first to it.second })
        for ((a, b, w) in edges) {
            val pa = pos.getValue(a)
            val pb = pos.getValue(b)
            if (a == b) {
                // A self-loop: a small circle outside the node.
                val out = unit(pa).takeIf { it.length() > 0.5f } ?: Vec2(0f, 1f)
                pen.circle(pa + out * (r + 6f), 7f)
                w?.let { pen.label(it, pa + out * (r + 14f), out, gap = 1f) }
                continue
            }
            val t = unit(pb - pa)
            // Both directions given: two parallel arrows.
            val twin = directed && edges.any { it.first == b && it.second == a }
            val o = if (twin) t.rot90() * 3.5f else Vec2(0f, 0f)
            val s = pa + t * r + o
            val e = pb - t * r + o
            if (directed) pen.arrow(s, e, Role.Ink, head = 5f) else pen.line(s, e)
            w?.let { pen.label(it, mid(s, e), t.rot90().let { n -> if (twin) unit(o) else if (n.y < 0f) n * -1f else n }, gap = 1.5f, role = Role.Accent, size = Pen.FONT * 0.85f) }
        }
        for (id in ids) {
            pen.circle(pos.getValue(id), r)
            pen.label(label.getValue(id), pos.getValue(id), inside = true)
        }
        return true
    }

    /** A deterministic spring embedding for larger graphs. */
    private fun spring(ids: List<String>, edges: List<Pair<String, String>>): Map<String, Vec2> {
        val rnd = Random(ids.hashCode())
        val p = ids.associateWith { Vec2(rnd.nextFloat() * 150f, rnd.nextFloat() * 150f) }.toMutableMap()
        val k = 45f
        repeat(300) { iter ->
            val f = ids.associateWith { Vec2(0f, 0f) }.toMutableMap()
            for (a in ids) for (b in ids) if (a != b) {
                val d = p.getValue(a) - p.getValue(b)
                val l = max(d.length(), 1f)
                f[a] = f.getValue(a) + d * (k * k / (l * l))
            }
            for ((a, b) in edges) if (a != b) {
                val d = p.getValue(b) - p.getValue(a)
                val l = max(d.length(), 1f)
                val pull = d * (l / k)
                f[a] = f.getValue(a) + pull
                f[b] = f.getValue(b) - pull
            }
            val temp = 10f * (1f - iter / 300f) + 0.5f
            for (a in ids) {
                val m = f.getValue(a)
                p[a] = p.getValue(a) + unit(m) * min(m.length(), temp)
            }
        }
        return p
    }

    private fun array(p: Params, pen: Pen): Boolean {
        val vals = p.all("a", "array", "v", "values").flatten().mapNotNull(::txt).take(24)
        if (vals.isEmpty()) return false
        val w = max(22f, vals.maxOf { Pen.extent(it).first } + 8f)
        val h = 22f
        for ((i, v) in vals.withIndex()) {
            val c = Vec2(i * w, 0f)
            pen.rect(c, w, h)
            pen.label(v, c, inside = true)
            if (p.flag("idx", "index", "indices", default = true)) pen.label(i.toString(), c - Vec2(0f, h / 2 + 7f), inside = true, size = Pen.FONT * 0.75f)
        }
        for (r in p.all("hl", "highlight", "mark")) {
            val i = Params.numOf(r.getOrNull(0))?.toInt()?.takeIf { it in vals.indices } ?: continue
            val c = Vec2(i * w, 0f)
            pen.hatch(listOf(c + Vec2(-w / 2, -h / 2), c + Vec2(w / 2, -h / 2), c + Vec2(w / 2, h / 2), c + Vec2(-w / 2, h / 2)), spacing = 4f)
        }
        for (r in p.all("ptr", "pointer", "p")) {
            val name = txt(r.getOrNull(0)) ?: continue
            val i = Params.numOf(r.getOrNull(1))?.toInt()?.coerceIn(0, vals.lastIndex) ?: continue
            val top = Vec2(i * w, h / 2 + 1f)
            pen.arrow(top + Vec2(0f, 20f), top, Role.Vector, head = 5f)
            pen.label(name, top + Vec2(0f, 20f), Vec2(0f, 1f), gap = 1.5f, role = Role.Vector)
        }
        return true
    }

    private fun list(p: Params, pen: Pen): Boolean {
        val vals = p.all("n", "node", "v", "values").flatten().mapNotNull(::txt).take(10)
        if (vals.isEmpty()) return false
        val double = p.flag("double", "doubly")
        val dw = max(22f, vals.maxOf { Pen.extent(it).first } + 8f)
        val pw = 12f
        val h = 22f
        val gap = 26f
        val nodeW = dw + pw * (if (double) 2 else 1)
        fun left(i: Int) = i * (nodeW + gap)
        for ((i, v) in vals.withIndex()) {
            val l = left(i)
            val x0 = if (double) l + pw else l
            pen.rect(Vec2(l + nodeW / 2, 0f), nodeW, h)
            pen.line(Vec2(x0 + dw, -h / 2), Vec2(x0 + dw, h / 2))
            if (double) pen.line(Vec2(x0, -h / 2), Vec2(x0, h / 2))
            pen.label(v, Vec2(x0 + dw / 2, 0f), inside = true)
            val nextDot = Vec2(x0 + dw + pw / 2, 0f)
            pen.dot(nextDot, 1.3f)
            if (i < vals.lastIndex) pen.arrow(nextDot, Vec2(left(i + 1), if (double) 4f else 0f), Role.Ink, head = 5f)
            else pen.label("null", nextDot + Vec2(pw / 2 + 4f, 0f), Vec2(1f, 0f), gap = 2f, size = Pen.FONT * 0.85f)
            if (double && i > 0) {
                val back = Vec2(l + pw / 2, -4f)
                pen.dot(back, 1.3f)
                pen.arrow(back, Vec2(left(i - 1) + nodeW, -4f), Role.Ink, head = 5f)
            }
        }
        p.str("head", "start")?.let { name ->
            pen.arrow(Vec2(left(0) + nodeW / 3, h / 2 + 20f), Vec2(left(0) + nodeW / 3, h / 2), Role.Vector, head = 5f)
            pen.label(name, Vec2(left(0) + nodeW / 3, h / 2 + 20f), Vec2(0f, 1f), gap = 1.5f, role = Role.Vector)
        }
        return true
    }

    private fun fsm(p: Params, pen: Pen): Boolean {
        val states = LinkedHashMap<String, Set<String>>()
        for (r in p.all("s", "state", "q")) {
            val n = txt(r.getOrNull(0)) ?: continue
            states[n] = r.drop(1).mapNotNull { txt(it)?.lowercase() }.toSet()
        }
        val trans = p.all("t", "tr", "edge", "e").mapNotNull { r ->
            val a = txt(r.getOrNull(0)) ?: return@mapNotNull null
            val b = txt(r.getOrNull(1)) ?: return@mapNotNull null
            states.putIfAbsent(a, emptySet())
            states.putIfAbsent(b, emptySet())
            Triple(a, b, txt(r.getOrNull(2)))
        }
        if (states.isEmpty() || states.size > 12) return false
        val ids = states.keys.toList()
        val r = max(12f, ids.maxOf { Pen.extent(it).first } / 2 + 5f)
        // In a row when every transition joins neighbours, else round a circle.
        val row = trans.all { (a, b, _) -> abs(ids.indexOf(a) - ids.indexOf(b)) <= 1 }
        val pos = if (row) ids.mapIndexed { i, id -> id to Vec2(i * (2 * r + 60f), 0f) }.toMap()
        else ids.mapIndexed { i, id -> id to dir(180f - 360f * i / ids.size) * max(55f, ids.size * 16f) }.toMap()
        val centre = Vec2(pos.values.map { it.x }.average().toFloat(), pos.values.map { it.y }.average().toFloat())
        // Labels of several transitions between the same two states are joined ("a, b").
        val grouped = trans.groupBy { it.first to it.second }.map { (k, v) -> Triple(k.first, k.second, v.mapNotNull { it.third }.distinct().joinToString(", ").ifEmpty { null }) }
        for ((a, b, label) in grouped) {
            val pa = pos.getValue(a)
            val pb = pos.getValue(b)
            if (a == b) {
                val out = unit(pa - centre).takeIf { it.length() > 0.5f } ?: Vec2(0f, 1f)
                val c = pa + out * (r + 7f)
                val n = out.rot90()
                pen.poly(Pen.arcPoints(c, 9f, 9f, Pen.angleOf(out) - 140f, Pen.angleOf(out) + 140f))
                val tip = pa + out * r + n * 4f
                pen.arrow(tip + out * 4f + n * 2f, tip, Role.Ink, head = 4.5f)
                label?.let { pen.label(it, c + out * 9f, out, gap = 1f, size = Pen.FONT * 0.85f) }
                continue
            }
            val t = unit(pb - pa)
            val both = grouped.any { it.first == b && it.second == a }
            val o = if (both) t.rot90() * 6f else Vec2(0f, 0f)
            // A gentle curve (three points) when both directions exist, straight otherwise.
            val s = pa + t * r + o * 0.6f
            val e = pb - t * r + o * 0.6f
            if (both) {
                val m = mid(s, e) + o
                pen.poly(listOf(s, m))
                pen.arrow(m, e, Role.Ink, head = 5f)
            } else {
                pen.arrow(s, e, Role.Ink, head = 5f)
            }
            label?.let { pen.label(it, mid(s, e) + o, if (both) unit(o) else t.rot90().let { n -> if (n.y < 0f) n * -1f else n }, gap = 1.5f, size = Pen.FONT * 0.85f) }
        }
        for ((id, flags) in states) {
            val c = pos.getValue(id)
            pen.circle(c, r)
            if (flags.any { it.startsWith("acc") || it.startsWith("fin") || it == "f" || it == "final" }) pen.circle(c, r - 3f)
            if (flags.any { it.startsWith("st") || it.startsWith("init") || it == "s" || it == "0" } || (id == ids.first() && states.values.none { f -> f.any { it.startsWith("st") || it.startsWith("init") } })) {
                pen.arrow(c - Vec2(r + 22f, 0f), c - Vec2(r, 0f), Role.Ink, head = 5f)
            }
            pen.label(id, c, inside = true)
        }
        return true
    }
}
