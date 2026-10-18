package app.bestnote.ai.vision.figeval

import app.bestnote.engine.layout.figlib.FigSpec
import kotlin.math.max
import kotlin.random.Random

/**
 * FIG-LIB evaluation: random ground-truth figures for every kind, wide parameter ranges and labels in
 * English, Hebrew and Arabic. Nothing here is taken from a real board: the set measures whether the
 * library generalises, not whether it fits a few photos.
 */
class FigGen(seed: Int) {
    private val rnd = Random(seed)

    private fun <T> pick(vararg v: T): T = v[rnd.nextInt(v.size)]
    private fun <T> pickL(v: List<T>): T = v[rnd.nextInt(v.size)]
    private fun chance(p: Double) = rnd.nextDouble() < p
    private fun int(a: Int, b: Int) = a + rnd.nextInt(b - a + 1)
    private fun r(vararg v: Any): List<Any> = v.map { if (it is Int) it.toDouble() else it }

    private val words = listOf(
        listOf("input", "output", "process", "start", "data", "cell", "energy", "heat"),
        listOf("קלט", "פלט", "תהליך", "גוף", "אנרגיה", "חום", "מצב", "כוח"),
        listOf("مدخل", "مخرج", "عملية", "جسم", "طاقة", "حرارة", "حالة", "قوة"),
    )

    fun word(): String = pickL(pickL(words))

    val kinds = listOf(
        "incline", "fbd", "blocks", "spring", "pulley", "pendulum", "circuit", "lens", "mirror", "refraction", "wave", "field", "vectors",
        "plot", "numberline", "geo", "triangle", "venn", "solid", "ptree", "sketch",
        "molecule", "lewis", "apparatus", "energy", "punnett", "labelled", "chain",
        "flowchart", "tree", "network", "array", "list", "fsm",
    )

    /** The stage-1 kinds (physics, maths, generic): the live boards were made from these. */
    val stage1 = kinds.take(21)

    val smiles = listOf(
        "CCO", "CC(=O)O", "c1ccccc1", "C1CCCCC1", "C=CC", "C#C", "Oc1ccccc1", "NCC(=O)O", "c1ccc2ccccc2c1", "CC(C)C", "CCOC(C)=O",
        "Cc1ccccc1", "CC(C)=O", "OCC(O)CO", "C1CC1", "ClC(Cl)Cl", "CC(N)C(=O)O", "c1ccncc1", "O=C=O", "CCCCC",
    )

    fun any(): FigSpec = of(pickL(kinds))

