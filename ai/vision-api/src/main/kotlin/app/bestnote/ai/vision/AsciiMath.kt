package app.bestnote.ai.vision

/**
 * Maths a model typed as plain text inside prose — `x↑2`, `x^(30)`, `lim _(x→∞)`, `(a)/(b)` — turned
 * into inline LaTeX `\( … \)` so it is written as real maths. Text already inside `\( … \)`, `\[ … \]`,
 * `$ … $` or `$$ … $$` is left alone. Only tokens with a clear maths marker (a superscript or a
 * subscript group, or a parenthesised fraction) are converted, so ordinary words never are.
 */
object AsciiMath {
    private val DELIMITED = Regex("""\\\(.+?\\\)|\\\[.+?\\]|\$\$.+?\$\$|\$.+?\$""")
    private val FUNCTIONS = listOf("lim", "sin", "cos", "tan", "log", "ln", "exp", "max", "min")

    fun inline(text: String): String {
        if ('↑' !in text && '^' !in text && "_(" !in text && "_ (" !in text && ")/(" !in text) return text
        val out = StringBuilder()
        var at = 0
        for (m in DELIMITED.findAll(text)) {
            out.append(plain(text.substring(at, m.range.first)))
            out.append(m.value)
            at = m.range.last + 1
        }
        out.append(plain(text.substring(at)))
        return out.toString()
    }

    private fun plain(s: String): String {
        if (s.isBlank()) return s
        // Glue "lim _(" and "x ^(" back together; ↑ is a superscript.
        val glued = s.replace('↑', '^').replace(Regex("""\s+([_^])\s*\("""), "$1(")
        return glued.split(' ').joinToString(" ") { word -> if (mathy(word)) wrap(word) else word }
    }

    private fun mathy(w: String) = '^' in w || "_(" in w || ")/(" in w

    private fun wrap(word: String): String {
        val tail = word.takeLastWhile { it in ".,;:!?" }
        val core = word.dropLast(tail.length)
        val lead = core.takeWhile { it in "\"'«“" }
        val body = core.drop(lead.length)
        if (body.isEmpty()) return word
        return lead + "\\( " + toLatex(body) + " \\)" + tail
    }

    /** `lim_(x→∞)(x^(30))/(e^x+1)` → `\lim_{x \to \infty}\frac{x^{30}}{e^x+1}`. */
    internal fun toLatex(s: String): String {
        var t = s
        for (f in FUNCTIONS) t = t.replace(Regex("(?<![A-Za-z\\\\])$f(?![A-Za-z])"), "\\\\$f")
        t = scripts(t)
        t = fractions(t)
        return t.replace("→", " \\to ").replace("∞", "\\infty ").replace(Regex(" {2,}"), " ").replace(" }", "}").trim()
    }

    private fun matchClose(s: String, open: Int): Int {
        var depth = 0
        for (i in open until s.length) {
            when (s[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
        }
        return -1
    }

    private fun matchOpen(s: String, close: Int): Int {
        var depth = 0
        for (i in close downTo 0) {
            when (s[i]) {
                ')' -> depth++
                '(' -> if (--depth == 0) return i
            }
        }
        return -1
    }

    /** `^(…)` / `_(…)` → `^{…}` / `_{…}`. */
    private fun scripts(s: String): String {
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if ((c == '^' || c == '_') && s.getOrNull(i + 1)?.isDigit() == true) {
                // x^30 → x^{30}: a run of digits is one exponent.
                var j = i + 1
                while (j < s.length && s[j].isDigit()) j++
                if (j - i > 2) {
                    b.append(c).append('{').append(s, i + 1, j).append('}')
                    i = j
                    continue
                }
            }
            if ((c == '^' || c == '_') && s.getOrNull(i + 1) == '(') {
                val close = matchClose(s, i + 1)
                if (close > 0) {
                    b.append(c).append('{').append(scripts(s.substring(i + 2, close))).append('}')
                    i = close + 1
                    continue
                }
            }
            b.append(c)
            i++
        }
        return b.toString()
    }

    /** `(A)/(B)` → `\frac{A}{B}` (innermost first). */
    private fun fractions(s: String): String {
        var t = s
        while (true) {
            val slash = t.indexOf(")/(")
            if (slash < 0) return t
            val aOpen = matchOpen(t, slash)
            val bClose = matchClose(t, slash + 2)
            if (aOpen < 0 || bClose < 0) return t
            val a = t.substring(aOpen + 1, slash)
            val bPart = t.substring(slash + 3, bClose)
            t = t.substring(0, aOpen) + "\\frac{" + a + "}{" + bPart + "}" + t.substring(bClose + 1)
        }
    }
}
