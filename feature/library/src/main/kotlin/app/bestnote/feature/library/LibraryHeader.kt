package app.bestnote.feature.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import app.bestnote.core.ui.keys.reportsTextEditing
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.hairline
import app.bestnote.core.designsystem.theme.softShadow

private val ControlHeight = 44.dp
private val NarrowContinueRow = 640.dp

/** Greeting (or destination name), breadcrumb, count, search, Select, layout switch and "New". */
@Composable
internal fun LibraryHeader(
    state: LibraryUiState,
    compact: Boolean,
    callbacks: LibraryCallbacks,
    modifier: Modifier = Modifier,
    searchFocus: FocusRequester = remember { FocusRequester() },
    phone: Boolean = false,
) {
    val destination = state.controls.destination
    val title = when (destination) {
        LibraryDestination.Library -> stringResource(
            when (state.greeting) {
                Greeting.Morning -> R.string.library_greeting_morning
                Greeting.Afternoon -> R.string.library_greeting_afternoon
                Greeting.Evening -> R.string.library_greeting_evening
            },
        )
        LibraryDestination.Recent -> stringResource(R.string.library_nav_recent)
        LibraryDestination.Favorites -> stringResource(R.string.library_nav_favorites)
        LibraryDestination.Quicknotes -> stringResource(R.string.library_nav_quicknotes)
        LibraryDestination.SmartBoard -> stringResource(R.string.library_nav_smart_board)
        LibraryDestination.Trash -> stringResource(R.string.library_nav_trash)
        is LibraryDestination.InFolder -> state.breadcrumb.lastOrNull()?.name.orEmpty()
    }
    val count = when (destination) {
        LibraryDestination.Library -> state.totalCount
        LibraryDestination.Trash -> state.trash.size
        else -> state.items.size
    }
    val canSelect = destination != LibraryDestination.Trash && destination != LibraryDestination.SmartBoard && state.items.isNotEmpty()
    Column(modifier.fillMaxWidth()) {
        if (destination is LibraryDestination.InFolder) {
            Breadcrumb(state.breadcrumb, callbacks.select, Modifier.padding(bottom = Spacing.S))
        }
        val titleBlock: @Composable (Modifier) -> Unit = { mod ->
            Column(mod) {
                Text(
                    title,
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    pluralStringResource(R.plurals.library_notebook_count, count, count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = BestNoteTheme.colors.muted,
                    modifier = Modifier.padding(top = Spacing.XS),
                )
            }
        }
        val controls: @Composable () -> Unit = {
            if (canSelect) {
                OutlinePill(stringResource(R.string.library_select), Icons.Outlined.CheckCircleOutline, onClick = { callbacks.startSelection(null) })
                Spacer(Modifier.width(Spacing.M))
            }
            LayoutSwitch(state.controls.layout, callbacks.layout)
            Spacer(Modifier.width(Spacing.M))
            NewButton(callbacks)
        }
        if (phone) {
            // DS-05: on a phone the title gets its own line and the controls scroll sideways under it.
            titleBlock(Modifier.fillMaxWidth())
            Row(
                Modifier.fillMaxWidth().padding(top = Spacing.M).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) { controls() }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                titleBlock(Modifier.weight(1f))
                Spacer(Modifier.width(Spacing.L))
                if (!compact) {
                    SearchField(state.controls.query, callbacks.query, Modifier.width(300.dp).focusRequester(searchFocus))
                    Spacer(Modifier.width(Spacing.M))
                }
                controls()
            }
        }
        if (compact) {
            SearchField(state.controls.query, callbacks.query, Modifier.fillMaxWidth().padding(top = Spacing.L).focusRequester(searchFocus))
        }
    }
}

/** "Library › University › Lectures": every crumb but the last is a link. */
@Composable
private fun Breadcrumb(path: List<Folder>, onSelect: (LibraryDestination) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Crumb(stringResource(R.string.library_nav_library), current = false) { onSelect(LibraryDestination.Library) }
        path.forEachIndexed { i, folder ->
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                null,
                tint = BestNoteTheme.colors.muted,
                modifier = Modifier.size(18.dp),
            )
            val last = i == path.lastIndex
            Crumb(folder.name, current = last) { if (!last) onSelect(LibraryDestination.InFolder(folder.id)) }
        }
    }
}

