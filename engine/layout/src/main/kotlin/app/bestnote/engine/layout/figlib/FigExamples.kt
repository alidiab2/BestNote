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
            FigSpec("molecule", listOf(r("smiles", "CC(=O)Oc1ccccc1C(=O)O"), r("name", "aspirin"))),
            FigSpec("lewis", listOf(r("c", "O"), r("l", "H", 1), r("l", "H", 1), r("lp", "O", 2))),
            FigSpec("apparatus", listOf(r("item", "flask", "A", 0.4), r("item", "testtube", "B", 0.5), r("item", "beaker", "C", 0.6), r("heat"))),
            FigSpec("energy", listOf(r("r", "A+B", 40), r("p", "C", 15), r("ts", 100), r("ea", "E_a"), r("dh", "\\Delta H"), r("cat"))),
            FigSpec("punnett", listOf(r("p1", "Aa"), r("p2", "Aa"))),
            FigSpec("labelled", listOf(r("outline", "ellipse"), r("part", "nucleus", 50, 50, "circle", 22), r("part", "membrane", 95, 40), r("part", "mitochondrion", 25, 70, "ellipse", 14), r("part", "ribosome", 70, 25), r("title", "cell"))),
            FigSpec("chain", listOf(r("n", "grass"), r("n", "rabbit"), r("n", "fox"))),
            FigSpec("flowchart", listOf(r("n", "s", "start", "Start"), r("n", "i", "io", "read n"), r("n", "d", "decision", "n > 0?"), r("n", "p", "process", "n = n - 1"), r("n", "e", "end", "End"), r("e", "s", "i"), r("e", "i", "d"), r("e", "d", "p", "yes"), r("e", "p", "d"), r("e", "d", "e", "no"))),
            FigSpec("tree", listOf(r("l", "8", "3"), r("r", "8", "10"), r("l", "3", "1"), r("r", "3", "6"), r("r", "10", "14"))),
            FigSpec("network", listOf(r("e", "A", "B", 4), r("e", "B", "C", 2), r("e", "A", "C", 5), r("e", "C", "D", 1), r("directed"))),
            FigSpec("array", listOf(r("a", 3, 8, 1, 9, 4), r("ptr", "i", 1), r("ptr", "j", 3), r("hl", 2))),
            FigSpec("list", listOf(r("n", 7, 2, 5), r("head", "head"))),
            FigSpec("fsm", listOf(r("s", "q0", "start"), r("s", "q1"), r("s", "q2", "accept"), r("t", "q0", "q1", "a"), r("t", "q1", "q2", "b"), r("t", "q1", "q1", "a"), r("t", "q2", "q0", "a"))),
            FigSpec("sketch", listOf(r("box", 5, 30, 30, 20, "input"), r("arrow", 35, 40, 60, 40, ""), r("circ", 75, 40, 12, "f"), r("brace", 5, 60, 35, 60, "n"), r("callout", 75, 28, 92, 8, "core"))),
        )
    }
}
