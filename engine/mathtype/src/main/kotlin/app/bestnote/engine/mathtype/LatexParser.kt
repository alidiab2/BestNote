package app.bestnote.engine.mathtype

import app.bestnote.engine.mathtype.MathNode.MatrixKind

/**
 * Reads the LaTeX maths students write (and vision models return) into [MathNode]s. It never throws:
 * anything it cannot read becomes a [MathNode.Error] drawn in the error colour, with a [LatexError]
 * pointing at the source characters, so a half-typed formula still shows everything else.
 */
object LatexParser {
    /** Longest source accepted; longer input is cut (a formula, not a document). */
    const val MAX_LENGTH = 4000
    private const val MAX_DEPTH = 48

    fun parse(latex: String): ParsedLatex {
        val src = if (latex.length > MAX_LENGTH) latex.substring(0, MAX_LENGTH) else latex
        val r = Reader(src)
        if (latex.length > MAX_LENGTH) r.errors += LatexError(MAX_LENGTH, latex.length, "formula too long")
        val root = try {
            r.top()
        } catch (@Suppress("SwallowedException") e: TooDeep) {
            r.errors += LatexError(0, src.length, "formula nested too deeply")
            MathNode.Error(src)
        }
        return ParsedLatex(root, r.errors.sortedBy { it.start })
    }

    private class TooDeep : RuntimeException()

    /** What ended a list of atoms. */
    private enum class Stop { End, Brace, Right, Cell, Row, EndEnv, Bracket }

    private class Reader(val s: String) {
        var i = 0
        val errors = ArrayList<LatexError>()
        var depth = 0
        var stop = Stop.End

        /** `\middle` delimiters met while reading the body of a `\left … \right`. */
        var middles: MutableList<Pair<Int, String>>? = null
        var rightDelim = ""

        fun error(start: Int, end: Int, message: String) {
            errors += LatexError(start, end.coerceAtMost(s.length).coerceAtLeast(start), message)
        }

        /** The whole input: one row, or several lines (`\\`) and alignment cells (`&`). */
        fun top(): MathNode {
            val rows = cellsUntil(setOf(Stop.End))
            if (rows.size == 1 && rows[0].size == 1) return rows[0][0]
            val aligned = rows.any { it.size > 1 }
            return MathNode.Matrix(rows, if (aligned) MatrixKind.Aligned else MatrixKind.Gathered)
        }

        /** Rows of cells until one of [ends] (an environment's `\end`, or the end of the input). */
        fun cellsUntil(ends: Set<Stop>): List<List<MathNode.Row>> {
            val rows = ArrayList<List<MathNode.Row>>()
            var row = ArrayList<MathNode.Row>()
            while (true) {
                val items = list(ends + setOf(Stop.Cell, Stop.Row))
                row += MathNode.Row(items)
                when (stop) {
                    Stop.Cell -> Unit
                    Stop.Row -> { rows += row; row = ArrayList() }
                    else -> { rows += row; break }
                }
            }
            // A trailing `\\` adds no empty line.
            if (rows.size > 1 && rows.last().all { it.items.isEmpty() }) rows.removeAt(rows.lastIndex)
            return rows
        }

        fun group(ends: Set<Stop>): MathNode.Row = MathNode.Row(list(ends))

