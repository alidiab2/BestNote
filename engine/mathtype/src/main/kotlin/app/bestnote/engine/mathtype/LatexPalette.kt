package app.bestnote.engine.mathtype

/** LaTeX source with a selection (caret when [start] == [end]). */
data class LatexEdit(val text: String, val start: Int, val end: Int = start)

/**
 * One button of the equation editor's structure palette. [template] is inserted at the caret: `#`
 * marks the slot that receives the selected text (or the caret, when nothing is selected), `@` marks
 * further slots the caret can reach next. [label] is what the button shows (LaTeX, typeset).
 */
data class PaletteItem(val label: String, val template: String, val description: String)

data class PaletteGroup(val name: String, val items: List<PaletteItem>)

/** The equation editor's palette (MATH-01) and how its templates go into the source. Pure, so it is unit-tested. */
object LatexPalette {
    private fun item(label: String, template: String = label, description: String = template) = PaletteItem(label, template, description)

    val groups: List<PaletteGroup> = listOf(
        PaletteGroup(
            "Structures",
            listOf(
                item("\\frac{a}{b}", "\\frac{#}{@}", "fraction"),
                item("\\sqrt{x}", "\\sqrt{#}", "square root"),
                item("\\sqrt[n]{x}", "\\sqrt[@]{#}", "n-th root"),
                item("x^{n}", "#^{@}", "power"),
                item("x_{i}", "#_{@}", "subscript"),
                item("x_{i}^{n}", "#_{@}^{@}", "subscript and power"),
                item("\\left( x \\right)", "\\left( # \\right)", "parentheses that grow"),
                item("\\left[ x \\right]", "\\left[ # \\right]", "brackets that grow"),
                item("\\left| x \\right|", "\\left| # \\right|", "absolute value"),
                item("\\left\\{ x \\right\\}", "\\left\\{ # \\right\\}", "braces that grow"),
                item("\\overline{x}", "\\overline{#}", "bar"),
                item("\\vec{v}", "\\vec{#}", "vector"),
                item("\\hat{x}", "\\hat{#}", "hat"),
                item("\\dot{x}", "\\dot{#}", "dot"),
                item("\\binom{n}{k}", "\\binom{#}{@}", "binomial"),
                item("\\boxed{x}", "\\boxed{#}", "boxed"),
            ),
        ),
        PaletteGroup(
            "Calculus",
            listOf(
                item("\\sum_{i=1}^{n}", "\\sum_{#}^{@} ", "sum"),
                item("\\prod_{i=1}^{n}", "\\prod_{#}^{@} ", "product"),
                item("\\int_{a}^{b}", "\\int_{#}^{@} ", "integral"),
                item("\\int", "\\int # \\,d@", "integral dx"),
                item("\\oint", "\\oint ", "contour integral"),
                item("\\iint", "\\iint ", "double integral"),
                item("\\lim_{x \\to a}", "\\lim_{# \\to @} ", "limit"),
                item("\\frac{d}{dx}", "\\frac{d}{d#}", "derivative"),
                item("\\frac{\\partial}{\\partial x}", "\\frac{\\partial}{\\partial #}", "partial derivative"),
                item("\\infty", "\\infty "),
                item("\\partial", "\\partial "),
                item("\\nabla", "\\nabla "),
                item("\\log", "\\log "),
                item("\\ln", "\\ln "),
                item("\\sin", "\\sin "),
                item("\\cos", "\\cos "),
                item("\\tan", "\\tan "),
            ),
        ),
        PaletteGroup(
            "Matrices",
            listOf(
                item("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}", "\\begin{pmatrix} # & @ \\\\ @ & @ \\end{pmatrix}", "2×2 matrix"),
                item("\\begin{bmatrix} a & b \\\\ c & d \\end{bmatrix}", "\\begin{bmatrix} # & @ \\\\ @ & @ \\end{bmatrix}", "2×2 matrix, brackets"),
                item("\\begin{vmatrix} a & b \\\\ c & d \\end{vmatrix}", "\\begin{vmatrix} # & @ \\\\ @ & @ \\end{vmatrix}", "determinant"),
                item("\\begin{pmatrix} x \\\\ y \\end{pmatrix}", "\\begin{pmatrix} # \\\\ @ \\end{pmatrix}", "column vector"),
                item("\\begin{cases} a & x > 0 \\\\ b & x \\le 0 \\end{cases}", "\\begin{cases} # & @ \\\\ @ & @ \\end{cases}", "cases"),
                item("\\begin{aligned} a &= b \\\\ &= c \\end{aligned}", "\\begin{aligned} # &= @ \\\\ &= @ \\end{aligned}", "aligned steps"),
            ),
        ),
        PaletteGroup(
            "Greek",
            listOf(
                "alpha", "beta", "gamma", "delta", "epsilon", "varepsilon", "theta", "lambda", "mu", "pi", "rho", "sigma",
                "tau", "phi", "varphi", "omega", "Gamma", "Delta", "Theta", "Lambda", "Pi", "Sigma", "Phi", "Omega",
            ).map { item("\\$it", "\\$it ") },
        ),
        PaletteGroup(
            "Relations",
            listOf("=", "\\ne", "<", ">", "\\le", "\\ge", "\\approx", "\\equiv", "\\sim", "\\propto", "\\in", "\\notin", "\\subset", "\\subseteq", "\\cup", "\\cap", "\\pm", "\\times", "\\div", "\\cdot", "\\perp", "\\parallel", "\\forall", "\\exists")
                .map { item(it, if (it.startsWith("\\")) "$it " else it) },
        ),
        PaletteGroup(
            "Arrows",
            listOf("\\to", "\\leftarrow", "\\leftrightarrow", "\\Rightarrow", "\\Leftarrow", "\\Leftrightarrow", "\\mapsto", "\\uparrow", "\\downarrow", "\\rightleftharpoons", "\\longrightarrow", "\\implies")
                .map { item(it, "$it ") },
        ),
    )

