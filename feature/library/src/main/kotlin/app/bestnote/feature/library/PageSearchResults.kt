package app.bestnote.feature.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.hairline
import app.bestnote.core.designsystem.theme.softShadow
import app.bestnote.engine.recognition.ModelStatus
import app.bestnote.engine.recognition.ModelsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Under the results title while searching: the ask to download the handwriting models, until they are on the device. */
internal fun LazyListScope.handwritingModelsPrompt(state: LibraryUiState, callbacks: LibraryCallbacks) {
    if (!state.searching || state.models.isReady) return
    item(key = "models-card", contentType = "models") {
        HandwritingModelsCard(state.models, callbacks.downloadModels, Modifier.padding(bottom = Spacing.XL))
    }
}

/**
 * Below the title matches: the pages whose handwriting, typed text or PDF text match the query, as page
 * pictures with the matched words marked (REC-03).
 */
internal fun LazyListScope.pageSearchSection(state: LibraryUiState, columns: Int, callbacks: LibraryCallbacks) {
    if (state.pageResults.isEmpty()) return
    item(key = "pages-header", contentType = "section") {
        Text(
            pluralStringResource(R.plurals.library_page_results, state.pageResults.size, state.pageResults.size),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = Spacing.XXL, bottom = Spacing.L),
        )
    }
    val perRow = columns.coerceIn(MIN_PAGE_COLUMNS, MAX_PAGE_COLUMNS)
    val rows = state.pageResults.chunked(perRow)
    itemsIndexed(rows, key = { _, row -> "p-" + row.first().hit.notebookId.value + row.first().hit.pageId }, contentType = { _, _ -> "pages" }) { _, row ->
        Row(Modifier.fillMaxWidth().padding(bottom = Spacing.XL), horizontalArrangement = Arrangement.spacedBy(Spacing.XL)) {
            row.forEach { r ->
                PageResultCard(r, onClick = { callbacks.openPage(r.hit.notebookId, r.hit.pageId, state.controls.query.trim()) }, Modifier.weight(1f))
            }
            repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

private const val MIN_PAGE_COLUMNS = 2
private const val MAX_PAGE_COLUMNS = 4

@Composable
private fun androidx.compose.foundation.layout.RowScope.PageResultCard(result: PageResult, onClick: () -> Unit, modifier: Modifier) {
    val hit = result.hit
    val marker = MaterialTheme.colorScheme.primary
    val image by produceState<ImageBitmap?>(null, hit.thumbnailPath) {
        value = hit.thumbnailPath?.let { p -> withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(p)?.asImageBitmap() }.getOrNull() } }
    }
    val shape = BestNoteShapes.Control
    Column(modifier.clickable(role = Role.Button, onClick = onClick), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(hit.pageWidthPt / hit.pageHeightPt.coerceAtLeast(1f))
                .softShadow(Elevation.Low, shape)
                .clip(shape)
                .background(BestNoteTheme.colors.paper.page),
        ) {
            image?.let { Image(it, null, contentScale = ContentScale.FillBounds, modifier = Modifier.matchParentSize()) }
            Canvas(Modifier.matchParentSize()) {
                val sx = size.width / hit.pageWidthPt
                val sy = size.height / hit.pageHeightPt
                val pad = 2.dp.toPx()
                hit.highlights.forEach { r ->
                    val topLeft = Offset(r.left * sx - pad, r.top * sy - pad)
                    val box = Size(r.width * sx + 2 * pad, r.height * sy + 2 * pad)
                    drawRoundRect(marker.copy(alpha = HIGHLIGHT_FILL), topLeft, box, CornerRadius(3.dp.toPx()))
                    drawRoundRect(marker.copy(alpha = HIGHLIGHT_LINE), topLeft, box, CornerRadius(3.dp.toPx()), Stroke(1.dp.toPx()))
                }
            }
        }
        Text(result.notebookTitle, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            stringResource(R.string.library_page_number, hit.pageIndex + 1),
            style = BestNoteTheme.type.caption,
            color = BestNoteTheme.colors.muted,
            maxLines = 1,
        )
        if (hit.snippet.isNotBlank()) {
            Text(hit.snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private const val HIGHLIGHT_FILL = 0.28f
private const val HIGHLIGHT_LINE = 0.85f

/** The ask to download the Arabic and English handwriting models, its progress, and a retry when it failed. */
@Composable
internal fun HandwritingModelsCard(models: ModelsState, onDownload: () -> Unit, modifier: Modifier = Modifier) {
    val shape = BestNoteShapes.Control
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .hairline(shape)
            .padding(Spacing.L),
        verticalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
            Icon(Icons.Outlined.CloudDownload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.XS)) {
                Text(stringResource(R.string.library_models_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    stringResource(if (models.status == ModelStatus.Failed) R.string.library_models_failed else R.string.library_models_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (models.status != ModelStatus.Downloading) {
                Button(onClick = onDownload) {
                    Text(stringResource(if (models.status == ModelStatus.Failed) R.string.library_models_retry else R.string.library_models_download))
                }
            }
        }
        if (models.status == ModelStatus.Downloading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.library_models_downloading), style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted)
        }
    }
}
