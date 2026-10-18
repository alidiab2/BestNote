package app.bestnote.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NoEncryption
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bestnote.core.datastore.ToolbarDock
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.softShadow

/** Actions the top pills and tools capsule raise. Unbuilt ones show "coming soon". */
internal data class ChromeActions(
    val onBack: () -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onComingSoon: (String) -> Unit,
    val onDock: (ToolbarDock) -> Unit,
    val onLeftHanded: (Boolean) -> Unit,
    val onClearPage: () -> Unit,
    val onSmartBoard: () -> Unit,
    /** The Paper button and its sheet: template, color and size (PAGES-10). */
    val pagesMenu: @Composable (open: Boolean, setOpen: (Boolean) -> Unit) -> Unit,
    /** The Pages button: opens the page manager (PAGES-10). */
    val onPages: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    /** Opens the Export dialog (PDF / PNG / .bnote, then the share sheet). */
    val onExport: () -> Unit = {},
    /** LIB-11: lock items of the More menu; null hides them (previews). */
    val lock: EditorLock? = null,
)

/**
 * The small floating back pill: back arrow, notebook title and "Page X of Y" (Atelier "float-back").
 * Mirrors in RTL with the rest of the chrome.
 */
@Composable
internal fun TitleCluster(title: String, page: Int, pageCount: Int, onBack: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(
        modifier.chromeSurface().padding(start = 2.dp, end = if (compact) Spacing.S else Spacing.L),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.XS),
    ) {
        ChromeIconButton(
            icon = AtelierIcons.Back,
            label = stringResource(R.string.editor_back),
            onClick = onBack,
            size = 44.dp,
        )
        Column(Modifier.widthIn(max = if (compact) 120.dp else 240.dp)) {
            Text(
                title.ifBlank { stringResource(R.string.editor_untitled) },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.editor_page_of, page, pageCount),
                style = BestNoteTheme.type.caption,
                color = BestNoteTheme.colors.muted,
                maxLines = 1,
            )
        }
    }
}

/** Undo/Redo pill, dark Smart Board pill, and the Pages · Paper · Layers · Export · More pill. */
@Composable
internal fun ActionPills(
    canUndo: Boolean,
    canRedo: Boolean,
    dock: ToolbarDock,
    leftHanded: Boolean,
    actions: ChromeActions,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
        Row(Modifier.chromeSurface().padding(horizontal = 2.dp)) {
            ChromeIconButton(AtelierIcons.Undo, stringResource(R.string.editor_undo), actions.onUndo, enabled = canUndo, size = 44.dp)
            ChromeIconButton(AtelierIcons.Redo, stringResource(R.string.editor_redo), actions.onRedo, enabled = canRedo, size = 44.dp)
        }
        if (!compact) SmartBoardPill { actions.onSmartBoard() }
        val export = stringResource(R.string.editor_export)
        var paperOpen by remember { mutableStateOf(false) }
        Row(Modifier.chromeSurface().padding(horizontal = 2.dp)) {
            ChromeIconButton(AtelierIcons.Pages, stringResource(R.string.editor_pages), actions.onPages, size = 44.dp)
            actions.pagesMenu(paperOpen) { paperOpen = it }
            // Compact windows keep only Pages and More here: Smart Board and Export move into More.
            // Layers is not built yet (QA-20): no dead "coming soon" button in the chrome.
            if (!compact) {
                ChromeIconButton(AtelierIcons.Export, export, { actions.onExport() }, size = 44.dp)
            }
            MoreMenu(dock, leftHanded, actions, compact, onPaper = { paperOpen = true })
        }
    }
}

