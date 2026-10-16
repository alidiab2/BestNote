package app.bestnote.engine.handwriting

/**
 * Word-level bidirectional ordering for handwritten lines (a small, predictable subset of UAX #9):
 * every word is right-to-left (Arabic letters), left-to-right (Latin, Greek, digits, inline maths)
 * or neutral (punctuation and operators on their own). Neutrals between two runs of the same
 * direction join them; otherwise they take the line's direction.
 */
internal object Bidi {
    data class Run(val rtl: Boolean, val words: List<String>)

    private enum class Kind { R, L, N }

    private fun kind(word: String): Kind = when {
        Arabic.isArabic(word) && !InlineMath.hasToken(word) && word.none { it.isLatinLetter() } -> Kind.R
        InlineMath.hasToken(word) || word.any { it.isLetterOrDigit() } -> Kind.L
        else -> Kind.N
    }

    private fun Char.isLatinLetter() = this in 'a'..'z' || this in 'A'..'Z'

    /** [words] in reading order → runs in reading order. */
    fun runs(words: List<String>, rtl: Boolean): List<Run> {
        if (words.isEmpty()) return emptyList()
        val kinds = words.map(::kind)
        val base = if (rtl) Kind.R else Kind.L
        val resolved = kinds.indices.map { i ->
            if (kinds[i] != Kind.N) return@map kinds[i]
            val before = (i - 1 downTo 0).map { kinds[it] }.firstOrNull { it != Kind.N } ?: base
            val after = (i + 1 until kinds.size).map { kinds[it] }.firstOrNull { it != Kind.N } ?: base
            if (before == after) before else base
        }
        val out = ArrayList<Run>()
        var start = 0
        for (i in 1..words.size) {
            if (i == words.size || resolved[i] != resolved[start]) {
                out += Run(resolved[start] == Kind.R, words.subList(start, i))
                start = i
            }
        }
        return out
    }
}

/** Measures and writes one inline maths token (see [InlineMath.encode]) inside a line of text. */
internal interface InlineRenderer {
    fun measure(token: Int, scale: Float): Float

    /** Writes the token from [x] on [baseline]. @return the x after it. */
    fun write(token: Int, x: Float, baseline: Float, color: Int, scale: Float, gap: Gap): Float
}
