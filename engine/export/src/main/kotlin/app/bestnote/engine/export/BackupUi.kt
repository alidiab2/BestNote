package app.bestnote.engine.export

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Spacing

/**
 * Settings → Backup & restore (DOC-08). Backs the whole library up to a folder the user picks and
 * restores it from one. Backups are incremental, so only edited notebooks are rewritten.
 */
@Composable
fun BackupDialog(controller: BackupController, onDismiss: () -> Unit) {
    val state by controller.state.collectAsState()
    var restoreInfo by remember { mutableStateOf(false) }
    val pickBackupFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(controller::chooseFolder) }
    val pickRestoreFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(controller::restoreFrom) }
    val running = state.job as? BackupJob.Running

    AlertDialog(
        onDismissRequest = { if (running == null) { controller.dismissResult(); onDismiss() } },
        shape = BestNoteShapes.Sheet,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                if (running != null) {
                    WorkingContent(
                        running.done,
                        running.total,
                        stringResource(if (running.restoring) R.string.backup_restoring else R.string.backup_running, running.done, running.total),
                    )
                } else {
                    FolderSection(state, onChoose = { pickBackupFolder.launch(null) })
                    (state.job as? BackupJob.Finished)?.let { Text(outcomeText(it.outcome), style = MaterialTheme.typography.bodyMedium, color = outcomeColor(it.outcome)) }
                    Text(stringResource(R.string.backup_hint), style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted)
                }
            }
        },
        confirmButton = {
            if (running == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                    ExportPill(stringResource(R.string.backup_restore), { restoreInfo = true }, background = MaterialTheme.colorScheme.surfaceVariant, foreground = MaterialTheme.colorScheme.onSurfaceVariant)
                    ExportPill(stringResource(R.string.backup_now), { controller.backUp() }, enabled = state.folder != null)
                }
            }
        },
        dismissButton = {
            if (running != null) {
                ExportPill(stringResource(R.string.export_cancel), controller::cancel, background = Color.Transparent, foreground = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ExportPill(stringResource(R.string.backup_close), { controller.dismissResult(); onDismiss() }, background = Color.Transparent, foreground = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )

    if (restoreInfo) {
        AlertDialog(
            onDismissRequest = { restoreInfo = false },
            shape = BestNoteShapes.Sheet,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            title = { Text(stringResource(R.string.backup_restore_title), style = MaterialTheme.typography.headlineSmall) },
            text = { Text(stringResource(R.string.backup_restore_body), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { ExportPill(stringResource(R.string.backup_restore_confirm), { restoreInfo = false; pickRestoreFolder.launch(null) }) },
            dismissButton = { ExportPill(stringResource(R.string.export_cancel), { restoreInfo = false }, background = Color.Transparent, foreground = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
    }
}

@Composable
private fun FolderSection(state: BackupState, onChoose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.backup_folder), style = BestNoteTheme.type.overline, color = BestNoteTheme.colors.muted)
            Text(
                state.folderName ?: stringResource(R.string.backup_no_folder),
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
            val last = state.lastBackupMillis
            Text(
                if (last == null) {
                    stringResource(R.string.backup_never)
                } else {
                    stringResource(R.string.backup_last, DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())
                },
                style = BestNoteTheme.type.caption,
                color = BestNoteTheme.colors.muted,
            )
        }
        ExportPill(
            stringResource(if (state.folder == null) R.string.backup_choose else R.string.backup_change),
            onChoose,
            background = MaterialTheme.colorScheme.surfaceVariant,
            foreground = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun outcomeColor(outcome: BackupOutcome): Color = when (outcome) {
    is BackupOutcome.Failed, BackupOutcome.NoBackupFound -> MaterialTheme.colorScheme.error
    is BackupOutcome.Restored -> if (outcome.failed.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun outcomeText(outcome: BackupOutcome): String = when (outcome) {
    is BackupOutcome.BackedUp -> pluralStringResource(R.plurals.backup_done, outcome.notebooks, outcome.notebooks)
    is BackupOutcome.Restored -> buildString {
        append(pluralStringResource(R.plurals.backup_restored, outcome.restored, outcome.restored))
        if (outcome.skippedNewerLocal > 0) append(' ').append(pluralStringResource(R.plurals.backup_kept_newer, outcome.skippedNewerLocal, outcome.skippedNewerLocal))
        if (outcome.failed.isNotEmpty()) append(' ').append(stringResource(R.string.backup_unreadable, outcome.failed.joinToString()))
    }
    BackupOutcome.NoBackupFound -> stringResource(R.string.backup_not_found)
    is BackupOutcome.Failed -> stringResource(R.string.backup_failed, outcome.message)
}
