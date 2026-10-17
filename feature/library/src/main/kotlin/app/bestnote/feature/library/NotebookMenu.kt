package app.bestnote.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.NoEncryption
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.CoverPalette
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.hairline
import app.bestnote.core.designsystem.theme.swatchOutline

/** Popover look shared by every library menu: white card, 18 dp corners, soft shadow, hairline. */
@Composable
internal fun AtelierMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier.widthIn(min = 240.dp),
        offset = DpOffset(0.dp, Spacing.XS),
        shape = BestNoteShapes.Card,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(Elevation.Hairline, MaterialTheme.colorScheme.outline),
    ) { content() }
}

@Composable
internal fun AtelierMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    trailing: String? = null,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.labelLarge, color = tint) },
        onClick = onClick,
        leadingIcon = { Icon(icon, null, tint = if (destructive) tint else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
        trailingIcon = trailing?.let {
            {
                Text(
                    it,
                    style = BestNoteTheme.type.caption,
                    color = BestNoteTheme.colors.onWarning,
                    modifier = Modifier.clip(BestNoteShapes.Pill).background(BestNoteTheme.colors.warning).padding(horizontal = Spacing.S, vertical = Spacing.XXS),
                )
            }
        },
        modifier = Modifier.height(48.dp),
    )
}

@Composable
private fun MenuDivider() = HorizontalDivider(Modifier.padding(vertical = Spacing.XS), color = MaterialTheme.colorScheme.outlineVariant)

/** The notebook action menu (long-press or "⋯"). */
@Composable
internal fun NotebookMenu(item: NotebookItem, expanded: Boolean, onDismiss: () -> Unit, onAction: (NotebookMenuAction) -> Unit) {
    AtelierMenu(expanded, onDismiss) {
        Row(
            Modifier.padding(start = Spacing.L, end = Spacing.L, top = Spacing.S, bottom = Spacing.M).widthIn(max = 280.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.M),
        ) {
            NotebookCover(item.title, item.coverIndex, Modifier.size(30.dp, 41.dp), showLabel = false, shadow = false, scale = 0.35f, style = item.coverStyle, locked = item.sealed)
            Column {
                Text(item.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    item.folderPath.joinToString(" › ") { it.name }.ifEmpty { stringResource(R.string.library_nav_library) },
                    style = BestNoteTheme.type.caption,
                    color = BestNoteTheme.colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        MenuDivider()
        AtelierMenuItem(Icons.Outlined.AutoStories, stringResource(R.string.library_menu_open), { onAction(NotebookMenuAction.Open) })
        AtelierMenuItem(Icons.Outlined.Edit, stringResource(R.string.library_menu_rename), { onAction(NotebookMenuAction.Rename) })
        AtelierMenuItem(Icons.Outlined.Palette, stringResource(R.string.library_menu_cover), { onAction(NotebookMenuAction.ChangeCover) })
        AtelierMenuItem(Icons.Outlined.ContentCopy, stringResource(R.string.library_menu_duplicate), { onAction(NotebookMenuAction.Duplicate) })
        AtelierMenuItem(Icons.Outlined.Folder, stringResource(R.string.library_menu_move), { onAction(NotebookMenuAction.Move) })
        AtelierMenuItem(
            if (item.favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline,
            stringResource(if (item.favorite) R.string.library_menu_unfavorite else R.string.library_menu_favorite),
            { onAction(NotebookMenuAction.Favorite) },
        )
        AtelierMenuItem(Icons.Outlined.IosShare, stringResource(R.string.library_menu_export), { onAction(NotebookMenuAction.Export) })
        AtelierMenuItem(Icons.Outlined.Share, stringResource(R.string.library_menu_share), { onAction(NotebookMenuAction.Share) })
        AtelierMenuItem(Icons.Outlined.CheckCircleOutline, stringResource(R.string.library_menu_select), { onAction(NotebookMenuAction.Select) })
        // LIB-11: lock, unlock for this session, lock again now, or remove the lock for good.
        MenuDivider()
        when {
            !item.sealed -> AtelierMenuItem(Icons.Outlined.Lock, stringResource(R.string.library_menu_lock), { onAction(NotebookMenuAction.Lock) })
            item.locked -> AtelierMenuItem(Icons.Outlined.LockOpen, stringResource(R.string.library_menu_unlock), { onAction(NotebookMenuAction.Unlock) })
            else -> AtelierMenuItem(Icons.Outlined.Lock, stringResource(R.string.library_menu_lock_now), { onAction(NotebookMenuAction.LockNow) })
        }
        if (item.sealed) AtelierMenuItem(Icons.Outlined.NoEncryption, stringResource(R.string.library_menu_remove_lock), { onAction(NotebookMenuAction.RemoveLock) })
        MenuDivider()
        AtelierMenuItem(Icons.Outlined.DeleteOutline, stringResource(R.string.library_menu_trash), { onAction(NotebookMenuAction.Trash) }, destructive = true)
    }
}

/** What a folder's "⋯" menu can do. */
internal enum class FolderMenuAction { Rename, Recolor, NewSubfolder, Delete, Lock, RemoveLock }

@Composable
internal fun FolderMenu(expanded: Boolean, onDismiss: () -> Unit, locked: Boolean = false, onAction: (FolderMenuAction) -> Unit) {
    AtelierMenu(expanded, onDismiss) {
        AtelierMenuItem(Icons.Outlined.Edit, stringResource(R.string.library_folder_rename), { onAction(FolderMenuAction.Rename) })
        AtelierMenuItem(Icons.Outlined.Palette, stringResource(R.string.library_folder_recolor), { onAction(FolderMenuAction.Recolor) })
        AtelierMenuItem(Icons.Outlined.CreateNewFolder, stringResource(R.string.library_folder_new_sub), { onAction(FolderMenuAction.NewSubfolder) })
        if (locked) {
            AtelierMenuItem(Icons.Outlined.NoEncryption, stringResource(R.string.library_folder_remove_lock), { onAction(FolderMenuAction.RemoveLock) })
        } else {
            AtelierMenuItem(Icons.Outlined.Lock, stringResource(R.string.library_folder_lock), { onAction(FolderMenuAction.Lock) })
        }
        MenuDivider()
        AtelierMenuItem(Icons.Outlined.DeleteOutline, stringResource(R.string.library_folder_delete), { onAction(FolderMenuAction.Delete) }, destructive = true)
    }
}

// region Dialogs

/** Atelier dialog: white sheet, 28 dp corners, Fraunces title, strong pill confirm. */
@Composable
internal fun AtelierDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String? = null,
    onConfirm: () -> Unit = {},
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = BestNoteShapes.Sheet,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface) },
        text = content,
        confirmButton = {
            if (confirmLabel != null) {
                PillButton(
                    confirmLabel,
                    onConfirm,
                    enabled = confirmEnabled,
                    background = if (destructive) MaterialTheme.colorScheme.error else BestNoteTheme.colors.strongButton,
                    foreground = if (destructive) MaterialTheme.colorScheme.onError else BestNoteTheme.colors.onStrongButton,
                )
            }
        },
        dismissButton = { PillButton(stringResource(R.string.library_cancel), onDismiss, background = Color.Transparent, foreground = MaterialTheme.colorScheme.onSurfaceVariant, outlined = true) },
    )
}