        /** Atoms until a stop in [ends]; sets [stop] to what ended them. */
        @Suppress("CyclomaticComplexMethod", "LongMethod")
        fun list(ends: Set<Stop>): List<MathNode> {
            if (++depth > MAX_DEPTH) throw TooDeep()
            val out = ArrayList<MathNode>()
            try {
                while (i < s.length) {
                    val c = s[i]
                    when {
                        c == '}' -> {
                            if (Stop.Brace in ends) { i++; stop = Stop.Brace; return out }
                            error(i, i + 1, "unexpected }")
                            out += MathNode.Error("}")
                            i++
                        }
                        c == ']' && Stop.Bracket in ends -> { i++; stop = Stop.Bracket; return out }
                        c == '{' -> {
                            val at = i
                            i++
                            val g = group(setOf(Stop.Brace))
                            if (stop != Stop.Brace) error(at, at + 1, "missing }")
                            out += g
                        }
                        c == '^' || c == '_' -> scripts(out, c)
                        c == '\'' -> primes(out)
                        c == '&' -> {
                            i++
                            if (Stop.Cell in ends) { stop = Stop.Cell; return out }
                        }
                        c == '%' -> while (i < s.length && s[i] != '\n') i++
                        c == '$' -> i++
                        c.isWhitespace() -> i++
                        c == '~' -> { i++; out += MathNode.Space(6f) }
                        c == '\\' -> {
                            if (s.startsWith("\\\\", i)) {
                                i += 2
                                skipOptional() // \\[2pt]
                                if (Stop.Row in ends) { stop = Stop.Row; return out }
                                continue
                            }
                            val at = i
                            val name = commandName()
                            when (name) {
                                "right" -> {
                                    if (Stop.Right in ends) { rightDelim = delimiter(at); stop = Stop.Right; return out }
                                    error(at, i, "\\right without \\left")
                                    delimiter(at)
                                }
                                "end" -> {
                                    val env = braced()
                                    if (Stop.EndEnv in ends) { stop = Stop.EndEnv; endName = env; return out }
                                    error(at, i, "\\end{$env} without \\begin")
                                }
                                "middle" -> {
                                    val d = delimiter(at)
                                    val m = middles
                                    if (m != null) m += out.size to d else error(at, i, "\\middle outside \\left … \\right")
                                }
                                "limits", "nolimits", "displaylimits" -> {
                                    val last = out.lastOrNull()
                                    if (last is MathNode.BigOp) out[out.lastIndex] = last.copy(limits = if (name == "displaylimits") null else name == "limits")
                                    else error(at, i, "\\$name must follow an operator")
                                }
                                "displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle" -> {
                                    val level = listOf("displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle").indexOf(name)
                                    val rest = list(ends)
                                    out += MathNode.Styled(level, MathNode.Row(rest))
                                    return out
                                }
                                else -> command(name, at, out)
                            }
                        }
                        else -> {
                            val cp = s.codePointAt(i)
                            val text = String(Character.toChars(cp))
                            i += Character.charCount(cp)
                            val op = MathSymbols.bigOpSymbols[text]
                            out += when {
                                op != null -> MathNode.BigOp(text, name = false, limitsByDefault = op)
                                else -> when (text) {
                                "-" -> MathNode.Sym("−", AtomKind.Bin)
                                "*" -> MathNode.Sym("∗", AtomKind.Bin)
                                else -> MathNode.Sym(text, MathSymbols.kindOf(text))
                                }
                            }
                        }
                    }
                }
                stop = Stop.End
                return out
            } finally {
                depth--
            }
        }

        var endName = ""

        fun commandName(): String {
            i++ // backslash
            if (i >= s.length) return ""
            val start = i
            if (s[i].isLetter()) {
                while (i < s.length && s[i].isLetter()) i++
                // \operatorname* and friends
                if (i < s.length && s[i] == '*' && s.substring(start, i) in STARRED) i++
            } else {
                i += Character.charCount(s.codePointAt(i))
            }
            return s.substring(start, i)
        }

        fun skipSpaces() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        /** `[…]` if present (an optional argument), else null. */
        fun optional(): MathNode.Row? {
            skipSpaces()
            if (i >= s.length || s[i] != '[') return null
            val at = i
            i++
            val g = group(setOf(Stop.Bracket))
            if (stop != Stop.Bracket) error(at, at + 1, "missing ]")
            return g
        }

        fun skipOptional() {
            skipSpaces()
            if (i < s.length && s[i] == '[') {
                val close = s.indexOf(']', i)
                i = if (close < 0) s.length else close + 1
            }
        }

        /** A required argument: a braced group, one command, or one character. */
        fun argument(owner: Int): MathNode.Row {
            skipSpaces()
            if (i >= s.length) {
                error(owner, i, "missing argument")
                return MathNode.Row(emptyList())
            }
            return when (s[i]) {
                '{' -> {
                    val at = i
                    i++
                    val g = group(setOf(Stop.Brace))
                    if (stop != Stop.Brace) error(at, at + 1, "missing }")
                    g
                }
                '}', '&', '^', '_' -> {
                    error(owner, i, "missing argument")
                    MathNode.Row(emptyList())
                }
                '\\' -> {
                    val out = ArrayList<MathNode>()
                    val at = i
                    if (s.startsWith("\\\\", i)) {
                        error(owner, i, "missing argument")
                        return MathNode.Row(out)
                    }
                    command(commandName(), at, out)
                    MathNode.Row(out)
                }
                else -> {
                    val cp = s.codePointAt(i)
                    val text = String(Character.toChars(cp))
                    i += Character.charCount(cp)
                    MathNode.Row(listOf(MathNode.Sym(if (text == "-") "−" else text, MathSymbols.kindOf(text))))
                }
            }
        }

