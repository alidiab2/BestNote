package app.bestnote.feature.library

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bestnote.core.designsystem.theme.BestNoteFonts
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.softShadow
import java.util.Date

private const val HOVER_SCALE = 1.04f
private val FadeHeight = 40.dp

/** Approved sidebar width. */
internal val SidebarWidth = 252.dp

@Composable
internal fun LibrarySidebar(
    state: LibraryUiState,
    onSelect: (LibraryDestination) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onNewFolder: () -> Unit = {},
    onFolderMenu: (Folder, FolderMenuAction) -> Unit = { _, _ -> },
) {
    val selected = state.controls.destination
    val hovered = LocalLibraryDrag.current.hovered
    Column(
        modifier
            .width(SidebarWidth)
            .background(BestNoteTheme.colors.sidebar)
            .padding(horizontal = Spacing.M, vertical = Spacing.L),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = Spacing.L), verticalAlignment = Alignment.CenterVertically) {
            Wordmark(Modifier.weight(1f).padding(start = Spacing.M))
            val settings = stringResource(R.string.library_nav_settings)
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(44.dp).semantics { contentDescription = settings }) {
                Icon(Icons.Outlined.Settings, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
        }
        val scroll = rememberScrollState()
        val fade = BestNoteTheme.colors.sidebar
        Column(
            Modifier
                .weight(1f)
                .drawWithContent {
                    drawContent()
                    // A soft fade tells the user there are more folders below.
                    if (scroll.canScrollForward) {
                        val h = FadeHeight.toPx()
                        drawRect(
                            Brush.verticalGradient(listOf(fade.copy(alpha = 0f), fade), startY = size.height - h, endY = size.height),
                            topLeft = Offset(0f, size.height - h),
                            size = Size(size.width, h),
                        )
                    }
                }
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(Spacing.XXS),
        ) {
            NavItem(
                Icons.AutoMirrored.Outlined.LibraryBooks,
                stringResource(R.string.library_nav_library),
                selected == LibraryDestination.Library,
                count = state.totalCount,
                hovered = hovered == LibraryDestination.Library,
                modifier = Modifier.dropTarget(LibraryDestination.Library),
            ) {
                onSelect(LibraryDestination.Library)
            }
            NavItem(Icons.Outlined.Schedule, stringResource(R.string.library_nav_recent), selected == LibraryDestination.Recent) {
                onSelect(LibraryDestination.Recent)
            }
            NavItem(
                Icons.Outlined.StarOutline,
                stringResource(R.string.library_nav_favorites),
                selected == LibraryDestination.Favorites,
                count = state.favoritesCount,
                hovered = hovered == LibraryDestination.Favorites,
                modifier = Modifier.dropTarget(LibraryDestination.Favorites),
            ) {
                onSelect(LibraryDestination.Favorites)
            }
            NavItem(Icons.Outlined.EditNote, stringResource(R.string.library_nav_quicknotes), selected == LibraryDestination.Quicknotes, count = state.quicknotesCount) {
                onSelect(LibraryDestination.Quicknotes)
            }
            NavItem(
                Icons.Outlined.AutoAwesome,
                stringResource(R.string.library_nav_smart_board),
                selected == LibraryDestination.SmartBoard,
                trailing = { AiBadge() },
            ) { onSelect(LibraryDestination.SmartBoard) }

            Row(
                Modifier.fillMaxWidth().padding(start = Spacing.M, top = Spacing.L),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.library_nav_folders).uppercase(),
                    style = BestNoteTheme.type.overline,
                    color = BestNoteTheme.colors.muted,
                    modifier = Modifier.weight(1f),
                )
                val newFolder = stringResource(R.string.library_folder_new)
                IconButton(onClick = onNewFolder, modifier = Modifier.size(40.dp).semantics { contentDescription = newFolder }) {
                    Icon(Icons.Outlined.Add, null, tint = BestNoteTheme.colors.muted, modifier = Modifier.size(20.dp))
                }
            }
            state.folders.forEach { item ->
                val dest = LibraryDestination.InFolder(item.folder.id)
                var menu by remember { mutableStateOf(false) }
                NavItem(
                    icon = null,
                    label = item.folder.name,
                    active = selected == dest,
                    count = item.count,
                    hovered = hovered == dest,
                    indent = Spacing.L * item.depth,
                    modifier = Modifier.dropTarget(dest),
                    leading = { FolderGlyph(item.folder.colorIndex, Modifier.size(18.dp, 15.dp)) },
                    trailing = {
                        Box {
                            val more = stringResource(R.string.library_folder_more, item.folder.name)
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(BestNoteShapes.Pill)
                                    .clickable(role = Role.Button, onClick = { menu = true })
                                    .semantics { contentDescription = more },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Outlined.MoreHoriz, null, tint = BestNoteTheme.colors.muted, modifier = Modifier.size(18.dp))
                            }
                            FolderMenu(menu, onDismiss = { menu = false }) { action ->
                                menu = false
                                onFolderMenu(item.folder, action)
                            }
                        }
                    },
                ) { onSelect(dest) }
            }
            NavItem(
                icon = Icons.Outlined.CreateNewFolder,
                label = stringResource(R.string.library_folder_new),
                active = false,
                muted = true,
                onClick = onNewFolder,
            )
        }
        Spacer(Modifier.height(Spacing.S))
        NavItem(
            Icons.Outlined.DeleteOutline,
            stringResource(R.string.library_nav_trash),
            selected == LibraryDestination.Trash,
            count = state.trashCount.takeIf { it > 0 },
            hovered = hovered == LibraryDestination.Trash,
            modifier = Modifier.dropTarget(LibraryDestination.Trash),
        ) {
            onSelect(LibraryDestination.Trash)
        }
        Spacer(Modifier.height(Spacing.M))
        StorageCard(state.lastSavedAtMillis)
    }
}

