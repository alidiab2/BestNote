package app.bestnote.feature.library

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
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

/** Approved sidebar width. */
internal val SidebarWidth = 252.dp

@Composable
internal fun LibrarySidebar(
    state: LibraryUiState,
    onSelect: (LibraryDestination) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = state.controls.destination
    Column(
        modifier
            .width(SidebarWidth)
            .background(BestNoteTheme.colors.sidebar)
            .padding(horizontal = Spacing.M, vertical = Spacing.L),
    ) {
        Wordmark(Modifier.padding(start = Spacing.M, top = Spacing.XS, bottom = Spacing.XL))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.XXS)) {
            NavItem(Icons.AutoMirrored.Outlined.LibraryBooks, stringResource(R.string.library_nav_library), selected == LibraryDestination.Library, state.totalCount) {
                onSelect(LibraryDestination.Library)
            }
            NavItem(Icons.Outlined.Schedule, stringResource(R.string.library_nav_recent), selected == LibraryDestination.Recent) {
                onSelect(LibraryDestination.Recent)
            }
            NavItem(Icons.Outlined.StarOutline, stringResource(R.string.library_nav_favorites), selected == LibraryDestination.Favorites, state.favoritesCount) {
                onSelect(LibraryDestination.Favorites)
            }
            NavItem(Icons.Outlined.EditNote, stringResource(R.string.library_nav_quicknotes), selected == LibraryDestination.Quicknotes, state.quicknotesCount) {
                onSelect(LibraryDestination.Quicknotes)
            }
            NavItem(
                Icons.Outlined.AutoAwesome,
                stringResource(R.string.library_nav_smart_board),
                selected == LibraryDestination.SmartBoard,
                trailing = { AiBadge() },
            ) { onSelect(LibraryDestination.SmartBoard) }

            Text(
                stringResource(R.string.library_nav_folders).uppercase(),
                style = BestNoteTheme.type.overline,
                color = BestNoteTheme.colors.muted,
                modifier = Modifier.padding(start = Spacing.M, top = Spacing.XL, bottom = Spacing.S),
            )
            state.folders.forEach { item ->
                val dest = LibraryDestination.InFolder(item.folder.id)
                NavItem(
                    icon = null,
                    label = item.folder.name,
                    active = selected == dest,
                    count = item.count,
                    leading = {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(coverColor(item.folder.colorIndex)))
                    },
                ) { onSelect(dest) }
            }
        }
        Spacer(Modifier.height(Spacing.S))
        NavItem(Icons.Outlined.DeleteOutline, stringResource(R.string.library_nav_trash), selected == LibraryDestination.Trash, state.trashCount.takeIf { it > 0 }) {
            onSelect(LibraryDestination.Trash)
        }
        NavItem(Icons.Outlined.Settings, stringResource(R.string.library_nav_settings), active = false, onClick = onOpenSettings)
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
    count: Int? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        if (active) MaterialTheme.colorScheme.surfaceBright else Color.Transparent,
        Motion.tween(Motion.Duration.Short),
        label = "navItem",
    )
    val shape = BestNoteShapes.Pill
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .then(if (active) Modifier.softShadow(Elevation.Low, shape) else Modifier)
            .clip(shape)
            .background(bg)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                leading != null -> leading()
                icon != null -> Icon(
                    icon,
                    contentDescription = null,
                    tint = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
        if (count != null) {
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = BestNoteTheme.colors.muted)
        }
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
