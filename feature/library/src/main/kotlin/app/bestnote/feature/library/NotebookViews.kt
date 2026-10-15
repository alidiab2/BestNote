package app.bestnote.feature.library

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.hairline
import app.bestnote.core.designsystem.theme.softShadow

/** Horizontal gap between covers on a shelf. */
internal val ShelfGap: Dp = 28.dp
private val ShelfStripHeight = 12.dp
internal val GridMinCell: Dp = 168.dp

/** Callbacks shared by every notebook view. */
internal class NotebookActions(
    val open: (NotebookItem) -> Unit,
    val rename: (NotebookItem) -> Unit,
    val trash: (NotebookItem) -> Unit,
)

/** Localised "25 min. ago". */
@Composable
internal fun relativeTime(millis: Long, now: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

@Composable
private fun metaLine(item: NotebookItem, now: Long): String = stringResource(
    R.string.library_meta,
    pluralStringResource(R.plurals.library_page_count, item.pageCount, item.pageCount),
    relativeTime(item.updatedAtMillis, now),
)

/**
 * Click opens, long-press shows Open / Rename / Move to Trash. The menu is anchored to [content].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookMenuHost(
    item: NotebookItem,
    actions: NotebookActions,
    modifier: Modifier = Modifier,
    content: @Composable (openMenu: () -> Unit) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(
        modifier.combinedClickable(
            role = Role.Button,
            onClick = { actions.open(item) },
            onLongClick = { menu = true },
        ),
    ) {
        content { menu = true }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.library_menu_open)) }, onClick = { menu = false; actions.open(item) })
            DropdownMenuItem(text = { Text(stringResource(R.string.library_menu_rename)) }, onClick = { menu = false; actions.rename(item) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_menu_trash), color = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; actions.trash(item) },
            )
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
                NotebookMenuHost(item, actions, Modifier.clip(BestNoteShapes.NotebookCover)) {
                    Box {
                        NotebookCover(item.title, item.coverIndex, Modifier.size(CoverWidth, CoverHeight))
                        if (item.favorite) FavoriteRibbon(Modifier.align(Alignment.TopEnd).padding(end = Spacing.M))
                    }
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
            ) {
                Column {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(GRID_ART_RATIO).background(MaterialTheme.colorScheme.surfaceContainerLow),
                        contentAlignment = Alignment.Center,
                    ) {
                        NotebookCover(
                            item.title,
                            item.coverIndex,
                            Modifier.fillMaxWidth(GRID_COVER_FRACTION).aspectRatio(COVER_RATIO),
                            scale = GRID_COVER_SCALE,
                        )
                    }
                    Row(Modifier.padding(Spacing.M), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(metaLine(item, now), style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (item.favorite) FavoriteStar()
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
                NotebookCover(item.title, item.coverIndex, Modifier.size(36.dp, 49.dp), showLabel = false, shadow = false)
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(metaLine(item, now), style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted, maxLines = 1)
                }
                item.folder?.let { folder ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(coverColor(folder.colorIndex)))
                        Text(folder.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (item.favorite) FavoriteStar()
                IconButton(onClick = openMenu) {
                    Icon(Icons.Outlined.MoreHoriz, contentDescription = stringResource(R.string.library_menu_rename), tint = BestNoteTheme.colors.muted)
                }
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