    /**
     * [edit] with [item] inserted: the selection goes into the `#` slot and the caret lands in the first
     * empty slot (or after the insertion). A command name never runs into a following letter.
     */
    fun insert(edit: LatexEdit, item: PaletteItem): LatexEdit = insert(edit, item.template)

    fun insert(edit: LatexEdit, template: String): LatexEdit {
        val start = edit.start.coerceIn(0, edit.text.length)
        val end = edit.end.coerceIn(start, edit.text.length)
        val selected = edit.text.substring(start, end).trim()
        val slots = ArrayList<Int>()
        val out = StringBuilder()
        for ((k, ch) in template.withIndex()) {
            when (ch) {
                '#' -> {
                    // A leading slot ("#^{@}") is what is already before the caret: the caret goes on to the next slot.
                    if (selected.isEmpty() && k > 0) slots += out.length
                    out.append(selected)
                }
                '@' -> slots += out.length
                else -> out.append(ch)
            }
        }
        var inserted = out.toString()
        val before = edit.text.substring(0, start)
        val after = edit.text.substring(end)
        // "\alpha" + "x" would read as "\alphax".
        if (before.isNotEmpty() && endsWithCommand(before) && inserted.firstOrNull()?.isLetter() == true) inserted = " $inserted".also { slots.replaceAll { it + 1 } }
        if (endsWithCommand(inserted) && after.firstOrNull()?.isLetter() == true) inserted = "$inserted "
        val caret = before.length + (slots.firstOrNull() ?: inserted.length)
        return LatexEdit(before + inserted + after, caret)
    }

    /** The caret moved to the next empty slot (`{}`) after it, wrapping to the start; null when there is none. */
    fun nextSlot(edit: LatexEdit): LatexEdit? {
        val t = edit.text
        val from = edit.end.coerceIn(0, t.length)
        val k = t.indexOf("{}", from).takeIf { it >= 0 } ?: t.indexOf("{}").takeIf { it >= 0 } ?: return null
        return LatexEdit(t, k + 1)
    }

    private fun endsWithCommand(s: String): Boolean {
        var k = s.length - 1
        if (k < 0 || !s[k].isLetter()) return false
        while (k >= 0 && s[k].isLetter()) k--
        return k >= 0 && s[k] == '\\'
    }
}