@Composable
internal fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    background: Color = BestNoteTheme.colors.strongButton,
    foreground: Color = BestNoteTheme.colors.onStrongButton,
    outlined: Boolean = false,
    icon: ImageVector? = null,
) {
    Row(
        modifier
            .height(44.dp)
            .clip(BestNoteShapes.Pill)
            .background(if (enabled) background else background.copy(alpha = 0.4f))
            .then(if (outlined) Modifier.hairline(BestNoteShapes.Pill) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.L + Spacing.XS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        if (icon != null) Icon(icon, null, tint = foreground, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = foreground)
    }
}

@Composable
private fun AtelierTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text(placeholder, color = BestNoteTheme.colors.muted) },
        textStyle = MaterialTheme.typography.titleMedium,
        shape = BestNoteShapes.Control,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.background,
            unfocusedContainerColor = MaterialTheme.colorScheme.background,
        ),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth().focusRequester(focus),
    )
}

/** Rename, with the current title selected so typing replaces it. */
@Composable
internal fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val ok = text.text.isNotBlank()
    AtelierDialog(
        title = stringResource(R.string.library_rename_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.library_rename_confirm),
        onConfirm = { if (ok) onConfirm(text.text) },
        confirmEnabled = ok,
    ) {
        AtelierTextField(text, { text = it }, stringResource(R.string.library_new_title), onDone = { if (ok) onConfirm(text.text) })
    }
}

