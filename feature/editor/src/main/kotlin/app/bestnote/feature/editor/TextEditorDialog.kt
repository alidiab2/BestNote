package app.bestnote.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.bestnote.core.model.TextAlignment
import app.bestnote.core.model.TextElement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import app.bestnote.core.designsystem.font.HandwritingTypefaces
import app.bestnote.core.model.ListStyle
import app.bestnote.core.model.Numerals
import app.bestnote.core.model.RunStyle
import app.bestnote.core.model.TextRuns
import kotlin.math.roundToInt
import androidx.compose.runtime.LaunchedEffect

/**
 * Types or edits a text box (TEXT-01/02): the words and their formatting. With part of the text selected, bold,
 * italic, underline, strikethrough, color and font apply to that part (stored as style runs); with nothing
 * selected they apply to the whole box. Lists, alignment (incl. justify) and line spacing are per box.
 * The field follows the typed language, so Arabic and Hebrew run right to left here as well as on the page.
 */
@Composable
internal fun TextEditorDialog(
    initial: TextElement,
    colors: List<Int>,
    onConfirm: (TextElement) -> Unit,
    onDismiss: () -> Unit,
) {
    var field by remember { mutableStateOf(TextFieldValue(initial.text, TextRange(initial.text.length))) }
    var runs by remember { mutableStateOf(TextRuns.sanitized(initial.runs, initial.text.length)) }
    var size by remember { mutableStateOf(initial.sizePt) }
    var color by remember { mutableStateOf(initial.colorArgb) }
    var bold by remember { mutableStateOf(initial.bold) }
    var italic by remember { mutableStateOf(initial.italic) }
    var underline by remember { mutableStateOf(initial.underline) }
    var strike by remember { mutableStateOf(initial.strike) }
    var align by remember { mutableStateOf(initial.align) }
    var font by remember { mutableStateOf(initial.font) }
    var list by remember { mutableStateOf(initial.list) }
    var numerals by remember { mutableStateOf(initial.numerals) }
    var spacing by remember { mutableStateOf(initial.lineSpacing) }
    var pickFont by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val selStart = field.selection.min
    val selEnd = field.selection.max
    val hasSelection = selEnd > selStart
    val len = field.text.length

    fun onType(next: TextFieldValue) {
        val old = field.text
        if (next.text != old) {
            var p = 0
            while (p < old.length && p < next.text.length && old[p] == next.text[p]) p++
            var q = 0
            while (q < old.length - p && q < next.text.length - p && old[old.length - 1 - q] == next.text[next.text.length - 1 - q]) q++
            runs = TextRuns.edited(runs, old.length, p, old.length - p - q, next.text.length - p - q)
        }
        field = next
    }

    /** Switches one flag: on the selected part when there is one, else on the whole box. */
    fun toggle(whole: Boolean, setWhole: (Boolean) -> Unit, has: (RunStyle) -> Boolean, set: (RunStyle, Boolean) -> RunStyle) {
        if (hasSelection) {
            val on = !TextRuns.all(runs, len, selStart, selEnd, has)
            runs = TextRuns.restyled(runs, len, selStart, selEnd) { set(it, on) }
        } else {
            setWhole(!whole)
        }
    }
    fun isOn(whole: Boolean, has: (RunStyle) -> Boolean) = if (hasSelection) TextRuns.all(runs, len, selStart, selEnd, has) else whole

    val baseFamily = font?.let { HandwritingTypefaces.of(it) }?.let { FontFamily(it) }
    val transformation = remember(runs, bold, italic, underline, strike, baseFamily) {
        VisualTransformation { text ->
            val built = buildAnnotatedString {
                append(text.text)
                runs.forEach { r ->
                    if (r.end <= text.text.length) addStyle(r.style.toSpanStyle(), r.start, r.end)
                }
            }
            TransformedText(built, OffsetMapping.Identity)
        }
    }
    val decoration = listOfNotNull(
        if (underline) TextDecoration.Underline else null,
        if (strike) TextDecoration.LineThrough else null,
    ).let { if (it.isEmpty()) TextDecoration.None else TextDecoration.combine(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.text_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = field,
                    onValueChange = ::onType,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "text-box-input" },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                        fontFamily = baseFamily ?: MaterialTheme.typography.bodyLarge.fontFamily,
                        textDecoration = decoration,
                        textAlign = TextAlign.Start,
                    ),
                    visualTransformation = transformation,
                    minLines = 2,
                    maxLines = 6,
                )
                if (hasSelection) Text(stringResource(R.string.text_applies_selection), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallButton("−", stringResource(R.string.text_smaller)) { size = (size - SIZE_STEP).coerceAtLeast(TextElement.MIN_SIZE_PT) }
                    Text("${size.toInt()}", style = MaterialTheme.typography.titleMedium)
                    SmallButton("+", stringResource(R.string.text_larger)) { size = (size + SIZE_STEP).coerceAtMost(TextElement.MAX_SIZE_PT) }
                    FilterChip(isOn(bold) { it.bold }, { toggle(bold, { bold = it }, { s -> s.bold }, { s, on -> s.copy(bold = on) }) }, label = { Text("B", fontWeight = FontWeight.Bold) }, modifier = Modifier.semantics { contentDescription = "text-bold" })
                    FilterChip(isOn(italic) { it.italic }, { toggle(italic, { italic = it }, { s -> s.italic }, { s, on -> s.copy(italic = on) }) }, label = { Text("I", fontStyle = FontStyle.Italic) }, modifier = Modifier.semantics { contentDescription = "text-italic" })
                    FilterChip(isOn(underline) { it.underline }, { toggle(underline, { underline = it }, { s -> s.underline }, { s, on -> s.copy(underline = on) }) }, label = { Text("U", textDecoration = TextDecoration.Underline) }, modifier = Modifier.semantics { contentDescription = "text-underline" })
                    FilterChip(isOn(strike) { it.strike }, { toggle(strike, { strike = it }, { s -> s.strike }, { s, on -> s.copy(strike = on) }) }, label = { Text("S", textDecoration = TextDecoration.LineThrough) }, modifier = Modifier.semantics { contentDescription = "text-strike" })
                }
                androidx.compose.material3.OutlinedButton(onClick = { pickFont = true }, modifier = Modifier.semantics { contentDescription = "text-font" }) {
                    Text(stringResource(R.string.text_font, app.bestnote.core.fonts.FontRegistry.get(androidx.compose.ui.platform.LocalContext.current).let { r -> font?.let(r::nameOf) ?: stringResource(R.string.text_font_default) }))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(align == TextAlignment.Start, { align = TextAlignment.Start }, label = { Text(stringResource(R.string.text_align_start)) })
                    FilterChip(align == TextAlignment.Center, { align = TextAlignment.Center }, label = { Text(stringResource(R.string.text_align_center)) })
                    FilterChip(align == TextAlignment.End, { align = TextAlignment.End }, label = { Text(stringResource(R.string.text_align_end)) })
                    FilterChip(align == TextAlignment.Justify, { align = TextAlignment.Justify }, label = { Text(stringResource(R.string.text_align_justify)) }, modifier = Modifier.semantics { contentDescription = "text-justify" })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(list == ListStyle.Bullet, { list = if (list == ListStyle.Bullet) ListStyle.None else ListStyle.Bullet }, label = { Text(stringResource(R.string.text_list_bullets)) }, modifier = Modifier.semantics { contentDescription = "text-bullets" })
                    FilterChip(list == ListStyle.Numbered, { list = if (list == ListStyle.Numbered) ListStyle.None else ListStyle.Numbered }, label = { Text(stringResource(R.string.text_list_numbers)) }, modifier = Modifier.semantics { contentDescription = "text-numbers" })
                    if (list == ListStyle.Numbered) {
                        FilterChip(numerals == Numerals.ArabicIndic, { numerals = if (numerals == Numerals.ArabicIndic) Numerals.Latin else Numerals.ArabicIndic }, label = { Text("١٢٣") }, modifier = Modifier.semantics { contentDescription = "text-arabic-digits" })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.text_line_spacing), style = MaterialTheme.typography.labelLarge)
                    SmallButton("−", stringResource(R.string.text_spacing_tighter)) { spacing = (((spacing - SPACING_STEP) * 100).roundToInt() / 100f).coerceAtLeast(TextElement.MIN_LINE_SPACING) }
                    Text("%.1f".format(spacing), style = MaterialTheme.typography.titleMedium)
                    SmallButton("+", stringResource(R.string.text_spacing_looser)) { spacing = (((spacing + SPACING_STEP) * 100).roundToInt() / 100f).coerceAtMost(TextElement.MAX_LINE_SPACING) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val shown = if (hasSelection) runs.firstOrNull { it.start <= selStart && it.end >= selEnd }?.style?.colorArgb ?: color else color
                    (colors + color).distinct().forEach { c ->
                        val label = stringResource(R.string.selection_recolor, "#%06X".format(c and 0xFFFFFF))
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(if (c == shown) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .clickable(onClickLabel = label) {
                                    if (hasSelection) runs = TextRuns.restyled(runs, len, selStart, selEnd) { it.copy(colorArgb = c.takeIf { x -> x != color }) } else color = c
                                }
                                .semantics { contentDescription = label },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(24.dp).clip(CircleShape).background(Color(c)).lowContrastRing(Color(c)))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val text = field.text.trimEnd()
                    onConfirm(
                        initial.copy(
                            text = text, sizePt = size, colorArgb = color, bold = bold, italic = italic, underline = underline, strike = strike,
                            align = align, font = font, list = list, numerals = numerals, lineSpacing = spacing,
                            runs = TextRuns.sanitized(runs, text.length),
                        ),
                    )
                },
                enabled = field.text.isNotBlank(),
            ) { Text(stringResource(R.string.dialog_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
    if (pickFont) {
        app.bestnote.core.fonts.FontPickerDialog(
            null, if (hasSelection) runs.firstOrNull { it.start <= selStart && it.end >= selEnd }?.style?.font ?: font else font,
            onSelect = { id ->
                if (hasSelection) runs = TextRuns.restyled(runs, len, selStart, selEnd) { it.copy(font = id) } else font = id
                pickFont = false
            },
            onDismiss = { pickFont = false }, allowDefault = true,
        )
    }
}

private fun RunStyle.toSpanStyle() = SpanStyle(
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
    textDecoration = listOfNotNull(if (underline) TextDecoration.Underline else null, if (strike) TextDecoration.LineThrough else null)
        .takeIf { it.isNotEmpty() }?.let { TextDecoration.combine(it) },
    color = colorArgb?.let { Color(it) } ?: Color.Unspecified,
    fontFamily = font?.let { HandwritingTypefaces.of(it) }?.let { FontFamily(it) },
)

@Composable
private fun SmallButton(label: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.titleLarge) }
}

private const val SIZE_STEP = 2f
private const val SPACING_STEP = 0.1f
