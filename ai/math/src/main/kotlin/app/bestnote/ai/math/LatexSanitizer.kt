package app.bestnote.ai.math

sealed interface LatexResult {
    data class Ok(val latex: String) : LatexResult
    data class Invalid(val reason: String) : LatexResult
}

/**
 * Normalises LaTeX returned by a vision model before it reaches the renderer: strips math
 * delimiters, standardises board symbols written as Unicode into LaTeX commands, rejects anything
 * that is not plain maths (file I/O, macro definitions) and checks that groups balance.
 */
object LatexSanitizer {
    private val forbidden = Regex("""\\(input|include|write|immediate|openout|read|def|gdef|edef|newcommand|renewcommand|catcode|csname|url|href)\b""")

    // Longest keys first so "≤" never loses to a shorter overlapping glyph.
    private val unicode = linkedMapOf(
        "⇔" to "\\Leftrightarrow ", "⇒" to "\\Rightarrow ", "⇐" to "\\Leftarrow ", "↔" to "\\leftrightarrow ",
        "→" to "\\to ", "←" to "\\leftarrow ", "↦" to "\\mapsto ",
        "×" to "\\times ", "÷" to "\\div ", "·" to "\\cdot ", "±" to "\\pm ", "∓" to "\\mp ",
        "≤" to "\\le ", "≥" to "\\ge ", "≠" to "\\ne ", "≈" to "\\approx ", "≡" to "\\equiv ", "∝" to "\\propto ",
        "∞" to "\\infty ", "∑" to "\\sum ", "∏" to "\\prod ", "∫" to "\\int ", "∮" to "\\oint ", "√" to "\\sqrt ",
        "∂" to "\\partial ", "∇" to "\\nabla ", "∈" to "\\in ", "∉" to "\\notin ", "⊂" to "\\subset ",
        "⊆" to "\\subseteq ", "∪" to "\\cup ", "∩" to "\\cap ", "∅" to "\\emptyset ", "∀" to "\\forall ",
        "∃" to "\\exists ", "¬" to "\\neg ", "∧" to "\\land ", "∨" to "\\lor ", "∠" to "\\angle ",
        "⊥" to "\\perp ", "∥" to "\\parallel ", "°" to "^{\\circ}",
        "Δ" to "\\Delta ", "Σ" to "\\Sigma ", "Ω" to "\\Omega ", "Φ" to "\\Phi ", "Π" to "\\Pi ",
        "α" to "\\alpha ", "β" to "\\beta ", "γ" to "\\gamma ", "δ" to "\\delta ", "ε" to "\\varepsilon ",
        "θ" to "\\theta ", "λ" to "\\lambda ", "μ" to "\\mu ", "π" to "\\pi ", "ρ" to "\\rho ",
        "σ" to "\\sigma ", "τ" to "\\tau ", "φ" to "\\varphi ", "ω" to "\\omega ",
        "²" to "^{2}", "³" to "^{3}", "¹" to "^{1}", "₀" to "_{0}", "₁" to "_{1}", "₂" to "_{2}", "−" to "-",
    )

    fun sanitize(raw: String): LatexResult {
        var s = raw.trim()
        s = stripDelimiters(s)
        unicode.forEach { (k, v) -> s = s.replace(k, v) }
        s = normaliseCommands(s)
        s = s.replace(Regex("""\s+"""), " ").trim()

        if (s.isEmpty()) return LatexResult.Invalid("empty")
        forbidden.find(s)?.let { return LatexResult.Invalid("forbidden command ${it.value}") }
        balance(s)?.let { return LatexResult.Invalid(it) }
        return LatexResult.Ok(s)
    }

    /** Commands the typesetter draws; kept as written, with common variants and a missing backslash repaired. */
    private val bareDrawn = Regex("""(?<![\\A-Za-z])(cancelto|bcancel|xcancel|cancel|underbrace|overbrace|boxed)(?=\s*\{)""")

    private fun normaliseCommands(s: String): String {
        var t = bareDrawn.replace(s) { "\\" + it.value }
        t = t.replace(Regex("""\\sout\b"""), "\\cancel")
        // Hebrew or Arabic words in \mathrm{…} are plain text (typeset as one right-to-left run).
        t = t.replace(Regex("""\\(?:mathrm|textrm|textnormal|mbox)\s*\{([^{}]*[\u0590-\u08FF][^{}]*)\}""")) { "\\text{" + it.groupValues[1] + "}" }
        return t
    }

    private fun stripDelimiters(s: String): String {
        val pairs = listOf("$$" to "$$", "\\[" to "\\]", "\\(" to "\\)", "$" to "$")
        for ((open, close) in pairs) {
            if (s.length >= open.length + close.length && s.startsWith(open) && s.endsWith(close)) {
                return s.substring(open.length, s.length - close.length).trim()
            }
        }
        return s
    }

    /** Null when braces, \left/\right and environments pair up. */
    private fun balance(s: String): String? {
        var depth = 0
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++ // skip escaped char, e.g. \{
                '{' -> depth++
                '}' -> if (--depth < 0) return "unbalanced '}'"
            }
            i++
        }
        if (depth != 0) return "unbalanced '{'"
        val lefts = Regex("""\\left\b""").findAll(s).count()
        val rights = Regex("""\\right\b""").findAll(s).count()
        if (lefts != rights) return "\\left/\\right mismatch"
        val begins = Regex("""\\begin\{(\w+\*?)\}""").findAll(s).map { it.groupValues[1] }.toList()
        val ends = Regex("""\\end\{(\w+\*?)\}""").findAll(s).map { it.groupValues[1] }.toList()
        if (begins.sorted() != ends.sorted()) return "\\begin/\\end mismatch"
        return null
    }
}
