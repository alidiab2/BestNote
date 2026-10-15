package app.bestnote.feature.settings.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.hairline
import app.bestnote.core.designsystem.theme.softShadow
import app.bestnote.feature.settings.R

/** Title + one-line description at the top of a detail pane (Fraunces, design board 07). */
@Composable
internal fun DetailHeader(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = Spacing.S)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.size(Spacing.XS))
        Text(description, style = MaterialTheme.typography.bodyMedium, color = BestNoteTheme.colors.muted)
    }
}

/** Builds the rows of a [SettingsGroup]; rows are separated by hairlines. */
internal class GroupScope {
    val rows = mutableListOf<@Composable () -> Unit>()
    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/** An overline label over a white, hairline-bordered card of rows. */
@Composable
internal fun SettingsGroup(label: String?, modifier: Modifier = Modifier, content: GroupScope.() -> Unit) {
    val scope = GroupScope().apply(content)
    Column(modifier.fillMaxWidth().padding(top = Spacing.XL)) {
        if (label != null) {
            Text(
                label.uppercase(),
                style = BestNoteTheme.type.overline,
                color = BestNoteTheme.colors.muted,
                modifier = Modifier.padding(start = Spacing.XS, bottom = Spacing.S).semantics { heading() },
            )
        }
        Surface(
            shape = BestNoteShapes.Card,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().softShadow(Elevation.Low, BestNoteShapes.Card).hairline(BestNoteShapes.Card),
        ) {
            Column(Modifier.selectableGroup()) {
                scope.rows.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = Spacing.L))
                    row()
                }
            }
        }
    }
}

/** Leading icon in a soft tile. */
@Composable
internal fun IconTile(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, background: Color = tileBackground()) {
    Box(
        Modifier.size(TileSize).clip(RoundedCornerShape(TileRadius)).background(background),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(TileIconSize)) }
}

/** Icon tile tone that reads on cards in both themes (in dark, surface == surfaceContainer). */
@Composable
internal fun tileBackground(): Color =
    if (MaterialTheme.colorScheme.surfaceContainer == MaterialTheme.colorScheme.surface) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

/** A generic row: optional icon tile, title, optional subtitle, trailing slot. */
@Composable
internal fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else DisabledAlpha
    Row(
        modifier.fillMaxWidth().heightIn(min = RowMinHeight).padding(horizontal = Spacing.L, vertical = Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.M + Spacing.XXS),
    ) {
        if (icon != null) IconTile(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted.copy(alpha = alpha))
            }
        }
        trailing?.invoke(this)
    }
}

@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                uncheckedThumbColor = BestNoteTheme.colors.muted,
                uncheckedBorderColor = BestNoteTheme.colors.muted,
                uncheckedTrackColor = tileBackground(),
            ),
        )
    }
}

@Composable
internal fun RadioRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        enabled = enabled,
        modifier = Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
    ) {
        trailing?.invoke(this)
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}

/** Pill segmented control in the Atelier style (selected segment is a white pill on the sidebar tone). */
@Composable
internal fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(BestNoteShapes.Pill)
            .background(tileBackground())
            .padding(Spacing.XXS + 1.dp)
            .selectableGroup(),
    ) {
        options.forEach { option ->
            val on = option == selected
            val bg by animateColorAsState(
                if (on) MaterialTheme.colorScheme.surface else Color.Transparent,
                Motion.tween(Motion.Duration.Short),
                label = "segment",
            )
            val fg by animateColorAsState(
                if (on) MaterialTheme.colorScheme.onSurface else BestNoteTheme.colors.muted,
                Motion.tween(Motion.Duration.Short),
                label = "segmentText",
            )
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .clip(BestNoteShapes.Pill)
                    .background(bg)
                    .selectable(on, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = Spacing.M + Spacing.XXS, vertical = Spacing.S),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** A row whose control sits under the text (segmented controls, sliders, previews). */
@Composable
internal fun StackedRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = Spacing.M)) {
        SettingsRow(title = title, subtitle = subtitle, icon = icon)
        Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.L)) { content() }
    }
}

@Composable
internal fun ComingSoonChip() {
    Text(
        stringResource(R.string.settings_coming_soon),
        style = MaterialTheme.typography.labelMedium,
        color = BestNoteTheme.colors.onWarning,
        modifier = Modifier
            .clip(BestNoteShapes.Pill)
            .background(BestNoteTheme.colors.warning)
            .padding(horizontal = Spacing.S + Spacing.XXS, vertical = Spacing.XXS),
    )
}

/** Text-ish action button used inside rows ("Clear", "Open"). */
@Composable
internal fun RowAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) MaterialTheme.colorScheme.primary else BestNoteTheme.colors.muted,
        modifier = Modifier
            .clip(BestNoteShapes.Pill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.M, vertical = Spacing.S),
    )
}

/** Swatch spacing helper for chip rows. */
internal val ChipGap = Spacing.S
internal val TileSize = 32.dp
private val TileRadius = 10.dp
private val TileIconSize = 19.dp
private val RowMinHeight = 56.dp
private const val DisabledAlpha = 0.45f

@Composable
internal fun HSpace() = Spacer(Modifier.width(Spacing.S))
