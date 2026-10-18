package app.bestnote.engine.layout.figlib

import app.bestnote.core.model.Vec2
import app.bestnote.engine.layout.figlib.Pen.Companion.angleOf
import app.bestnote.engine.layout.figlib.Pen.Companion.dir
import app.bestnote.engine.layout.figlib.Pen.Companion.mid
import app.bestnote.engine.layout.figlib.Pen.Companion.unit
import app.bestnote.engine.layout.figlib.Pen.Role
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/** FIG-LIB chemistry: skeletal formulas from SMILES, Lewis structures, lab apparatus, energy profiles. */
internal object Chemistry {
    val all: List<FigElement> by lazy {
        listOf(
            FigElement("molecule", Subject.Chemistry, "smiles=[SMILES]; name=[label]", listOf("skeletal", "structure", "smiles", "organic", "formula", "compound"), ::molecule),
            FigElement("lewis", Subject.Chemistry, "c=[atom]; l=[atom, bond order]…; lp=[atom|0, pairs]…; charge; e=[atom, dots] (lone atom)", listOf("lewisstructure", "electrondot", "dotcross", "dots"), ::lewis),
            FigElement("apparatus", Subject.Chemistry, "item=[beaker|flask|roundflask|testtube|cylinder|burner|funnel, label, fill]…; heat", listOf("lab", "labsetup", "glassware", "equipment", "beaker", "flask"), ::apparatus),
            FigElement("energy", Subject.Chemistry, "r|p=[label, level]; ts=[level]; ea|dh=[label]; cat", listOf("energyprofile", "energydiagram", "reactionprofile", "activationenergy", "enthalpy"), ::energy),
        )
    }

    // ------------------------------------------------------------------ SMILES

    private class Atom(val el: String, val aromatic: Boolean, val hx: Int?, val charge: Int) {
        var p = Vec2(0f, 0f)
        var placed = false
        var turn = 1f
    }

    private class Bond(val a: Int, val b: Int, val order: Int, val aromatic: Boolean)

    private val ORGANIC = listOf("Cl", "Br", "B", "C", "N", "O", "P", "S", "F", "I")
    private val VALENCE = mapOf("B" to 3, "C" to 4, "N" to 3, "O" to 2, "P" to 3, "S" to 2, "F" to 1, "Cl" to 1, "Br" to 1, "I" to 1)