@Composable
private fun Crumb(label: String, current: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (current) MaterialTheme.colorScheme.onSurface else BestNoteTheme.colors.muted,
        maxLines = 1,
        modifier = Modifier
            .clip(BestNoteShapes.Pill)
            .then(if (current) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
            .padding(horizontal = Spacing.S, vertical = Spacing.XS),
    )
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val hint = stringResource(R.string.library_search_hint)
    val clear = stringResource(R.string.library_clear_search)
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier.reportsTextEditing().semantics { contentDescription = hint },
        decorationBox = { inner ->
            Row(
                Modifier
                    .height(ControlHeight)
                    .clip(BestNoteShapes.Pill)
                    .background(MaterialTheme.colorScheme.surface)
                    .hairline(BestNoteShapes.Pill)
                    .padding(start = Spacing.L, end = Spacing.XS),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                Icon(Icons.Outlined.Search, null, tint = BestNoteTheme.colors.muted, modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(hint, style = MaterialTheme.typography.bodyMedium, color = BestNoteTheme.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
                if (query.isNotEmpty()) {
                    Box(
                        Modifier.size(36.dp).clip(BestNoteShapes.Pill).clickable(role = Role.Button, onClickLabel = clear) { onQuery("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Close, clear, tint = BestNoteTheme.colors.muted, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Spacer(Modifier.width(Spacing.S))
                }
            }
        },
    )
}

/** White pill with a hairline: secondary header actions ("Select", "Sort"). */
@Composable
internal fun OutlinePill(label: String, icon: ImageVector?, modifier: Modifier = Modifier, trailingIcon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        modifier
            .height(ControlHeight)
            .clip(BestNoteShapes.Pill)
            .background(MaterialTheme.colorScheme.surface)
            .hairline(BestNoteShapes.Pill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.L),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        if (trailingIcon != null) Icon(trailingIcon, null, tint = BestNoteTheme.colors.muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun LayoutSwitch(layout: LibraryLayout, onLayout: (LibraryLayout) -> Unit) {
    Row(
        Modifier
            .height(ControlHeight)
            .clip(BestNoteShapes.Pill)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(Spacing.XS),
        horizontalArrangement = Arrangement.spacedBy(Spacing.XXS),
    ) {
        Segment(Icons.Outlined.ViewWeek, stringResource(R.string.library_layout_shelf), layout == LibraryLayout.Shelf) { onLayout(LibraryLayout.Shelf) }
        Segment(Icons.Outlined.GridView, stringResource(R.string.library_layout_grid), layout == LibraryLayout.Grid) { onLayout(LibraryLayout.Grid) }
        Segment(Icons.AutoMirrored.Outlined.ViewList, stringResource(R.string.library_layout_list), layout == LibraryLayout.List) { onLayout(LibraryLayout.List) }
    }
}

@Composable
private fun Segment(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.surfaceBright else Color.Transparent,
        Motion.tween(Motion.Duration.Short),
        label = "segment",
    )
    Box(
        Modifier
            .width(42.dp)
            .fillMaxHeight()
            .then(if (selected) Modifier.softShadow(Elevation.Low, BestNoteShapes.Pill) else Modifier)
            .clip(BestNoteShapes.Pill)
            .background(bg)
            .clickable(role = Role.Tab, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.onSurface else BestNoteTheme.colors.muted,
            modifier = Modifier.size(19.dp),
        )
    }
}

/** "New" split pill: the main part opens the new-notebook sheet, the chevron offers more. */
@Composable
private fun NewButton(callbacks: LibraryCallbacks) {
    var menu by remember { mutableStateOf(false) }
    val fg = BestNoteTheme.colors.onStrongButton
    val moreLabel = stringResource(R.string.library_new_more)
    Box {
        Row(
            Modifier
                .height(ControlHeight)
                .clip(BestNoteShapes.Pill)
                .background(BestNoteTheme.colors.strongButton),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .fillMaxHeight()
                    .clickable(role = Role.Button, onClick = callbacks.newNotebook)
                    .padding(start = Spacing.L, end = Spacing.M),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                Icon(Icons.Outlined.Add, null, tint = fg, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.library_new), style = MaterialTheme.typography.labelLarge, color = fg)
            }
            Box(Modifier.width(Elevation.Hairline).height(22.dp).background(fg.copy(alpha = 0.25f)))
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(40.dp)
                    .clickable(role = Role.Button, onClickLabel = moreLabel) { menu = true }
                    .semantics { contentDescription = moreLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.KeyboardArrowDown, null, tint = fg, modifier = Modifier.size(20.dp))
            }
        }
        AtelierMenu(menu, onDismiss = { menu = false }) {
            AtelierMenuItem(Icons.Outlined.Book, stringResource(R.string.library_new_notebook), { menu = false; callbacks.newNotebook() })
            AtelierMenuItem(Icons.Outlined.EditNote, stringResource(R.string.library_quicknote_title), { menu = false; callbacks.quicknote() })
            AtelierMenuItem(Icons.Outlined.CreateNewFolder, stringResource(R.string.library_folder_new), { menu = false; callbacks.newFolder() })
            AtelierMenuItem(Icons.Outlined.PictureAsPdf, stringResource(R.string.library_import_pdf), { menu = false; callbacks.importPdf() })
        }
    }
}

private val ContinueCardHeight: Dp = 176.dp

/** "Continue writing": last notebook, Quicknote, Smart Board. */
@Composable
internal fun ContinueWritingRow(
    last: NotebookItem,
    now: Long,
    onOpen: (NotebookItem) -> Unit,
    onQuicknote: () -> Unit,
    onSmartBoard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < NarrowContinueRow) {
            // DS-05: on a phone the three cards keep a readable width and scroll sideways.
            Row(
                Modifier.fillMaxWidth().height(ContinueCardHeight).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.M),
            ) {
                LastNotebookCard(last, now, onClick = { onOpen(last) }, modifier = Modifier.width(300.dp))
                QuicknoteCard(onQuicknote, Modifier.width(190.dp))
                SmartBoardCard(onSmartBoard, Modifier.width(190.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth().height(ContinueCardHeight), horizontalArrangement = Arrangement.spacedBy(Spacing.L)) {
                LastNotebookCard(last, now, onClick = { onOpen(last) }, modifier = Modifier.weight(1.35f))
                QuicknoteCard(onQuicknote, Modifier.weight(1f))
                SmartBoardCard(onSmartBoard, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LastNotebookCard(item: NotebookItem, now: Long, onClick: () -> Unit, modifier: Modifier) {
    val shape = BestNoteShapes.Card
    Row(
        modifier
            .fillMaxHeight()
            .softShadow(Elevation.Low, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(Spacing.L),
        horizontalArrangement = Arrangement.spacedBy(Spacing.L),
    ) {
        NotebookThumbnail(item.thumbnailPath, item.updatedAtMillis, Modifier.fillMaxHeight().width(108.dp), seed = item.pageCount)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                Box(Modifier.size(10.dp).clip(BestNoteShapes.Control).background(coverColor(item.coverIndex)))
                Text(
                    (item.folder?.name ?: stringResource(R.string.library_nav_library)).uppercase(),
                    style = BestNoteTheme.type.overline,
                    color = BestNoteTheme.colors.muted,
                    maxLines = 1,
                )
            }
            Text(
                item.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.S),
            )
            Text(
                stringResource(R.string.library_meta, stringResource(R.string.library_continue_page, item.lastPageIndex + 1, item.pageCount), relativeTime(item.updatedAtMillis, now)),
                style = BestNoteTheme.type.caption,
                color = BestNoteTheme.colors.muted,
                maxLines = 1,
                modifier = Modifier.padding(top = Spacing.XS),
            )
            Spacer(Modifier.weight(1f))
            LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(BestNoteShapes.Pill),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun QuicknoteCard(onClick: () -> Unit, modifier: Modifier) {
    val fg = BestNoteTheme.colors.onWarning
    ActionCard(
        background = BestNoteTheme.colors.warning,
        foreground = fg,
        icon = Icons.Outlined.EditNote,
        iconBackground = fg.copy(alpha = 0.12f),
        title = stringResource(R.string.library_quicknote_title),
        body = stringResource(R.string.library_quicknote_body),
        action = stringResource(R.string.library_quicknote_action),
        actionBackground = fg.copy(alpha = 0.12f),
        actionForeground = fg,
        actionIcon = Icons.AutoMirrored.Outlined.ArrowForward,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun SmartBoardCard(onClick: () -> Unit, modifier: Modifier) {
    // Light theme: the dark ink card of the design. Dark theme: a deep fountain-blue card, so it still
    // stands apart from the graphite background instead of turning into a bright slab.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bg = if (dark) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.inverseSurface
    val fg = if (dark) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.inverseOnSurface
    val accent = BestNoteTheme.colors.aiAccent
    ActionCard(
        background = bg,
        foreground = fg,
        icon = Icons.Outlined.AutoAwesome,
        iconBackground = accent.copy(alpha = 0.22f),
        iconTint = accent,
        title = stringResource(R.string.library_nav_smart_board),
        body = stringResource(R.string.library_smart_board_body),
        action = stringResource(R.string.library_smart_board_action),
        actionBackground = accent,
        actionForeground = if (dark) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.inverseSurface,
        actionIcon = Icons.Outlined.DocumentScanner,
        badge = stringResource(R.string.library_nav_ai_badge),
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun ActionCard(
    background: Color,
    foreground: Color,
    icon: ImageVector,
    iconBackground: Color,
    title: String,
    body: String,
    action: String,
    actionBackground: Color,
    actionForeground: Color,
    actionIcon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
    iconTint: Color = foreground,
    badge: String? = null,
) {
    val shape = BestNoteShapes.Card
    Column(
        modifier
            .fillMaxHeight()
            .softShadow(Elevation.Low, shape)
            .clip(shape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(Spacing.L),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
            Box(Modifier.size(34.dp).clip(BestNoteShapes.Control).background(iconBackground), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                    color = actionForeground,
                    modifier = Modifier.clip(BestNoteShapes.Pill).background(actionBackground).padding(horizontal = 7.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = foreground.copy(alpha = 0.78f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.M),
        )
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clip(BestNoteShapes.Pill).background(actionBackground).padding(horizontal = Spacing.M, vertical = Spacing.S - Spacing.XXS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.XS + Spacing.XXS),
        ) {
            Icon(actionIcon, null, tint = actionForeground, modifier = Modifier.size(16.dp))
            Text(action, style = MaterialTheme.typography.labelMedium, color = actionForeground)
        }
    }
}

/** "Notebooks" (or "Results") title, filter chips, folder-color dots and the sort menu. */
@Composable
internal fun NotebooksHeader(state: LibraryUiState, callbacks: LibraryCallbacks, modifier: Modifier = Modifier) {
    val c = state.controls
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (state.searching) {
            Text(
                pluralStringResource(R.plurals.library_results, state.items.size, state.items.size, c.query.trim()),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            return@Row
        }
        Text(
            stringResource(R.string.library_notebooks),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(end = Spacing.M),
        )
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip(stringResource(R.string.library_filter_all), c.filter == NotebookFilter.All && c.folderColor == null) { callbacks.clearFilters() }
            Chip(stringResource(R.string.library_filter_week), c.filter == NotebookFilter.ThisWeek) { callbacks.filter(NotebookFilter.ThisWeek) }
            Chip(stringResource(R.string.library_filter_favorites), c.filter == NotebookFilter.Favorites) { callbacks.filter(NotebookFilter.Favorites) }
            Chip(stringResource(R.string.library_filter_pdfs), c.filter == NotebookFilter.Pdfs) { callbacks.filter(NotebookFilter.Pdfs) }
            if (state.folderColors.isNotEmpty()) {
                Box(Modifier.padding(horizontal = Spacing.XS).width(Elevation.Hairline).height(22.dp).background(MaterialTheme.colorScheme.outline))
                state.folderColors.forEach { color -> ColorChip(color, c.folderColor == color) { callbacks.folderColor(color) } }
            }
        }
        Spacer(Modifier.width(Spacing.M))
        SortMenu(c.sort, callbacks.sort)
    }
}

@Composable
private fun ColorChip(colorIndex: Int, selected: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.library_filter_color, colorIndex + 1)
    Box(
        Modifier
            .size(36.dp)
            .clip(BestNoteShapes.Pill)
            .clickable(role = Role.Checkbox, onClickLabel = label, onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (selected) 26.dp else 20.dp)
                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, BestNoteShapes.Pill).padding(3.dp) else Modifier)
                .clip(BestNoteShapes.Pill)
                .background(coverColor(colorIndex)),
        )
    }
}

@Composable
private fun SortMenu(sort: SortOrder, onSort: (SortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(sortLabel(sort))
    Box {
        OutlinePill(stringResource(R.string.library_sort_by, label), Icons.AutoMirrored.Outlined.Sort, trailingIcon = Icons.Outlined.KeyboardArrowDown) { open = true }
        AtelierMenu(open, onDismiss = { open = false }) {
            SortOrder.entries.forEach { o ->
                DropdownMenuItem(
                    text = { Text(stringResource(sortLabel(o)), style = MaterialTheme.typography.labelLarge) },
                    onClick = {
                        open = false
                        onSort(o)
                    },
                    trailingIcon = { if (o == sort) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.height(48.dp),
                )
            }
        }
    }
}

private fun sortLabel(sort: SortOrder): Int = when (sort) {
    SortOrder.Modified -> R.string.library_sort_modified
    SortOrder.Created -> R.string.library_sort_created
    SortOrder.Name -> R.string.library_sort_name
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (selected) BestNoteTheme.colors.strongButton else MaterialTheme.colorScheme.surface,
        Motion.tween(Motion.Duration.Short),
        label = "chip",
    )
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) BestNoteTheme.colors.onStrongButton else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(BestNoteShapes.Pill)
            .background(bg)
            .then(if (selected) Modifier else Modifier.hairline(BestNoteShapes.Pill))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = Spacing.M + Spacing.XS, vertical = Spacing.S - Spacing.XXS),
    )
}

/** Centered message for empty destinations. */
@Composable
internal fun LibraryEmpty(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(vertical = Spacing.XXL * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = BestNoteTheme.colors.muted)
    }
}