    fun of(kind: String): FigSpec = FigSpec(kind, when (kind) {
        "incline" -> incline()
        "fbd" -> {
            val rows = arrayListOf(r("body", pick("box", "dot"), pick("m", "M", "A")))
            val names = listOf("N", "mg", "F", "T", "f", "F_1", "F_2").shuffled(rnd).take(int(2, 5))
            for (n in names) rows += r("f", n, when (n) { "N" -> "up"; "mg" -> "down"; else -> int(0, 35) * 10 })
            if (chance(0.4)) rows.firstOrNull { it[0] == "f" && it[2] is Double && (it[2] as Double) % 90.0 != 0.0 }?.let { rows += r("ang", pick("\\alpha", "\\theta", "30^\\circ"), it[1]) }
            rows
        }
        "blocks" -> {
            val n = int(1, 3)
            val rows = ArrayList<List<Any>>()
            for (i in 1..n) rows += r("m", pick("m_$i", "M", "${int(1, 9)}kg", "A", "B").let { if (n > 1 && it == "M") "m_$i" else it })
            rows += r("link", pick("rope", "touch", "spring"))
            rows += r("f", "F", pick("right", "left", int(1, 6) * 10), n)
            if (chance(0.5)) rows += r("wall", pick("L", "R"))
            if (chance(0.4)) rows += r("v", "v", 1)
            rows.distinctBy { it.toString() }
        }
        "spring" -> listOfNotNull(r("state", pick("relaxed", "compressed", "stretched")), r("k", pick("k", "k_1")), r("m", pick("m", "M")), if (chance(0.5)) r("x", pick("x", "\\Delta x")) else null, if (chance(0.5)) r("eq") else null, r("coils", int(5, 10)))
        "pulley" -> listOfNotNull(r("type", pick("atwood", "table", "incline")), r("m", pick("m_1", "M")), r("m", pick("m_2", "m")), if (chance(0.5)) r("t") else null, r("a", int(20, 50)))
        "pendulum" -> listOfNotNull(r("a", int(10, 50)), r("l", pick("L", "l")), r("m", "m"), if (chance(0.6)) r("f", "T", "t") else null, if (chance(0.6)) r("f", "mg", "g") else null, if (chance(0.5)) r("path") else null)
        "circuit" -> circuit()
        "lens" -> listOf(r("type", pick("convex", "concave")), r("f", 1), r("obj", pick(0.6, 1.5, 2.0, 2.5, 3.2), pick(0.5, 0.8, 1.0)))
        "mirror" -> listOf(r("type", pick("concave", "convex", "plane")), r("f", 1), r("obj", pick(0.6, 1.5, 2.5, 3.2), pick(0.5, 0.8)))
        "refraction" -> listOfNotNull(r("n", pick(1.0, 1.33), pick(1.5, 1.33, 2.4)), r("i", int(15, 70)), if (chance(0.4)) r("reflect") else null)
        "wave" -> listOfNotNull(r("type", pick("transverse", "standing", "longitudinal")), r("n", pick(1.0, 1.5, 2.0, 3.0)), if (chance(0.7)) r("lam", "\\lambda") else null, if (chance(0.5)) r("A", "A") else null, if (chance(0.4)) r("v", "v") else null)
        "field" -> when (int(0, 2)) {
            0 -> (1..int(1, 3)).map { r("q", pick("+", "-"), int(-4, 4), int(-3, 3)) }.distinctBy { "${it[2]},${it[3]}" }
            1 -> listOfNotNull(r("uniform", pick(0, 90, 270, 180)), if (chance(0.5)) r("plates") else null, r("e", "\\vec E"))
            else -> listOf(r("b", pick("in", "out")))
        }
        "vectors" -> {
            val n = int(1, 3)
            val rows = (1..n).map { i -> r("v", "\\vec ${"abc"[i - 1]}", int(-4, 5).takeIf { it != 0 } ?: 2, int(-3, 4).takeIf { it != 0 } ?: 1) }.toMutableList()
            if (n >= 2 && chance(0.5)) {
                // Head to tail: the second starts where the first ends.
                val a = rows[0]
                rows[1] = rows[1] + listOf(a[2], a[3])
                rows += r("sum", "\\vec r")
            }
            rows
        }
        "plot" -> plot()
        "numberline" -> {
            val a = int(-5, 2)
            val b = a + int(2, 6)
            listOfNotNull(r("x", a - 2, b + 2), r("int", a, if (chance(0.3)) "inf" else b, pick("[]", "[)", "(]", "()")), if (chance(0.5)) r("pt", b + 1, pick("open", "closed"), "a") else null)
        }
        "geo" -> geo()
        "triangle" -> listOfNotNull(r("v", "A", "B", "C"), r("type", pick("right", "iso", "equi", "any")), if (chance(0.6)) r("side", "A", "B", pick("c", "5", "x")) else null, if (chance(0.5)) r("ang", "A", pick("\\alpha", "30^\\circ")) else null, if (chance(0.3)) r("h", "C", "h") else null)
        "venn" -> {
            val names = if (chance(0.3)) listOf("א", "ב", "ג") else listOf("A", "B", "C")
            val n = int(2, 3)
            val sets = names.take(n)
            listOfNotNull(r("sets", *sets.toTypedArray()), r("shade", pick("${sets[0]}∩${sets[1]}", "${sets[0]}∪${sets[1]}", "${sets[0]}-${sets[1]}", "${sets[1]}'")), r("in", sets[0], int(1, 20)), r("in", sets[0] + sets[1], int(1, 9)), if (chance(0.5)) r("in", "out", int(1, 9)) else null)
        }
        "solid" -> pick(
            listOf(r("type", "cuboid"), r("a", "a"), r("b", "b"), r("c", "c")),
            listOf(r("type", "cube"), r("a", "a")),
            listOf(r("type", "cylinder"), r("r", "r"), r("h", "h")),
            listOf(r("type", "cone"), r("r", "r"), r("h", "h"), r("l", "l")),
            listOf(r("type", "sphere"), r("r", "R")),
            listOf(r("type", "pyramid"), r("h", "h"), r("a", "a")),
        )
        "ptree" -> {
            val (o1, o2) = pick(listOf("H", "T"), listOf("R", "B"), listOf("ע", "פ"), listOf("W", "L"))
            val p = pick("1/2", "1/3", "0.4")
            val q = mapOf("1/2" to "1/2", "1/3" to "2/3", "0.4" to "0.6").getValue(p)
            val rows = arrayListOf(r("b", "", p, o1), r("b", "", q, o2))
            if (chance(0.8)) for (par in listOf(o1, o2)) { rows += r("b", par, pick("1/4", "1/2"), o1); rows += r("b", par, pick("3/4", "1/2"), o2) }
            rows
        }
        "sketch" -> {
            val rows = ArrayList<List<Any>>()
            val n = int(2, 4)
            var prev: IntArray? = null
            for (i in 0 until n) {
                val x = 4 + i * (90 / n)
                val b = intArrayOf(x, int(20, 50), int(14, 20), int(10, 16))
                rows += r("box", b[0], b[1], b[2], b[3], word())
                // An arrow from the previous box's right side to this box's left side.
                prev?.let { a -> rows += r("arrow", a[0] + a[2], a[1] + a[3] / 2, b[0], b[1] + b[3] / 2) }
                prev = b
            }
            if (chance(0.5)) rows += r("callout", 10, 60, 20, 85, word())
            rows
        }
        "molecule" -> listOfNotNull(r("smiles", pickL(smiles)), if (chance(0.5)) r("name", word()) else null)
        "lewis" -> pick(
            listOf(r("c", "O"), r("l", "H", 1), r("l", "H", 1), r("lp", "O", 2)),
            listOf(r("c", "N"), r("l", "H", 1), r("l", "H", 1), r("l", "H", 1), r("lp", "N", 1)),
            listOf(r("c", "C"), r("l", "H", 1), r("l", "H", 1), r("l", "H", 1), r("l", "H", 1)),
            listOf(r("c", "C"), r("l", "O", 2), r("l", "O", 2), r("lp", "O", 2)),
            listOf(r("c", "C"), r("l", "H", 1), r("l", "N", 3), r("lp", "N", 1)),
            listOf(r("e", pick("Na", "Mg", "Al", "C", "N", "O", "F"), int(1, 7))),
        )
        "apparatus" -> (1..int(1, 3)).map { r("item", pick("beaker", "flask", "roundflask", "testtube", "cylinder", "funnel"), word(), pick(0.3, 0.5, 0.7)) } + (if (chance(0.4)) listOf(r("heat")) else emptyList())
        "energy" -> {
            val a = int(20, 60)
            val b = int(5, 80)
            listOfNotNull(r("r", pick("A+B", "R", "reactants"), a), r("p", pick("C", "P", "AB"), b), r("ts", max(a, b) + int(30, 60)), r("ea", "E_a"), if (chance(0.6)) r("dh", "\\Delta H") else null, if (chance(0.3)) r("cat") else null)
        }
        "punnett" -> {
            val di = chance(0.3)
            val g = { if (di) pick("Aa", "AA", "aa") + pick("Bb", "BB", "bb") else pick("Aa", "AA", "aa", "Bb", "Tt") }
            val g1 = g()
            listOf(r("p1", g1), r("p2", if (di) g() else g1.uppercase().take(1) + pick(g1.uppercase().take(1), g1.lowercase().take(1))))
        }
        "labelled" -> {
            val rows = arrayListOf(r("outline", pick("ellipse", "circle", "rect", "rounded")))
            val used = HashSet<String>()
            repeat(int(2, 6)) {
                val w = word()
                if (used.add(w)) rows += r("part", w, int(10, 90), int(10, 90), pick("dot", "circle", "ellipse"), int(6, 20))
            }
            rows
        }
        "chain" -> {
            val names = (words[0] + listOf("grass", "rabbit", "fox", "algae", "fish", "hawk", "חיטה", "עכבר", "نبات", "أرنب")).shuffled(rnd).take(int(3, 5))
            if (chance(0.5)) names.map { r("n", it) } else names.zipWithNext().map { (a, b) -> r("e", a, b) } + (if (names.size > 3) listOf(r("e", names[0], names[2])) else emptyList())
        }
        "flowchart" -> {
            val n = int(3, 6)
            val rows = ArrayList<List<Any>>()
            rows += r("n", "n0", "start", pick("Start", "התחלה", "بداية"))
            for (i in 1 until n - 1) rows += r("n", "n$i", pick("process", "io", "decision"), word())
            rows += r("n", "n${n - 1}", "end", pick("End", "סוף", "نهاية"))
            for (i in 0 until n - 1) rows += r("e", "n$i", "n${i + 1}")
            rows.firstOrNull { it[2] == "decision" }?.let { d -> val k = (d[1] as String).drop(1).toInt(); if (k >= 2) rows += r("e", d[1], "n${k - 1}", "no") }
            rows
        }
        "tree" -> {
            val vals = (1..int(4, 9)).map { int(1, 99) }.distinct()
            // A binary search tree from inserts.
            val left = HashMap<Int, Int>(); val right = HashMap<Int, Int>()
            for (v in vals.drop(1)) {
                var cur = vals[0]
                while (true) {
                    val m = if (v < cur) left else right
                    val nx = m[cur]
                    if (nx == null) { m[cur] = v; break }
                    cur = nx
                }
            }
            left.map { (pv, c) -> r("l", pv, c) } + right.map { (pv, c) -> r("r", pv, c) }
        }
        "network" -> {
            val names = listOf("A", "B", "C", "D", "E", "F").take(int(3, 6))
            val rows = ArrayList<List<Any>>()
            for (i in 1 until names.size) rows += r("e", names[int(0, i - 1)], names[i], int(1, 9))
            if (chance(0.6)) rows += r("e", names.last(), names[0], int(1, 9))
            if (chance(0.5)) rows += r("directed")
            rows
        }
        "array" -> listOfNotNull(r("a", *(1..int(3, 8)).map { int(0, 99) }.toTypedArray()), r("ptr", pick("i", "j", "lo", "top"), 0), if (chance(0.5)) r("hl", 1) else null)
        "list" -> listOfNotNull(r("n", *(1..int(2, 5)).map { int(0, 99) }.toTypedArray()), if (chance(0.6)) r("head", "head") else null, if (chance(0.3)) r("double") else null)
        "fsm" -> {
            val n = int(2, 4)
            val rows = (0 until n).map { r("s", "q$it", if (it == 0) "start" else if (it == n - 1) "accept" else "") }.toMutableList()
            for (i in 0 until n - 1) rows += r("t", "q$i", "q${i + 1}", pick("a", "b", "0", "1"))
            if (chance(0.5)) rows += r("t", "q${n - 1}", "q0", pick("a", "b"))
            if (chance(0.5)) rows += r("t", "q1", "q1", pick("a", "b"))
            rows
        }
        else -> error(kind)
    })

