package app.bestnote.engine.handwriting

import kotlin.math.max

/**
 * Handwritten maths from (sanitised) LaTeX: a small subset a student actually writes by hand —
 * stacked fractions, super/subscripts, square roots, Greek letters and operators — laid out as
 * boxes and written left to right with the print hand. Anything unknown is written as its name,
 * so a formula is never lost.
 */
internal object MathParser {
    sealed interface Node
    data class Text(val s: String) : Node
    data class Space(val em: Float) : Node
    data class Frac(val num: List<Node>, val den: List<Node>) : Node
    data class Scripts(val base: Node?, val sup: List<Node>?, val sub: List<Node>?) : Node
    data class Sqrt(val body: List<Node>) : Node
    data class Group(val body: List<Node>) : Node

    /** A drawn mark on [body]: strike lines, bars, braces (with a [label]), a box, an arrow or a hat. */
    data class Deco(val body: List<Node>, val kind: DecoKind, val label: List<Node>? = null) : Node

    enum class DecoKind { Cancel, BCancel, XCancel, CancelTo, Under, Over, UnderBrace, OverBrace, Box, Vec, Hat }

    /** `\left( … \right)` and similar pairs: delimiters as tall as what they hold ("" = none; "|" bars are drawn as lines). */
    data class Delim(val left: String, val body: List<Node>, val right: String) : Node

    /** `cases`, `array`, `pmatrix`…: rows of cells with the environment's delimiters ("" = none); [alignLeft] cells start level. */
    data class Matrix(val rows: List<List<List<Node>>>, val left: String, val right: String, val alignLeft: Boolean) : Node

    /** Environments whose rows ARE the formula's lines when they wrap the whole formula. */
    private val LINE_ENVS = Regex("""^\\begin\{(aligned|align\*?|gather\*?|gathered|eqnarray\*?|split|multline\*?)\}(.*)\\end\{\1\}$""", RegexOption.DOT_MATCHES_ALL)

    /** Top-level rows (split on `\\` outside braces and environments). Alignment `&` is ignored at the top level. */
    fun parse(latex: String): List<List<Node>> = rowsOf(latex).map { Reader(it).group(untilBrace = false) }.filter { it.isNotEmpty() }

