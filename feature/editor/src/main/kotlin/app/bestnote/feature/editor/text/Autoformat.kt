package app.bestnote.feature.editor.text

import app.bestnote.core.model.ListStyle
import app.bestnote.core.model.ParagraphFormat
import app.bestnote.core.model.ParagraphStyle

/**
 * Markdown-style shortcuts typed at the start of a line (TEXT-04): `# ` Title, `## ` Heading, `### ` Subheading,
 * `- ` / `* ` bullets, `1. ` numbers, `> ` quote, "``` " code block; and `` `code` `` inline. Pure, so it is unit tested.
 */
internal object Autoformat {
    sealed interface Match

    /** The paragraph starting at [start] begins with a [length]-character marker that becomes [format]. */
    data class Paragraph(val start: Int, val length: Int, val format: (ParagraphFormat) -> ParagraphFormat) : Match

    /** Backticks at [open] and [close] (exclusive pair) wrap code: both go, the text between turns monospaced. */
    data class InlineCode(val open: Int, val close: Int) : Match

    private val NUMBERED = Regex("""^[0-9٠-٩]{1,3}[.)] $""")

    /**
     * What the character just typed before [caret] in [text] completes, or null. Only a space completes a line marker
     * (and only when everything before it on that line is the marker); only a backtick closes inline code.
     */
    fun detect(text: String, caret: Int): Match? {
        if (caret <= 0 || caret > text.length) return null
        val start = text.lastIndexOf('\n', caret - 1) + 1
        val typed = text[caret - 1]
        if (typed == ' ') {
            val prefix = text.substring(start, caret)
            val change: ((ParagraphFormat) -> ParagraphFormat)? = when {
                prefix == "# " -> { f -> f.copy(style = ParagraphStyle.Title) }
                prefix == "## " -> { f -> f.copy(style = ParagraphStyle.Heading) }
                prefix == "### " -> { f -> f.copy(style = ParagraphStyle.Subheading) }
                prefix == "> " -> { f -> f.copy(style = ParagraphStyle.Quote) }
                prefix == "``` " -> { f -> f.copy(style = ParagraphStyle.Code) }
                prefix == "- " || prefix == "* " || prefix == "• " -> { f -> f.copy(list = ListStyle.Bullet) }
                NUMBERED.matches(prefix) -> { f -> f.copy(list = ListStyle.Numbered) }
                else -> null
            }
            return change?.let { Paragraph(start, prefix.length, it) }
        }
        if (typed == '`') {
            val close = caret - 1
            // "``" or "```" being typed is a code-block marker in the making, not inline code.
            if (close > start && text[close - 1] == '`') return null
            val open = text.lastIndexOf('`', close - 1)
            if (open < start || open + 1 >= close) return null
            if (open > start && text[open - 1] == '`') return null
            return InlineCode(open, close)
        }
        return null
    }
}
