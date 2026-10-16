package app.bestnote.feature.editor

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import app.bestnote.core.model.ImageElement
import app.bestnote.core.model.PageElement
import app.bestnote.core.model.TextElement
import app.bestnote.engine.canvas.document.PagedDocument
import app.bestnote.engine.canvas.document.PagedDocumentView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Text boxes and pictures on the page (TEXT-01/02, MEDIA-01/02): the Text and Image tools, and what the
 * selection menu does to a selected text box or picture. Dialogs it needs are drawn by [Dialogs].
 */
internal class ElementTools(
    private val context: Context,
    private val scope: CoroutineScope,
    private val canvas: () -> PagedDocumentView?,
    private val document: PagedDocument,
    private val session: DocumentSession,
    private val colors: () -> List<Int>,
    private val onSelecting: () -> Unit,
    private val onMessage: (Int) -> Unit,
) {
    var newText by mutableStateOf<Pair<Int, TextElement>?>(null)
    var editing by mutableStateOf<TextElement?>(null)
    var cropping by mutableStateOf<ImageElement?>(null)
    var sourceMenu by mutableStateOf(false)
    var pickPhoto: () -> Unit = {}
    var takePhoto: () -> Unit = {}
    var cameraUri: Uri? = null

    /** The Text tool: asks for the words, then puts the box on the page being read and selects it. */
    fun insertText() {
        val view = canvas() ?: return
        val index = view.currentPage.coerceAtLeast(0)
        val (w, h) = view.pageSize(index) ?: return
        // Each new box lands a little lower than the last, so boxes added in a row do not hide each other.
        val step = (document.pages.getOrNull(index)?.elements?.size ?: 0) % CASCADE_STEPS
        val box = TextElement(PageElement.newId(), w / 2f, h * TEXT_TOP + step * CASCADE_PT, w * TEXT_WIDTH, TextElement.DEFAULT_SIZE_PT * 1.3f, "")
        newText = index to box
    }

    fun confirmNewText(box: TextElement) {
        val (index, _) = newText ?: return
        newText = null
        val view = canvas() ?: return
        document.addElement(index, view.fitted(box))
        view.selectElement(index, box.id)
        onSelecting()
    }

    fun editSelectedText() {
        editing = canvas()?.selectedElement as? TextElement
    }

    fun confirmEdit(box: TextElement) {
        editing = null
        val view = canvas() ?: return
        view.updateSelectedElement { old -> if (old is TextElement) view.fitted(box.copy(width = old.width)) else old }
    }

    fun cropSelected() {
        cropping = canvas()?.selectedElement as? ImageElement
    }

    fun confirmCrop(crop: app.bestnote.core.model.CropRect) {
        cropping = null
        canvas()?.updateSelectedElement { if (it is ImageElement) it.cropped(crop) else it }
    }

    fun rotateSelected() {
        canvas()?.updateSelectedElement { if (it is ImageElement) it.turnedQuarter() else it }
    }

    fun toggleLock() {
        canvas()?.updateSelectedElement { it.withLocked(!it.locked) }
    }

    /** Stores the picture at [uri] (downsampled, once per notebook) and puts it on the page being read. */
    fun insertImage(uri: Uri) {
        scope.launch {
            val picture = ImageImport.load(context, uri)
            val view = canvas()
            if (picture == null || view == null) return@launch onMessage(R.string.image_failed)
            val index = view.currentPage.coerceAtLeast(0)
            val (pageW, pageH) = view.pageSize(index) ?: return@launch
            val path = session.addImage(picture.bytes, picture.ext)
            val (w, h) = ElementPlacement.pictureFrame(pageW, pageH, picture.width, picture.height)
            val id = PageElement.newId()
            document.addElement(index, ImageElement(id, pageW / 2f, pageH * IMAGE_TOP, w, h, path, picture.width, picture.height))
            view.selectElement(index, id)
            onSelecting()
        }
    }

    fun pasteImage() {
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val uri = (0 until (clip?.itemCount ?: 0)).firstNotNullOfOrNull { clip?.getItemAt(it)?.uri }
        if (uri == null) onMessage(R.string.image_nothing_to_paste) else insertImage(uri)
    }

    fun cameraTarget(): Uri {
        val file = File(File(context.cacheDir, "exports/camera").apply { mkdirs() }, "shot.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.exports", file).also { cameraUri = it }
    }

    @Composable
    fun Dialogs() {
        if (sourceMenu) {
            AlertDialog(
                onDismissRequest = { sourceMenu = false },
                title = { Text(stringResource(R.string.image_add)) },
                text = {
                    androidx.compose.foundation.layout.Column {
                        TextButton({ sourceMenu = false; pickPhoto() }) { Text(stringResource(R.string.image_photos)) }
                        TextButton({ sourceMenu = false; takePhoto() }) { Text(stringResource(R.string.image_camera)) }
                        TextButton({ sourceMenu = false; pasteImage() }) { Text(stringResource(R.string.image_paste)) }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton({ sourceMenu = false }) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
        newText?.second?.let { box ->
            TextEditorDialog(box, colors(), onConfirm = ::confirmNewText, onDismiss = { newText = null })
        }
        editing?.let { box ->
            TextEditorDialog(box, colors(), onConfirm = ::confirmEdit, onDismiss = { editing = null })
        }
        cropping?.let { image ->
            CropDialog(image, session.assetBytes(image.asset), onConfirm = ::confirmCrop, onDismiss = { cropping = null })
        }
    }

    private companion object {
        const val TEXT_WIDTH = 0.6f
        const val TEXT_TOP = 0.3f
        const val CASCADE_PT = 36f
        const val CASCADE_STEPS = 6
        const val IMAGE_TOP = 0.4f
    }
}

@Composable
internal fun rememberElementTools(
    canvas: () -> PagedDocumentView?,
    document: PagedDocument,
    session: DocumentSession,
    colors: () -> List<Int>,
    onSelecting: () -> Unit,
    onMessage: (Int) -> Unit,
): ElementTools {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tools = remember(document, session) { ElementTools(context, scope, canvas, document, session, colors, onSelecting, onMessage) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(tools::insertImage) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) tools.cameraUri?.let(tools::insertImage) }
    tools.pickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    tools.takePhoto = { camera.launch(tools.cameraTarget()) }
    return tools
}
