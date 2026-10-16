package app.bestnote.feature.editor

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.bestnote.ai.vision.AiBlock
import app.bestnote.ai.vision.AiDocument
import app.bestnote.ai.vision.DemoVisionProvider
import app.bestnote.ai.vision.VisionError
import app.bestnote.ai.vision.VisionImage
import app.bestnote.ai.vision.providers.AiProviders
import app.bestnote.core.model.PageSize
import app.bestnote.engine.canvas.document.PagedDocument
import app.bestnote.engine.canvas.document.PagedDocumentView
import app.bestnote.engine.recognition.HandwritingModels
import app.bestnote.engine.recognition.HandwritingRecognizer
import app.bestnote.engine.recognition.Transcriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * AI-08 "Tidy" (Beautify selection): the lasso's handwriting is read and written back in place as
 * neat handwriting, replacing the selection in ONE undo step. Reading uses the Smart Board provider
 * from Settings (the selection image goes to it with the user's key); without one it falls back to
 * the on-device handwriting models when they are downloaded. Cancellable while it works.
 */
internal class InkTidier(
    private val scope: CoroutineScope,
    private val canvas: () -> PagedDocumentView?,
    private val document: PagedDocument,
    private val models: HandwritingModels,
    private val recognizer: HandwritingRecognizer,
    private val ai: AiProviders?,
    private val adopt: (List<Pair<app.bestnote.core.model.InkStroke, androidx.ink.strokes.Stroke>>) -> Unit,
    private val debuggable: Boolean,
    /** The notebook's Smart Board notes language (null = keep the handwriting's own). */
    private val language: () -> String?,
    private val message: (Int) -> Unit,
) {
    var working by mutableStateOf(false)
        private set

    private var job: Job? = null

    private val measure by lazy { PaintMeasure() }

    fun tidy() {
        val selected = canvas()?.selectedInk() ?: return
        job?.cancel()
        working = true
        job = scope.launch {
            try {
                val doc = read(selected) ?: return@launch
                val page = document.pages.getOrNull(selected.pageIndex) ?: return@launch
                val color = selected.strokes.groupingBy { InkConversion.brushFromInk(it.brush).colorArgb }.eachCount().maxByOrNull { it.value }?.key
                    ?: app.bestnote.engine.handwriting.PenChoice.INK
                val (written, pairs) = withContext(Dispatchers.Default) {
                    val paper = page.page
                    val out = TidyWriter.write(doc, selected.box, paper.template, PageSize(paper.widthPt, paper.heightPt), color or OPAQUE, measure) { UUID.randomUUID().toString() }
                    out to out.strokes.mapNotNull { model -> runCatching { model to InkConversion.toInk(model) }.getOrNull() }
                }
                if (written.elements.isEmpty() && pairs.isEmpty()) {
                    message(R.string.tidy_nothing)
                    return@launch
                }
                canvas()?.clearSelection()
                adopt(pairs)
                // Ink out, neat text (and its pen marks) in: one undo step.
                document.editContent(
                    selected.pageIndex,
                    removedStrokes = selected.strokes,
                    addedStrokes = pairs.map { it.second },
                    elements = page.elements + written.elements,
                )
                message(R.string.tidy_done)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                android.util.Log.w("BestNoteTidy", "Tidy failed: ${e.javaClass.simpleName}")
                message(
                    when (e) {
                        is VisionError.InvalidKey, is VisionError.MissingKey -> R.string.tidy_error_key
                        is VisionError.RateLimited -> R.string.tidy_error_quota
                        is VisionError.Network, is java.io.IOException -> R.string.tidy_error_network
                        is VisionError.ServiceUnavailable -> R.string.tidy_error_busy
                        else -> R.string.tidy_error_other
                    },
                )
            } finally {
                working = false
            }
        }
    }

    fun cancel() {
        job?.cancel()
        working = false
    }

    /** The selection's content: AI provider, else on-device recognition, else (debug builds) the offline demo. */
    private suspend fun read(selected: PagedDocumentView.SelectedInk): AiDocument? {
        val providers = ai
        if (providers != null && runCatching { providers.isConfigured() }.getOrDefault(false)) {
            val png = withContext(Dispatchers.Default) { InkSnapshot.png(selected.strokes) } ?: return null.also { message(R.string.tidy_nothing) }
            return providers.create().beautify(VisionImage(png, "image/png"), selected.box.width, selected.box.height, language())
        }
        if (models.state.value.isReady) {
            val samples = withContext(Dispatchers.Default) { selected.strokes.map(InkConversion::samplesOf) }
            val transcript = withContext(Dispatchers.Default) { Transcriber(recognizer).transcribe(samples, null) }
            if (transcript.isEmpty) return null.also { message(R.string.tidy_nothing) }
            return AiDocument(blocks = transcript.lines.filter { it.isNotBlank() }.mapIndexed { i, line -> AiBlock(type = "paragraph", order = i, text = line) })
        }
        if (debuggable) return DemoVisionProvider(latencyMs = DEMO_MS).beautify(VisionImage(ByteArray(0), "image/png"), selected.box.width, selected.box.height)
        message(R.string.tidy_needs_reader)
        return null
    }

    @Composable
    fun Dialogs() {
        if (!working) return
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.tidy_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text(stringResource(R.string.tidy_working))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(::cancel) { Text(stringResource(R.string.dialog_cancel)) } },
        )
    }

    private companion object {
        const val OPAQUE = 0xFF000000.toInt()
        const val DEMO_MS = 900L
    }
}

@Composable
internal fun rememberInkTidier(
    canvas: () -> PagedDocumentView?,
    document: PagedDocument,
    models: HandwritingModels,
    recognizer: HandwritingRecognizer,
    ai: AiProviders?,
    adopt: (List<Pair<app.bestnote.core.model.InkStroke, androidx.ink.strokes.Stroke>>) -> Unit,
    snackbar: SnackbarHostState,
    language: () -> String? = { null },
): InkTidier {
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val context = LocalContext.current
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    return remember(document, models, recognizer) {
        app.bestnote.core.designsystem.font.HandwritingTypefaces.init(context)
        InkTidier(scope, canvas, document, models, recognizer, ai, adopt, debuggable, language) { id ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(resources.getString(id))
            }
        }
    }
}
