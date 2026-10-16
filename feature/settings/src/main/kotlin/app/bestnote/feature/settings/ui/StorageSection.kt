package app.bestnote.feature.settings.ui

import android.content.Context
import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.bestnote.core.datastore.StoragePreferences
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.engine.export.BackupDialog
import app.bestnote.engine.export.rememberBackupController
import app.bestnote.feature.settings.R
import app.bestnote.feature.settings.StorageUiState

internal fun formatBytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

@Composable
internal fun StorageSection(
    storage: StorageUiState,
    prefs: StoragePreferences,
    update: ((StoragePreferences) -> StoragePreferences) -> Unit,
    onClearThumbnails: () -> Unit,
) {
    val context = LocalContext.current
    val usage = storage.usage
    var showBackup by remember { mutableStateOf(false) }
    if (showBackup) BackupDialog(rememberBackupController(), onDismiss = { showBackup = false })
    DetailHeader(stringResource(R.string.settings_cat_storage), stringResource(R.string.settings_storage_desc))

    SettingsGroup(stringResource(R.string.settings_storage_used)) {
        row {
            Column(Modifier.fillMaxWidth().padding(Spacing.L)) {
                Text(
                    usage?.let { formatBytes(context, it.totalBytes) } ?: "…",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val total = usage?.totalBytes?.coerceAtLeast(1L) ?: 1L
                val notebooksShare by animateFloatAsState(
                    (usage?.notebooksBytes ?: 0L).toFloat() / total,
                    Motion.Spring.gentle(),
                    label = "storageBar",
                )
                val notebookColor = MaterialTheme.colorScheme.primary
                val cacheColor = BestNoteTheme.colors.aiAccent
                Row(
                    Modifier.fillMaxWidth().padding(vertical = Spacing.M).height(BarHeight).clip(BestNoteShapes.Pill)
                        .background(tileBackground()),
                ) {
                    if (notebooksShare > 0f) Box(Modifier.weight(notebooksShare).fillMaxHeight().background(notebookColor))
                    if (notebooksShare < 1f && usage != null && usage.clearableBytes > 0) {
                        Box(Modifier.weight(1f - notebooksShare).fillMaxHeight().background(cacheColor))
                    } else if (notebooksShare < 1f) {
                        Box(Modifier.weight(1f - notebooksShare).fillMaxHeight())
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.XL), verticalArrangement = Arrangement.spacedBy(Spacing.XS)) {
                    Legend(notebookColor, stringResource(R.string.settings_storage_notebooks), usage?.let { formatBytes(context, it.notebooksBytes) })
                    Legend(cacheColor, stringResource(R.string.settings_storage_cache), usage?.let { formatBytes(context, it.clearableBytes) })
                }
            }
        }
        row {
            SettingsRow(
                title = stringResource(R.string.settings_storage_location),
                subtitle = stringResource(R.string.settings_storage_location_sub),
                icon = Icons.Outlined.FolderOpen,
            )
        }
        if (usage != null) {
            row {
                Text(
                    usage.notebooksPath,
                    // A path reads left to right even in Arabic.
                    style = BestNoteTheme.type.caption.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                    color = BestNoteTheme.colors.muted,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.L, vertical = Spacing.M),
                )
            }
        }
    }

    SettingsGroup(stringResource(R.string.settings_backup_group)) {
        row {
            SettingsRow(
                title = stringResource(R.string.settings_backup_restore),
                subtitle = stringResource(R.string.settings_backup_restore_sub),
                icon = Icons.Outlined.IosShare,
                modifier = Modifier.clickable(role = Role.Button) { showBackup = true },
            ) { RowAction(stringResource(R.string.settings_open), { showBackup = true }) }
        }
    }

    // LIB-11: how long unlocked notebooks stay open while the app is in the background.
    SettingsGroup(stringResource(R.string.settings_lock_group)) {
        row {
            StackedRow(
                stringResource(R.string.settings_relock),
                subtitle = stringResource(R.string.settings_relock_sub),
                icon = Icons.Outlined.Lock,
            ) {
                Segmented(
                    StoragePreferences.RELOCK_CHOICES,
                    prefs.relockAfterSeconds,
                    label = {
                        when (it) {
                            0 -> stringResource(R.string.settings_relock_now)
                            StoragePreferences.RELOCK_UNTIL_CLOSED -> stringResource(R.string.settings_relock_until_closed)
                            else -> pluralStringResource(R.plurals.settings_relock_minutes, it / SECONDS_PER_MINUTE, it / SECONDS_PER_MINUTE)
                        }
                    },
                    onSelect = { v -> update { it.copy(relockAfterSeconds = v) } },
                )
            }
        }
    }

    SettingsGroup(stringResource(R.string.settings_cleanup_group)) {
        row {
            StackedRow(
                stringResource(R.string.settings_trash_retention),
                subtitle = if (prefs.trashRetentionDays == 0) {
                    stringResource(R.string.settings_trash_never_sub)
                } else {
                    pluralStringResource(R.plurals.settings_trash_days_sub, prefs.trashRetentionDays, prefs.trashRetentionDays)
                },
                icon = Icons.Outlined.DeleteOutline,
            ) {
                Segmented(
                    StoragePreferences.RETENTION_CHOICES,
                    prefs.trashRetentionDays,
                    label = { if (it == 0) stringResource(R.string.settings_trash_never) else pluralStringResource(R.plurals.settings_days_short, it, it) },
                    onSelect = { v -> update { it.copy(trashRetentionDays = v) } },
                )
            }
        }
        row {
            SettingsRow(
                title = stringResource(R.string.settings_clear_cache),
                subtitle = usage?.let { stringResource(R.string.settings_clear_cache_sub, formatBytes(context, it.clearableBytes)) },
                icon = Icons.Outlined.CleaningServices,
            ) {
                RowAction(
                    stringResource(if (storage.clearing) R.string.settings_clearing else R.string.settings_clear),
                    onClearThumbnails,
                    enabled = !storage.clearing && (usage?.clearableBytes ?: 0L) > 0L,
                )
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, value: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value != null) Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

private val BarHeight = 10.dp
private const val SECONDS_PER_MINUTE = 60