/** "BestNote" in Fraunces with "Note" in italic. A brand mark: never translated or font-swapped. */
@Composable
private fun Wordmark(modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            append("Best")
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("Note") }
        },
        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = BestNoteFonts.Title, fontSize = 25.sp),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

@Composable
private fun NavItem(
    icon: ImageVector?,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    count: Int? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    hovered: Boolean = false,
    muted: Boolean = false,
    indent: Dp = 0.dp,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        when {
            hovered -> MaterialTheme.colorScheme.primaryContainer
            active -> MaterialTheme.colorScheme.surfaceBright
            else -> Color.Transparent
        },
        Motion.tween(Motion.Duration.Short),
        label = "navItem",
    )
    val shape = BestNoteShapes.Pill
    val scale by animateFloatAsState(if (hovered) HOVER_SCALE else 1f, Motion.Spring.Snappy, label = "navHover")
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = indent)
            .height(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (active || hovered) Modifier.softShadow(Elevation.Low, shape) else Modifier)
            .clip(shape)
            .background(bg)
            .then(if (hovered) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(start = Spacing.M, end = if (trailing != null) Spacing.XS else Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                leading != null -> leading()
                icon != null -> Icon(
                    icon,
                    contentDescription = null,
                    tint = when {
                        active -> MaterialTheme.colorScheme.onSurface
                        muted -> BestNoteTheme.colors.muted
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                active -> MaterialTheme.colorScheme.onSurface
                muted -> BestNoteTheme.colors.muted
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = BestNoteTheme.colors.muted)
        }
        trailing?.invoke()
    }
}

@Composable
private fun AiBadge() {
    Text(
        stringResource(R.string.library_nav_ai_badge),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp, fontSize = 10.sp),
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .clip(BestNoteShapes.Pill)
            .background(BestNoteTheme.colors.aiAccent.copy(alpha = 0.45f))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

@Composable
private fun StorageCard(lastSavedAtMillis: Long?) {
    val context = LocalContext.current
    val detail = if (lastSavedAtMillis == null) {
        stringResource(R.string.library_storage_detail_none)
    } else {
        stringResource(R.string.library_storage_detail, DateFormat.getTimeFormat(context).format(Date(lastSavedAtMillis)))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(BestNoteShapes.Control)
            .background(MaterialTheme.colorScheme.surfaceBright)
            .padding(Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        Icon(Icons.Outlined.TabletAndroid, null, tint = BestNoteTheme.colors.success, modifier = Modifier.size(20.dp))
        Column {
            Text(stringResource(R.string.library_storage_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted)
        }
    }
}