    private fun incline(): List<List<Any>> {
        val a = int(15, 60)
        val rows = arrayListOf(r("a", a), r("up", pick("L", "R")), r("blk", pick("m", "M", "2kg", "A")))
        rows += r("f", "N", "n")
        rows += r("f", pick("mg", "W", "m\\vec g"), "g")
        if (chance(0.7)) rows += r("f", pick("f_k", "f", "f_s"), pick("down", "up"))
        if (chance(0.5)) rows += r("f", "F", pick(0, 10, 20, 30, "h"))
        rows += r("ang", pick("$a^\\circ", "\\alpha", "\\theta"))
        if (chance(0.3)) rows += r("comp")
        if (chance(0.3)) rows += r("h", "h")
        return rows
    }

    private fun circuit(): List<List<Any>> {
        val rows = arrayListOf(r(pick("bat", "cell"), pick("\\varepsilon", "V", "E"), "a", "b"))
        var node = "a"
        var k = 0
        val parts = int(1, 3)
        for (i in 0 until parts) {
            val next = if (i == parts - 1) "b" else "n$i"
            if (chance(0.35)) {
                rows += r("R", "R_${++k}", node, next)
                rows += r(pick("R", "bulb", "V"), "R_${++k}".let { if (rows.last()[0] == "R") it else "-" }, node, next)
            } else {
                rows += r(pick("R", "R", "C", "bulb", "A", "sw", "L"), "R_${++k}", node, next)
            }
            node = next
        }
        return rows.map { row -> if (row[0] != "R" && row[1].toString().startsWith("R_")) row.toMutableList().also { it[1] = when (row[0]) { "C" -> "C"; "L" -> "L"; "sw" -> "S"; else -> "-" } } else row }
    }

