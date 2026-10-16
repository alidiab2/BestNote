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
import androidx.compose.runtime.LaunchedEffect

/**
 * Types or edits a text box (TEXT-01/02): the words, size, color, bold, italic and alignment. [initial] is the
 * box as it is now; [onConfirm] gets it with the new words and formatting. The field follows the typed
 * language, so Arabic runs right to left here as well as on the page.
 */
@Composable
internal fun TextEditorDialog(
    initial: TextElement,
    colors: List<Int>,
    onConfirm: (TextElement) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial.text) }
    var size by remember { mutableStateOf(initial.sizePt) }
    var color by remember { mutableStateOf(initial.colorArgb) }
    var bold by remember { mutableStateOf(initial.bold) }
    var italic by remember { mutableStateOf(initial.italic) }
    var align by remember { mutableStateOf(initial.align) }
    var font by remember { mutableStateOf(initial.font) }
    var pickFont by remember { mutableStateOf(false) }
    val fontFamily = app.bestnote.core.fonts.rememberFontFamily(font)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.text_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "text-box-input" },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                        fontFamily = fontFamily ?: MaterialTheme.typography.bodyLarge.fontFamily,
                        textAlign = TextAlign.Start,
                    ),
                    minLines = 2,
                    maxLines = 6,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallButton("−", stringResource(R.string.text_smaller)) { size = (size - SIZE_STEP).coerceAtLeast(TextElement.MIN_SIZE_PT) }
                    Text("${size.toInt()}", style = MaterialTheme.typography.titleMedium)
                    SmallButton("+", stringResource(R.string.text_larger)) { size = (size + SIZE_STEP).coerceAtMost(TextElement.MAX_SIZE_PT) }
                    FilterChip(bold, { bold = !bold }, label = { Text("B", fontWeight = FontWeight.Bold) })
                    FilterChip(italic, { italic = !italic }, label = { Text("I", fontStyle = FontStyle.Italic) })
                }
                androidx.compose.material3.OutlinedButton(onClick = { pickFont = true }, modifier = Modifier.semantics { contentDescription = "text-font" }) {
                    Text(stringResource(R.string.text_font, app.bestnote.core.fonts.FontRegistry.get(androidx.compose.ui.platform.LocalContext.current).let { r -> font?.let(r::nameOf) ?: stringResource(R.string.text_font_default) }))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(align == TextAlignment.Start, { align = TextAlignment.Start }, label = { Text(stringResource(R.string.text_align_start)) })
                    FilterChip(align == TextAlignment.Center, { align = TextAlignment.Center }, label = { Text(stringResource(R.string.text_align_center)) })
                    FilterChip(align == TextAlignment.End, { align = TextAlignment.End }, label = { Text(stringResource(R.string.text_align_end)) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (colors + color).distinct().forEach { c ->
                        val label = stringResource(R.string.selection_recolor, "#%06X".format(c and 0xFFFFFF))
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(if (c == color) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .clickable(onClickLabel = label) { color = c }
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
                onClick = { onConfirm(initial.copy(text = text.trimEnd(), sizePt = size, colorArgb = color, bold = bold, italic = italic, align = align, font = font)) },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(R.string.dialog_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
    if (pickFont) {
        app.bestnote.core.fonts.FontPickerDialog(null, font, onSelect = { font = it; pickFont = false }, onDismiss = { pickFont = false }, allowDefault = true)
    }
}

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