        /** The raw text of a braced argument (`\text{…}`, environment names). */
        fun braced(): String {
            skipSpaces()
            if (i >= s.length || s[i] != '{') return ""
            var d = 0
            val start = i + 1
            while (i < s.length) {
                when (s[i]) {
                    '\\' -> i++
                    '{' -> d++
                    '}' -> if (--d == 0) { i++; return s.substring(start, i - 1) }
                }
                i++
            }
            error(start - 1, start, "missing }")
            return s.substring(start)
        }

        /** The delimiter after `\left`, `\right`, `\big`…: "" for `.`. */
        fun delimiter(owner: Int): String {
            skipSpaces()
            if (i >= s.length) {
                error(owner, i, "missing delimiter")
                return ""
            }
            if (s[i] == '\\') {
                val at = i
                val name = commandName()
                return MathSymbols.delimiters[name] ?: run {
                    error(at, i, "\\$name is not a delimiter")
                    ""
                }
            }
            val c = s[i++]
            return when (c) {
                '.' -> ""
                '(', ')', '[', ']', '|', '/', '<', '>' -> when (c) {
                    '<' -> "⟨"
                    '>' -> "⟩"
                    else -> c.toString()
                }
                else -> {
                    error(i - 1, i, "'$c' is not a delimiter")
                    ""
                }
            }
        }

        fun scripts(out: MutableList<MathNode>, c: Char) {
            val at = i
            i++
            val arg = argument(at)
            val last = out.removeLastOrNull()
            out += when {
                last is MathNode.Scripts && c == '^' && last.sup == null -> last.copy(sup = arg)
                last is MathNode.Scripts && c == '_' && last.sub == null -> last.copy(sub = arg)
                last is MathNode.Scripts -> {
                    error(at, i, if (c == '^') "double superscript" else "double subscript")
                    if (c == '^') MathNode.Scripts(last, arg, null) else MathNode.Scripts(last, null, arg)
                }
                last is MathNode.Brace && last.label == null && (c == '^') == last.over -> last.copy(label = arg)
                c == '^' -> MathNode.Scripts(last, arg, null)
                else -> MathNode.Scripts(last, null, arg)
            }
        }

        fun primes(out: MutableList<MathNode>) {
            var n = 0
            while (i < s.length && s[i] == '\'') { n++; i++ }
            val prime = MathNode.Sym(PRIMES.getOrElse(n - 1) { "′".repeat(n) }, AtomKind.Ord, Alphabet.Upright)
            val last = out.removeLastOrNull()
            out += if (last is MathNode.Scripts && last.sup == null) {
                last.copy(sup = MathNode.Row(listOf(prime)))
            } else {
                MathNode.Scripts(last, MathNode.Row(listOf(prime)), null)
            }
        }

