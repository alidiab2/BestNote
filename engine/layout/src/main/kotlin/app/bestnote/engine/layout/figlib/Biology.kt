package app.bestnote.engine.layout.figlib

import app.bestnote.core.model.Vec2
import app.bestnote.engine.layout.figlib.Pen.Companion.unit
import kotlin.math.max

/** FIG-LIB biology: Punnett squares, labelled outline diagrams with callouts, food chains and webs. */
internal object Biology {
    val all: List<FigElement> by lazy {
        listOf(
            FigElement("punnett", Subject.Biology, "p1=[genotype e.g. Aa or AaBb]; p2=[genotype]", listOf("punnettsquare", "cross", "genetics", "geneticcross"), ::punnett),
            FigElement("labelled", Subject.Biology, "outline=circle|ellipse|rect; part=[label, x, y, circle|ellipse|dot, size]… (0-100, y down); title", listOf("labeled", "cell", "organelles", "anatomy", "callouts", "outline", "labelleddiagram"), ::labelled),
            FigElement("chain", Subject.Biology, "n=[organism]… (chain) or e=[eaten, eater]… (web)", listOf("foodchain", "foodweb", "trophic", "energyflow"), ::chain),
        )
    }

    /** "AaBb" → genes ["Aa", "Bb"]; null when it is not pairs of letters. */
    private fun genes(g: String): List<String>? {
        val s = g.filter(Char::isLetter)
        if (s.isEmpty() || s.length % 2 != 0 || s.length > 6) return null
        return s.chunked(2)
    }

    private fun gametes(gs: List<String>): List<String> = gs.fold(listOf("")) { acc, gene -> acc.flatMap { a -> listOf(a + gene[0], a + gene[1]) } }

    /** Alleles of each gene written dominant first ("aA" → "Aa"). */
    private fun genotype(a: String, b: String): String = a.indices.joinToString("") { i -> listOf(a[i], b[i]).sortedWith(compareBy({ it.isLowerCase() }, { it })).joinToString("") }

    private fun punnett(p: Params, pen: Pen): Boolean {
        val g1 = p.str("p1", "a", "mother", "parent1")?.let(::genes) ?: return false
        val g2 = p.str("p2", "b", "father", "parent2")?.let(::genes) ?: return false
        if (g1.size != g2.size) return false
        val top = gametes(g1).distinct()
        val left = gametes(g2).distinct()
        val cell = 18f + 9f * g1.size
        val n = top.size
        val m = left.size
        // Header row and column, then the grid.
        for (i in 0..n) pen.line(Vec2(i * cell, 0f), Vec2(i * cell, -m * cell))
        for (j in 0..m) pen.line(Vec2(0f, -j * cell), Vec2(n * cell, -j * cell))
        for ((i, g) in top.withIndex()) pen.label(g, Vec2((i + 0.5f) * cell, 0f), Vec2(0f, 1f), gap = 3f)
        for ((j, g) in left.withIndex()) pen.label(g, Vec2(0f, -(j + 0.5f) * cell), Vec2(-1f, 0f), gap = 3f)
        for ((i, a) in top.withIndex()) for ((j, b) in left.withIndex()) pen.label(genotype(a, b), Vec2((i + 0.5f) * cell, -(j + 0.5f) * cell), inside = true)
        pen.label(g1.joinToString(""), Vec2(n * cell / 2, 18f), Vec2(0f, 1f), gap = 3f)
        // The parents' genotypes beyond the gamete headers.
        val gw = left.maxOf { Pen.extent(it).first }
        pen.label(g2.joinToString(""), Vec2(-gw - 10f, -m * cell / 2), Vec2(-1f, 0f), gap = 4f)
        return true
    }

