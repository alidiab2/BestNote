package app.bestnote.engine.layout.figlib

import app.bestnote.core.model.RectPt
import app.bestnote.core.model.Vec2
import app.bestnote.engine.layout.figlib.Pen.Companion.dir
import app.bestnote.engine.layout.figlib.Pen.Role

/** FIG-LIB Venn diagrams: two or three sets in a universe, shaded regions from set expressions (∩ ∪ − ′), counts or elements in the exact regions. */
internal object Venn {
    private const val R = 50f

    fun draw(p: Params, pen: Pen): Boolean {
        val names = (p.first("sets", "set", "s")?.mapNotNull(Params::textOf) ?: emptyList()).ifEmpty { listOf("A", "B") }.take(3)
        val centres = when (names.size) {
            1 -> listOf(Vec2(0f, 0f))
            2 -> listOf(Vec2(-30f, 0f), Vec2(30f, 0f))
            else -> listOf(Vec2(-30f, 17f), Vec2(30f, 17f), Vec2(0f, -35f))
        }
        val box = RectPt(centres.minOf { it.x } - R - 14f, centres.minOf { it.y } - R - 16f, centres.maxOf { it.x } + R + 14f, centres.maxOf { it.y } + R + 24f)
        fun inside(i: Int, q: Vec2) = q.dist(centres[i]) <= R
        if (p.has("u", "universe") || p.flag("box", default = true)) {
            pen.poly(listOf(Vec2(box.left, box.top), Vec2(box.right, box.top), Vec2(box.right, box.bottom), Vec2(box.left, box.bottom)), closed = true)
            pen.label(p.str("u", "universe") ?: "U", Vec2(box.left + 2f, box.bottom - 2f), Vec2(1f, -1f), gap = 1f)
        }
        for ((i, c) in centres.withIndex()) {
            pen.circle(c, R)
            // The set's name outside its circle, away from the middle.
            val d = Pen.unit(Pen.unit(c) + Vec2(0f, 0.8f)).takeIf { it.length() > 0.5f } ?: Vec2(0f, 1f)
            pen.label(names[i], c + d * R, d, gap = 1.5f)
        }
        for (r in p.all("shade", "fill", "region")) {
            val expr = Params.textOf(r.getOrNull(0)) ?: continue
            val f = parse(expr, names) ?: continue
            shade(pen, box) { q -> f(BooleanArray(names.size) { inside(it, q) }) }
        }
        for (r in p.all("in", "n", "label", "count")) {
            val region = Params.textOf(r.getOrNull(0)) ?: continue
            val text = Params.textOf(r.getOrNull(1)) ?: continue
            val want = exact(region, names) ?: continue
            // The middle of the exact region (sampled).
            val pts = ArrayList<Vec2>()
            var x = box.left
            while (x <= box.right) {
                var y = box.top
                while (y <= box.bottom) {
                    val q = Vec2(x, y)
                    val m = BooleanArray(names.size) { inside(it, q) }
                    if (m.contentEquals(want) && centres.indices.all { i -> !want[i] || q.dist(centres[i]) < R - 8f } && centres.indices.all { i -> want[i] || q.dist(centres[i]) > R + 8f }) pts += q
                    y += 3f
                }
                x += 3f
            }
            if (pts.isEmpty()) continue
            // Outside every set: the corner opposite the universe's name; a region: its middle.
            val c = if (want.none { it }) Vec2(box.right - 10f, box.top + 10f) else Vec2(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat())
            val best = pts.minBy { it.dist(c) }
            pen.label(text, best, inside = true)
        }
        return true
    }

    private fun shade(pen: Pen, box: RectPt, f: (Vec2) -> Boolean) {
        val spacing = 5f
        val u = dir(45f)
        val n = Vec2(-u.y, u.x)
        val corners = listOf(Vec2(box.left, box.top), Vec2(box.right, box.top), Vec2(box.right, box.bottom), Vec2(box.left, box.bottom))
        val offs = corners.map { it.dot(n) }
        val alongs = corners.map { it.dot(u) }
        var off = offs.min() + spacing / 2
        while (off < offs.max()) {
            var s = alongs.min()
            var start: Vec2? = null
            var last: Vec2? = null
            while (s <= alongs.max()) {
                val q = u * s + n * off
                val inBox = box.contains(q)
                if (inBox && f(q)) {
                    if (start == null) start = q
                    last = q
                } else if (start != null) {
                    if (start.dist(last!!) > 1.5f) pen.shade(start, last, Role.Note)
                    start = null
                }
                s += 0.8f
            }
            if (start != null && start.dist(last!!) > 1.5f) pen.shade(start, last, Role.Note)
            off += spacing
        }
    }

