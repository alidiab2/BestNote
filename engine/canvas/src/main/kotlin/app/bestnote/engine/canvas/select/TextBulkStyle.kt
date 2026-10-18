package app.bestnote.engine.canvas.select

import app.bestnote.core.model.TextAlignment
import app.bestnote.core.model.TextElement

/**
 * One change the lasso's text bar makes to every selected text box at once (CANVAS-20): a font, a size step, bold /
 * italic / underline, a colour or an alignment. Null fields stay as each box has them. Pure Kotlin.
 *
 * @property font the font id to set; [clearFont] true sets the default font (null id) instead.
 * @property sizeStep points added to each box's size (each keeps its own size relation), clamped to the text limits.
 */
data class TextBulkStyle(
    val font: String? = null,
    val clearFont: Boolean = false,
    val sizeStep: Float = 0f,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val colorArgb: Int? = null,
    val align: TextAlignment? = null,
) {
    /** [e] with this style; the very same instance when nothing changes. Runs keep their own extras. */
    fun applyTo(e: TextElement): TextElement {
        val next = e.copy(
            font = if (clearFont) null else font ?: e.font,
            sizePt = if (sizeStep == 0f) e.sizePt else (e.sizePt + sizeStep).coerceIn(TextElement.MIN_SIZE_PT, TextElement.MAX_SIZE_PT),
            bold = bold ?: e.bold,
            italic = italic ?: e.italic,
            underline = underline ?: e.underline,
            colorArgb = colorArgb ?: e.colorArgb,
            align = align ?: e.align,
            // A colour or font for the whole box replaces per-run colours / fonts, so the change is visible everywhere.
            runs = when {
                colorArgb != null || font != null || clearFont -> e.runs.map { r ->
                    r.copy(style = r.style.copy(colorArgb = if (colorArgb != null) null else r.style.colorArgb, font = if (font != null || clearFont) null else r.style.font))
                }.filter { !it.style.isPlain }
                else -> e.runs
            },
        )
        return if (next == e) e else next
    }

    companion object {
        /** The state the bar shows for [texts]: on only when every box has it. */
        fun summary(texts: List<TextElement>): Summary = Summary(
            bold = texts.isNotEmpty() && texts.all { it.bold },
            italic = texts.isNotEmpty() && texts.all { it.italic },
            underline = texts.isNotEmpty() && texts.all { it.underline },
            font = texts.map { it.font }.distinct().singleOrNull(),
            mixedFonts = texts.map { it.font }.distinct().size > 1,
            align = texts.map { it.align }.distinct().singleOrNull(),
        )
    }

    data class Summary(val bold: Boolean, val italic: Boolean, val underline: Boolean, val font: String?, val mixedFonts: Boolean, val align: TextAlignment?)
}