    /** A small SMILES reader: organic subset, aromatic lowercase, [bracket atoms], = # bonds, branches, ring closures, dots. Null on anything else. */
    private fun parse(s: String): Pair<List<Atom>, List<Bond>>? {
        val atoms = ArrayList<Atom>()
        val bonds = ArrayList<Bond>()
        val stack = ArrayList<Int>()
        val rings = HashMap<Int, Pair<Int, Int>>()
        var prev = -1
        var order = 1
        var i = 0
        fun add(a: Atom) {
            atoms += a
            val cur = atoms.lastIndex
            if (prev >= 0) bonds += Bond(prev, cur, order, order == 1 && a.aromatic && atoms[prev].aromatic)
            prev = cur
            order = 1
        }
        while (i < s.length) {
            val c = s[i]
            when {
                c == '(' -> { stack += prev; i++ }
                c == ')' -> { prev = stack.removeLastOrNull() ?: return null; i++ }
                c == '-' || c == '/' || c == '\\' || c == ':' -> { order = 1; i++ }
                c == '=' -> { order = 2; i++ }
                c == '#' -> { order = 3; i++ }
                c == '.' -> { prev = -1; i++ }
                c.isDigit() || c == '%' -> {
                    val n = if (c == '%') s.substring(i + 1, minOf(s.length, i + 3)).toIntOrNull().also { i += 3 } ?: return null else c.digitToInt().also { i++ }
                    val open = rings.remove(n)
                    if (open == null) rings[n] = prev to order else {
                        val o = max(open.second, order)
                        bonds += Bond(open.first, prev, o, o == 1 && atoms[open.first].aromatic && atoms[prev].aromatic)
                    }
                    order = 1
                }
                c == '[' -> {
                    val end = s.indexOf(']', i)
                    if (end < 0) return null
                    val body = s.substring(i + 1, end)
                    val m = Regex("""^(\d*)([A-Z][a-z]?|[a-z]{1,2})(@*)(H\d*)?([+-]+\d*)?$""").find(body) ?: return null
                    val el = m.groupValues[2]
                    val h = m.groupValues[4].let { if (it.isEmpty()) 0 else it.drop(1).ifEmpty { "1" }.toInt() }
                    val ch = m.groupValues[5].let { q -> if (q.isEmpty()) 0 else { val sign = if (q[0] == '+') 1 else -1; val d = q.drop(1).filter(Char::isDigit); sign * (d.toIntOrNull() ?: q.count { it == q[0] }) } }
                    add(Atom(el.replaceFirstChar(Char::uppercase), el[0].isLowerCase(), h, ch))
                    i = end + 1
                }
                c.isLetter() -> {
                    val two = if (i + 1 < s.length) s.substring(i, i + 2) else ""
                    val el = when {
                        two == "Cl" || two == "Br" -> two
                        c.uppercase() in ORGANIC -> c.toString()
                        else -> return null
                    }
                    add(Atom(el.replaceFirstChar(Char::uppercase), c.isLowerCase(), null, 0))
                    i += el.length
                }
                else -> return null
            }
            if (atoms.size > 60) return null
        }
        if (atoms.isEmpty() || rings.isNotEmpty()) return null
        return atoms to bonds
    }