    private fun labelled(p: Params, pen: Pen): Boolean {
        val parts = p.all("part", "p", "organelle", "label").mapNotNull { r ->
            val label = Params.textOf(r.getOrNull(0)) ?: return@mapNotNull null
            val x = Params.numOf(r.getOrNull(1))?.coerceIn(0f, 100f) ?: return@mapNotNull null
            val y = Params.numOf(r.getOrNull(2))?.coerceIn(0f, 100f) ?: return@mapNotNull null
            Triple(label, Vec2(x, y), r.drop(3))
        }.take(14)
        val shape = p.choice("outline", "shape", options = listOf("circle", "ellipse", "rect", "rounded", "none"), default = "ellipse")
        if (parts.isEmpty() && shape == "none") return false
        val w = 160f
        val h = if (shape == "circle") 160f else 110f
        // Box coordinates (0–100, y down) → drawing units (y up, centred).
        fun at(v: Vec2) = Vec2((v.x - 50f) / 100f * w, (50f - v.y) / 100f * h)
        when (shape) {
            "circle", "ellipse" -> pen.ellipse(Vec2(0f, 0f), w / 2, h / 2)
            "rect" -> pen.rect(Vec2(0f, 0f), w, h)
            "rounded" -> {
                val r = 18f
                pen.poly(Pen.arcPoints(Vec2(w / 2 - r, h / 2 - r), r, r, 0f, 90f) + Pen.arcPoints(Vec2(-w / 2 + r, h / 2 - r), r, r, 90f, 180f) +
                    Pen.arcPoints(Vec2(-w / 2 + r, -h / 2 + r), r, r, 180f, 270f) + Pen.arcPoints(Vec2(w / 2 - r, -h / 2 + r), r, r, 270f, 360f), closed = true)
            }
        }
        for ((_, v, extra) in parts) {
            val c = at(v)
            val kind = extra.firstOrNull()?.toString()?.lowercase() ?: "dot"
            val size = (extra.getOrNull(1)?.let(Params::numOf) ?: 10f).coerceIn(2f, 40f) / 100f * w
            when {
                kind.startsWith("c") -> pen.circle(c, size / 2)
                kind.startsWith("e") -> pen.ellipse(c, size / 2, size / 3)
                else -> pen.dot(c, 1.6f)
            }
        }
        // Callouts: each part's label in a column outside the outline on its side, in order of height, with a leader line.
        for (side in listOf(-1f, 1f)) {
            val mine = parts.filter { (it.second.x < 50f) == (side < 0f) }.sortedByDescending { at(it.second).y }
            if (mine.isEmpty()) continue
            val step = max(16f, (h + 20f) / mine.size)
            val y0 = (mine.size - 1) * step / 2
            for ((k, part) in mine.withIndex()) {
                val (label, v, _) = part
                val c = at(v)
                val ly = y0 - k * step
                val (lw, _) = Pen.extent(label)
                val lx = side * (w / 2 + 18f)
                pen.line(c, Vec2(lx - side * 2f, ly))
                pen.label(label, Vec2(lx + side * (lw / 2 + 1f), ly), inside = true)
            }
        }
        p.str("title", "name")?.let { pen.label(it, Vec2(0f, -h / 2), Vec2(0f, -1f), gap = 6f) }
        return true
    }

    private fun chain(p: Params, pen: Pen): Boolean {
        val names = LinkedHashSet<String>()
        p.all("n", "node", "o").forEach { r -> r.mapNotNull(Params::textOf).forEach { names += it } }
        val edges = p.all("e", "edge", "eats").mapNotNull { r ->
            val a = Params.textOf(r.getOrNull(0)) ?: return@mapNotNull null
            val b = Params.textOf(r.getOrNull(1)) ?: return@mapNotNull null
            names += a; names += b
            a to b
        }.ifEmpty { names.toList().zipWithNext() }
        if (names.size < 2 || names.size > 16) return false
        val ids = names.toList()
        val pos: Map<String, Vec2>
        val web = p.has("e", "edge", "eats")
        if (!web) {
            // A chain: left to right, energy flowing with the arrows.
            var x = 0f
            val m = HashMap<String, Vec2>()
            for (id in ids) {
                val (w, _) = Pen.extent(id)
                m[id] = Vec2(x + w / 2, 0f)
                x += w + 34f
            }
            pos = m
        } else {
            // A web: producers at the bottom, each level above what it eats.
            val placed = Layered.layout(ids.map { Layered.Node(it, Pen.extent(it).first) }, edges, gap = 26f)
            pos = ids.associateWith { Vec2(placed.getValue(it).first, placed.getValue(it).second * 42f) }
        }
        for (id in ids) pen.label(id, pos.getValue(id), inside = true)
        for ((a, b) in edges) {
            val pa = pos.getValue(a)
            val pb = pos.getValue(b)
            val t = unit(pb - pa)
            fun clear(id: String, d: Vec2): Float {
                val (w, h) = Pen.extent(id)
                return minOf(if (kotlin.math.abs(d.x) > 1e-3f) (w / 2 + 3f) / kotlin.math.abs(d.x) else 1e9f, if (kotlin.math.abs(d.y) > 1e-3f) (h / 2 + 3f) / kotlin.math.abs(d.y) else 1e9f)
            }
            pen.arrow(pa + t * clear(a, t), pb - t * clear(b, t), Pen.Role.Ink, head = 5f)
        }
        return true
    }
}