    /** "AB", "A∩B", "only A", "out" → which sets an exact region is inside. */
    private fun exact(region: String, names: List<String>): BooleanArray? {
        val s = region.lowercase().replace("only", "").trim()
        if (s in setOf("out", "outside", "u", "none", "neither", "0")) return BooleanArray(names.size)
        val m = BooleanArray(names.size)
        var any = false
        for ((i, n) in names.withIndex()) if (n.lowercase() in s) { m[i] = true; any = true }
        return if (any) m else null
    }

    /** A set expression over [names]: ∩ (n, &, and, ^, ·, adjacency), ∪ (u, |, or, +), − (\, -, minus), complement (′, ', ^c, not). */
    fun parse(expr: String, names: List<String>): ((BooleanArray) -> Boolean)? {
        val toks = ArrayList<String>()
        var i = 0
        val s = expr.replace("\\cap", "∩").replace("\\cup", "∪").replace("\\setminus", "-").replace("^c", "'").replace("^{c}", "'").replace("\\prime", "'").replace("′", "'")
        val sorted = names.sortedByDescending { it.length }
        while (i < s.length) {
            val c = s[i]
            if (c.isWhitespace()) { i++; continue }
            val word = Regex("^(and|or|not|minus|only)", RegexOption.IGNORE_CASE).find(s.substring(i))
            if (word != null) {
                toks += when (word.value.lowercase()) { "and" -> "∩"; "or" -> "∪"; "minus" -> "-"; "not" -> "!"; else -> "" }
                i += word.value.length
                continue
            }
            val name = sorted.firstOrNull { s.startsWith(it, i) }
            when {
                name != null -> { toks += "#" + names.indexOf(name); i += name.length }
                c == '∩' || c == '&' || c == '^' || c == '·' -> { toks += "∩"; i++ }
                c == '∪' || c == '|' || c == '+' || c == 'u' -> { toks += "∪"; i++ }
                c == '-' || c == '\\' || c == '−' -> { toks += "-"; i++ }
                c == '\'' -> { toks += "'"; i++ }
                c == '(' || c == ')' -> { toks += c.toString(); i++ }
                c == 'U' -> { toks += "#U"; i++ }
                else -> return null
            }
        }
        val t = toks.filter { it.isNotEmpty() }
        if (t.isEmpty()) return null
        var pos = 0
        fun expr(): (BooleanArray) -> Boolean {
            fun atom(): (BooleanArray) -> Boolean {
                require(pos < t.size)
                val tok = t[pos++]
                var f: (BooleanArray) -> Boolean = when {
                    tok == "!" -> { val g = atom(); { m -> !g(m) } }
                    tok == "(" -> { val g = expr(); require(pos < t.size && t[pos] == ")"); pos++; g }
                    tok == "#U" -> { _ -> true }
                    tok.startsWith("#") -> { val k = tok.drop(1).toInt(); { m -> m[k] } }
                    else -> throw IllegalArgumentException(tok)
                }
                while (pos < t.size && t[pos] == "'") { pos++; val g = f; f = { m -> !g(m) } }
                return f
            }
            var f = atom()
            while (pos < t.size && t[pos] != ")") {
                val op = t[pos]
                val g0 = f
                f = when (op) {
                    "∩" -> { pos++; val h = atom(); { m -> g0(m) && h(m) } }
                    "∪" -> { pos++; val h = atom(); { m -> g0(m) || h(m) } }
                    "-" -> { pos++; val h = atom(); { m -> g0(m) && !h(m) } }
                    else -> { val h = atom(); { m -> g0(m) && h(m) } } // AB = A∩B
                }
            }
            return f
        }
        return runCatching { expr().takeIf { pos == t.size } }.getOrNull()
    }
}
