package app.bestnote.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.swatchOutline
import app.bestnote.core.fonts.FontRegistry
import app.bestnote.core.fonts.rememberFontFamily
import app.bestnote.core.model.TextAlignment
import app.bestnote.core.model.TextElement
import app.bestnote.engine.canvas.select.LassoFilter
import app.bestnote.engine.canvas.select.TextBulkStyle

private val TOUCH = 44.dp
private const val SIZE_STEP = 2f
private const val MAX_COLORS = 6

private fun LassoFilter.label(): Int = when (this) {
    LassoFilter.All -> R.string.lasso_filter_all
    LassoFilter.Handwriting -> R.string.lasso_filter_handwriting
    LassoFilter.Text -> R.string.lasso_filter_text
    LassoFilter.Images -> R.string.lasso_filter_images
    LassoFilter.Shapes -> R.string.lasso_filter_shapes
}

/**
 * The lasso's options (CANVAS-20), docked at the bottom while the lasso is on: what it picks up (everything, or only
 * handwriting / text / pictures / shapes, like Goodnotes) and "Select all" on the page in view.
 */
@Composable
internal fun LassoOptionsBar(filter: LassoFilter, onFilter: (LassoFilter) -> Unit, onSelectAll: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .chromeSurface(BestNoteShapes.ToolTray)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.S, vertical = Spacing.XS)
            .semantics { contentDescription = "lasso-options" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.XXS),
    ) {
        LassoFilter.entries.forEach { f ->
            Chip(stringResource(f.label()), on = f == filter) { onFilter(f) }
        }
        BarDivider()
        Chip(stringResource(R.string.lasso_select_all), on = false, onClick = onSelectAll)
    }
}

/**
 * The text bar for a lasso selection holding text boxes (CANVAS-20): one change goes to every selected text box at
 * once (font from the TEXT-05 picker, size, bold / italic / underline, colour, alignment), even when the selection
 * also holds ink, pictures or shapes. Each change is one undo step.
 */
@Composable
internal fun SelectionTextBar(
    texts: List<TextElement>,
    colors: List<Int>,
    onStyle: (TextBulkStyle) -> Unit,
    onFont: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (texts.isEmpty()) return
    val summary = TextBulkStyle.summary(texts)
    val registry = FontRegistry.get(androidx.compose.ui.platform.LocalContext.current)
    val fontName = if (summary.mixedFonts) "…" else summary.font?.let(registry::nameOf) ?: stringResource(R.string.text_font_default)
    val family = rememberFontFamily(summary.font)
    Row(
        modifier
            .chromeSurface(BestNoteShapes.ToolTray)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.S, vertical = Spacing.XS)
            .semantics { contentDescription = "selection-text-bar" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.XXS),
    ) {
        Text(
            pluralCount(texts.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.XS),
        )
        val fontLabel = stringResource(R.string.text_font, fontName)
        Box(
            Modifier
                .height(TOUCH)
                .widthIn(max = 180.dp)
                .clip(BestNoteShapes.Control)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClickLabel = fontLabel, onClick = onFont)
                .padding(horizontal = Spacing.S)
                .semantics { contentDescription = fontLabel },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                fontName,
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = family ?: MaterialTheme.typography.labelLarge.fontFamily),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BarButton(Icons.Filled.TextDecrease, stringResource(R.string.text_smaller)) { onStyle(TextBulkStyle(sizeStep = -SIZE_STEP)) }
        texts.map { it.sizePt.toInt() }.distinct().singleOrNull()?.let {
            Text("$it", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Spacing.XXS))
        }
        BarButton(Icons.Filled.TextIncrease, stringResource(R.string.text_larger)) { onStyle(TextBulkStyle(sizeStep = SIZE_STEP)) }
        BarDivider()
        BarButton(Icons.Filled.FormatBold, stringResource(R.string.text_bold), on = summary.bold) { onStyle(TextBulkStyle(bold = !summary.bold)) }
        BarButton(Icons.Filled.FormatItalic, stringResource(R.string.text_italic), on = summary.italic) { onStyle(TextBulkStyle(italic = !summary.italic)) }
        BarButton(Icons.Filled.FormatUnderlined, stringResource(R.string.text_underline), on = summary.underline) { onStyle(TextBulkStyle(underline = !summary.underline)) }
        BarDivider()
        val current = texts.map { it.colorArgb }.distinct().singleOrNull()
        colors.distinct().take(MAX_COLORS).forEach { c ->
            val label = stringResource(R.string.selection_recolor, "#%06X".format(c and 0xFFFFFF))
            Box(
                Modifier
                    .size(TOUCH)
                    .clip(CircleShape)
                    .clickable(onClickLabel = label) { onStyle(TextBulkStyle(colorArgb = c)) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(if (c == current) 26.dp else 20.dp)
                        .clip(CircleShape)
                        .background(if (c == current) MaterialTheme.colorScheme.primary else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(20.dp).clip(CircleShape).background(Color(c)).swatchOutline(Color(c))) }
            }
        }
        BarDivider()
        BarButton(Icons.AutoMirrored.Filled.FormatAlignLeft, stringResource(R.string.text_align_start), on = summary.align == TextAlignment.Start) {
            onStyle(TextBulkStyle(align = TextAlignment.Start))
        }
        BarButton(Icons.Filled.FormatAlignCenter, stringResource(R.string.text_align_center), on = summary.align == TextAlignment.Center) {
            onStyle(TextBulkStyle(align = TextAlignment.Center))
        }
        BarButton(Icons.AutoMirrored.Filled.FormatAlignRight, stringResource(R.string.text_align_end), on = summary.align == TextAlignment.End) {
            onStyle(TextBulkStyle(align = TextAlignment.End))
        }
    }
}

@Composable
private fun pluralCount(n: Int): String = androidx.compose.ui.res.pluralStringResource(R.plurals.selection_text_boxes, n, n)

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .height(TOUCH)
            .clip(BestNoteShapes.Control)
            .background(if (on) colors.primaryContainer else Color.Transparent)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = Spacing.S)
            .semantics {
                contentDescription = label
                selected = on
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) colors.onPrimaryContainer else colors.onSurface) }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, on: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(TOUCH)
            .clip(BestNoteShapes.Control)
            .background(if (on) colors.primaryContainer else Color.Transparent)
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics {
                contentDescription = label
                selected = on
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (on) colors.onPrimaryContainer else colors.onSurfaceVariant) }
}

@Composable
private fun BarDivider() {
    Box(Modifier.padding(horizontal = Spacing.XS).width(Elevation.Hairline).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
}
