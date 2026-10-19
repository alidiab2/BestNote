package app.bestnote.feature.settings.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.feature.settings.RequestDetail
import app.bestnote.feature.settings.RequestEditState
import app.bestnote.feature.settings.RequestsViewModel
import app.bestnote.feature.settings.R
import app.bestnote.feature.settings.data.Attachment
import app.bestnote.feature.settings.data.FeatureRequest
import app.bestnote.feature.settings.data.RequestPriority
import app.bestnote.feature.settings.data.RequestStatus
import java.text.DateFormat
import java.util.Date

private val DialogMaxWidth = 640.dp

@Composable
private fun SheetDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(Spacing.M).widthIn(max = DialogMaxWidth).fillMaxWidth(),
        ) {
            Column(Modifier.heightIn(max = 760.dp).verticalScroll(rememberScrollState()).padding(Spacing.L)) { content() }
        }
    }
}

/** Change a request that already exists: text, type, priority, and which files it keeps. */
@Composable
internal fun EditRequestDialog(state: RequestEditState, viewModel: RequestsViewModel) {
    SheetDialog(onDismiss = viewModel::cancelEdit) {
        Text(stringResource(R.string.settings_requests_edit_title), style = MaterialTheme.typography.titleLarge)
        Column(Modifier.padding(top = Spacing.M)) {
            val files = state.kept.map { a ->
                FormFile(
                    key = a.file, name = a.name, mime = a.mime, size = a.size, file = viewModel.fileOf(a), removed = a.file in state.removed,
                    onToggle = { if (a.file in state.removed) viewModel.restoreEditExisting(a) else viewModel.removeEditExisting(a) },
                )
            } + state.added.map { f ->
                FormFile(f.id, f.attachment.name, f.attachment.mime, f.attachment.bytes.size.toLong(), bytes = f.attachment.bytes, onToggle = { viewModel.removeEditAdded(f) })
            }
            RequestForm(
                kind = state.kind,
                priority = state.priority,
                title = state.title,
                details = state.details,
                files = files,
                onKind = viewModel::editKind,
                onPriority = viewModel::editPriority,
                onTitle = viewModel::editTitle,
                onDetails = viewModel::editDetails,
                onPick = viewModel::addEditFiles,
                canAddMore = state.fileCount < RequestsViewModel.MAX_FILES,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.L), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (state.saving) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            TextButton(onClick = viewModel::cancelEdit) { Text(stringResource(R.string.settings_requests_cancel)) }
            Button(onClick = viewModel::saveEdit, enabled = state.canSave) { Text(stringResource(R.string.settings_requests_save_changes)) }
        }
    }
}

