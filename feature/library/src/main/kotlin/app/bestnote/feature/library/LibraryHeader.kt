package app.bestnote.feature.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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

/** Greeting (or destination name), count, search, layout switch and "New". */
@Composable
internal fun LibraryHeader(
    state: LibraryUiState,
    compact: Boolean,
    onQuery: (String) -> Unit,
    onLayout: (LibraryLayout) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = when (val d = state.controls.destination) {
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
        is LibraryDestination.InFolder -> state.folders.firstOrNull { it.folder.id == d.folderId }?.folder?.name.orEmpty()
    }
    val count = when (state.controls.destination) {
        LibraryDestination.Library -> state.totalCount
        LibraryDestination.Trash -> state.trash.size
        else -> state.items.size
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
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
        Spacer(Modifier.width(Spacing.L))
        SearchField(state.controls.query, onQuery, Modifier.width(if (compact) 220.dp else 300.dp))
        Spacer(Modifier.width(Spacing.M))
        LayoutSwitch(state.controls.layout, onLayout)
        Spacer(Modifier.width(Spacing.M))
        NewButton(onNew)
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val hint = stringResource(R.string.library_search_hint)
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier.semantics { contentDescription = hint },
        decorationBox = { inner ->
            Row(
                Modifier
                    .height(ControlHeight)
                    .clip(BestNoteShapes.Pill)
                    .background(MaterialTheme.colorScheme.surface)
                    .hairline(BestNoteShapes.Pill)
                    .padding(horizontal = Spacing.L),
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
            }
        },
    )
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

@Composable
private fun NewButton(onClick: () -> Unit) {
    Row(
        Modifier
            .height(ControlHeight)
            .clip(BestNoteShapes.Pill)
            .background(BestNoteTheme.colors.strongButton)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = Spacing.L, end = Spacing.L + Spacing.XS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        Icon(Icons.Outlined.Add, null, tint = BestNoteTheme.colors.onStrongButton, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.library_new), style = MaterialTheme.typography.labelLarge, color = BestNoteTheme.colors.onStrongButton)
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
    Row(modifier.fillMaxWidth().height(ContinueCardHeight), horizontalArrangement = Arrangement.spacedBy(Spacing.L)) {
        LastNotebookCard(last, now, onClick = { onOpen(last) }, modifier = Modifier.weight(1.35f))
        QuicknoteCard(onQuicknote, Modifier.weight(1f))
        SmartBoardCard(onSmartBoard, Modifier.weight(1f))
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

/** "Notebooks" title with the filter chips. */
@Composable
internal fun NotebooksHeader(filter: NotebookFilter, onFilter: (NotebookFilter) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
        Text(
            stringResource(R.string.library_notebooks),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(end = Spacing.M),
        )
        Chip(stringResource(R.string.library_filter_all), filter == NotebookFilter.All) { onFilter(NotebookFilter.All) }
        Chip(stringResource(R.string.library_filter_week), filter == NotebookFilter.ThisWeek) { onFilter(NotebookFilter.ThisWeek) }
        Chip(stringResource(R.string.library_filter_favorites), filter == NotebookFilter.Favorites) { onFilter(NotebookFilter.Favorites) }
    }
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
