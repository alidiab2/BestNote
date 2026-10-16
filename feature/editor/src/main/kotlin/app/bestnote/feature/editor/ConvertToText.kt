package app.bestnote.feature.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.AutoAwesome
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
import app.bestnote.ai.vision.VisionError
import app.bestnote.ai.vision.VisionImage
import app.bestnote.ai.vision.providers.AiProviders
import app.bestnote.ai.vision.transcriptLines
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
    /** The Smart Board provider from Settings, for "Convert with AI"; null hides the option. */
    private val ai: AiProviders?,
    private val message: suspend (Int) -> Unit,
) {
    enum class Step { Idle, NeedsModels, Downloading, DownloadFailed, Reading, Confirm }

    /** Which language to read: null = pick per line from how it was written. */
    var language by mutableStateOf<String?>(null)
        private set

    /** The reading shown for confirmation; the user may edit it before it becomes a text box. */
    var draft by mutableStateOf("")

    var step by mutableStateOf(Step.Idle)
        private set

    /** An AI provider is configured in Settings → Smart Board AI. */
    var aiAvailable by mutableStateOf(false)
        private set

    /** The draft came from the AI provider (shown so the user knows which reading they see). */
    var aiReading by mutableStateOf(false)
        private set

    private var pending: PagedDocumentView.SelectedInk? = null
    private var samples: List<List<app.bestnote.core.model.StylusSample>> = emptyList()

    fun convert() {
        val selected = canvas()?.selectedInk() ?: return
        pending = selected
        language = null
        aiReading = false
        scope.launch { aiAvailable = runCatching { ai?.isConfigured() == true }.getOrDefault(false) }
        if (models.state.value.isReady) read(selected) else step = Step.NeedsModels
    }

    /**
     * "Convert with AI" (REC-05): the selection, drawn as black ink on white, goes to the user's own
     * AI provider, which reads Arabic handwriting far better than the on-device model.
     */
    fun convertWithAi() {
        val selected = pending ?: return
        val providers = ai ?: return
        val before = step
        step = Step.Reading
        scope.launch {
            try {
                val png = withContext(Dispatchers.Default) { InkSnapshot.png(selected.strokes) }
                if (png == null) {
                    message(R.string.convert_nothing)
                    step = before
                    return@launch
                }
                val doc = providers.create().transcribe(VisionImage(png, "image/png"), languageName(language))
                val lines = doc.transcriptLines()
                if (lines.isEmpty()) {
                    message(R.string.convert_ai_nothing)
                    step = if (before == Step.Confirm) Step.Confirm else before
                } else {
                    draft = lines.joinToString("\n")
                    aiReading = true
                    step = Step.Confirm
                }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                android.util.Log.w("BestNoteConvert", "Convert with AI failed: ${e.javaClass.simpleName}")
                message(if (e is VisionError.InvalidKey || e is VisionError.MissingKey) R.string.convert_ai_key else R.string.convert_ai_failed)
                step = before
            }
        }
    }

    private fun languageName(tag: String?) = when (tag) {
        "ar" -> "Arabic"
        "he" -> "Hebrew"
        "en-US" -> "English"
        else -> null
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
        samples = emptyList()
    }

    /** Re-reads the selection in [tag] (null = automatic), e.g. from the language chips in the confirmation. */
    fun readAs(tag: String?) {
        // Hebrew is read by the AI provider (the on-device models cover Arabic and English).
        if (tag == "he") {
            language = tag
            if (aiAvailable) convertWithAi() else scope.launch { message(R.string.convert_hebrew_needs_ai) }
            return
        }
        language = tag
        aiReading = false
        pending?.let(::read)
    }

    private fun read(selected: PagedDocumentView.SelectedInk) {
        step = Step.Reading
        scope.launch {
            try {
                if (samples.isEmpty()) samples = withContext(Dispatchers.Default) { selected.strokes.map(InkConversion::samplesOf) }
                val transcript = withContext(Dispatchers.Default) { Transcriber(recognizer).transcribe(samples, language) }
                if (transcript.isEmpty && language == null) {
                    message(R.string.convert_nothing)
                    dismiss()
                } else {
                    draft = transcript.text
                    step = Step.Confirm
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    /** Puts the (edited) [draft] on the page in place of the selected ink. */
    fun insert() {
        val selected = pending
        val lines = draft.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        if (selected != null && lines.isNotEmpty()) replaceWithText(selected, lines)
        dismiss()
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
                dismissButton = {
                    androidx.compose.foundation.layout.Row {
                        if (aiAvailable) TextButton(::convertWithAi) { Text(stringResource(R.string.convert_with_ai)) }
                        TextButton(::dismiss) { Text(stringResource(R.string.dialog_cancel)) }
                    }
                },
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
            Step.Confirm -> AlertDialog(
                onDismissRequest = ::dismiss,
                title = { Text(title) },
                text = {
                    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                            LanguageChip(R.string.convert_lang_auto, language == null) { readAs(null) }
                            LanguageChip(R.string.convert_lang_ar, language == "ar") { readAs("ar") }
                            LanguageChip(R.string.convert_lang_en, language == "en-US") { readAs("en-US") }
                            LanguageChip(R.string.convert_lang_he, language == "he") { readAs("he") }
                        }
                        if (aiAvailable) {
                            androidx.compose.material3.FilterChip(
                                selected = aiReading,
                                onClick = ::convertWithAi,
                                label = { Text(stringResource(R.string.convert_with_ai)) },
                                leadingIcon = {
                                    androidx.compose.material3.Icon(
                                        androidx.compose.material.icons.Icons.Rounded.AutoAwesome,
                                        null,
                                        androidx.compose.ui.Modifier.size(18.dp),
                                    )
                                },
                            )
                        }
                        // Arabic reads right to left: the field follows the text, not the UI language.
                        val rtl = InkConversion.isMostlyRtl(draft)
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.ui.platform.LocalLayoutDirection provides
                                if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
                        ) {
                            androidx.compose.material3.OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                label = { Text(stringResource(R.string.convert_review)) },
                                placeholder = { Text(stringResource(R.string.convert_nothing)) },
                                textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                                    textDirection = if (rtl) androidx.compose.ui.text.style.TextDirection.Rtl else androidx.compose.ui.text.style.TextDirection.Content,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                                ),
                                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                            )
                        }
                    }
                },
                confirmButton = { TextButton(::insert, enabled = draft.isNotBlank()) { Text(stringResource(R.string.convert_insert)) } },
                dismissButton = { TextButton(::dismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
            Step.Idle -> Unit
        }
    }

    @Composable
    private fun LanguageChip(label: Int, selected: Boolean, onClick: () -> Unit) {
        androidx.compose.material3.FilterChip(selected = selected, onClick = onClick, label = { Text(stringResource(label)) })
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
    ai: AiProviders? = null,
): InkConverter {
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    return remember(document, models, recognizer) {
        InkConverter(scope, canvas, document, models, recognizer, onSelecting, ai) { id ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(resources.getString(id))
        }
    }
}
