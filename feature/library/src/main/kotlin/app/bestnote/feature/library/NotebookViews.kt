package app.bestnote.feature.library

import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.hairline
import app.bestnote.core.designsystem.theme.softShadow
import app.bestnote.core.model.NotebookId

/** Horizontal gap between covers on a shelf. */
internal val ShelfGap: Dp = 28.dp
private val ShelfStripHeight = 12.dp
internal val GridMinCell: Dp = 168.dp

/** Everything a notebook's "⋯" menu can do. */
internal enum class NotebookMenuAction { Open, Rename, ChangeCover, Duplicate, Move, Favorite, Export, Select, Trash }

/** Callbacks and selection state shared by every notebook view. */
internal class NotebookActions(
    val open: (NotebookItem) -> Unit = {},
    val menu: (NotebookItem, NotebookMenuAction) -> Unit = { _, _ -> },
    val toggleSelect: (NotebookItem) -> Unit = {},
    val drop: (DragPayload, LibraryDestination) -> Unit = { _, _ -> },
    val selecting: Boolean = false,
    val selected: Set<NotebookId> = emptySet(),
)

/** Localised "25 min. ago". */
@Composable
internal fun relativeTime(millis: Long, now: Long): String =
    if (millis <= now && now - millis < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.library_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }

@Composable
private fun metaLine(item: NotebookItem, now: Long): String = stringResource(
    R.string.library_meta,
    pluralStringResource(R.plurals.library_page_count, item.pageCount, item.pageCount),
    relativeTime(item.updatedAtMillis, now),
)

/**
 * Tap opens (or selects while selecting), long-press or the "⋯" button opens the action menu, and
 * long-press + move drags the notebook to the sidebar. The menu is a popover anchored to [content].
 */
@Composable
private fun NotebookMenuHost(
    item: NotebookItem,
    actions: NotebookActions,
    modifier: Modifier = Modifier,
    content: @Composable (openMenu: () -> Unit) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(
        modifier.notebookGestures(
            item = item,
            selection = actions.selected,
            selecting = actions.selecting,
            onTap = { if (actions.selecting) actions.toggleSelect(item) else actions.open(item) },
            onLongPress = { if (actions.selecting) actions.toggleSelect(item) else menu = true },
            onDragLift = { menu = false },
            onDrop = actions.drop,
            longPressLabel = stringResource(R.string.library_more_actions),
        ),
    ) {
        content { menu = true }
        NotebookMenu(
            item = item,
            expanded = menu,
            onDismiss = { menu = false },
            onAction = { action ->
                menu = false
                actions.menu(item, action)
            },
        )
    }
}

/** The always-visible "⋯" on covers and cards: a white pill, 40 dp touch target. */
@Composable
internal fun MoreButton(onClick: () -> Unit, modifier: Modifier = Modifier, onCover: Boolean = false) {
    val label = stringResource(R.string.library_more_actions)
    Box(
        modifier
            .size(40.dp)
            .clip(BestNoteShapes.Pill)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (onCover) 30.dp else 34.dp)
                .then(if (onCover) Modifier.softShadow(Elevation.Low, BestNoteShapes.Pill) else Modifier)
                .clip(BestNoteShapes.Pill)
                .background(if (onCover) MaterialTheme.colorScheme.surface.copy(alpha = 0.94f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.MoreHoriz,
                contentDescription = null,
                tint = if (onCover) MaterialTheme.colorScheme.onSurface else BestNoteTheme.colors.muted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Round check shown on every notebook while selecting. */
@Composable
internal fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        Motion.tween(Motion.Duration.Short),
        label = "selectionMark",
    )
    Box(
        modifier
            .size(26.dp)
            .softShadow(Elevation.Low, BestNoteShapes.Pill)
            .clip(BestNoteShapes.Pill)
            .background(bg)
            .then(if (selected) Modifier else Modifier.hairline(BestNoteShapes.Pill)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
    }
}

/** A cover with its favorite ribbon, "⋯" button and selection state. */
@Composable
private fun ShelfCover(item: NotebookItem, actions: NotebookActions, openMenu: () -> Unit) {
    val selected = item.id in actions.selected
    Box(Modifier.size(CoverWidth, CoverHeight)) {
        NotebookCover(item.title, item.coverIndex, Modifier.fillMaxSize(), style = item.coverStyle)
        if (item.favorite) FavoriteRibbon(Modifier.align(Alignment.TopEnd).padding(end = Spacing.M))
        if (actions.selecting) {
            if (selected) {
                Box(Modifier.fillMaxSize().border(3.dp, MaterialTheme.colorScheme.primary, BestNoteShapes.NotebookCover))
            }
            SelectionMark(selected, Modifier.align(Alignment.BottomEnd).padding(Spacing.S))
        } else {
            MoreButton(openMenu, Modifier.align(Alignment.BottomEnd).padding(Spacing.XS), onCover = true)
        }
    }
}

/** One shelf: covers standing on a strip, with title and meta underneath each. */
@Composable
internal fun ShelfRow(row: List<NotebookItem>, now: Long, actions: NotebookActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = Spacing.L),
            horizontalArrangement = Arrangement.spacedBy(ShelfGap),
            verticalAlignment = Alignment.Bottom,
        ) {
            row.forEach { item ->
                NotebookMenuHost(item, actions, Modifier.clip(BestNoteShapes.NotebookCover)) { openMenu ->
                    ShelfCover(item, actions, openMenu)
                }
            }
        }
        // The shelf board.
        val board = RoundedCornerShape(2.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(ShelfStripHeight)
                .softShadow(Elevation.Low, board)
                .clip(board)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)))
        }
        Row(
            Modifier.padding(start = Spacing.L, end = Spacing.L, top = Spacing.M),
            horizontalArrangement = Arrangement.spacedBy(ShelfGap),
        ) {
            row.forEach { item ->
                Column(Modifier.width(CoverWidth)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        metaLine(item, now),
                        style = BestNoteTheme.type.caption,
                        color = BestNoteTheme.colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteRibbon(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 14.dp, height = 22.dp)
            .clip(RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp))
            .background(BestNoteTheme.colors.paper.marginLine),
    )
}