    /** The source of each top-level row of [latex] (a wrapping aligned/gather environment is removed). */
    fun rowsOf(latex: String): List<String> {
        val whole = LINE_ENVS.find(latex.trim())?.groupValues?.get(2) ?: latex
        return splitTop(whole, "\\\\").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** [s] split at [sep] where it is not inside `{…}` or a `\begin … \end` environment. */
    internal fun splitTop(s: String, sep: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var env = 0
        var start = 0
        var i = 0
        while (i < s.length) {
            when {
                s.startsWith("\\begin{", i) -> { env++; i += 7; continue }
                s.startsWith("\\end{", i) -> { env--; i += 5; continue }
                depth == 0 && env == 0 && s.startsWith(sep, i) -> {
                    out += s.substring(start, i)
                    i += sep.length
                    start = i
                    continue
                }
                s[i] == '\\' -> { i += 2; continue }
                s[i] == '{' -> depth++
                s[i] == '}' -> depth--
            }
            i++
        }
        out += s.substring(start)
        return out
    }

    /** A delimited group or an environment on one line (the stroke hand writes them inline): "(a+b)", "{ a=1 ; b=2". */
    fun inline(n: Node): List<Node> = when (n) {
        is Delim -> listOfNotNull(n.left.takeIf { it.isNotEmpty() }?.let(::Text)) + n.body + listOfNotNull(n.right.takeIf { it.isNotEmpty() }?.let(::Text))
        is Matrix -> listOfNotNull(n.left.takeIf { it.isNotEmpty() }?.let(::Text)) +
            n.rows.flatMapIndexed { k, r -> (if (k > 0) listOf(Text(";"), Space(1f)) else emptyList()) + r.flatMapIndexed { c, cell -> (if (c > 0) listOf(Space(0.6f)) else emptyList()) + cell } } +
            listOfNotNull(n.right.takeIf { it.isNotEmpty() }?.let(::Text))
        else -> listOf(n)
    }

    /** Commands this parser does not know, in the order met; they are written as their bare name. */
    fun unknownCommands(latex: String): Set<String> {
        val out = LinkedHashSet<String>()
        Reader(latex, out).group(untilBrace = false)
        return out
    }

    private class Reader(val s: String, val unknown: MutableSet<String>? = null) {
        var i = 0

        /** The delimiter after the `\right` that ended the last [group] with untilRight. */
        var rightDelim = ""

        private fun isRight(at: Int) = s.startsWith("\\right", at) && (at + 6 >= s.length || !s[at + 6].isLetter())

        fun group(untilBrace: Boolean, untilRight: Boolean = false): List<Node> {
            val out = ArrayList<Node>()
            while (i < s.length) {
                val c = s[i]
                when {
                    untilRight && c == '\\' && isRight(i) -> {
                        i += 6
                        rightDelim = delimiter()
                        return out
                    }
                    c == '}' -> {
                        i++
                        if (untilBrace) return out
                    }
                    c == '{' -> {
                        i++
                        out += Group(group(untilBrace = true))
                    }
                    c == '^' || c == '_' -> {
                        i++
                        val arg = argument()
                        val last = out.removeLastOrNull()
                        out += if (last is Deco && last.label == null && (last.kind == DecoKind.UnderBrace && c == '_' || last.kind == DecoKind.OverBrace && c == '^')) {
                            last.copy(label = arg)
                        } else if (last is Scripts && (if (c == '^') last.sup == null else last.sub == null)) {
                            if (c == '^') last.copy(sup = arg) else last.copy(sub = arg)
                        } else {
                            if (c == '^') Scripts(last, arg, null) else Scripts(last, null, arg)
                        }
                    }
                    c == '\\' -> command(out)
                    c == '&' || c == '$' -> i++
                    c.isWhitespace() -> i++
                    else -> {
                        out += Text(c.toString())
                        i++
                    }
                }
            }
            return out
        }

        fun argument(): List<Node> {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) return emptyList()
            return when (s[i]) {
                '{' -> { i++; group(untilBrace = true) }
                '\\' -> ArrayList<Node>().also { command(it) }
                else -> listOf(Text(s[i++].toString()))
            }
        }

        /** The delimiter after `\left`/`\right`: a character or a command; "" for `.`. */
        fun delimiter(): String {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) return ""
            val c = s[i++]
            if (c == '.') return ""
            if (c != '\\') return c.toString()
            if (i >= s.length) return ""
            if (!s[i].isLetter()) return s[i++].toString()
            val st = i
            while (i < s.length && s[i].isLetter()) i++
            return when (val name = s.substring(st, i)) {
                "lvert", "rvert", "vert", "mid" -> "|"
                "lVert", "rVert", "Vert" -> "‖"
                "lbrace", "rbrace" -> if (name == "lbrace") "{" else "}"
                "langle" -> "⟨"
                "rangle" -> "⟩"
                "lfloor" -> "⌊"
                "rfloor" -> "⌋"
                "lceil" -> "⌈"
                "rceil" -> "⌉"
                else -> ""
            }
        }

        /** `\begin{name} … \end{name}` (the `\begin{name}` already read): rows split on `\\\\`, cells on `&`. */
        fun environment(name: String): Node {
            if (name == "array" || name == "tabular") { while (i < s.length && s[i].isWhitespace()) i++; if (i < s.length && s[i] == '{') rawArgument() }
            val bodyStart = i
            var depth = 1
            var end = s.length
            while (i < s.length) {
                if (s.startsWith("\\begin{", i)) { depth++; i += 7; continue }
                if (s.startsWith("\\end{", i)) {
                    depth--
                    if (depth == 0) {
                        end = i
                        i += 4
                        rawArgument()
                        break
                    }
                    i += 5
                    continue
                }
                i++
            }
            val body = s.substring(bodyStart, minOf(end, s.length))
            val rows = splitTop(body, "\\\\").map { row -> splitTop(row, "&").map { cell -> Reader(cell, unknown).group(untilBrace = false) } }
                .filter { r -> r.any { it.isNotEmpty() } }
            val (l, r) = when (name.trimEnd('*')) {
                "cases", "dcases" -> "{" to ""
                "rcases" -> "" to "}"
                "pmatrix" -> "(" to ")"
                "bmatrix" -> "[" to "]"
                "Bmatrix" -> "{" to "}"
                "vmatrix" -> "|" to "|"
                "Vmatrix" -> "‖" to "‖"
                else -> "" to ""
            }
            val left = name.startsWith("cases") || name.startsWith("dcases") || name.startsWith("align") || name == "split" || name == "array"
            return Matrix(rows, l, r, alignLeft = left)
        }