/** One request in full: text, every file (tap to open), GitHub's replies, and every action. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RequestDetailDialog(
    request: FeatureRequest,
    detail: RequestDetail,
    githubConnected: Boolean,
    viewModel: RequestsViewModel,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val done = request.status == RequestStatus.Done
    val date = remember(request.createdAt) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(request.createdAt)) }
    val notOpenable = stringResource(R.string.settings_requests_cannot_open)
    fun open(a: Attachment) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(viewModel.uriFor(a), a.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, notOpenable, Toast.LENGTH_SHORT).show()
        }
    }
    SheetDialog(onDismiss = viewModel::closeDetail) {
        Text("${request.ref} · ${kindLabel(request.kind)}", style = BestNoteTheme.type.overline, color = BestNoteTheme.colors.muted)
        Text(request.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = Spacing.XS))
        Text(
            buildString {
                append(date)
                if (request.updatedAt > 0) append(" · ").append(stringResource(R.string.settings_requests_edited))
                if (request.priority != RequestPriority.Normal) append(" · ").append(priorityLabelPlain(request.priority))
                append(" · ").append(stringResource(if (done) R.string.settings_requests_status_done else R.string.settings_requests_status_open))
            },
            style = MaterialTheme.typography.bodySmall,
            color = BestNoteTheme.colors.muted,
            modifier = Modifier.padding(top = Spacing.XS),
        )
        if (request.details.isNotBlank()) {
            SelectionContainer {
                Text(request.details, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.M))
            }
        }

        if (request.attachments.isNotEmpty()) {
            Text(stringResource(R.string.settings_requests_files), style = BestNoteTheme.type.overline, color = BestNoteTheme.colors.muted, modifier = Modifier.padding(top = Spacing.L, bottom = Spacing.S))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                request.attachments.forEach { a ->
                    if (a.isImage) {
                        Thumb(null, viewModel.fileOf(a), 96.dp, modifier = Modifier.clickable(role = Role.Button) { open(a) })
                    } else {
                        Row(
                            Modifier
                                .widthIn(max = 280.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(tileBackground())
                                .clickable(role = Role.Button) { open(a) }
                                .padding(horizontal = Spacing.M, vertical = Spacing.S),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                        ) {
                            Icon(Icons.Outlined.InsertDriveFile, null, modifier = Modifier.size(18.dp), tint = BestNoteTheme.colors.muted)
                            Column {
                                Text(a.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(formatSize(a.size), style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted)
                            }
                        }
                    }
                }
            }
        }

        Text(stringResource(R.string.settings_requests_github_group), style = BestNoteTheme.type.overline, color = BestNoteTheme.colors.muted, modifier = Modifier.padding(top = Spacing.L, bottom = Spacing.S))
        val url = request.issueUrl
        if (request.issueNumber != null && url != null) {
            Text(
                stringResource(R.string.settings_requests_sent, request.issueNumber),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(role = Role.Button) { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) },
            )
            if (request.unsynced) {
                Text(stringResource(R.string.settings_requests_edit_pending), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
            }
        } else {
            Text(
                stringResource(if (githubConnected) R.string.settings_requests_waiting_send else R.string.settings_requests_not_sent),
                style = MaterialTheme.typography.bodyMedium,
                color = BestNoteTheme.colors.muted,
            )
        }
        if (detail.loadingComments) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(top = Spacing.S).size(18.dp))
        }
        detail.comments.forEach { c ->
            Column(
                Modifier
                    .padding(top = Spacing.S)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(tileBackground())
                    .padding(Spacing.M),
            ) {
                Text(c.author, style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted)
                SelectionContainer { Text(c.body, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        FlowRow(
            Modifier.fillMaxWidth().padding(top = Spacing.L),
            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            verticalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            OutlinedButton(onClick = { viewModel.closeDetail(); viewModel.startEdit(request) }) {
                Icon(Icons.Outlined.Edit, null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.settings_requests_edit), modifier = Modifier.padding(start = Spacing.S))
            }
            OutlinedButton(onClick = { viewModel.toggleDone(request) }) {
                Icon(if (done) Icons.Outlined.RadioButtonUnchecked else Icons.Outlined.CheckCircle, null, modifier = Modifier.size(18.dp))
                Text(stringResource(if (done) R.string.settings_requests_reopen else R.string.settings_requests_mark_done), modifier = Modifier.padding(start = Spacing.S))
            }
            OutlinedButton(onClick = { viewModel.share(request.number) }) {
                Icon(Icons.Outlined.Share, null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.settings_requests_share_one), modifier = Modifier.padding(start = Spacing.S))
            }
            OutlinedButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.settings_requests_delete), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = Spacing.S))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = viewModel::closeDetail) { Text(stringResource(R.string.settings_debug_close)) }
        }
    }
}

@Composable
private fun priorityLabelPlain(p: RequestPriority) = priorityLabel(p)

/** Asks before deleting; for a sent request it offers to close the GitHub issue too (issues can't be deleted). */
@Composable
internal fun DeleteRequestDialog(request: FeatureRequest, githubConnected: Boolean, onConfirm: (closeIssue: Boolean) -> Unit, onDismiss: () -> Unit) {
    val canClose = request.issueNumber != null && githubConnected
    var closeIssue by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_requests_delete_title, request.ref)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                Text(request.title, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.settings_requests_delete_body), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
                if (canClose) {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { closeIssue = !closeIssue },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = closeIssue, onCheckedChange = null)
                        Text(
                            stringResource(R.string.settings_requests_delete_close_issue, request.issueNumber ?: 0),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = Spacing.S),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(canClose && closeIssue) }) {
                Text(stringResource(R.string.settings_requests_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_requests_cancel)) } },
    )
}

@Composable
internal fun ClearDoneDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_requests_clear_title, count)) },
        text = { Text(stringResource(R.string.settings_requests_clear_body), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_requests_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_requests_cancel)) } },
    )
}
