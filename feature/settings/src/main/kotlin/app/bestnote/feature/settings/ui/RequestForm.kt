package app.bestnote.feature.settings.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.feature.settings.R
import app.bestnote.feature.settings.data.RequestKind
import app.bestnote.feature.settings.data.RequestPriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** What the form needs to draw one attached file; the same shape for new, kept and removed files. */
internal class FormFile(
    val key: Any,
    val name: String,
    val mime: String,
    val size: Long,
    /** Bytes in memory (just picked) or [file] on disk (already stored); one of them for images. */
    val bytes: ByteArray? = null,
    val file: File? = null,
    /** Taken off in an edit but not saved yet: shown struck through with an undo. */
    val removed: Boolean = false,
    val onToggle: () -> Unit,
) {
    val isImage get() = mime.startsWith("image/")
}

/** Type and priority pickers, title, details, attachments. Used for a new request and for editing one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RequestForm(
    kind: RequestKind,
    priority: RequestPriority,
    title: String,
    details: String,
    files: List<FormFile>,
    onKind: (RequestKind) -> Unit,
    onPriority: (RequestPriority) -> Unit,
    onTitle: (String) -> Unit,
    onDetails: (String) -> Unit,
    onPick: (List<android.net.Uri>) -> Unit,
    canAddMore: Boolean,
    modifier: Modifier = Modifier,
) {
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { onPick(it) }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onPick(it) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
        Segmented(options = RequestKind.entries, selected = kind, label = { kindLabel(it) }, onSelect = onKind)
        Segmented(options = RequestPriority.entries, selected = priority, label = { priorityLabel(it) }, onSelect = onPriority)
        OutlinedTextField(
            value = title,
            onValueChange = onTitle,
            label = { Text(stringResource(R.string.settings_requests_title_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = details,
            onValueChange = onDetails,
            label = { Text(stringResource(R.string.settings_requests_details_hint)) },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
            files.forEach { FormFileItem(it) }
        }
        if (canAddMore) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                OutlinedButton(onClick = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.settings_requests_add_photos), modifier = Modifier.padding(start = Spacing.S))
                }
                OutlinedButton(onClick = { documents.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Outlined.AttachFile, null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.settings_requests_add_files), modifier = Modifier.padding(start = Spacing.S))
                }
            }
        }
    }
}

@Composable
private fun FormFileItem(file: FormFile) {
    if (file.isImage) {
        Box(Modifier.size(ThumbSize)) {
            Thumb(file.bytes, file.file, ThumbSize, dim = file.removed)
            RemoveButton(file, Modifier.align(Alignment.TopEnd))
        }
    } else {
        Row(
            Modifier
                .widthIn(max = 260.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tileBackground())
                .padding(start = Spacing.M, top = 2.dp, bottom = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            Icon(Icons.Outlined.InsertDriveFile, null, modifier = Modifier.size(18.dp), tint = BestNoteTheme.colors.muted)
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (file.removed) TextDecoration.LineThrough else null,
                    color = if (file.removed) BestNoteTheme.colors.muted else MaterialTheme.colorScheme.onSurface,
                )
                Text(formatSize(file.size), style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted)
            }
            IconButton(onClick = file.onToggle, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (file.removed) Icons.Outlined.Undo else Icons.Outlined.Close,
                    contentDescription = stringResource(if (file.removed) R.string.settings_requests_restore_file else R.string.settings_requests_remove_file),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun RemoveButton(file: FormFile, modifier: Modifier) {
    IconButton(
        onClick = file.onToggle,
        modifier = modifier.size(24.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(12.dp)),
    ) {
        Icon(
            if (file.removed) Icons.Outlined.Undo else Icons.Outlined.Close,
            contentDescription = stringResource(if (file.removed) R.string.settings_requests_restore_file else R.string.settings_requests_remove_file),
            modifier = Modifier.size(14.dp),
        )
    }
}

internal val ThumbSize = 64.dp

/** A cropped square preview of an image held in memory or stored on disk. */
@Composable
internal fun Thumb(bytes: ByteArray?, file: File?, size: Dp, dim: Boolean = false, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, bytes, file) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeThumb(bytes ?: file!!.readBytes()) }.getOrNull()
        }
    }
    Box(modifier.size(size).clip(RoundedCornerShape(8.dp)).background(tileBackground())) {
        bitmap?.let {
            Image(
                it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = if (dim) 0.35f else 1f,
                modifier = Modifier.size(size),
            )
        }
    }
}

private fun decodeThumb(bytes: ByteArray): ImageBitmap? {
    val options = BitmapFactory.Options().apply { inSampleSize = 4 }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(Locale.ROOT, bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

@Composable
internal fun kindLabel(kind: RequestKind): String = stringResource(
    when (kind) {
        RequestKind.Feature -> R.string.settings_requests_kind_feature
        RequestKind.Improvement -> R.string.settings_requests_kind_improvement
        RequestKind.Bug -> R.string.settings_requests_kind_bug
    },
)

@Composable
internal fun priorityLabel(priority: RequestPriority): String = stringResource(
    when (priority) {
        RequestPriority.Low -> R.string.settings_requests_priority_low
        RequestPriority.Normal -> R.string.settings_requests_priority_normal
        RequestPriority.High -> R.string.settings_requests_priority_high
    },
)