        /** Raw text of a `{...}` argument (for \text). */
        fun rawArgument(): String {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length || s[i] != '{') return ""
            var depth = 0
            val start = i + 1
            while (i < s.length) {
                when (s[i]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { i++; return s.substring(start, i - 1) } }
                }
                i++
            }
            return s.substring(start)
        }

        fun command(out: MutableList<Node>) {
            i++ // backslash
            if (i >= s.length) return
            val name = if (s[i].isLetter()) {
                val st = i
                while (i < s.length && s[i].isLetter()) i++
                s.substring(st, i)
            } else {
                s[i++].toString()
            }
            when (name) {
                "frac", "dfrac", "tfrac" -> out += Frac(argument(), argument())
                "overset", "stackrel" -> {
                    val top = argument()
                    out += Scripts(Group(argument()), top, null)
                }
                "underset" -> {
                    val bottom = argument()
                    out += Scripts(Group(argument()), null, bottom)
                }
                "sqrt" -> {
                    if (i < s.length && s[i] == '[') { while (i < s.length && s[i] != ']') i++; i++ }
                    out += Sqrt(argument())
                }
                "text", "mathrm", "textrm", "textbf", "operatorname", "mbox", "textit", "textnormal", "mathsf", "mathtt", "texttt" -> {
                    val raw = rawArgument()
                    val words = ArrayList<Node>()
                    raw.split(' ').forEachIndexed { k, w ->
                        if (k > 0) words += Space(0.9f)
                        if (w.isNotEmpty()) words += Text(w)
                    }
                    // Right-to-left words stay one run of their own so the device's BiDi orders them.
                    if (raw.any { it.code in 0x0590..0x08FF }) out += Group(words) else out += words
                }
                "cancel" -> out += Deco(argument(), DecoKind.Cancel)
                "bcancel" -> out += Deco(argument(), DecoKind.BCancel)
                "xcancel" -> out += Deco(argument(), DecoKind.XCancel)
                "cancelto" -> {
                    val to = argument()
                    out += Deco(argument(), DecoKind.CancelTo, to)
                }
                "underbrace" -> out += Deco(argument(), DecoKind.UnderBrace)
                "overbrace" -> out += Deco(argument(), DecoKind.OverBrace)
                "boxed", "fbox" -> out += Deco(argument(), DecoKind.Box)
                "underline" -> out += Deco(argument(), DecoKind.Under)
                "overline", "bar" -> out += Deco(argument(), DecoKind.Over)
                "vec", "overrightarrow" -> out += Deco(argument(), DecoKind.Vec)
                "hat", "widehat" -> out += Deco(argument(), DecoKind.Hat)
                "displaystyle", "textstyle" -> Unit
                "mathbf", "mathit", "boldsymbol", "mathcal", "mathbb", "bm", "mathscr", "tilde", "dot", "ddot", "textcolor" -> out.add(Group(argument()))
                "left" -> {
                    val l = delimiter()
                    rightDelim = ""
                    val body = group(untilBrace = false, untilRight = true)
                    out += Delim(l, body, rightDelim)
                }
                "right" -> delimiter()
                "big", "Big", "bigg", "Bigg" -> {
                    if (i < s.length && s[i] == '.') i++
                }
                "begin" -> out += environment(rawArgument().trim())
                "end" -> rawArgument()
                ",", ":", ";", ">" -> out += Space(0.35f)
                "!" -> Unit
                " " -> out += Space(0.5f)
                "quad" -> out += Space(1.2f)
                "qquad" -> out += Space(2.4f)
                "{", "}", "%", "#", "&", "_" -> out += Text(name)
                in FUNCTIONS -> {
                    // "mg sin θ": a hand leaves a little air around function names.
                    if (out.isNotEmpty()) out += Space(0.3f)
                    out += Text(name)
                    // "\lim_{x→0}": the limits attach to the name, not to the air after it.
                    if (i >= s.length || (s[i] != '_' && s[i] != '^')) out += Space(0.25f)
                }
                "limits", "nolimits", "displaylimits", "big", "Bigl", "Bigr", "bigl", "bigr", "middle" -> Unit
                "binom", "dbinom", "tbinom" -> {
                    val n = argument()
                    val k = argument()
                    out += Text("(")
                    out += Frac(n, k)
                    out += Text(")")
                }
                else -> {
                    val symbol = SYMBOLS[name]
                    if (symbol == null) unknown?.add(name)
                    // Unknown command: never print its name; draw its braced argument, if any.
                    if (symbol != null) out += Text(symbol)
                    else if (i < s.length && s[i] == '{') out += Group(argument())
                }
            }
        }
    }

    private val FUNCTIONS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "log", "ln", "exp", "lim", "max", "min", "det",
        "sinh", "cosh", "arcsin", "arccos", "arctan", "sup", "inf", "gcd", "deg", "dim", "ker", "arg", "limsup", "liminf",
    )

    private val SYMBOLS = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "Delta" to "Δ", "epsilon" to "ε", "varepsilon" to "ε",
        "eta" to "η", "theta" to "θ", "vartheta" to "θ", "lambda" to "λ", "mu" to "μ", "pi" to "π", "rho" to "ρ",
        "sigma" to "σ", "tau" to "τ", "phi" to "φ", "varphi" to "φ", "omega" to "ω", "Sigma" to "Σ", "Pi" to "Π",
        "Omega" to "Ω", "Phi" to "Φ", "Theta" to "Θ", "Lambda" to "Λ",
        "cdot" to "·", "times" to "×", "div" to "÷", "pm" to "±", "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
        "neq" to "≠", "ne" to "≠", "approx" to "≈", "sim" to "≈", "infty" to "∞", "partial" to "∂", "int" to "∫",
        "sum" to "Σ", "prod" to "Π", "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒",
        "implies" to "⇒", "Leftarrow" to "⇐", "downarrow" to "↓", "parallel" to "∥", "perp" to "⊥", "angle" to "∠",
        "circ" to "°", "degree" to "°", "in" to "∈", "ldots" to "…", "dots" to "…", "cdots" to "…", "checkmark" to "✓",
        "sin" to "sin", "cos" to "cos", "tan" to "tan", "log" to "log", "ln" to "ln", "exp" to "exp", "lim" to "lim",
        "max" to "max", "min" to "min", "det" to "det",
        "lbrace" to "{", "rbrace" to "}", "langle" to "<", "rangle" to ">", "mid" to "|", "vert" to "|",
        // Dots in every flavour models use (\dotsm 1 was showing as "dotsm1").
        "dotsm" to "…", "dotsc" to "…", "dotsb" to "…", "dotsi" to "…", "dotso" to "…", "vdots" to "⋮", "ddots" to "⋱",
        "cdotp" to "·", "colon" to ":", "lvert" to "|", "rvert" to "|", "lVert" to "‖", "rVert" to "‖", "Vert" to "‖",
        "nabla" to "∇", "forall" to "∀", "exists" to "∃", "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃",
        "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "varnothing" to "∅", "neg" to "¬", "land" to "∧", "wedge" to "∧",
        "lor" to "∨", "vee" to "∨", "iff" to "⇔", "Leftrightarrow" to "⇔", "leftrightarrow" to "↔", "mapsto" to "↦",
        "gets" to "←", "prime" to "′", "hbar" to "ℏ", "ell" to "ℓ", "notin" to "∉", "ni" to "∋", "equiv" to "≡",
        "propto" to "∝", "cong" to "≅", "simeq" to "≃", "ll" to "≪", "gg" to "≫", "leqslant" to "≤", "geqslant" to "≥",
        "zeta" to "ζ", "xi" to "ξ", "chi" to "χ", "psi" to "ψ", "kappa" to "κ", "iota" to "ι", "nu" to "ν", "upsilon" to "υ",
        "Gamma" to "Γ", "Psi" to "Ψ", "Xi" to "Ξ", "varrho" to "ρ", "varsigma" to "ς", "star" to "⋆", "ast" to "∗",
        "bullet" to "•", "oint" to "∮", "iint" to "∬", "coprod" to "∐", "bigcup" to "∪", "bigcap" to "∩",
        "sinh" to "sinh", "cosh" to "cosh", "arcsin" to "arcsin", "arccos" to "arccos", "arctan" to "arctan",
        "sup" to "sup", "inf" to "inf", "gcd" to "gcd", "deg" to "deg", "dim" to "dim", "ker" to "ker", "arg" to "arg",
    )
}