    private fun molecule(p: Params, pen: Pen): Boolean {
        val smiles = p.str("smiles", "s", "formula") ?: return false
        val (atoms, bonds) = parse(smiles.replace(" ", "")) ?: return false
        val nb = atoms.indices.map { a -> bonds.filter { it.a == a || it.b == a }.map { if (it.a == a) it.b else it.a } }
        val L = 30f
        // Rings: every bond that closes a cycle, with the shortest path between its ends without it.
        val rings = ArrayList<List<Int>>()
        for ((bi, b) in bonds.withIndex()) {
            val path = shortest(b.a, b.b, nb) { u, v -> !((u == b.a && v == b.b) || (u == b.b && v == b.a)) } ?: continue
            if (path.size in 3..8) {
                val set = path.toSet()
                if (rings.none { it.toSet() == set }) rings += path
            }
            @Suppress("UNUSED_VARIABLE") val unusedIndex = bi
        }
        val ringCentres = HashMap<Int, Vec2>()
        fun placeRing(ri: Int) {
            val ring = rings[ri]
            val k = ring.size
            val rr = L / (2 * sin(PI / k).toFloat())
            val done = ring.filter { atoms[it].placed }
            if (done.size >= 3) return
            // Start at a placed atom; for a fused ring at its shared edge, turned away from the other ring.
            val startIdx = ring.indexOfFirst { atoms[it].placed }.let { if (it < 0) 0 else it }
            var seq = ring.drop(startIdx) + ring.take(startIdx)
            val a0 = atoms[seq[0]]
            if (!a0.placed) { a0.p = Vec2(0f, 0f); a0.placed = true }
            val centre: Vec2
            if (done.size == 2 && atoms[seq[1]].placed.not() && atoms[seq.last()].placed) seq = listOf(seq[0]) + seq.drop(1).reversed()
            if (done.size == 2 && atoms[seq[1]].placed) {
                val m = mid(atoms[seq[0]].p, atoms[seq[1]].p)
                var n = unit(atoms[seq[1]].p - atoms[seq[0]].p).rot90()
                val other = ringCentres.values.minByOrNull { it.dist(m) } ?: (m - n)
                if (n.dot(other - m) > 0f) n = n * -1f
                centre = m + n * (L / (2 * tan(PI / k).toFloat()))
            } else {
                val placedNb = nb[seq[0]].filter { atoms[it].placed && it !in ring }
                val away = if (placedNb.isEmpty()) Vec2(0f, 1f) else unit(a0.p - Vec2(placedNb.map { atoms[it].p.x }.average().toFloat(), placedNb.map { atoms[it].p.y }.average().toFloat()))
                centre = a0.p + away * rr
            }
            ringCentres[ri] = centre
            val start = angleOf(atoms[seq[0]].p - centre)
            // Go round in the direction that meets the second (already placed) atom, else counter-clockwise.
            val sign = if (atoms[seq[1]].placed) (if (Pen.norm360(angleOf(atoms[seq[1]].p - centre) - start) < 180f) 1f else -1f) else 1f
            for ((j, a) in seq.withIndex()) if (!atoms[a].placed) {
                atoms[a].p = centre + dir(start + sign * 360f * j / k) * rr
                atoms[a].placed = true
            }
        }
        val queue = ArrayDeque<Int>()
        atoms[0].placed = true
        queue += 0
        val seen = HashSet<Int>()
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            if (!seen.add(u)) continue
            for ((ri, ring) in rings.withIndex()) if (u in ring && ring.any { !atoms[it].placed }) placeRing(ri)
            val placedNb = nb[u].filter { atoms[it].placed }
            val free = nb[u].filter { !atoms[it].placed }
            if (free.isNotEmpty()) {
                val base = if (placedNb.isEmpty()) -30f + 180f else angleOf(Vec2(placedNb.map { atoms[it].p.x - atoms[u].p.x }.average().toFloat(), placedNb.map { atoms[it].p.y - atoms[u].p.y }.average().toFloat()))
                val dirs = when {
                    free.size == 1 && placedNb.size <= 1 -> {
                        val tripleBond = bonds.any { (it.a == u || it.b == u) && it.order == 3 }
                        listOf(base + 180f + if (tripleBond) 0f else atoms[u].turn * 60f)
                    }
                    else -> {
                        val spread = 360f / (free.size + placedNb.size.coerceAtLeast(1))
                        free.indices.map { base + spread * (it + 1) }
                    }
                }
                for ((j, v) in free.withIndex()) {
                    atoms[v].p = atoms[u].p + dir(dirs[j]) * L
                    atoms[v].placed = true
                    atoms[v].turn = -atoms[u].turn
                }
            }
            for (v in nb[u]) if (v !in seen) queue += v
            // Disconnected parts (salts, ".") start beside what is drawn.
            if (queue.isEmpty()) atoms.indexOfFirst { !it.placed }.takeIf { it >= 0 }?.let { v ->
                atoms[v].p = Vec2(atoms.filter { it.placed }.maxOf { it.p.x } + 2 * L, 0f)
                atoms[v].placed = true
                queue += v
            }
        }
        val showAll = p.flag("all", "carbons") || atoms.size == 1
        val labels = atoms.mapIndexed { idx, a ->
            val order = bonds.filter { it.a == idx || it.b == idx }.sumOf { if (it.aromatic) 1 else it.order }
            val aromaticExtra = if (a.aromatic && bonds.none { (it.a == idx || it.b == idx) && it.order > 1 }) 1 else 0
            val h = a.hx ?: ((VALENCE[a.el] ?: 0) - order - aromaticExtra + a.charge * (if (a.el == "N") 1 else -1)).coerceAtLeast(0)
            if (a.el == "C" && !showAll && a.charge == 0) null else {
                val hs = when (h) { 0 -> ""; 1 -> "H"; else -> "H_$h" }
                val ch = when { a.charge > 0 -> "^{${if (a.charge > 1) a.charge else ""}+}"; a.charge < 0 -> "^{${if (a.charge < -1) -a.charge else ""}-}"; else -> "" }
                // H on the side away from the bonds.
                val right = nb[idx].all { atoms[it].p.x <= a.p.x + 0.5f } && nb[idx].isNotEmpty()
                if (right && hs.isNotEmpty()) "$hs${a.el}$ch" else "${a.el}$hs$ch"
            }
        }
        fun trimmed(i: Int, j: Int): Pair<Vec2, Vec2> {
            val a = atoms[i].p
            val b = atoms[j].p
            val t = unit(b - a)
            return (if (labels[i] != null) a + t * 7f else a) to (if (labels[j] != null) b - t * 7f else b)
        }
        for (b in bonds) {
            val (s, e) = trimmed(b.a, b.b)
            val n = unit(e - s).rot90()
            val ring = rings.indices.firstOrNull { ri -> b.a in rings[ri] && b.b in rings[ri] }
            when {
                b.order == 2 && ring != null && ringCentres[ring] != null -> {
                    pen.line(s, e)
                    val c = ringCentres.getValue(ring)
                    val inward = if (n.dot(c - mid(s, e)) > 0f) n else n * -1f
                    val sh = unit(e - s) * (atoms[b.a].p.dist(atoms[b.b].p) * 0.15f)
                    pen.line(s + inward * 4f + sh, e + inward * 4f - sh)
                }
                b.order == 2 -> { pen.line(s + n * 2f, e + n * 2f); pen.line(s - n * 2f, e - n * 2f) }
                b.order == 3 -> { pen.line(s, e); pen.line(s + n * 3.5f, e + n * 3.5f); pen.line(s - n * 3.5f, e - n * 3.5f) }
                else -> pen.line(s, e)
            }
        }
        // Aromatic rings: a circle inside.
        for ((ri, ring) in rings.withIndex()) if (ring.all { atoms[it].aromatic } && bonds.none { it.order == 2 && it.a in ring && it.b in ring }) {
            val c = ringCentres[ri] ?: continue
            pen.circle(c, L / (2 * tan(PI / ring.size).toFloat()) * 0.62f)
        }
        for ((i, l) in labels.withIndex()) if (l != null) pen.label(l, atoms[i].p, inside = true)
        p.str("name", "label")?.let { n ->
            val low = atoms.minOf { it.p.y }
            pen.label(n, Vec2(atoms.map { it.p.x }.average().toFloat(), low - 12f), Vec2(0f, -1f), gap = 2f)
        }
        return true
    }

    private fun shortest(from: Int, to: Int, nb: List<List<Int>>, ok: (Int, Int) -> Boolean): List<Int>? {
        val prev = HashMap<Int, Int>()
        val q = ArrayDeque(listOf(from))
        val seen = hashSetOf(from)
        while (q.isNotEmpty()) {
            val u = q.removeFirst()
            if (u == to) break
            for (v in nb[u]) if (v !in seen && ok(u, v)) { seen += v; prev[v] = u; q += v }
        }
        if (to !in seen) return null
        val path = arrayListOf(to)
        while (path.last() != from) path += prev.getValue(path.last())
        return path.reversed()
    }

    // ------------------------------------------------------------------ Lewis

    private fun lewis(p: Params, pen: Pen): Boolean {
        p.first("e", "dots", "atom")?.let { r ->
            val el = Params.textOf(r.getOrNull(0)) ?: return false
            val n = Params.numOf(r.getOrNull(1))?.toInt()?.coerceIn(0, 8) ?: 0
            pen.label(el, Vec2(0f, 0f), inside = true, size = Pen.FONT * 1.2f)
            // Dots one per side first, then paired (Hund's rule as drawn in class).
            val sides = listOf(0f, 90f, 180f, 270f)
            for ((i, side) in sides.withIndex()) {
                // One dot on each side first, then pairs (as drawn in class).
                when ((if (i < n) 1 else 0) + (if (i + 4 < n) 1 else 0)) {
                    1 -> electron(pen, Vec2(0f, 0f), side, 0f)
                    2 -> { electron(pen, Vec2(0f, 0f), side, 2.4f); electron(pen, Vec2(0f, 0f), side, -2.4f) }
                }
            }
            return true
        }
        val central = p.str("c", "central", "center") ?: return false
        val ligands = p.all("l", "ligand", "b", "bond").mapNotNull { r -> Params.textOf(r.getOrNull(0))?.let { it to (Params.numOf(r.getOrNull(1))?.toInt()?.coerceIn(1, 3) ?: 1) } }.take(4)
        val slots = listOf(180f, 0f, 90f, 270f)
        val L = 32f
        val pos = ligands.indices.map { dir(slots[it]) * L }
        val big = Pen.FONT * 1.15f
        pen.label(central, Vec2(0f, 0f), inside = true, size = big)
        for ((i, lig) in ligands.withIndex()) {
            val (el, order) = lig
            val d = dir(slots[i])
            val s = d * 8f
            val e = pos[i] - d * 8f
            val n = d.rot90()
            when (order) {
                1 -> pen.line(s, e)
                2 -> { pen.line(s + n * 2f, e + n * 2f); pen.line(s - n * 2f, e - n * 2f) }
                else -> { pen.line(s, e); pen.line(s + n * 3.5f, e + n * 3.5f); pen.line(s - n * 3.5f, e - n * 3.5f) }
            }
            pen.label(el, pos[i], inside = true, size = big)
        }
        for (r in p.all("lp", "pairs", "lone")) {
            val who = Params.textOf(r.getOrNull(0)) ?: continue
            val n = Params.numOf(r.getOrNull(1))?.toInt()?.coerceIn(0, 4) ?: continue
            val idx = if (who == "0" || who == central) -1 else ligands.indexOfFirst { it.first == who }.let { if (it < 0) (Params.numOf(who)?.toInt()?.minus(1) ?: -2) else it }
            if (idx < -1 || idx >= ligands.size) continue
            val at = if (idx < 0) Vec2(0f, 0f) else pos[idx]
            val used = if (idx < 0) slots.take(ligands.size) else listOf(slots[idx] + 180f)
            val free = slots.filter { s -> used.none { abs(Pen.norm360(it - s)) < 1f || abs(Pen.norm360(it - s) - 360f) < 1f } }
            for (k in 0 until minOf(n, free.size)) {
                electron(pen, at, free[k], 2.4f)
                electron(pen, at, free[k], -2.4f)
            }
        }
        p.str("charge")?.let { q ->
            val ext = 32f + 14f
            val h = if (ligands.size >= 3) ext else 18f
            pen.poly(listOf(Vec2(-ext + 4f, h), Vec2(-ext, h), Vec2(-ext, -h), Vec2(-ext + 4f, -h)))
            pen.poly(listOf(Vec2(ext - 4f, h), Vec2(ext, h), Vec2(ext, -h), Vec2(ext - 4f, -h)))
            pen.label(q, Vec2(ext + 4f, h), Vec2(1f, 0.3f), gap = 1f, size = Pen.FONT * 0.85f)
        }
        return true
    }

    /** One electron dot beside an atom label: on [side] (degrees), [shift] along the side. */
    private fun electron(pen: Pen, atom: Vec2, side: Float, shift: Float) {
        val d = dir(side)
        val c = atom + d * 10f + d.rot90() * shift
        pen.dot(c, 1.1f)
    }

    // ------------------------------------------------------------------ apparatus

    private fun apparatus(p: Params, pen: Pen): Boolean {
        val items = p.all("item", "i", "a").mapNotNull { r -> Params.textOf(r.getOrNull(0))?.lowercase()?.let { Triple(it, Params.textOf(r.getOrNull(1)), Params.numOf(r.getOrNull(2))?.coerceIn(0f, 1f)) } }.take(5)
        if (items.isEmpty()) return false
        val heat = p.flag("heat", "burner", "heating")
        var x = 0f
        for ((i, it) in items.withIndex()) {
            val (type, label, fill) = it
            // Heated: on a gauze on a tripod, the burner's flame under it.
            val heated = heat && i == 0 && !type.startsWith("burn")
            val o = Vec2(x, if (heated) 70f else 0f)
            draw(pen, type, o, fill)
            if (heated) {
                pen.line(o + Vec2(-28f, 0f), o + Vec2(28f, 0f))
                pen.line(o + Vec2(-22f, 0f), Vec2(x - 27f, 0f))
                pen.line(o + Vec2(22f, 0f), Vec2(x + 27f, 0f))
                draw(pen, "burner", Vec2(x, 0f), null)
            }
            label?.let { l -> pen.label(l, Vec2(x, 0f), Vec2(0f, -1f), gap = 3f) }
            x += 72f
        }
        return true
    }

    /** Draws one piece of glassware with its base centre at [o]; returns its top. */
    private fun draw(pen: Pen, type: String, o: Vec2, fill: Float?): Float {
        fun at(x: Float, y: Float) = o + Vec2(x, y)
        fun liquid(halfWidthAt: (Float) -> Float, h: Float) {
            fill ?: return
            val y = h * fill
            val w = halfWidthAt(y)
            pen.line(at(-w, y), at(w, y), Role.Vector)
            val pts = (0..10).map { k -> val yy = y * k / 10f; at(-halfWidthAt(yy), yy) } + (10 downTo 0).map { k -> val yy = y * k / 10f; at(halfWidthAt(yy), yy) }
            pen.hatch(pts, spacing = 5f, angle = 0f)
        }
        return when {
            type.startsWith("beak") -> {
                pen.poly(listOf(at(-22f, 48f), at(-20f, 46f), at(-20f, 0f), at(20f, 0f), at(20f, 46f), at(23f, 49f)))
                liquid({ 20f }, 46f)
                48f
            }
            type.startsWith("round") -> {
                pen.poly(Pen.arcPoints(at(0f, 20f), 20f, 20f, 105f, 435f))
                pen.line(at(-5f, 39.3f), at(-5f, 62f))
                pen.line(at(5f, 39.3f), at(5f, 62f))
                liquid({ y -> kotlin.math.sqrt(max(0f, 400f - (y - 20f) * (y - 20f))) }, 40f)
                62f
            }
            type.startsWith("flask") || type.startsWith("erlen") || type.startsWith("conical") -> {
                pen.poly(listOf(at(-5f, 62f), at(-5f, 40f), at(-24f, 0f), at(24f, 0f), at(5f, 40f), at(5f, 62f)))
                liquid({ y -> 24f - (19f * y / 40f) }, 40f)
                62f
            }
            type.startsWith("test") || type.startsWith("tube") -> {
                pen.poly(listOf(at(-7f, 62f), at(-7f, 7f)) + Pen.arcPoints(at(0f, 7f), 7f, 7f, 180f, 360f) + listOf(at(7f, 62f)))
                liquid({ 7f }, 55f)
                62f
            }
            type.startsWith("cyl") || type.startsWith("grad") -> {
                pen.poly(listOf(at(-8f, 72f), at(-8f, 4f), at(8f, 4f), at(8f, 72f)))
                pen.line(at(-14f, 0f), at(14f, 0f))
                pen.line(at(-8f, 4f), at(-14f, 0f)); pen.line(at(8f, 4f), at(14f, 0f))
                for (k in 1..6) pen.line(at(-8f, 4f + k * 10f), at(-3f, 4f + k * 10f))
                liquid({ 8f }, 68f)
                72f
            }
            type.startsWith("burn") || type.startsWith("bunsen") -> {
                pen.poly(listOf(at(-14f, 0f), at(14f, 0f), at(10f, 6f), at(-10f, 6f)), closed = true)
                pen.rect(at(0f, 22f), 8f, 32f)
                pen.poly(listOf(at(-5f, 40f), at(-6f, 48f), at(0f, 60f), at(6f, 48f), at(5f, 40f)), role = Role.Accent)
                60f
            }
            type.startsWith("fun") -> {
                pen.poly(listOf(at(-22f, 60f), at(-3f, 30f), at(-3f, 0f)))
                pen.poly(listOf(at(22f, 60f), at(3f, 30f), at(3f, 0f)))
                60f
            }
            else -> {
                pen.rect(at(0f, 20f), 30f, 40f)
                40f
            }
        }
    }

    // ------------------------------------------------------------------ energy profile

    private fun energy(p: Params, pen: Pen): Boolean {
        val r = p.first("r", "reactants")
        val pr = p.first("p", "products")
        val rl = r?.getOrNull(1)?.let(Params::numOf)
        val pl = pr?.getOrNull(1)?.let(Params::numOf)
        val tl = p.first("ts", "peak")?.getOrNull(0)?.let(Params::numOf)
        // Levels as given (any unit), else an exothermic profile.
        val raw = listOf(rl ?: 40f, pl ?: (if (rl != null) rl * 0.5f else 20f), tl ?: (max(rl ?: 40f, pl ?: 20f) + 60f))
        val lo = raw.min()
        val hi = raw.max()
        val k = 110f / max(hi - lo, 1e-3f)
        val yr = (raw[0] - lo) * k + 15f
        val yp = (raw[1] - lo) * k + 15f
        val yt = max((raw[2] - lo) * k + 15f, max(yr, yp) + 25f)
        val w = 220f
        pen.arrow(Vec2(0f, 0f), Vec2(0f, 150f), Role.Ink)
        pen.arrow(Vec2(0f, 0f), Vec2(w + 10f, 0f), Role.Ink)
        pen.label(p.str("yl", "y") ?: "E", Vec2(0f, 150f), Vec2(1f, 0.2f), gap = 2f)
        pen.label(p.str("xl", "x") ?: "\\text{progress}", Vec2(w + 10f, 0f), Vec2(0f, -1f), gap = 3f, size = Pen.FONT * 0.85f)
        fun path(peak: Float): List<Vec2> {
            val pts = ArrayList<Vec2>()
            pts += Vec2(15f, yr)
            pts += Vec2(55f, yr)
            // A smooth hump: cosine ease up to the peak and down to the products.
            for (i in 0..20) { val t = i / 20f; pts += Vec2(55f + 55f * t, yr + (peak - yr) * (1 - kotlin.math.cos(PI.toFloat() * t)) / 2) }
            for (i in 1..20) { val t = i / 20f; pts += Vec2(110f + 55f * t, peak + (yp - peak) * (1 - kotlin.math.cos(PI.toFloat() * t)) / 2) }
            pts += Vec2(w, yp)
            return pts
        }
        pen.poly(path(yt), role = Role.Vector)
        if (p.flag("cat", "catalyst")) pen.poly(path(yr + (yt - yr) * 0.55f), role = Role.Note, dashed = true)
        r?.getOrNull(0)?.let(Params::textOf)?.let { pen.label(it, Vec2(35f, yr), Vec2(0f, 1f), gap = 2f) }
        pr?.getOrNull(0)?.let(Params::textOf)?.let { pen.label(it, Vec2(185f, yp), Vec2(0f, if (yp < yr) -1f else 1f), gap = 2f) }
        p.str("ea", "activation")?.let {
            pen.line(Vec2(55f, yr), Vec2(110f, yr), Role.Note, dashed = true)
            pen.doubleArrow(Vec2(100f, yr), Vec2(100f, yt))
            pen.label(it, Vec2(100f, (yr + yt) / 2), Vec2(-1f, 0f), gap = 2f)
        }
        p.str("dh", "deltah", "h")?.let {
            pen.line(Vec2(55f, yr), Vec2(w + 8f, yr), Role.Note, dashed = true)
            pen.doubleArrow(Vec2(w + 4f, yr), Vec2(w + 4f, yp))
            pen.label(it, Vec2(w + 4f, (yr + yp) / 2), Vec2(1f, 0f), gap = 2f)
        }
        return true
    }
}
