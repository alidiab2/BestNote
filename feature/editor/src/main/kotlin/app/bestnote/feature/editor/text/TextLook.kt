package app.bestnote.feature.editor.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import app.bestnote.core.designsystem.font.HandwritingTypefaces
import app.bestnote.core.model.ListStyle
import app.bestnote.core.model.Paragraphs
import app.bestnote.core.model.RunStyle
import app.bestnote.core.model.TextAlignment
import app.bestnote.core.model.TextElement

/** What the margin of one paragraph shows, in px of the editor (mirrors the page painter's leading margins). */
internal data class ParagraphDecor(
    val start: Int,
    val end: Int,
    val marker: String?,
    val indentPx: Float,
    val markerRoomPx: Float,
    val quoteRoomPx: Float,
    val code: Boolean,
)

/** A text box styled for Compose at [pxPerPt] (TEXT-04): the same sizes, paragraph styles and margins the page draws. */
internal class TextLook(val annotated: AnnotatedString, val style: TextStyle, val decor: List<ParagraphDecor>, val emPx: Float)

/** Compose font for a [TextElement.font] / [RunStyle.font] id; null = default. */
internal fun fontFamilyOf(id: String?): FontFamily? = when (id) {
    null -> null
    TextElement.FONT_MONO -> FontFamily.Monospace
    else -> HandwritingTypefaces.of(id)?.let { FontFamily(it) }
}

internal fun textLook(box: TextElement, pxPerPt: Float, density: Density): TextLook {
    fun sp(px: Float) = with(density) { px.toSp() }
    val em = box.sizePt * pxPerPt
    val spacing = box.lineSpacing.coerceIn(TextElement.MIN_LINE_SPACING, TextElement.MAX_LINE_SPACING)
    val decorations = listOfNotNull(if (box.underline) TextDecoration.Underline else null, if (box.strike) TextDecoration.LineThrough else null)
    val style = TextStyle(
        color = Color(box.colorArgb),
        fontSize = sp(em),
        fontWeight = if (box.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (box.italic) FontStyle.Italic else FontStyle.Normal,
        fontFamily = fontFamilyOf(box.font) ?: FontFamily.Default,
        textDecoration = if (decorations.isEmpty()) TextDecoration.None else TextDecoration.combine(decorations),
        textAlign = when (box.align) {
            TextAlignment.Start -> TextAlign.Start
            TextAlignment.Center -> TextAlign.Center
            TextAlignment.End -> TextAlign.End
            TextAlignment.Justify -> TextAlign.Justify
        },
        // First strong character decides each paragraph's direction, as on the page: Arabic runs right to left.
        textDirection = TextDirection.Content,
        lineHeight = (NATURAL_LINE * spacing).em,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    val text = box.text
    val markers = Paragraphs.markers(text, box.list, box.paragraphs, box.numerals)
    val decor = ArrayList<ParagraphDecor>()
    val annotated = buildAnnotatedString {
        append(text)
        Paragraphs.ranges(text).forEachIndexed { i, range ->
            val f = Paragraphs.formatOf(box.paragraphs, i)
            val listed = (f.list ?: box.list) != ListStyle.None
            val indent = f.indent * em * LIST_INDENT_EM
            val markerRoom = if (listed) em * LIST_INDENT_EM else 0f
            val quoteRoom = if (f.style == app.bestnote.core.model.ParagraphStyle.Quote) em * QUOTE_INDENT_EM else 0f
            val end = (range.last + 2).coerceAtMost(text.length) // includes the line break
            val start = range.first
            decor += ParagraphDecor(start, range.last + 1, markers[i], indent, markerRoom, quoteRoom, f.style == app.bestnote.core.model.ParagraphStyle.Code)
            val margin = indent + markerRoom + quoteRoom
            if (start < end && margin > 0f) addStyle(ParagraphStyle(textIndent = TextIndent(sp(margin), sp(margin))), start, end)
            if (start < end && f.style != app.bestnote.core.model.ParagraphStyle.Body) {
                addStyle(
                    SpanStyle(
                        fontSize = sp(em * f.style.sizeScale),
                        fontWeight = if (f.style.bold) FontWeight.Bold else null,
                        fontStyle = if (f.style.italic) FontStyle.Italic else null,
                        fontFamily = if (f.style.mono) FontFamily.Monospace else null,
                    ),
                    start, end,
                )
            }
        }
        for (r in box.runs) if (r.end <= text.length) addStyle(r.style.toSpanStyle(pxPerPt, density), r.start, r.end)
    }
    return TextLook(annotated, style, decor, em)
}

internal fun RunStyle.toSpanStyle(pxPerPt: Float, density: Density): SpanStyle = SpanStyle(
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
    textDecoration = listOfNotNull(if (underline) TextDecoration.Underline else null, if (strike) TextDecoration.LineThrough else null)
        .takeIf { it.isNotEmpty() }?.let { TextDecoration.combine(it) },
    color = colorArgb?.let { Color(it) } ?: Color.Unspecified,
    fontFamily = fontFamilyOf(font),
    fontSize = sizePt?.let { with(density) { (it * pxPerPt).toSp() } } ?: androidx.compose.ui.unit.TextUnit.Unspecified,
)

/** Android's natural line height for the default sans is about this many ems; the box's line spacing multiplies it. */
private const val NATURAL_LINE = 1.17f
internal const val LIST_INDENT_EM = 1.4f
internal const val QUOTE_INDENT_EM = 0.9f
internal const val QUOTE_BAR = 0.22f
