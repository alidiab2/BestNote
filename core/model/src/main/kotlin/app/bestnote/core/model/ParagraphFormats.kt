package app.bestnote.core.model

/**
 * One-tap paragraph looks (TEXT-04). Sizes are multiples of the box's [TextElement.sizePt], so a box that is made
 * bigger keeps its hierarchy. Painter and on-canvas editor both read these numbers, so typing looks like the page.
 */
enum class ParagraphStyle(
    val sizeScale: Float,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val mono: Boolean = false,
) {
    Body(1f),
    Title(TITLE_SCALE, bold = true),
    Heading(HEADING_SCALE, bold = true),
    Subheading(SUBHEADING_SCALE, bold = true),
    Quote(1f, italic = true),
    Code(CODE_SCALE, mono = true),
    ;

    /** Pressing Enter at the end of a heading starts a Body paragraph; quotes, code and body continue. */
    val continues: Boolean get() = this == Body || this == Quote || this == Code
}

private const val TITLE_SCALE = 1.9f
private const val HEADING_SCALE = 1.5f
private const val SUBHEADING_SCALE = 1.25f
private const val CODE_SCALE = 0.9f

/**
 * How one paragraph (line-break separated) of a [TextElement] is formatted (TEXT-04). [list] null means the box's
 * own [TextElement.list]; [ListStyle.None] switches the box's list off for this paragraph. [indent] is a nesting
 * level (Tab / Shift+Tab in lists).
 */
data class ParagraphFormat(val style: ParagraphStyle = ParagraphStyle.Body, val list: ListStyle? = null, val indent: Int = 0) {
    init {
        require(indent in 0..MAX_INDENT) { "Bad indent $indent" }
    }

    val isPlain: Boolean get() = this == PLAIN

    companion object {
        val PLAIN = ParagraphFormat()
        const val MAX_INDENT = 6
    }
}

/** Pure helpers over per-paragraph formats, kept aligned with the text's paragraphs while it is edited. */
object Paragraphs {
    fun count(text: String): Int = text.count { it == '\n' } + 1

    /** Index of the paragraph holding character [offset] (an offset at a line break belongs to the paragraph it ends). */
    fun indexAt(text: String, offset: Int): Int {
        var n = 0
        for (i in 0 until offset.coerceIn(0, text.length)) if (text[i] == '\n') n++
        return n
    }

    /** Start (inclusive) and end (exclusive, without the line break) of every paragraph. */
    fun ranges(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val nl = text.indexOf('\n', from)
            if (nl < 0) {
                out += from until text.length
                return out
            }
            out += from until nl
            from = nl + 1
        }
    }

    /** One format per paragraph of [text] (missing ones plain); trailing plain entries dropped so plain text stores nothing. */
    fun normalized(formats: List<ParagraphFormat>, text: String): List<ParagraphFormat> {
        val n = count(text)
        val out = (0 until n).map { formats.getOrNull(it) ?: ParagraphFormat.PLAIN }.toMutableList()
        while (out.isNotEmpty() && out.last().isPlain) out.removeAt(out.lastIndex)
        return out
    }

    fun formatOf(formats: List<ParagraphFormat>, index: Int): ParagraphFormat = formats.getOrNull(index) ?: ParagraphFormat.PLAIN

    /**
     * The formats after [oldText] changed: [removed] characters at [at] were replaced by [inserted]. Paragraphs merged
     * by a deleted line break keep the first one's format; new paragraphs (typed line breaks) continue the format they
     * were split from, except
     * headings split at their end, whose new paragraph is Body.
     */
    fun edited(formats: List<ParagraphFormat>, oldText: String, at: Int, removed: Int, inserted: String): List<ParagraphFormat> {
        val all = (0 until count(oldText)).map { formatOf(formats, it) }
        val first = indexAt(oldText, at)
        val last = indexAt(oldText, at + removed)
        val head = all.subList(0, first + 1)
        val tail = all.subList(last + 1, all.size)
        val base = all[first]
        val added = inserted.count { it == '\n' }
        val cut = at + removed
        val endOfParagraph = (oldText.indexOf('\n', cut).takeIf { it >= 0 } ?: oldText.length) == cut
        val next = if (base.style.continues || !endOfParagraph) base else base.copy(style = ParagraphStyle.Body)
        val out = ArrayList<ParagraphFormat>(all.size + added)
        out += head
        repeat(added) { out += next }
        out += tail
        return out
    }

    /**
     * List markers of each paragraph (null when it has none). Numbers count consecutive numbered paragraphs at the
     * same indent and restart after anything else; deeper items count on their own.
     */
    fun markers(text: String, boxList: ListStyle, formats: List<ParagraphFormat>, numerals: Numerals): List<String?> {
        val counters = IntArray(ParagraphFormat.MAX_INDENT + 1)
        return text.split('\n').mapIndexed { i, p ->
            val f = formatOf(formats, i)
            val list = f.list ?: boxList
            if (list != ListStyle.Numbered) {
                // Anything that is not a numbered item at this level restarts its count (deeper levels too).
                for (k in f.indent until counters.size) counters[k] = 0
            } else {
                for (k in f.indent + 1 until counters.size) counters[k] = 0
            }
            if (list == ListStyle.None || p.isBlank()) {
                null
            } else {
                val n = if (list == ListStyle.Numbered) ++counters[f.indent] else 0
                ListNumbering.marker(list, n, numerals, ListNumbering.isRtl(p))
            }
        }
    }
}