/** Width, height above the baseline and depth below it, in page units. */
internal data class Metrics(val width: Float, val ascent: Float, val descent: Float)

internal class MathWriter(private val hand: Hand, private val text: TextWriter, private val shapes: ShapeWriter) {
    private val xh get() = hand.xh

    fun measureRows(latex: String, scale: Float = 1f): List<Metrics> = MathParser.parse(latex).map { measure(it, scale) }

    fun measure(nodes: List<MathParser.Node>, scale: Float): Metrics {
        var w = 0f
        var a = 0.0f
        var d = 0f
        for ((k, n) in nodes.withIndex()) {
            val m = measure(n, scale)
            w += m.width + if (k > 0 && needsGap(nodes[k - 1], n)) gap(scale) else 0f
            a = max(a, m.ascent)
            d = max(d, m.descent)
        }
        return Metrics(w, max(a, xh * scale), d)
    }

    private fun gap(scale: Float) = 0.28f * xh * scale

    /** Operators get air around them, like a student spaces "a = b + c". */
    private fun needsGap(a: MathParser.Node, b: MathParser.Node): Boolean = isOp(a) || isOp(b)

    private fun isOp(n: MathParser.Node) = n is MathParser.Text && n.s.length == 1 && n.s[0] in "=+−-×·<>≤≥≈≠⇒→±÷"

