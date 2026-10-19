package app.bestnote.feature.settings.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.feature.settings.GitHubUiState
import app.bestnote.feature.settings.Notice
import app.bestnote.feature.settings.R
import app.bestnote.feature.settings.RequestEvent
import app.bestnote.feature.settings.RequestFilter
import app.bestnote.feature.settings.RequestsViewModel
import app.bestnote.feature.settings.data.FeatureRequest
import app.bestnote.feature.settings.data.RequestPriority
import app.bestnote.feature.settings.data.RequestStatus
import app.bestnote.feature.settings.data.SyncProblem
import java.text.DateFormat
import java.util.Date

/**
 * Settings, Requests: write down a feature, improvement or bug with screenshots and any other files, then manage
 * the list: search, filter, open, edit, mark done, share, delete. Every request is saved on the device first. With a
 * GitHub token set it is also sent as an issue (files included) to a private repo; edits are pushed, replies show
 * up in the detail view, and closing the issue marks the request done here. Without a token nothing leaves the device.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RequestsSection(viewModel: RequestsViewModel) {
    val list by viewModel.list.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val github by viewModel.github.collectAsStateWithLifecycle()
    val edit by viewModel.edit.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val all by viewModel.requests.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var deleting by remember { mutableStateOf<Int?>(null) }
    var clearing by remember { mutableStateOf(false) }

    val saved = stringResource(R.string.settings_requests_saved)
    val savedSent = stringResource(R.string.settings_requests_saved_sent)
    val savedLocal = stringResource(R.string.settings_requests_saved_not_sent)
    val connectedMsg = stringResource(R.string.settings_requests_connected)
    val shareTitle = stringResource(R.string.settings_requests_share)
    val updated = stringResource(R.string.settings_requests_updated)
    val deleted = stringResource(R.string.settings_requests_deleted)
    val tooLarge = stringResource(R.string.settings_requests_file_too_large, RequestsViewModel.MAX_FILE_MB)
    val unreadable = stringResource(R.string.settings_requests_file_unreadable)
    val tooMany = stringResource(R.string.settings_requests_too_many_files, RequestsViewModel.MAX_FILES)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is RequestEvent.Saved -> toast(context, when {
                    event.sent -> savedSent
                    viewModel.github.value.connected -> savedLocal
                    else -> saved
                })
                RequestEvent.Connected -> toast(context, connectedMsg)
                is RequestEvent.Message -> toast(context, when (event.notice) {
                    Notice.FileTooLarge -> tooLarge
                    Notice.FileUnreadable -> unreadable
                    Notice.TooManyFiles -> tooMany
                    Notice.Updated -> updated
                    Notice.Deleted -> deleted
                    Notice.DoneCleared -> context.resources.getQuantityString(R.plurals.settings_requests_cleared, event.count, event.count)
                })
                is RequestEvent.Share -> {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/markdown")
                        .putExtra(Intent.EXTRA_STREAM, event.uri)
                        .putExtra(Intent.EXTRA_SUBJECT, "BestNote requests")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, shareTitle))
                }
            }
        }
    }

    DetailHeader(stringResource(R.string.settings_cat_requests), stringResource(R.string.settings_requests_desc))

    SettingsGroup(stringResource(R.string.settings_requests_new_group)) {
        row {
            Column(Modifier.fillMaxWidth().padding(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                val files = draft.files.map { f ->
                    FormFile(f.id, f.attachment.name, f.attachment.mime, f.attachment.bytes.size.toLong(), bytes = f.attachment.bytes, onToggle = { viewModel.removeDraftFile(f) })
                }
                RequestForm(
                    kind = draft.kind,
                    priority = draft.priority,
                    title = draft.title,
                    details = draft.details,
                    files = files,
                    onKind = viewModel::setKind,
                    onPriority = viewModel::setPriority,
                    onTitle = viewModel::setTitle,
                    onDetails = viewModel::setDetails,
                    onPick = viewModel::addDraftFiles,
                    canAddMore = draft.files.size < RequestsViewModel.MAX_FILES,
                )
                Button(onClick = viewModel::send, enabled = draft.canSend && !github.busy) {
                    Text(stringResource(if (github.connected) R.string.settings_requests_save_send else R.string.settings_requests_save))
                }
            }
        }
    }

    GitHubGroup(github, viewModel)

    if (list.total > 0) {
        Column(Modifier.fillMaxWidth().padding(top = Spacing.XL), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text(stringResource(R.string.settings_requests_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.settings_requests_clear_search))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Segmented(
                options = RequestFilter.entries,
                selected = filter,
                label = {
                    when (it) {
                        RequestFilter.All -> stringResource(R.string.settings_requests_filter_all, list.total)
                        RequestFilter.Open -> stringResource(R.string.settings_requests_filter_open, list.openTotal)
                        RequestFilter.Done -> stringResource(R.string.settings_requests_filter_done, list.doneTotal)
                    }
                },
                onSelect = viewModel::setFilter,
            )
        }
    }

    if (filter != RequestFilter.Done) {
        SettingsGroup(stringResource(R.string.settings_requests_open_group, list.open.size)) {
            if (list.open.isEmpty()) {
                row { SettingsRow(title = stringResource(if (query.isBlank()) R.string.settings_requests_empty else R.string.settings_requests_no_match)) }
            }
            list.open.forEach { request -> row { RequestRow(request, viewModel) { deleting = request.number } } }
        }
    }
    if (list.done.isNotEmpty() || (filter == RequestFilter.Done)) {
        SettingsGroup(stringResource(R.string.settings_requests_done_group, list.done.size)) {
            if (list.done.isEmpty()) {
                row { SettingsRow(title = stringResource(if (query.isBlank()) R.string.settings_requests_empty else R.string.settings_requests_no_match)) }
            }
            list.done.forEach { request -> row { RequestRow(request, viewModel) { deleting = request.number } } }
            if (list.doneTotal > 0 && query.isBlank()) {
                row {
                    SettingsRow(
                        title = stringResource(R.string.settings_requests_clear_done),
                        icon = Icons.Outlined.ClearAll,
                        modifier = Modifier.clickable(role = Role.Button) { clearing = true },
                    )
                }
            }
        }
    }
    if (list.total > 0) {
        SettingsGroup(stringResource(R.string.settings_requests_export_group)) {
            row {
                SettingsRow(
                    title = shareTitle,
                    subtitle = stringResource(R.string.settings_requests_share_sub),
                    icon = Icons.Outlined.Share,
                    modifier = Modifier.clickable(role = Role.Button) { viewModel.share() },
                )
            }
        }
    }

    edit?.let { EditRequestDialog(it, viewModel) }
    detail?.let { d ->
        all.firstOrNull { it.number == d.number }?.let { r ->
            RequestDetailDialog(r, d, github.connected, viewModel, onDelete = { deleting = r.number })
        }
    }
    deleting?.let { n ->
        all.firstOrNull { it.number == n }?.let { r ->
            DeleteRequestDialog(
                request = r,
                githubConnected = github.connected,
                onConfirm = { closeIssue -> deleting = null; viewModel.delete(r, closeIssue) },
                onDismiss = { deleting = null },
            )
        } ?: run { deleting = null }
    }
    if (clearing) {
        ClearDoneDialog(count = list.doneTotal, onConfirm = { clearing = false; viewModel.clearDone() }, onDismiss = { clearing = false })
    }
}

private fun toast(context: android.content.Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

@Composable
private fun GitHubGroup(github: GitHubUiState, viewModel: RequestsViewModel) {
    SettingsGroup(stringResource(R.string.settings_requests_github_group)) {
        if (github.connected) {
            row {
                SettingsRow(
                    title = stringResource(R.string.settings_requests_github_connected, github.repo),
                    subtitle = stringResource(R.string.settings_requests_github_token, github.tokenHint.orEmpty()),
                    icon = Icons.Outlined.CloudDone,
                ) {
                    if (github.busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
            github.problem?.let { problem -> row { SettingsRow(title = problemText(problem), icon = Icons.Outlined.CloudOff) } }
            row {
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.M), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = viewModel::retry, enabled = !github.busy) {
                        Icon(Icons.Outlined.Sync, null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.settings_requests_sync), modifier = Modifier.padding(start = Spacing.S))
                    }
                    TextButton(onClick = viewModel::disconnect) { Text(stringResource(R.string.settings_requests_disconnect)) }
                }
            }
        } else {
            row {
                var token by rememberSaveable { mutableStateOf("") }
                var repo by rememberSaveable(github.repo) { mutableStateOf(github.repo) }
                Column(Modifier.fillMaxWidth().padding(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                    Text(
                        stringResource(R.string.settings_requests_github_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = BestNoteTheme.colors.muted,
                    )
                    OutlinedTextField(
                        value = repo,
                        onValueChange = { repo = it },
                        label = { Text(stringResource(R.string.settings_requests_github_repo)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text(stringResource(R.string.settings_requests_github_token_field)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    github.problem?.let { Text(problemText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = { viewModel.connect(token, repo) },
                        enabled = token.isNotBlank() && !github.busy,
                    ) { Text(stringResource(R.string.settings_requests_github_connect)) }
                }
            }
        }
    }
}

@Composable
private fun problemText(problem: SyncProblem): String = stringResource(
    when (problem) {
        SyncProblem.NoToken -> R.string.settings_requests_problem_token
        SyncProblem.BadToken -> R.string.settings_requests_problem_token
        SyncProblem.NoRepo -> R.string.settings_requests_problem_repo
        SyncProblem.Offline -> R.string.settings_requests_problem_offline
        SyncProblem.Other -> R.string.settings_requests_problem_other
    },
)

@Composable
private fun RequestRow(request: FeatureRequest, viewModel: RequestsViewModel, onDelete: () -> Unit) {
    val done = request.status == RequestStatus.Done
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(request.createdAt))
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { viewModel.openDetail(request) }
            .padding(start = Spacing.S, end = Spacing.XS, top = Spacing.S, bottom = Spacing.S),
        verticalAlignment = Alignment.Top,
    ) {
        IconButton(onClick = { viewModel.toggleDone(request) }) {
            Icon(
                if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = stringResource(if (done) R.string.settings_requests_reopen else R.string.settings_requests_mark_done),
                tint = if (done) MaterialTheme.colorScheme.primary else BestNoteTheme.colors.muted,
            )
        }
        Column(Modifier.weight(1f).padding(top = Spacing.S)) {
            Text(
                request.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (done) BestNoteTheme.colors.muted else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (done) TextDecoration.LineThrough else null,
            )
            Text(
                "${request.ref} · ${kindLabel(request.kind)} · $date",
                style = BestNoteTheme.type.caption,
                color = BestNoteTheme.colors.muted,
            )
            if (request.priority == RequestPriority.High && !done) {
                Text(stringResource(R.string.settings_requests_priority_high_flag), style = BestNoteTheme.type.caption, color = MaterialTheme.colorScheme.error)
            }
            if (request.details.isNotBlank()) {
                Text(
                    request.details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (request.attachments.isNotEmpty()) {
                val images = request.attachments.filter { it.isImage }
                val others = request.attachments.size - images.size
                Row(Modifier.padding(top = Spacing.S), horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalAlignment = Alignment.CenterVertically) {
                    images.take(3).forEach { Thumb(null, viewModel.fileOf(it), 48.dp) }
                    if (images.size > 3) Text("+${images.size - 3}", style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted)
                    if (others > 0) {
                        Icon(Icons.Outlined.AttachFile, null, modifier = Modifier.size(16.dp), tint = BestNoteTheme.colors.muted)
                        Text(
                            pluralStringResource(R.plurals.settings_requests_other_files, others, others),
                            style = BestNoteTheme.type.caption,
                            color = BestNoteTheme.colors.muted,
                        )
                    }
                }
            }
            Text(
                when {
                    request.issueNumber == null -> stringResource(R.string.settings_requests_not_sent)
                    request.unsynced -> stringResource(R.string.settings_requests_edit_pending)
                    else -> stringResource(R.string.settings_requests_sent, request.issueNumber)
                },
                style = BestNoteTheme.type.caption,
                color = if (request.issueNumber != null && !request.unsynced) MaterialTheme.colorScheme.primary else BestNoteTheme.colors.muted,
                modifier = Modifier.padding(top = Spacing.XS),
            )
        }
        IconButton(onClick = { viewModel.startEdit(request) }) {
            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.settings_requests_edit), tint = BestNoteTheme.colors.muted)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.settings_requests_delete), tint = BestNoteTheme.colors.muted)
        }
    }
}
