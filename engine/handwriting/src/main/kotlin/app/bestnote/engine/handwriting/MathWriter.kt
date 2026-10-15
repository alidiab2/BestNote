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

    /** Top-level rows (split on `\\`). Alignment `&` is ignored. */
    fun parse(latex: String): List<List<Node>> =
        latex.split("\\\\").map { Reader(it).group(untilBrace = false) }.filter { it.isNotEmpty() }

    private class Reader(val s: String) {
        var i = 0

        fun group(untilBrace: Boolean): List<Node> {
            val out = ArrayList<Node>()
            while (i < s.length) {
                val c = s[i]
                when {
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
                        out += if (last is Scripts && (if (c == '^') last.sup == null else last.sub == null)) {
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
                "sqrt" -> {
                    if (i < s.length && s[i] == '[') { while (i < s.length && s[i] != ']') i++; i++ }
                    out += Sqrt(argument())
                }
                "text", "mathrm", "textrm", "textbf", "operatorname", "mbox" -> {
                    val raw = rawArgument()
                    raw.split(' ').forEachIndexed { k, w ->
                        if (k > 0) out += Space(0.9f)
                        if (w.isNotEmpty()) out += Text(w)
                    }
                }
                "displaystyle", "textstyle" -> Unit
                "mathbf", "mathit", "boldsymbol", "mathcal", "mathbb", "vec", "hat", "bar", "overline", "underline" -> out.add(Group(argument()))
                "left", "right", "big", "Big", "bigg", "Bigg" -> {
                    if (i < s.length && s[i] == '.') i++
                }
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
                    out += Space(0.25f)
                }
                else -> out += Text(SYMBOLS[name] ?: name)
            }
        }
    }

    private val FUNCTIONS = setOf("sin", "cos", "tan", "cot", "sec", "csc", "log", "ln", "exp", "lim", "max", "min", "det")

    private val SYMBOLS = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "Δ", "Delta" to "Δ", "epsilon" to "ε", "varepsilon" to "ε",
        "eta" to "η", "theta" to "θ", "vartheta" to "θ", "lambda" to "λ", "mu" to "μ", "pi" to "π", "rho" to "ρ",
        "sigma" to "σ", "tau" to "τ", "phi" to "φ", "varphi" to "φ", "omega" to "ω", "Sigma" to "Σ", "Pi" to "Π",
        "Omega" to "Ω", "Phi" to "Φ", "Theta" to "θ", "Lambda" to "λ",
        "cdot" to "·", "times" to "×", "div" to "÷", "pm" to "±", "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
        "neq" to "≠", "ne" to "≠", "approx" to "≈", "sim" to "≈", "infty" to "∞", "partial" to "∂", "int" to "∫",
        "sum" to "Σ", "prod" to "Π", "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒",
        "implies" to "⇒", "Leftarrow" to "⇐", "downarrow" to "↓", "parallel" to "∥", "perp" to "⊥", "angle" to "∠",
        "circ" to "°", "degree" to "°", "in" to "∈", "ldots" to "…", "dots" to "…", "cdots" to "…", "checkmark" to "✓",
        "sin" to "sin", "cos" to "cos", "tan" to "tan", "log" to "log", "ln" to "ln", "exp" to "exp", "lim" to "lim",
        "max" to "max", "min" to "min", "det" to "det",
        "lbrace" to "{", "rbrace" to "}", "langle" to "<", "rangle" to ">", "mid" to "|", "vert" to "|",
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
    }

    private companion object {
        const val SCRIPT = 0.65f
    }
}

/** Inline maths inside prose (`\( … \)` or `$ … $`) flattened to what a hand writes on one line. */
internal object InlineMath {
    private val INLINE = Regex("""\\\((.+?)\\\)|\$(.+?)\$""")

    fun flatten(text: String): String = if (!text.contains('\\') && !text.contains('$')) text else INLINE.replace(text) { m ->
        val tex = m.groupValues[1].ifEmpty { m.groupValues[2] }
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
            is MathParser.Frac -> wrapped(n.num) + "/" + wrapped(n.den)
            is MathParser.Scripts -> (n.base?.let { flat(listOf(it)) } ?: "") +
                (n.sub?.let { "_" + wrapped(it) } ?: "") + (n.sup?.let { "^" + wrapped(it) } ?: "")
            is MathParser.Sqrt -> "√" + wrapped(n.body)
        }
    }
}