    private fun measure(n: MathParser.Node, scale: Float): Metrics = when (n) {
        is MathParser.Text -> {
            val tall = n.s.any { it.isUpperCase() || it.isDigit() || it in "bdfhklt()[]{}|/∫∂βλθΣΠΩΦ√" }
            val deep = n.s.any { it in "gjpqyβγμρφ()[]{}|,;∫" }
            Metrics(text.measureWord(n.s, scale, Script.Print), (if (tall) 1.5f else 1f) * xh * scale, (if (deep) 0.5f else 0.05f) * xh * scale)
        }
        is MathParser.Space -> Metrics(n.em * xh * scale, 0f, 0f)
        is MathParser.Group -> measure(n.body, scale)
        is MathParser.Deco -> {
            val b = measure(n.body, scale)
            val e = DecoGeometry.extra(n.kind, xh * scale)
            val label = n.label?.takeIf { n.kind == MathParser.DecoKind.UnderBrace || n.kind == MathParser.DecoKind.OverBrace }?.let { measure(it, scale * SCRIPT) }
            val above = n.kind == MathParser.DecoKind.OverBrace
            Metrics(
                max(b.width + e.side, label?.width ?: 0f),
                b.ascent + e.up + if (above && label != null) label.ascent + label.descent else 0f,
                b.descent + e.down + if (!above && label != null && n.kind == MathParser.DecoKind.UnderBrace) label.ascent + label.descent else 0f,
            )
        }
        is MathParser.Frac -> {
            val s = fracScale(scale)
            val num = measure(n.num, s)
            val den = measure(n.den, s)
            val axis = 0.55f * xh * scale
            val pad = 0.3f * xh * scale
            Metrics(max(num.width, den.width) + 0.5f * xh * scale, axis + pad + num.descent + num.ascent, -axis + pad + den.ascent + den.descent)
        }
        is MathParser.Scripts -> {
            val base = n.base?.let { measure(it, scale) } ?: Metrics(0f, xh * scale, 0f)
            val sup = n.sup?.let { measure(it, scale * SCRIPT) }
            val sub = n.sub?.let { measure(it, scale * SCRIPT) }
            val w = base.width + max(sup?.width ?: 0f, sub?.width ?: 0f) + 0.08f * xh * scale
            val a = max(base.ascent, (sup?.let { supRaise(scale) + it.ascent }) ?: 0f)
            val d = max(base.descent, (sub?.let { subDrop(scale) + it.descent }) ?: 0f)
            Metrics(w, a, d)
        }
        is MathParser.Sqrt -> {
            val b = measure(n.body, scale)
            Metrics(b.width + 1.1f * xh * scale, b.ascent + 0.35f * xh * scale, max(b.descent, 0.1f * xh * scale))
        }
        is MathParser.Delim, is MathParser.Matrix -> measure(MathParser.inline(n), scale)
    }