/** A row of grid cards. */
@Composable
internal fun GridRow(row: List<NotebookItem>, columns: Int, now: Long, actions: NotebookActions, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.L)) {
        row.forEach { item ->
            val shape = BestNoteShapes.Card
            NotebookMenuHost(
                item,
                actions,
                Modifier.weight(1f).softShadow(Elevation.Low, shape).clip(shape).background(MaterialTheme.colorScheme.surface),
            ) { openMenu ->
                val selected = item.id in actions.selected
                Column(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(GRID_ART_RATIO).background(MaterialTheme.colorScheme.surfaceContainerLow),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (item.thumbnailPath != null) {
                            // A real page beats a generic cover once DOC-10 has rendered one.
                            NotebookThumbnail(
                                item.thumbnailPath,
                                item.updatedAtMillis,
                                Modifier.fillMaxWidth(GRID_COVER_FRACTION).aspectRatio(COVER_RATIO),
                            )
                        } else {
                            NotebookCover(
                                item.title,
                                item.coverIndex,
                                Modifier.fillMaxWidth(GRID_COVER_FRACTION).aspectRatio(COVER_RATIO),
                                scale = GRID_COVER_SCALE,
                                style = item.coverStyle,
                            )
                        }
                        if (actions.selecting) SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(Spacing.M))
                    }
                    Row(Modifier.padding(start = Spacing.M, top = Spacing.S, bottom = Spacing.S, end = Spacing.XS), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(metaLine(item, now), style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (item.favorite) FavoriteStar()
                        if (!actions.selecting) MoreButton(openMenu)
                    }
                }
            }
        }
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** One list row. */
@Composable
internal fun ListRow(item: NotebookItem, now: Long, actions: NotebookActions, modifier: Modifier = Modifier) {
    NotebookMenuHost(item, actions, modifier.fillMaxWidth()) { openMenu ->
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.S, vertical = Spacing.M),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.L),
            ) {
                if (actions.selecting) SelectionMark(item.id in actions.selected)
                NotebookCover(item.title, item.coverIndex, Modifier.size(36.dp, 49.dp), showLabel = false, shadow = false, scale = LIST_COVER_SCALE, style = item.coverStyle)
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(metaLine(item, now), style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted, maxLines = 1)
                }
                if (item.folderPath.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(coverColor(item.folderPath.last().colorIndex)))
                        Text(
                            item.folderPath.joinToString(" › ") { it.name },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 260.dp),
                        )
                    }
                }
                if (item.favorite) FavoriteStar()
                if (!actions.selecting) MoreButton(openMenu)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = Elevation.Hairline)
        }
    }
}

@Composable
private fun FavoriteStar() {
    Icon(
        Icons.Filled.Star,
        contentDescription = stringResource(R.string.library_favorite),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(18.dp),
    )
}

/** A notebook in the Trash, with when it goes for good and a Restore button. */
@Composable
internal fun TrashRow(item: TrashItem, now: Long, onRestore: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.S, vertical = Spacing.M),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.L),
        ) {
            NotebookCover(item.title, item.coverIndex, Modifier.size(36.dp, 49.dp), showLabel = false, shadow = false)
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.library_trash_purge, relativeTime(item.deletedAtMillis, now), relativeTime(item.purgeAtMillis, now)),
                    style = BestNoteTheme.type.caption,
                    color = BestNoteTheme.colors.muted,
                    maxLines = 1,
                )
            }
            TextButton(onClick = onRestore, modifier = Modifier.clip(BestNoteShapes.Pill).hairline(BestNoteShapes.Pill)) {
                Icon(Icons.Outlined.Restore, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.XS))
                Text(stringResource(R.string.library_restore), style = MaterialTheme.typography.labelLarge)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = Elevation.Hairline)
    }
}

private const val COVER_RATIO = 124f / 168f
private const val GRID_ART_RATIO = 1.15f
private const val GRID_COVER_FRACTION = 0.5f
private const val GRID_COVER_SCALE = 0.75f
private const val LIST_COVER_SCALE = 0.4f
