package app.bestnote.core.model

/** How paragraphs of a [TextElement] are marked (TEXT-02). */
enum class ListStyle { None, Bullet, Numbered }

/** Digits used for numbered lists: Latin 1 2 3, or Arabic-Indic ١ ٢ ٣. */
enum class Numerals { Latin, ArabicIndic }

/**
 * Styling added to one part of a text box, on top of the box's own style. A null [colorArgb] / [font] keeps
 * the box's. Bold, italic, underline and strike only ever switch things on.
 */
data class RunStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val colorArgb: Int? = null,
    val font: String? = null,
    val sizePt: Float? = null,
) {
    val isPlain: Boolean get() = this == PLAIN

    companion object {
        val PLAIN = RunStyle()
    }
}

/** [style] applies to characters [start] until [end] (end exclusive) of the text. */
data class TextRun(val start: Int, val end: Int, val style: RunStyle) {
    init {
        require(start in 0 until end) { "Bad run $start..$end" }
    }
}

/** Pure helpers over run lists, used by the editor and the storage layer. */
object TextRuns {
    /** Runs made valid for text of [length]: clipped, empty and plain ones dropped, sorted, non-overlapping, merged. */
    fun sanitized(runs: List<TextRun>, length: Int): List<TextRun> = fromStyles(toStyles(runs, length))

    /** One style per character; later runs win where they overlap. */
    fun toStyles(runs: List<TextRun>, length: Int): Array<RunStyle> {
        val out = Array(length) { RunStyle.PLAIN }
        for (r in runs) for (i in r.start.coerceAtLeast(0) until r.end.coerceAtMost(length)) out[i] = r.style
        return out
    }

    fun fromStyles(styles: Array<RunStyle>): List<TextRun> {
        val out = ArrayList<TextRun>()
        var i = 0
        while (i < styles.size) {
            val st = styles[i]
            var j = i + 1
            while (j < styles.size && styles[j] == st) j++
            if (!st.isPlain) out += TextRun(i, j, st)
            i = j
        }
        return out
    }

    /**
     * The runs after the text changed: [removed] characters at [at] were replaced by [inserted] new ones.
     * Typed characters take the style of the character before them (so typing continues bold).
     */
    fun edited(runs: List<TextRun>, oldLength: Int, at: Int, removed: Int, inserted: Int): List<TextRun> {
        val old = toStyles(runs, oldLength)
        val carry = if (at > 0 && at <= old.size) old[at - 1] else RunStyle.PLAIN
        val out = ArrayList<RunStyle>(old.size - removed + inserted)
        for (i in 0 until at.coerceAtMost(old.size)) out += old[i]
        repeat(inserted) { out += carry }
        for (i in (at + removed).coerceAtMost(old.size) until old.size) out += old[i]
        return fromStyles(out.toTypedArray())
    }

    /** [transform] applied to the style of every character in [start] until [end]. */
    fun restyled(runs: List<TextRun>, length: Int, start: Int, end: Int, transform: (RunStyle) -> RunStyle): List<TextRun> {
        val st = toStyles(runs, length)
        for (i in start.coerceAtLeast(0) until end.coerceAtMost(length)) st[i] = transform(st[i])
        return fromStyles(st)
    }

    /** True when every character of [start] until [end] satisfies [has] (false for an empty range). */
    fun all(runs: List<TextRun>, length: Int, start: Int, end: Int, has: (RunStyle) -> Boolean): Boolean {
        if (start >= end) return false
        val st = toStyles(runs, length)
        return (start.coerceAtLeast(0) until end.coerceAtMost(length)).all { has(st[it]) }
    }
}

/** Paragraph markers for lists, with correct right-to-left behavior (TEXT-02). */
object ListNumbering {
    private const val RLM = '‏'
    private const val LRM = '‎'
    const val BULLET = '•'

    /** Whether a paragraph runs right to left: its first strong letter is Arabic or Hebrew (same rule the page painter uses). */
    fun isRtl(paragraph: String): Boolean {
        for (ch in paragraph) {
            when (Character.getDirectionality(ch)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
                else -> Unit
            }
        }
        return false
    }

    /** The digits of [n] in [numerals]. */
    fun digits(n: Int, numerals: Numerals): String {
        val s = n.toString()
        return if (numerals == Numerals.ArabicIndic) s.map { '٠' + (it - '0') }.joinToString("") else s
    }

    /**
     * The marker drawn before an item: "•", or "3." (Latin) / "٣." (Arabic-Indic). Right-to-left items get a
     * right-to-left mark first so the dot lands on the far side of the number, as a reader of Arabic expects (".٣").
     */
    fun marker(style: ListStyle, number: Int, numerals: Numerals, rtl: Boolean): String? = when (style) {
        ListStyle.None -> null
        ListStyle.Bullet -> BULLET.toString()
        ListStyle.Numbered -> (if (rtl) RLM else LRM).toString() + digits(number, numerals) + "."
    }

    /**
     * The marker of each paragraph of [text] (split on line breaks), null for blank paragraphs and when
     * [style] is None. Numbers count only the non-blank paragraphs.
     */
    fun markers(text: String, style: ListStyle, numerals: Numerals): List<String?> {
        var n = 0
        return text.split('\n').map { p ->
            if (style == ListStyle.None || p.isBlank()) null else marker(style, ++n, numerals, isRtl(p))
        }
    }
}
