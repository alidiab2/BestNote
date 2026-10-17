package app.bestnote.feature.editor.math

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.bestnote.engine.canvas.document.EquationRendering
import app.bestnote.engine.mathtype.LatexError
import app.bestnote.engine.mathtype.MathLayout
import kotlin.math.min

/**
 * A typeset formula, scaled to fit the box (never larger than [maxSizeDp] per em), centered, in [color].
 * Empty slots show as faint boxes when [placeholders] is on (the editor).
 */
@Composable
internal fun EquationPreview(
    latex: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    maxSizeDp: Dp = 40.dp,
    placeholders: Boolean = true,
    display: Boolean = true,
) {
    val errorColor = MaterialTheme.colorScheme.error
    val layout: MathLayout = remember(latex, display, placeholders) { EquationRendering.layout(latex, LAYOUT_PT, display, placeholders) }
    val maxPx = with(LocalDensity.current) { maxSizeDp.toPx() }
    Box(modifier.semantics { contentDescription = latex }) {
        Canvas(Modifier.matchParentSize()) {
            if (layout.width <= 0f || layout.height <= 0f) return@Canvas
            val s = min(min(size.width * FILL / layout.width, size.height * FILL / layout.height), maxPx / LAYOUT_PT)
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                nc.translate(size.width / 2f, size.height / 2f)
                nc.scale(s, s)
                EquationRendering.draw(nc, layout, -layout.width / 2f, -layout.height / 2f + layout.ascent, color.toArgb(), errorColor.toArgb())
                nc.restore()
            }
        }
    }
}

/** Errors of [latex] as the typesetter sees them (for the field's highlight and the message under it). */
internal fun latexErrors(latex: String): List<LatexError> = EquationRendering.layout(latex, LAYOUT_PT).errors

/** Marks the characters of [errors] in the field: error colour, underlined, tinted. Never changes the text. */
private class ErrorHighlight(private val errors: List<LatexError>, private val color: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (errors.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val b = AnnotatedString.Builder(text)
        errors.forEach { e ->
            var start = e.start.coerceIn(0, text.length)
            var end = e.end.coerceIn(start, text.length)
            // A zero-width error (a missing argument) marks the character at it, or the one before at the end.
            if (end == start) {
                if (start < text.length) end = start + 1 else if (start > 0) start -= 1
            }
            if (start < end) b.addStyle(SpanStyle(color = color, background = color.copy(alpha = HIGHLIGHT_ALPHA), textDecoration = TextDecoration.Underline), start, end)
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is ErrorHighlight && other.errors == errors && other.color == color
    override fun hashCode() = errors.hashCode() * 31 + color.hashCode()
}

/** The raw LaTeX, monospaced and left-to-right even in Arabic, with errors highlighted and named under it. */
@Composable
internal fun LatexField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, label: String, modifier: Modifier = Modifier) {
    val errors = remember(value.text) { latexErrors(value.text) }
    val errorColor = MaterialTheme.colorScheme.error
    val highlight = remember(errors, errorColor) { ErrorHighlight(errors, errorColor) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = errors.isNotEmpty(),
        supportingText = errors.firstOrNull()?.let { e -> { Text(e.message, color = errorColor) } },
        visualTransformation = highlight,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
        minLines = 2,
        maxLines = 6,
        modifier = modifier,
    )
}

/** Points per em the previews are typeset at (then scaled to their box). */
private const val LAYOUT_PT = 24f
private const val FILL = 0.92f
private const val HIGHLIGHT_ALPHA = 0.12f
