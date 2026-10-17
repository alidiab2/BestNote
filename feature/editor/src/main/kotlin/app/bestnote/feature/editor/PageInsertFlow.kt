package app.bestnote.feature.editor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.bestnote.core.designsystem.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Insert pages from a PDF or an image" (PAGES-05): picks the file, reads it off the main thread with progress
 * and Cancel, asks which pages and where, then inserts them as ONE undo step. One instance per editor; the
 * paper menu and the page manager both call [startPdf] / [startImage] and the editor shows [Dialogs].
 */
internal class PageInsertFlow(
    private val context: Context,
    private val scope: CoroutineScope,
    private val document: app.bestnote.engine.canvas.document.PagedDocument,
    private val session: DocumentSession,
    private val currentPage: () -> Int,
) {
    sealed interface Stage {
        data object None : Stage
        data object ChooseImage : Stage
        data class Working(val labelRes: Int, val progress: Float?) : Stage
        data class PdfOptions(val staged: StagedPdf) : Stage
        data class ImageOptions(val pictures: List<ImportedImage>) : Stage
        data class Failed(val messageRes: Int) : Stage
    }

    var stage by mutableStateOf<Stage>(Stage.None)
        private set

    /** The page "after current" counts from. */
    private var anchor = 0
    private var job: Job? = null
    private var cameraUri: Uri? = null

    /** Shows the result ("3 pages inserted"); the page manager swaps in its Undo snackbar. */
    var notify: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }

    /** Called with the first inserted page's index after a successful insert. */
    var onInserted: (Int) -> Unit = {}

    internal var launchPdfPicker: () -> Unit = {}
    internal var launchPhotoPicker: () -> Unit = {}
    internal var launchFilePicker: () -> Unit = {}
    internal var launchCamera: (Uri) -> Unit = {}

    /** Pick a PDF; [after] is the page "after current" counts from (default: the page being read). */
    fun startPdf(after: Int = currentPage()) {
        anchor = after
        launchPdfPicker()
    }

    /** Offer Photos / Camera / Files. */
    fun startImage(after: Int = currentPage()) {
        anchor = after
        stage = Stage.ChooseImage
    }

    fun fromPhotos() { stage = Stage.None; launchPhotoPicker() }
    fun fromFiles() { stage = Stage.None; launchFilePicker() }
    fun fromCamera() {
        stage = Stage.None
        val file = File(File(context.cacheDir, "exports/camera").apply { mkdirs() }, "page.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        cameraUri = uri
        launchCamera(uri)
    }

    fun cameraDone(ok: Boolean) {
        val uri = cameraUri ?: return
        if (ok) loadImages(listOf(uri), deleteAfter = File(File(context.cacheDir, "exports/camera"), "page.jpg"))
    }

    fun pdfPicked(uri: Uri) {
        job?.cancel()
        stage = Stage.Working(R.string.insert_reading_pdf, 0f)
        job = scope.launch {
            try {
                val total = withContext(Dispatchers.IO) { sizeOf(uri) }
                val staged = session.stagePdf(total, { context.contentResolver.openInputStream(uri) }) { p ->
                    stage = Stage.Working(R.string.insert_reading_pdf, p)
                }
                stage = Stage.PdfOptions(staged)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PdfInsertException) {
                stage = Stage.Failed(
                    when (e.reason) {
                        PdfInsertException.Reason.Password -> R.string.insert_err_password
                        PdfInsertException.Reason.Empty -> R.string.insert_err_empty
                        PdfInsertException.Reason.TooLarge -> R.string.insert_err_large
                        PdfInsertException.Reason.Unreadable -> R.string.insert_err_unreadable
                    },
                )
            }
        }
    }

    fun imagesPicked(uris: List<Uri>) {
        if (uris.isNotEmpty()) loadImages(uris)
    }

    private fun loadImages(uris: List<Uri>, deleteAfter: File? = null) {
        job?.cancel()
        stage = Stage.Working(R.string.insert_reading_image, 0f)
        job = scope.launch {
            try {
                val loaded = ArrayList<ImportedImage>()
                uris.forEachIndexed { i, uri ->
                    ImageImport.load(context, uri)?.let(loaded::add)
                    stage = Stage.Working(R.string.insert_reading_image, (i + 1f) / uris.size)
                }
                stage = if (loaded.isEmpty()) Stage.Failed(R.string.image_failed) else Stage.ImageOptions(loaded)
            } finally {
                deleteAfter?.delete()
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        (stage as? Stage.PdfOptions)?.let { session.discard(it.staged) }
        stage = Stage.None
    }

    fun insertPdf(staged: StagedPdf, indices: List<Int>, position: InsertPosition) {
        val at = InsertPlan.insertIndex(position, anchor, document.size)
        val added = session.insertPdf(staged, indices, at)
        stage = Stage.None
        done(added)
    }

    fun insertImages(pictures: List<ImportedImage>, position: InsertPosition) {
        val at = InsertPlan.insertIndex(position, anchor, document.size)
        val added = session.insertImagePages(pictures, at)
        stage = Stage.None
        done(added)
    }

    private fun done(added: List<Int>) {
        if (added.isEmpty()) return
        notify(context.resources.getQuantityString(R.plurals.insert_done, added.size, added.size))
        onInserted(added.first())
    }

    internal fun anchorPage(): Int = anchor
    internal fun pageCount(): Int = document.size

    private fun sizeOf(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        }
    }.getOrNull() ?: -1L

    @Composable
    fun Dialogs() {
        when (val s = stage) {
            Stage.None -> Unit
            Stage.ChooseImage -> AlertDialog(
                onDismissRequest = ::cancel,
                title = { Text(stringResource(R.string.insert_from_image)) },
                text = {
                    Column {
                        TextButton(onClick = ::fromPhotos, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.image_photos)) }
                        TextButton(onClick = ::fromCamera, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.image_camera)) }
                        TextButton(onClick = ::fromFiles, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.insert_files)) }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = ::cancel) { Text(stringResource(R.string.insert_cancel)) } },
            )
            is Stage.Working -> AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(s.labelRes)) },
                text = {
                    if (s.progress != null) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = ::cancel) { Text(stringResource(R.string.insert_cancel)) } },
            )
            is Stage.Failed -> AlertDialog(
                onDismissRequest = ::cancel,
                text = { Text(stringResource(s.messageRes)) },
                confirmButton = { TextButton(onClick = ::cancel) { Text(stringResource(R.string.insert_ok)) } },
            )
            is Stage.PdfOptions -> OptionsDialog(pdf = s.staged, count = 0, onInsert = { indices, pos -> insertPdf(s.staged, indices, pos) })
            is Stage.ImageOptions -> OptionsDialog(pdf = null, count = s.pictures.size, onInsert = { _, pos -> insertImages(s.pictures, pos) })
        }
    }

    @Composable
    private fun OptionsDialog(pdf: StagedPdf?, count: Int, onInsert: (List<Int>, InsertPosition) -> Unit) {
        var all by remember { mutableStateOf(true) }
        var range by remember { mutableStateOf("") }
        var position by remember { mutableStateOf(InsertPosition.AfterCurrent) }
        val plan = if (pdf == null) null else InsertPlan.pdfPages(if (all) PdfSelection.All else PdfSelection.Range(range), pdf.pageCount)
        val invalid = plan as? PdfPagesPlan.Invalid
        val rangeEmpty = !all && range.isBlank()
        val canInsert = pdf == null || (plan is PdfPagesPlan.Pages && !rangeEmpty)
        AlertDialog(
            onDismissRequest = ::cancel,
            title = {
                Text(
                    if (pdf != null) pluralStringResource(R.plurals.insert_pdf_title, pdf.pageCount, pdf.pageCount)
                    else pluralStringResource(R.plurals.insert_image_title, count, count),
                )
            },
            text = {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.S)) {
                    if (pdf != null) {
                        Choice(stringResource(R.string.insert_all_pages), all) { all = true }
                        Choice(stringResource(R.string.insert_page_range), !all) { all = false }
                        if (!all) {
                            OutlinedTextField(
                                range,
                                { range = it },
                                singleLine = true,
                                isError = invalid != null,
                                placeholder = { Text(stringResource(R.string.insert_range_hint)) },
                                supportingText = invalid?.let { { Text(stringResource(R.string.insert_range_error, it.token)) } },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Text(stringResource(R.string.insert_where), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Spacing.S))
                    Choice(stringResource(R.string.insert_after_page, anchorPage() + 1), position == InsertPosition.AfterCurrent) { position = InsertPosition.AfterCurrent }
                    Choice(stringResource(R.string.insert_at_end), position == InsertPosition.AtEnd) { position = InsertPosition.AtEnd }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canInsert,
                    onClick = { onInsert((plan as? PdfPagesPlan.Pages)?.indices.orEmpty(), position) },
                ) { Text(stringResource(R.string.insert_action)) }
            },
            dismissButton = { TextButton(onClick = ::cancel) { Text(stringResource(R.string.insert_cancel)) } },
        )
    }

    @Composable
    private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().semantics { this.selected = selected },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected, onClick)
            TextButton(onClick = onClick) { Text(label) }
        }
    }
}

/** Remembers a [PageInsertFlow] with its pickers wired up. */
@Composable
internal fun rememberPageInsertFlow(
    document: app.bestnote.engine.canvas.document.PagedDocument,
    session: DocumentSession,
    currentPage: () -> Int,
): PageInsertFlow {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val flow = remember(document, session) { PageInsertFlow(context, scope, document, session, currentPage) }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(flow::pdfPicked) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICTURES)) { flow.imagesPicked(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { flow.imagesPicked(it.take(MAX_PICTURES)) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { flow.cameraDone(it) }
    flow.launchPdfPicker = { pdf.launch(arrayOf("application/pdf")) }
    flow.launchPhotoPicker = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    flow.launchFilePicker = { files.launch(arrayOf("image/*")) }
    flow.launchCamera = { camera.launch(it) }
    return flow
}

private const val MAX_PICTURES = 10
