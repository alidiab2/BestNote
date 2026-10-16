package app.bestnote.core.ui.keys

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.reducedMotion
import app.bestnote.core.ui.R

/** The shortcut cheat sheet: shown while Meta is held, or after Ctrl+/ (dismiss with Esc or a tap). */
@Composable
fun ShortcutOverlay(host: ShortcutHost, modifier: Modifier = Modifier) {
    val reduce = reducedMotion()
    AnimatedVisibility(
        visible = host.helpVisible,
        enter = Motion.Enter.popover(reduce),
        exit = Motion.Exit.popover(reduce),
        modifier = modifier,
    ) {
        ShortcutSheet(onDismiss = { host.pinned = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShortcutSheet(onDismiss: () -> Unit) {
    val title = stringResource(R.string.shortcuts_title)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(Spacing.L)
                .widthIn(max = 760.dp)
                .heightIn(max = 640.dp)
                .clip(BestNoteShapes.Card)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .verticalScroll(rememberScrollState())
                .padding(Spacing.XL)
                .semantics { paneTitle = title },
            verticalArrangement = Arrangement.spacedBy(Spacing.L),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.XL),
                verticalArrangement = Arrangement.spacedBy(Spacing.L),
            ) {
                ShortcutGroup.entries.forEach { group ->
                    val actions = ShortcutAction.entries.filter { it.group == group }
                    if (actions.isNotEmpty()) {
                        Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                            Text(
                                stringResource(group.labelRes()),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            actions.forEach { ShortcutRow(it) }
                        }
                    }
                }
            }
            Text(stringResource(R.string.shortcuts_hint), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
        }
    }
}

@Composable
private fun ShortcutRow(action: ShortcutAction) {
    // Show the first two combos (Redo has Ctrl+Shift+Z and Ctrl+Y); the rest are aliases.
    val combos = ShortcutTable.combosOf(action, includeAliases = false).take(2)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(action.labelRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Spacing.M))
        combos.forEachIndexed { i, combo ->
            if (i > 0) Text("/", style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted, modifier = Modifier.padding(horizontal = Spacing.XS))
            KeyCap(combo.label())
        }
    }
}

@Composable
private fun KeyCap(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(BestNoteShapes.Control)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = Spacing.S, vertical = Spacing.XXS),
    )
}
