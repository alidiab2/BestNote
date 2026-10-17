package app.bestnote.feature.editor.text

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.MoreHoriz
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.swatchOutline
import app.bestnote.core.designsystem.theme.softShadow
import app.bestnote.core.model.ListStyle
import app.bestnote.core.model.ParagraphStyle
import app.bestnote.core.model.TextAlignment
import app.bestnote.feature.editor.R

/** What the format bar's buttons do; the editor routes them to [TextTyping]. */
internal class FormatBarActions(
    val onFont: () -> Unit,
    val onMore: () -> Unit,
    val onDone: () -> Unit,
)

/**
 * The format bar docked just above the soft keyboard (or the bottom edge with a hardware keyboard) while typing on
 * the page (TEXT-04): paragraph styles in one tap, then font, size, colour, B / I / U / S, lists, alignment.
 * Buttons never take focus, so the keyboard stays up. Scrolls sideways on narrow screens.
 */
@Composable
internal fun TextFormatBar(typing: TextTyping, colors: List<Int>, actions: FormatBarActions, modifier: Modifier = Modifier) {
    val e = typing.editing ?: return
    val session = e.session.takeIf { typing.version >= 0 } ?: return
    val box = session.draft.box
    val shape = BestNoteShapes.ToolTray
    Row(
        modifier
            .softShadow(Elevation.Medium, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.S, vertical = Spacing.XS)
            .semantics { contentDescription = "text-format-bar" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.XXS),
    ) {
        val current = session.style
        STYLES.forEach { (style, label) ->
            StyleChip(style, stringResource(label), current == style) { typing.act { it.setStyle(style) } }
        }
        Divider()
        BarText("Aa", stringResource(R.string.text_font, stringResource(R.string.text_font_default)), onClick = actions.onFont)
        BarIcon(Icons.Filled.TextDecrease, stringResource(R.string.text_smaller)) { typing.act { it.stepSize(-SIZE_STEP) } }
        Text("${box.sizePt.toInt()}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Spacing.XXS))
        BarIcon(Icons.Filled.TextIncrease, stringResource(R.string.text_larger)) { typing.act { it.stepSize(SIZE_STEP) } }
        Divider()
        (colors + box.colorArgb).distinct().take(MAX_COLORS).forEach { c ->
            val label = stringResource(R.string.selection_recolor, "#%06X".format(c and 0xFFFFFF))
            Box(
                Modifier
                    .size(TOUCH)
                    .clip(CircleShape)
                    .clickable(onClickLabel = label) { typing.act { it.setColor(c) } }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(if (c == box.colorArgb) 26.dp else 20.dp)
                        .clip(CircleShape)
                        .background(if (c == box.colorArgb) MaterialTheme.colorScheme.primary else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(20.dp).clip(CircleShape).background(Color(c)).swatchOutline(Color(c))) }
            }
        }
        Divider()
        FlagButton(typing, Flag.Bold, Icons.Filled.FormatBold, stringResource(R.string.text_bold))
        FlagButton(typing, Flag.Italic, Icons.Filled.FormatItalic, stringResource(R.string.text_italic))
        FlagButton(typing, Flag.Underline, Icons.Filled.FormatUnderlined, stringResource(R.string.text_underline))
        FlagButton(typing, Flag.Strike, Icons.Filled.FormatStrikethrough, stringResource(R.string.text_strike))
        Divider()
        BarIcon(Icons.AutoMirrored.Filled.FormatListBulleted, stringResource(R.string.text_list_bullets), on = session.list == ListStyle.Bullet) {
            typing.act { it.toggleList(ListStyle.Bullet) }
        }
        BarIcon(Icons.Filled.FormatListNumbered, stringResource(R.string.text_list_numbers), on = session.list == ListStyle.Numbered) {
            typing.act { it.toggleList(ListStyle.Numbered) }
        }
        val (alignIcon, alignLabel) = when (box.align) {
            TextAlignment.Start -> Icons.AutoMirrored.Filled.FormatAlignLeft to R.string.text_align_start
            TextAlignment.Center -> Icons.Filled.FormatAlignCenter to R.string.text_align_center
            TextAlignment.End -> Icons.AutoMirrored.Filled.FormatAlignRight to R.string.text_align_end
            TextAlignment.Justify -> Icons.Filled.FormatAlignJustify to R.string.text_align_justify
        }
        BarIcon(alignIcon, stringResource(alignLabel)) {
            typing.act { it.setAlign(TextAlignment.entries[(box.align.ordinal + 1) % TextAlignment.entries.size]) }
        }
        BarIcon(Icons.Filled.MoreHoriz, stringResource(R.string.text_more), onClick = actions.onMore)
        Spacer(Modifier.width(Spacing.XS))
        BarIcon(Icons.Filled.Check, stringResource(R.string.text_done_typing), on = true, onClick = actions.onDone)
    }
}

private val STYLES = listOf(
    ParagraphStyle.Title to R.string.text_style_title,
    ParagraphStyle.Heading to R.string.text_style_heading,
    ParagraphStyle.Subheading to R.string.text_style_subheading,
    ParagraphStyle.Body to R.string.text_style_body,
    ParagraphStyle.Quote to R.string.text_style_quote,
    ParagraphStyle.Code to R.string.text_style_code,
)

@Composable
private fun StyleChip(style: ParagraphStyle, label: String, on: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .height(TOUCH)
            .clip(BestNoteShapes.Control)
            .background(if (on) colors.primaryContainer else Color.Transparent)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = Spacing.S)
            .semantics {
                contentDescription = "text-style-${style.name.lowercase()}"
                selected = on
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
            fontFamily = if (style.mono) FontFamily.Monospace else null,
            color = if (on) colors.onPrimaryContainer else colors.onSurface,
        )
    }
}

@Composable
private fun FlagButton(typing: TextTyping, flag: Flag, icon: ImageVector, label: String) {
    val session = typing.editing?.session ?: return
    BarIcon(icon, label, on = session.isOn(flag)) { typing.act { it.toggle(flag) } }
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, on: Boolean = false, onClick: () -> Unit) {
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
private fun BarText(text: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(TOUCH)
            .clip(BestNoteShapes.Control)
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(horizontal = Spacing.XS).width(Elevation.Hairline).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

private val TOUCH = 44.dp
private const val SIZE_STEP = 2f
private const val MAX_COLORS = 6