        @Suppress("CyclomaticComplexMethod", "LongMethod")
        fun command(name: String, at: Int, out: MutableList<MathNode>) {
            MathSymbols.spaces[name]?.let { out += MathNode.Space(it); return }
            MathSymbols.symbols[name]?.let { out += MathNode.Sym(it.text, it.kind); return }
            MathSymbols.bigOps[name]?.let { (sym, limits) -> out += MathNode.BigOp(sym, name = false, limitsByDefault = limits); return }
            MathSymbols.opNames[name]?.let { limits ->
                out += MathNode.BigOp(MathSymbols.opText[name] ?: name, name = true, limitsByDefault = limits)
                return
            }
            MathSymbols.accents[name]?.let { (cp, stretchy) -> out += MathNode.Accent(cp, argument(at), stretchy); return }
            when (name) {
                "frac", "dfrac", "tfrac", "cfrac" -> {
                    val num = argument(at)
                    val den = argument(at)
                    val style = when (name) {
                        "dfrac", "cfrac" -> MathNode.FracStyle.Display
                        "tfrac" -> MathNode.FracStyle.Text
                        else -> MathNode.FracStyle.Auto
                    }
                    out += MathNode.Frac(num, den, style = style)
                }
                "binom", "dbinom", "tbinom" -> {
                    val style = when (name) {
                        "dbinom" -> MathNode.FracStyle.Display
                        "tbinom" -> MathNode.FracStyle.Text
                        else -> MathNode.FracStyle.Auto
                    }
                    out += MathNode.Frac(argument(at), argument(at), rule = false, style = style, left = "(", right = ")")
                }
                "sqrt" -> {
                    val index = optional()
                    out += MathNode.Radical(argument(at), index)
                }
                "left" -> {
                    val left = delimiter(at)
                    val saved = middles
                    val mids = ArrayList<Pair<Int, String>>()
                    middles = mids
                    val body = list(setOf(Stop.Right))
                    middles = saved
                    val right = if (stop == Stop.Right) rightDelim else {
                        error(at, at + 5, "\\left without \\right")
                        ""
                    }
                    val parts = ArrayList<MathNode.Row>()
                    var from = 0
                    mids.forEach { (index, _) ->
                        parts += MathNode.Row(body.subList(from, index))
                        from = index
                    }
                    parts += MathNode.Row(body.subList(from, body.size))
                    out += MathNode.Fenced(left, parts, mids.map { it.second }, right)
                    stop = Stop.End
                }
                "big", "Big", "bigg", "Bigg", "bigl", "Bigl", "biggl", "Biggl", "bigr", "Bigr", "biggr", "Biggr", "bigm", "Bigm", "biggm", "Biggm" -> {
                    val size = when (name.removeSuffix("l").removeSuffix("r").removeSuffix("m")) {
                        "big" -> 1
                        "Big" -> 2
                        "bigg" -> 3
                        else -> 4
                    }
                    val d = delimiter(at)
                    val kind = when {
                        name.endsWith("l") -> AtomKind.Open
                        name.endsWith("r") -> AtomKind.Close
                        name.endsWith("m") -> AtomKind.Rel
                        else -> MathSymbols.kindOf(d)
                    }
                    out += MathNode.BigDelim(d, size, kind)
                }
                "overline" -> out += MathNode.Line(argument(at), over = true)
                "underline" -> out += MathNode.Line(argument(at), over = false)
                "overbrace" -> out += MathNode.Brace(argument(at), over = true, label = null)
                "underbrace" -> out += MathNode.Brace(argument(at), over = false, label = null)
                "overset" -> {
                    val over = argument(at)
                    val base = argument(at)
                    out += MathNode.Stack(base, over, null, null)
                }
                "underset" -> {
                    val under = argument(at)
                    val base = argument(at)
                    out += MathNode.Stack(base, null, under, null)
                }
                "stackrel" -> {
                    val over = argument(at)
                    val base = argument(at)
                    out += MathNode.Stack(base, over, null, AtomKind.Rel)
                }
                "text", "textrm", "textnormal", "mbox", "hbox", "textup", "textsf", "texttt" -> out += MathNode.Text(braced())
                "textbf" -> out += MathNode.Text(braced(), bold = true)
                "textit", "emph", "textsl" -> out += MathNode.Text(braced(), italic = true)
                "operatorname", "operatorname*" -> {
                    val words = braced().replace(Regex("""\\,|\\ |\\!|\\;"""), " ").trim()
                    out += MathNode.BigOp(words, name = true, limitsByDefault = name.endsWith("*"))
                }
                "mathrm", "rm", "mathup", "mathsf", "mathtt", "mathnormal" -> out += alphabet(at, if (name == "mathnormal") Alphabet.Default else Alphabet.Upright)
                "mathit", "it" -> out += alphabet(at, Alphabet.Italic)
                "mathbf", "bf" -> out += alphabet(at, Alphabet.Bold)
                "boldsymbol", "bm", "mathbfit" -> out += alphabet(at, Alphabet.BoldItalic)
                "mathbb", "Bbb" -> out += alphabet(at, Alphabet.DoubleStruck)
                "mathcal", "mathscr" -> out += alphabet(at, Alphabet.Script)
                "mathfrak" -> out += alphabet(at, Alphabet.Fraktur)
                "mathord" -> out += argument(at).copy(kind = AtomKind.Ord)
                "mathop" -> out += argument(at).copy(kind = AtomKind.Op)
                "mathbin" -> out += argument(at).copy(kind = AtomKind.Bin)
                "mathrel" -> out += argument(at).copy(kind = AtomKind.Rel)
                "mathopen" -> out += argument(at).copy(kind = AtomKind.Open)
                "mathclose" -> out += argument(at).copy(kind = AtomKind.Close)
                "mathpunct" -> out += argument(at).copy(kind = AtomKind.Punct)
                "mathinner" -> out += argument(at).copy(kind = AtomKind.Inner)
                "boxed", "fbox" -> out += MathNode.Boxed(argument(at))
                "phantom" -> out += MathNode.Phantom(argument(at))
                "hphantom" -> out += MathNode.Phantom(argument(at), width = true, height = false)
                "vphantom" -> out += MathNode.Phantom(argument(at), width = false, height = true)
                "color", "textcolor" -> {
                    // Colour comes from the element; the words stay.
                    braced()
                    if (name == "textcolor") out += argument(at)
                }
                "not" -> {
                    skipSpaces()
                    val next = ArrayList<MathNode>()
                    if (i < s.length && s[i] == '\\') command(commandName(), at, next) else if (i < s.length) next += argument(at)
                    val sym = (next.singleOrNull() as? MathNode.Sym) ?: (next.singleOrNull() as? MathNode.Row)?.items?.singleOrNull() as? MathNode.Sym
                    val negated = sym?.let { MathSymbols.negations[it.text] }
                    if (negated != null) out += MathNode.Sym(negated, AtomKind.Rel) else {
                        error(at, i, "cannot negate this")
                        out += next
                    }
                }
                "bmod" -> out += MathNode.Row(listOf(MathNode.Text("mod")), AtomKind.Bin)
                "pmod" -> {
                    val arg = argument(at)
                    out += MathNode.Space(18f)
                    out += MathNode.Row(listOf(MathNode.Sym("(", AtomKind.Open), MathNode.Text("mod"), MathNode.Space(6f)) + arg.items + MathNode.Sym(")", AtomKind.Close))
                }
                "begin" -> out += environment(at)
                "tag", "tag*" -> {
                    val t = braced()
                    out += MathNode.Space(36f)
                    out += MathNode.Text("($t)")
                }
                "label", "ref", "eqref" -> braced()
                "nonumber", "notag", "hline", "displaybreak", "allowbreak", "nobreak", "left.", "relax", "limits" -> Unit
                "" -> {
                    error(at, i, "lone backslash")
                    out += MathNode.Error("\\")
                }
                else -> {
                    error(at, i, "unknown command \\$name")
                    out += MathNode.Error("\\$name")
                }
            }
        }

