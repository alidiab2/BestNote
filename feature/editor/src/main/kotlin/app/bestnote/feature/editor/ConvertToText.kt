package app.bestnote.feature.editor

import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import app.bestnote.core.model.PageElement
import app.bestnote.core.model.TextElement
import app.bestnote.engine.canvas.document.PagedDocument
import app.bestnote.engine.canvas.document.PagedDocumentView
import app.bestnote.engine.recognition.HandwritingModels
import app.bestnote.engine.recognition.HandwritingRecognizer
import app.bestnote.engine.recognition.ModelStatus
import app.bestnote.engine.recognition.Transcriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lasso → "Convert to text" (REC-05): reads the selected handwriting, line by line, in Arabic or English,
 * and replaces the ink with a text box in one undo step. The first time, it asks to download the on-device
 * models and carries on by itself once they arrive.
 */
internal class InkConverter(
    private val scope: CoroutineScope,
    private val canvas: () -> PagedDocumentView?,
    private val document: PagedDocument,
    private val models: HandwritingModels,
    private val recognizer: HandwritingRecognizer,
    private val onSelecting: () -> Unit,
    private val message: suspend (Int) -> Unit,
) {
    enum class Step { Idle, NeedsModels, Downloading, DownloadFailed, Reading }

    var step by mutableStateOf(Step.Idle)
        private set

    private var pending: PagedDocumentView.SelectedInk? = null

    fun convert() {
        val selected = canvas()?.selectedInk() ?: return
        pending = selected
        if (models.state.value.isReady) read(selected) else step = Step.NeedsModels
    }

    fun download() {
        val selected = pending ?: return
        step = Step.Downloading
        models.download()
        scope.launch {
            val done = models.state.first { it.status == ModelStatus.Ready || it.status == ModelStatus.Failed }
            if (done.status == ModelStatus.Ready) read(selected) else step = Step.DownloadFailed
        }
    }

    fun dismiss() {
        step = Step.Idle
        pending = null
    }

    private fun read(selected: PagedDocumentView.SelectedInk) {
        step = Step.Reading
        scope.launch {
            try {
                val transcript = withContext(Dispatchers.Default) {
                    Transcriber(recognizer).transcribe(selected.strokes.map(InkConversion::samplesOf))
                }
                if (transcript.isEmpty) {
                    message(R.string.convert_nothing)
                } else {
                    replaceWithText(selected, transcript.lines)
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                dismiss()
            }
        }
    }

    private fun replaceWithText(selected: PagedDocumentView.SelectedInk, lines: List<String>) {
        val view = canvas() ?: return
        val page = document.pages.getOrNull(selected.pageIndex) ?: return
        val box = selected.box
        val size = (box.height / lines.size * LINE_TO_FONT).coerceIn(MIN_FONT_PT, MAX_FONT_PT)
        val width = box.width.coerceAtLeast(MIN_WIDTH_PT)
        val color = selected.strokes.firstOrNull()?.let { InkConversion.brushFromInk(it.brush).colorArgb } ?: TextElement.DEFAULT_COLOR
        val text = TextElement(
            PageElement.newId(),
            box.centerX,
            box.centerY,
            width,
            size * LINE_HEIGHT * lines.size,
            lines.joinToString("\n"),
            sizePt = size,
            colorArgb = color or OPAQUE,
        )
        val fitted = view.fitted(text)
        document.editContent(selected.pageIndex, removedStrokes = selected.strokes, elements = page.elements + fitted)
        view.selectElement(selected.pageIndex, fitted.id)
        onSelecting()
    }

    @Composable
    fun Dialogs() {
        if (step == Step.Idle) return
        val title = stringResource(R.string.convert_title)
        when (step) {
            Step.NeedsModels, Step.DownloadFailed -> AlertDialog(
                onDismissRequest = ::dismiss,
                title = { Text(title) },
                text = { Text(stringResource(if (step == Step.DownloadFailed) R.string.convert_failed else R.string.convert_models_needed)) },
                confirmButton = { TextButton(::download) { Text(stringResource(if (step == Step.DownloadFailed) R.string.convert_retry else R.string.convert_download)) } },
                dismissButton = { TextButton(::dismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
            Step.Downloading, Step.Reading -> AlertDialog(
                onDismissRequest = {},
                title = { Text(title) },
                text = {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(androidx.compose.ui.Modifier.size(28.dp))
                        Text(stringResource(if (step == Step.Downloading) R.string.convert_downloading else R.string.convert_reading))
                    }
                },
                confirmButton = {},
                dismissButton = if (step == Step.Downloading) {
                    { TextButton(::dismiss) { Text(stringResource(R.string.dialog_cancel)) } }
                } else {
                    null
                },
            )
            Step.Idle -> Unit
        }
    }

    private companion object {
        const val LINE_TO_FONT = 0.62f
        const val LINE_HEIGHT = 1.3f
        const val MIN_FONT_PT = 12f
        const val MAX_FONT_PT = 36f
        const val MIN_WIDTH_PT = 120f
        const val OPAQUE = 0xFF000000.toInt()
    }
}

@Composable
internal fun rememberInkConverter(
    canvas: () -> PagedDocumentView?,
    document: PagedDocument,
    models: HandwritingModels,
    recognizer: HandwritingRecognizer,
    onSelecting: () -> Unit,
    snackbar: SnackbarHostState,
): InkConverter {
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    return remember(document, models, recognizer) {
        InkConverter(scope, canvas, document, models, recognizer, onSelecting) { id ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(resources.getString(id))
        }
    }
}