    private fun plot(): List<List<Any>> {
        val (e, xr) = pick(
            "x^2-1" to listOf(-3, 3), "sin(x)" to listOf(-6, 6), "1/x" to listOf(-4, 4), "e^x" to listOf(-3, 2), "2x+1" to listOf(-3, 3),
            "x^3-3x" to listOf(-3, 3), "sqrt(x)" to listOf(0, 9), "-x^2+4" to listOf(-3, 3), "cos(x)" to listOf(-6, 6), "ln(x)" to listOf(0, 6),
        )
        val rows = arrayListOf(r("x", xr[0], xr[1]), r("f", e, pick("f(x)", "y", "g(x)")))
        if (chance(0.3)) rows += r("shade", 0, xr[1] / 2.0)
        if (e == "1/x") { rows += r("asy", "x", 0); rows += r("y", -4, 4) }
        if (chance(0.3)) rows += r("grid")
        if (chance(0.3)) rows += r("pt", "A", 1, app.bestnote.engine.layout.FigureMath.parse(e)!!(1.0))
        return rows
    }

    private fun geo(): List<List<Any>> {
        val n = int(3, 5)
        val names = listOf("A", "B", "C", "D", "E")
        val pts = ArrayList<Pair<Int, Int>>()
        // A convex polygon: points around a circle.
        for (i in 0 until n) {
            val a = 2 * Math.PI * i / n + rnd.nextDouble() * 0.6
            pts += Pair((5 * Math.cos(a)).toInt() + 5, (4 * Math.sin(a)).toInt() + 4)
        }
        if (pts.distinct().size < n) return geo()
        val rows = ArrayList<List<Any>>()
        for (i in 0 until n) rows += r("pt", names[i], pts[i].first, pts[i].second)
        rows += r("poly", *names.take(n).toTypedArray())
        if (n >= 4 && chance(0.6)) rows += r("dash", "A", "C")
        if (chance(0.5)) rows += r("ang", names[n - 1], "A", "B", pick("\\alpha", "\\beta"))
        if (chance(0.4)) rows += r("eq", "A", "B", 1)
        return rows
    }
}