        fun alphabet(at: Int, a: Alphabet): MathNode.Row = MathNode.Row(argument(at).items.map { restyle(it, a) })

        fun restyle(n: MathNode, a: Alphabet): MathNode = when (n) {
            is MathNode.Sym -> if (n.alphabet == Alphabet.Default) n.copy(alphabet = a) else n
            is MathNode.Row -> n.copy(items = n.items.map { restyle(it, a) })
            is MathNode.Scripts -> n.copy(base = n.base?.let { restyle(it, a) })
            else -> n
        }

        fun environment(at: Int): MathNode {
            val env = braced()
            val kind: MatrixKind
            var left = ""
            var right = ""
            var columns = ""
            when (env.removeSuffix("*")) {
                "matrix" -> kind = MatrixKind.Matrix
                "pmatrix" -> { kind = MatrixKind.Matrix; left = "("; right = ")" }
                "bmatrix" -> { kind = MatrixKind.Matrix; left = "["; right = "]" }
                "Bmatrix" -> { kind = MatrixKind.Matrix; left = "{"; right = "}" }
                "vmatrix" -> { kind = MatrixKind.Matrix; left = "|"; right = "|" }
                "Vmatrix" -> { kind = MatrixKind.Matrix; left = "‖"; right = "‖" }
                "smallmatrix" -> kind = MatrixKind.SmallMatrix
                "cases", "dcases" -> { kind = MatrixKind.Cases; left = "{" }
                "rcases" -> { kind = MatrixKind.Cases; right = "}" }
                "array", "subarray" -> { kind = MatrixKind.Array; columns = braced().filter { it in "lcr" } }
                "aligned", "align", "alignat", "alignedat", "split", "eqnarray", "flalign" -> {
                    kind = MatrixKind.Aligned
                    if (env.startsWith("alignat")) braced()
                }
                "gathered", "gather", "multline", "equation", "displaymath" -> kind = MatrixKind.Gathered
                else -> {
                    error(at, i, "unknown environment $env")
                    kind = MatrixKind.Matrix
                }
            }
            val rows = cellsUntil(setOf(Stop.EndEnv))
            if (stop != Stop.EndEnv) {
                error(at, at + 6 + env.length + 2, "\\begin{$env} without \\end")
            } else if (endName != env) {
                error(at, i, "\\begin{$env} ended by \\end{$endName}")
            }
            stop = Stop.End
            return MathNode.Matrix(rows, kind, left, right, columns)
        }
    }

    private val PRIMES = listOf("′", "″", "‴", "⁗")
    private val STARRED = setOf("operatorname", "tag", "align", "gather")
}