    private fun fracScale(scale: Float) = max(0.55f, scale * 0.82f)
    private fun supRaise(scale: Float) = 0.85f * xh * scale
    private fun subDrop(scale: Float) = 0.35f * xh * scale

    /** Writes one row starting at [x] on [baseline]. @return the x after it. */
    fun write(nodes: List<MathParser.Node>, x: Float, baseline: Float, color: Int, scale: Float = 1f, firstGap: Gap = Gap.Line): Float {
        var cx = x
        var g = firstGap
        for ((k, n) in nodes.withIndex()) {
            if (k > 0 && needsGap(nodes[k - 1], n)) cx += gap(scale) * hand.rnd.uniform(0.8f, 1.25f)
            cx = write(n, cx, baseline, color, scale, g)
            if (n !is MathParser.Space) g = Gap.Word
        }
        return cx
    }

    private fun write(n: MathParser.Node, x: Float, baseline: Float, color: Int, scale: Float, gap: Gap): Float = when (n) {
        is MathParser.Text -> {
            val line = Baseline(hand, x, baseline, steady = true)
            text.writeWord(line, n.s, color, scale, Script.Print, gap)
            line.x
        }
        is MathParser.Space -> x + n.em * xh * scale
        is MathParser.Group -> write(n.body, x, baseline, color, scale, gap)
        is MathParser.Deco -> {
            val b = measure(n.body, scale)
            val e = DecoGeometry.extra(n.kind, xh * scale)
            val m = measure(n, scale)
            val bx = x + (m.width - b.width) / 2 + e.side / 2
            write(n.body, bx, baseline, color, scale, gap)
            DecoGeometry.draw(shapes, n.kind, bx, baseline, b.width, b.ascent, b.descent, xh * scale, color, e)
            n.label?.takeIf { n.kind == MathParser.DecoKind.UnderBrace || n.kind == MathParser.DecoKind.OverBrace }?.let { l ->
                val lm = measure(l, scale * SCRIPT)
                val ly = if (n.kind == MathParser.DecoKind.OverBrace) baseline - b.ascent - e.up - lm.descent else baseline + b.descent + e.down + lm.ascent
                write(l, bx + (b.width - lm.width) / 2, ly, color, scale * SCRIPT, Gap.Letter)
            }
            x + m.width
        }
        is MathParser.Frac -> {
            val m = measure(n, scale)
            val s = fracScale(scale)
            val num = measure(n.num, s)
            val den = measure(n.den, s)
            val axis = baseline - 0.55f * xh * scale
            val pad = 0.3f * xh * scale
            write(n.num, x + (m.width - num.width) / 2, axis - pad - num.descent, color, s, gap)
            shapes.line(P(x + 0.05f * xh, axis), P(x + m.width - 0.05f * xh, axis), color, gap = Gap.Letter, ruler = false)
            write(n.den, x + (m.width - den.width) / 2, axis + pad + den.ascent, color, s, Gap.Letter)
            x + m.width
        }
        is MathParser.Scripts -> {
            var cx = n.base?.let { write(it, x, baseline, color, scale, gap) } ?: x
            cx += 0.05f * xh * scale
            val end1 = n.sup?.let { write(it, cx, baseline - supRaise(scale), color, scale * SCRIPT, Gap.Letter) } ?: cx
            val end2 = n.sub?.let { write(it, cx, baseline + subDrop(scale), color, scale * SCRIPT, Gap.Letter) } ?: cx
            max(end1, end2)
        }
        is MathParser.Sqrt -> {
            val b = measure(n.body, scale)
            val top = baseline - b.ascent - 0.25f * xh * scale
            val w = 1.0f * xh * scale
            val sign = listOf(
                P(x, baseline - 0.45f * xh * scale),
                P(x + 0.2f * w, baseline - 0.55f * xh * scale),
                P(x + 0.45f * w, baseline + 0.1f * xh * scale),
                P(x + 0.85f * w, top),
                P(x + w + b.width + 0.1f * xh * scale, top),
            )
            shapes.polyline(sign, closed = false, color = color, ruler = false)
            write(n.body, x + w + 0.05f * xh * scale, baseline, color, scale, Gap.Letter)
            x + w + b.width + 0.1f * xh * scale
        }
        is MathParser.Delim, is MathParser.Matrix -> write(MathParser.inline(n), x, baseline, color, scale, gap)
    }