@Composable
private fun SmartBoardPill(onClick: () -> Unit) {
    val label = stringResource(R.string.editor_smart_board)
    val colors = BestNoteTheme.colors
    Row(
        Modifier
            .softShadow(Elevation.Low, BestNoteShapes.Pill)
            .clip(BestNoteShapes.Pill)
            .background(colors.strongButton)
            .clickable(role = Role.Button, onClickLabel = label) { onClick() }
            .height(44.dp)
            .padding(start = 13.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        Icon(AtelierIcons.Sparkle, contentDescription = null, tint = colors.aiAccent, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = colors.onStrongButton)
    }
}

@Composable
private fun MoreMenu(dock: ToolbarDock, leftHanded: Boolean, actions: ChromeActions, compact: Boolean, onPaper: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ChromeIconButton(AtelierIcons.More, stringResource(R.string.editor_more), { open = true }, size = 44.dp)
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = BestNoteShapes.Card,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            // PAGES-11: the paper sheet is also one labelled tap away, first in the menu.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_paper_template)) },
                leadingIcon = { Icon(AtelierIcons.Paper, null, modifier = Modifier.size(20.dp)) },
                onClick = { open = false; onPaper() },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (compact) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_smart_board)) },
                    leadingIcon = { Icon(AtelierIcons.Sparkle, null, tint = BestNoteTheme.colors.aiAccent, modifier = Modifier.size(20.dp)) },
                    onClick = { actions.onSmartBoard(); open = false },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            // The tray sits at the bottom of a compact window: the dock choice only applies to wider ones.
            if (!compact) Text(
                stringResource(R.string.toolbar_position),
                style = BestNoteTheme.type.overline,
                color = BestNoteTheme.colors.muted,
                modifier = Modifier.padding(horizontal = Spacing.L, vertical = Spacing.S),
            )
            if (!compact) listOf(
                ToolbarDock.Top to R.string.toolbar_top,
                ToolbarDock.Left to R.string.toolbar_left,
                ToolbarDock.Right to R.string.toolbar_right,
            ).forEach { (option, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    trailingIcon = { if (option == dock) Icon(AtelierIcons.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                    onClick = { actions.onDock(option); open = false },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.toolbar_left_handed)) },
                trailingIcon = { Switch(checked = leftHanded, onCheckedChange = null) },
                onClick = { actions.onLeftHanded(!leftHanded) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_export)) },
                leadingIcon = { Icon(AtelierIcons.Export, null, modifier = Modifier.size(20.dp)) },
                onClick = { actions.onExport(); open = false },
            )
            actions.lock?.let { lock ->
                if (lock.sealed) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.editor_lock_now)) },
                        leadingIcon = { Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(20.dp)) },
                        onClick = { open = false; lock.onLockNow() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.editor_remove_lock)) },
                        leadingIcon = { Icon(Icons.Outlined.NoEncryption, null, modifier = Modifier.size(20.dp)) },
                        onClick = { open = false; lock.onRemoveLock() },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.editor_lock)) },
                        leadingIcon = { Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(20.dp)) },
                        onClick = { open = false; lock.onLock() },
                    )
                }
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_settings)) },
                leadingIcon = { Icon(Icons.Default.Settings, null, modifier = Modifier.size(20.dp)) },
                onClick = { actions.onOpenSettings(); open = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_clear_page), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(AtelierIcons.Trash, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp)) },
                onClick = { actions.onClearPage(); open = false },
            )
        }
    }
}

private data class ToolEntry(val icon: ImageVector, val label: Int)

private val Tools = listOf(
    ToolEntry(AtelierIcons.Lasso, R.string.tool_lasso),
    ToolEntry(AtelierIcons.Shapes, R.string.tool_shapes),
    ToolEntry(AtelierIcons.Text, R.string.tool_text),
    ToolEntry(AtelierIcons.Image, R.string.tool_image),
    ToolEntry(AtelierIcons.Mic, R.string.tool_audio),
    // The ruler is not built yet (QA-20): hidden rather than a "coming soon" button.
)

/** The lighter second capsule: lasso, shapes, text, image, audio, ruler (audio and ruler are placeholders until built). */
@Composable
internal fun ToolsCapsule(
    vertical: Boolean,
    onComingSoon: (String) -> Unit,
    modifier: Modifier = Modifier,
    lassoActive: Boolean = false,
    onLasso: () -> Unit = {},
    shapesActive: Boolean = false,
    onShapes: () -> Unit = {},
    textActive: Boolean = false,
    onText: () -> Unit = {},
    onImage: () -> Unit = {},
    audioActive: Boolean = false,
    onAudio: () -> Unit = {},
    onAudioLongPress: () -> Unit = {},
) {
    val content: @Composable () -> Unit = {
        Tools.forEach { tool ->
            val label = stringResource(tool.label)
            val isLasso = tool.label == R.string.tool_lasso
            val isShapes = tool.label == R.string.tool_shapes
            val isText = tool.label == R.string.tool_text
            val toggle = isLasso || isShapes || isText
            val isAudio = tool.label == R.string.tool_audio
            val selected = (isLasso && lassoActive) || (isShapes && shapesActive) || (isText && textActive) || (isAudio && audioActive)
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(13.dp)
            ChromeIconButton(
                tool.icon,
                label,
                {
                    when {
                        isLasso -> onLasso()
                        isShapes -> onShapes()
                        isText -> onText()
                        tool.label == R.string.tool_image -> onImage()
                        isAudio -> onAudio()
                        else -> onComingSoon(label)
                    }
                },
                size = 44.dp,
                onLongClick = if (isAudio) onAudioLongPress else null,
                longClickLabel = if (isAudio) stringResource(R.string.audio_list) else null,
                shape = shape,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer, shape) else Modifier)
                    .semantics {
                        contentDescription = label
                        if (toggle || isAudio) this.selected = selected
                    },
            )
        }
    }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    val surface = Modifier
        .softShadow(Elevation.Low, shape)
        .background(MaterialTheme.colorScheme.surface.copy(alpha = ToolsCapsuleAlpha), shape)
    if (vertical) {
        Column(modifier.then(surface).padding(vertical = 6.dp, horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    } else {
        Row(modifier.then(surface).padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

private const val ToolsCapsuleAlpha = 0.82f
