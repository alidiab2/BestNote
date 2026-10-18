package app.bestnote.engine.layout.figlib

/** FIG-LIB: a worked example per kind (catalogue previews, the gallery and tests). */
object FigExamples {
    private fun r(vararg v: Any): List<Any> = v.map { if (it is Int) it.toDouble() else it }

    val all: List<FigSpec> by lazy {
        listOf(
            FigSpec("incline", listOf(r("a", 37), r("blk", "m"), r("f", "N", "n"), r("f", "mg", "g"), r("f", "f_k", "down"), r("f", "F", 20), r("ang", "37^\\circ"))),
            FigSpec("fbd", listOf(r("body", "box", "m"), r("f", "T", 60), r("f", "mg", "down"), r("f", "N", "up"), r("f", "f", "left"), r("ang", "\\alpha", "T"))),
            FigSpec("blocks", listOf(r("m", "m_1"), r("m", "m_2"), r("link", "rope"), r("f", "F", "right", 2), r("v", "v", 1))),
            FigSpec("spring", listOf(r("state", "compressed"), r("k", "k"), r("m", "m"), r("x", "\\Delta x"), r("eq"))),
            FigSpec("pulley", listOf(r("type", "table"), r("m", "M"), r("m", "m"), r("t"))),
            FigSpec("pendulum", listOf(r("a", 25), r("l", "L"), r("m", "m"), r("f", "T", "t"), r("f", "mg", "g"), r("path"))),
            FigSpec("circuit", listOf(r("bat", "\\varepsilon", "a", "b"), r("A", "-", "a", "d"), r("R", "R_1", "d", "c"), r("R", "R_2", "c", "b"), r("R", "R_3", "c", "b"), r("V", "-", "c", "b"), r("I", "I", "a", "d"))),
            FigSpec("lens", listOf(r("type", "convex"), r("f", 1), r("obj", 2.5, 0.8))),
            FigSpec("mirror", listOf(r("type", "concave"), r("f", 1), r("obj", 3, 0.7))),
            FigSpec("refraction", listOf(r("n", 1, 1.5), r("i", 45), r("reflect"))),
            FigSpec("wave", listOf(r("type", "transverse"), r("n", 2), r("A", "A"), r("lam", "\\lambda"), r("v", "v"))),
            FigSpec("field", listOf(r("q", "+", -2, 0), r("q", "-", 2, 0))),
            FigSpec("vectors", listOf(r("v", "\\vec a", 3, 1), r("v", "\\vec b", 1, 2, 3, 1), r("sum", "\\vec a+\\vec b"))),
            FigSpec("plot", listOf(r("x", -3, 3), r("f", "x^2-1", "f(x)"), r("shade", -1, 1), r("pt", "A", 2, 3), r("drop"))),
            FigSpec("numberline", listOf(r("x", -3, 5), r("int", -1, 3, "[)"), r("pt", 4, "closed", "a"))),
            FigSpec("geo", listOf(r("pt", "A", 0, 0), r("pt", "B", 4, 0), r("pt", "C", 3, 3), r("pt", "D", 0, 3), r("poly", "A", "B", "C", "D"), r("dash", "A", "C"), r("ang", "B", "A", "C", "\\alpha"), r("eq", "A", "D", 1), r("eq", "D", "C", 1), r("right", "D", "A", "B"))),
            FigSpec("triangle", listOf(r("v", "A", "B", "C"), r("type", "right"), r("right", "C"), r("side", "A", "C", "b"), r("side", "B", "C", "a"), r("side", "A", "B", "c"), r("ang", "A", "\\alpha"))),
            FigSpec("venn", listOf(r("sets", "A", "B"), r("shade", "A∩B"), r("in", "A", "3"), r("in", "AB", "2"), r("in", "B", "5"), r("in", "out", "1"))),
            FigSpec("solid", listOf(r("type", "cylinder"), r("r", "r"), r("h", "h"))),
            FigSpec("ptree", listOf(r("b", "", "1/2", "H"), r("b", "", "1/2", "T"), r("b", "H", "1/3", "R"), r("b", "H", "2/3", "B"), r("b", "T", "1/4", "R"), r("b", "T", "3/4", "B"))),
            FigSpec("sketch", listOf(r("box", 5, 30, 30, 20, "input"), r("arrow", 35, 40, 60, 40, ""), r("circ", 75, 40, 12, "f"), r("brace", 5, 60, 35, 60, "n"), r("callout", 75, 28, 92, 8, "core"))),
        )
    }
}