/** Cover picker: every [CoverChoices] cover; tap applies. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CoverDialog(title: String, current: CoverChoice, onDismiss: () -> Unit, onPick: (CoverChoice) -> Unit) {
    AtelierDialog(title = stringResource(R.string.library_cover_title), onDismiss = onDismiss) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.M), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
            CoverChoices.all.forEach { choice ->
                CoverSwatch(title, choice, choice == current, Modifier.size(64.dp, 87.dp)) { onPick(choice) }
            }
        }
    }
}

/** One selectable cover (used by the cover dialog and the new-notebook sheet). */
@Composable
internal fun CoverSwatch(title: String, choice: CoverChoice, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val desc = stringResource(R.string.library_cover_option, choice.colorIndex + 1, stringResource(coverStyleLabel(choice.style)))
    Box(
        modifier
            .semantics {
                this.selected = selected
                contentDescription = desc
            }
            .clip(BestNoteShapes.NotebookCover)
            .clickable(role = Role.RadioButton, onClick = onClick),
    ) {
        NotebookCover(title, choice.colorIndex, Modifier.matchParentSize(), shadow = false, scale = 0.45f, style = choice.style, showLabel = false)
        if (selected) {
            Box(Modifier.matchParentSize().border(3.dp, MaterialTheme.colorScheme.primary, BestNoteShapes.NotebookCover))
            Box(
                Modifier.align(Alignment.Center).size(26.dp).clip(BestNoteShapes.Pill).background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
        }
    }
}

internal fun coverStyleLabel(style: CoverStyle): Int = when (style) {
    CoverStyle.Plain -> R.string.library_cover_plain
    CoverStyle.Stripes -> R.string.library_cover_stripes
    CoverStyle.Dots -> R.string.library_cover_dots
    CoverStyle.Grid -> R.string.library_cover_grid
}

/** Move to folder: Library (no folder), every folder (indented), or a new folder. */
@Composable
internal fun MoveDialog(
    count: Int,
    folders: List<FolderItem>,
    currentFolderId: String?,
    onDismiss: () -> Unit,
    onMove: (String?) -> Unit,
    onNewFolder: () -> Unit,
) {
    AtelierDialog(title = pluralStringResource(R.plurals.library_move_title, count, count), onDismiss = onDismiss) {
        LazyColumn(Modifier.heightIn(max = 380.dp).widthIn(min = 320.dp)) {
            item {
                MoveRow(null, stringResource(R.string.library_move_none), 0, currentFolderId == null) { onMove(null) }
            }
            items(folders, key = { it.folder.id }) { f ->
                MoveRow(f.folder.colorIndex, f.folder.name, f.depth, f.folder.id == currentFolderId) { onMove(f.folder.id) }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(BestNoteShapes.Control).clickable(role = Role.Button, onClick = onNewFolder).padding(horizontal = Spacing.M),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.M),
                ) {
                    Icon(Icons.Outlined.CreateNewFolder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Text(stringResource(R.string.library_folder_new), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun MoveRow(colorIndex: Int?, label: String, depth: Int, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(BestNoteShapes.Control)
            .background(if (current) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = Spacing.M + Spacing.L * depth, end = Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        if (colorIndex == null) {
            Icon(Icons.AutoMirrored.Outlined.LibraryBooks, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        } else {
            FolderGlyph(colorIndex, Modifier.size(22.dp, 18.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (current) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

/** A small folder shape in the folder's color. */
@Composable
internal fun FolderGlyph(colorIndex: Int, modifier: Modifier = Modifier) {
    val color = coverMarkColor(colorIndex)
    Box(modifier) {
        Box(Modifier.align(Alignment.TopStart).fillMaxWidth(0.45f).height(5.dp).clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)).background(color))
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.82f).clip(RoundedCornerShape(3.dp)).background(color))
    }
}

/** New folder / rename / recolor: a name field and the six Atelier colors. */
@Composable
internal fun FolderDialog(
    title: String,
    initialName: String,
    initialColor: Int,
    confirmLabel: String,
    parentName: String?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, colorIndex: Int) -> Unit,
) {
    var name by remember { mutableStateOf(TextFieldValue(initialName, selection = TextRange(0, initialName.length))) }
    var color by rememberSaveable { mutableIntStateOf(initialColor) }
    val ok = name.text.isNotBlank()
    AtelierDialog(title = title, onDismiss = onDismiss, confirmLabel = confirmLabel, onConfirm = { if (ok) onConfirm(name.text, color) }, confirmEnabled = ok) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.L)) {
            if (parentName != null) {
                Text(stringResource(R.string.library_folder_inside, parentName), style = MaterialTheme.typography.bodyMedium, color = BestNoteTheme.colors.muted)
            }
            AtelierTextField(name, { name = it }, stringResource(R.string.library_folder_name_hint), onDone = { if (ok) onConfirm(name.text, color) })
            Text(stringResource(R.string.library_folder_color).uppercase(), style = BestNoteTheme.type.overline, color = BestNoteTheme.colors.muted)
            ColorRow(color) { color = it }
        }
    }
}

@Composable
internal fun ColorRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
        CoverPalette.All.indices.forEach { i ->
            val label = stringResource(R.string.library_color_n, i + 1)
            Box(
                Modifier
                    .size(44.dp)
                    .clip(BestNoteShapes.Pill)
                    .clickable(role = Role.RadioButton, onClick = { onSelect(i) })
                    .semantics {
                        this.selected = i == selected
                        contentDescription = label
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .then(if (i == selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, BestNoteShapes.Pill).padding(4.dp) else Modifier)
                        .clip(BestNoteShapes.Pill)
                        .background(coverColor(i))
                        .swatchOutline(coverColor(i), BestNoteShapes.Pill),
                    contentAlignment = Alignment.Center,
                ) {
                    if (i == selected) Icon(Icons.Outlined.Check, null, tint = BestNoteTheme.colors.paper.page, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
internal fun DeleteFolderDialog(name: String, count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AtelierDialog(
        title = stringResource(R.string.library_folder_delete_title, name),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.library_folder_delete),
        onConfirm = onConfirm,
        destructive = true,
    ) {
        Text(
            pluralStringResource(R.plurals.library_folder_delete_body, count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// endregion