    private companion object {
        const val SCRIPT = 0.65f
    }
}

/** Inline maths inside prose (`\( … \)` or `$ … $`) flattened to what a hand writes on one line. */
internal object InlineMath {
    /** `\( … \)`, `\[ … \]`, `$$ … $$` and `$ … $` (all seen in real answers). */
    private val INLINE = Regex("""\\\((.+?)\\\)|\\\[(.+?)\\]|\$\$(.+?)\$\$|\$(.+?)\$""")
    private const val OPEN = '\uE000'
    private const val CLOSE = '\uE001'
    private val TOKEN = Regex("$OPEN(\\d+)$CLOSE")

    /**
     * Replaces each inline formula in [text] with a space-free token naming its index in [table],
     * so word wrap keeps a formula in one piece and the writer can draw it with real maths layout.
     */
    fun encode(text: String, table: MutableList<String>): String =
        if (!text.contains('\\') && !text.contains('$')) text else INLINE.replace(text) { m ->
            val tex = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty().trim()
            var i = table.indexOf(tex)
            if (i < 0) {
                table += tex
                i = table.lastIndex
            }
            "$OPEN$i$CLOSE"
        }

    fun hasToken(word: String) = word.indexOf(OPEN) >= 0

    /** (token index or null, text) pieces of a word, in order. */
    fun segments(word: String): List<Pair<Int?, String>> {
        val out = ArrayList<Pair<Int?, String>>()
        var at = 0
        for (m in TOKEN.findAll(word)) {
            if (m.range.first > at) out += null to word.substring(at, m.range.first)
            out += m.groupValues[1].toInt() to ""
            at = m.range.last + 1
        }
        if (at < word.length) out += null to word.substring(at)
        return out
    }

    fun stripTokens(word: String) = TOKEN.replace(word, "")

    fun flatten(text: String): String = if (!text.contains('\\') && !text.contains('$')) text else INLINE.replace(text) { m ->
        val tex = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
        MathParser.parse(tex).joinToString(" ") { flat(it) }
    }

    private fun wrapped(nodes: List<MathParser.Node>): String {
        val s = flat(nodes)
        return if (s.length <= 1) s else "($s)"
    }

    private fun flat(nodes: List<MathParser.Node>): String = nodes.joinToString("") { n ->
        when (n) {
            is MathParser.Text -> n.s
            is MathParser.Space -> " "
            is MathParser.Group -> flat(n.body)
            is MathParser.Deco -> flat(n.body)
            is MathParser.Frac -> wrapped(n.num) + "/" + wrapped(n.den)
            is MathParser.Scripts -> (n.base?.let { flat(listOf(it)) } ?: "") +
                (n.sub?.let { wrapped(it) } ?: "") + (n.sup?.let { wrapped(it) } ?: "")
            is MathParser.Sqrt -> "√" + wrapped(n.body)
            is MathParser.Delim -> n.left + flat(n.body) + n.right
            is MathParser.Matrix -> n.left + n.rows.joinToString("; ") { r -> r.joinToString(" ") { flat(it) } } + n.right
        }
    }
}
