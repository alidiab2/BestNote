package app.bestnote.feature.settings.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import app.bestnote.feature.settings.DraftImage
import app.bestnote.feature.settings.R
import app.bestnote.feature.settings.RequestEvent
import app.bestnote.feature.settings.RequestsViewModel
import app.bestnote.feature.settings.data.FeatureRequest
import app.bestnote.feature.settings.data.RequestKind
import app.bestnote.feature.settings.data.RequestStatus
import app.bestnote.feature.settings.data.SyncProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private val ThumbSize = 64.dp

/**
 * Settings, Requests: write down a feature, improvement or bug, with screenshots. Every request is saved on
 * the device first. With a GitHub token set it is also sent as an issue (screenshots included) to a private
 * repo, and closing the issue marks the request done here. Without a token nothing leaves the device.
 */
@Composable
internal fun RequestsSection(viewModel: RequestsViewModel) {
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val github by viewModel.github.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val saved = stringResource(R.string.settings_requests_saved)
    val savedSent = stringResource(R.string.settings_requests_saved_sent)
    val savedLocal = stringResource(R.string.settings_requests_saved_not_sent)
    val connectedMsg = stringResource(R.string.settings_requests_connected)
    val shareTitle = stringResource(R.string.settings_requests_share)
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(RequestsViewModel.MAX_IMAGES),
    ) { uris -> viewModel.addImages(uris) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is RequestEvent.Saved -> Toast.makeText(
                    context,
                    when {
                        event.sent -> savedSent
                        github.connected -> savedLocal
                        else -> saved
                    },
                    Toast.LENGTH_SHORT,
                ).show()
                RequestEvent.Connected -> Toast.makeText(context, connectedMsg, Toast.LENGTH_SHORT).show()
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
                Segmented(
                    options = RequestKind.entries,
                    selected = draft.kind,
                    label = { kindLabel(it) },
                    onSelect = viewModel::setKind,
                )
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = viewModel::setTitle,
                    label = { Text(stringResource(R.string.settings_requests_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.details,
                    onValueChange = viewModel::setDetails,
                    label = { Text(stringResource(R.string.settings_requests_details_hint)) },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    draft.images.forEach { DraftThumb(it) { viewModel.removeImage(it) } }
                    if (draft.images.size < RequestsViewModel.MAX_IMAGES) {
                        OutlinedButton(
                            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        ) {
                            Icon(Icons.Outlined.AddPhotoAlternate, null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.settings_requests_add_image), modifier = Modifier.padding(start = Spacing.S))
                        }
                    }
                }
                Button(onClick = viewModel::send, enabled = draft.canSend && !github.busy) {
                    Text(stringResource(if (github.connected) R.string.settings_requests_save_send else R.string.settings_requests_save))
                }
            }
        }
    }

    GitHubGroup(github, viewModel)

    val open = requests.filter { it.status == RequestStatus.Open }.sortedByDescending { it.number }
    val done = requests.filter { it.status == RequestStatus.Done }.sortedByDescending { it.number }

    SettingsGroup(stringResource(R.string.settings_requests_open_group, open.size)) {
        if (open.isEmpty()) {
            row { SettingsRow(title = stringResource(R.string.settings_requests_empty)) }
        }
        open.forEach { request -> row { RequestRow(request, viewModel) } }
    }
    if (done.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.settings_requests_done_group, done.size)) {
            done.forEach { request -> row { RequestRow(request, viewModel) } }
        }
    }
    if (requests.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.settings_requests_export_group)) {
            row {
                SettingsRow(
                    title = shareTitle,
                    subtitle = stringResource(R.string.settings_requests_share_sub),
                    icon = Icons.Outlined.Share,
                    modifier = Modifier.clickable(role = Role.Button, onClick = viewModel::share),
                )
            }
        }
    }
}

@Composable
private fun GitHubGroup(github: app.bestnote.feature.settings.GitHubUiState, viewModel: RequestsViewModel) {
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
private fun DraftThumb(image: DraftImage, onRemove: () -> Unit) {
    val bitmap = remember(image) { decodeThumb(image.bytes) }
    Box(Modifier.size(ThumbSize)) {
        if (bitmap != null) Thumb(bitmap)
        IconButton(onClick = onRemove, modifier = Modifier.size(24.dp).align(Alignment.TopEnd).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(12.dp))) {
            Icon(Icons.Outlined.Close, stringResource(R.string.settings_requests_remove_image), modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun Thumb(bitmap: ImageBitmap, modifier: Modifier = Modifier) {
    Image(
        bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(ThumbSize).clip(RoundedCornerShape(8.dp)),
    )
}

@Composable
private fun StoredThumb(file: File) {
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { decodeThumb(file.readBytes()) }.getOrNull() }
    }
    bitmap?.let { Thumb(it) }
}

private fun decodeThumb(bytes: ByteArray): ImageBitmap? {
    val options = BitmapFactory.Options().apply { inSampleSize = 4 }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

@Composable
private fun RequestRow(request: FeatureRequest, viewModel: RequestsViewModel) {
    val context = LocalContext.current
    val done = request.status == RequestStatus.Done
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(request.createdAt))
    Row(
        Modifier.fillMaxWidth().padding(start = Spacing.L, end = Spacing.S, top = Spacing.S, bottom = Spacing.S),
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
            if (request.details.isNotBlank()) {
                Text(
                    request.details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (request.images.isNotEmpty()) {
                Row(Modifier.padding(top = Spacing.S), horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                    request.images.forEach { StoredThumb(viewModel.imageFile(it)) }
                }
            }
            val url = request.issueUrl
            Text(
                if (request.issueNumber != null) stringResource(R.string.settings_requests_sent, request.issueNumber) else stringResource(R.string.settings_requests_not_sent),
                style = BestNoteTheme.type.caption,
                color = if (request.issueNumber != null) MaterialTheme.colorScheme.primary else BestNoteTheme.colors.muted,
                modifier = Modifier
                    .padding(top = Spacing.XS)
                    .then(if (url != null) Modifier.clickable(role = Role.Button) { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } else Modifier),
            )
        }
        IconButton(onClick = { viewModel.delete(request) }) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.settings_requests_delete), tint = BestNoteTheme.colors.muted)
        }
    }
}

@Composable
private fun kindLabel(kind: RequestKind): String = stringResource(
    when (kind) {
        RequestKind.Feature -> R.string.settings_requests_kind_feature
        RequestKind.Improvement -> R.string.settings_requests_kind_improvement
        RequestKind.Bug -> R.string.settings_requests_kind_bug
    },
)
