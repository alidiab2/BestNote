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
import androidx.compose.runtime.DisposableEffect
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
import app.bestnote.feature.editor.text.TextTyping
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

    /** TEXT-04: the Text tool and typing right on the page. */
    val typing = TextTyping(canvas, document)

    /** The "More" formatting dialog for the box being typed into (line spacing, numerals, the classic panel). */
    var moreFor by mutableStateOf<TextElement?>(null)

    /** The font picker for the box being typed into. */
    var pickingFont by mutableStateOf(false)
    var cropping by mutableStateOf<ImageElement?>(null)
    var sourceMenu by mutableStateOf(false)
    var pickPhoto: () -> Unit = {}
    var takePhoto: () -> Unit = {}
    var cameraUri: Uri? = null

    /** The Text tool (TEXT-04): on, a tap on the page places a caret; off again ends typing. */
    fun toggleTextTool() {
        if (typing.textTool) {
            typing.commit()
            typing.textTool = false
        } else {
            canvas()?.clearSelection()
            typing.textTool = true
        }
    }

    /** Another tool was picked: typing ends and the Text tool lets go. */
    fun leaveTextTool() {
        typing.commit()
        typing.textTool = false
    }

    /** The classic Text dialog: asks for the words, then puts the box on the page being read and selects it. */
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

    /** "Edit" on a selected text box: types into it right on the page (TEXT-04). */
    fun editSelectedText() {
        typing.editSelected()
    }

    fun confirmMore(box: TextElement) {
        moreFor = null
        typing.act { it.replaceBox(box) }
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
    fun insertImage(uri: Uri, drop: DropPoint? = null) {
        scope.launch {
            val picture = ImageImport.load(context, uri)
            val view = canvas()
            if (picture == null || view == null) return@launch onMessage(R.string.image_failed)
            val index = drop?.page ?: view.currentPage.coerceAtLeast(0)
            val (pageW, pageH) = view.pageSize(index) ?: return@launch
            val path = session.addImage(picture.bytes, picture.ext)
            val (w, h) = ElementPlacement.pictureFrame(pageW, pageH, picture.width, picture.height)
            val (cx, cy) = if (drop != null) ElementPlacement.centeredAt(drop.x, drop.y, w, h, pageW, pageH) else (pageW / 2f to pageH * IMAGE_TOP)
            val id = PageElement.newId()
            document.addElement(index, ImageElement(id, cx, cy, w, h, path, picture.width, picture.height))
            view.selectElement(index, id)
            onSelecting()
        }
    }

    /** Where something was dropped: page [page], at ([x], [y]) page points. */
    class DropPoint(val page: Int, val x: Float, val y: Float)

    /** A text box with [words] centered on the drop point, selected so it can be adjusted right away. */
    fun insertDroppedText(words: String, at: DropPoint) {
        val view = canvas() ?: return
        val (pageW, pageH) = view.pageSize(at.page) ?: return
        val w = pageW * TEXT_WIDTH
        val h = TextElement.DEFAULT_SIZE_PT * 1.3f
        val (cx, cy) = ElementPlacement.centeredAt(at.x, at.y, w, h, pageW, pageH)
        val box = view.fitted(TextElement(PageElement.newId(), cx, cy, w, h, words))
        document.addElement(at.page, box)
        view.selectElement(at.page, box.id)
        onSelecting()
    }

    /**
     * Takes what another app dropped (split screen, DeX): pictures and text land at the drop point. Content URIs
     * need the drop's temporary read permission, held until the pictures are read.
     */
    fun onDrop(page: Int, x: Float, y: Float, event: android.view.DragEvent): Boolean {
        val clip = event.clipData ?: return false
        val mimes = (0 until (event.clipDescription?.mimeTypeCount ?: 0)).map { event.clipDescription.getMimeType(it) }
        val items = (0 until clip.itemCount).map { i ->
            val item = clip.getItemAt(i)
            val uri = item.uri
            DropItem(uri?.let { runCatching { context.contentResolver.getType(it) }.getOrNull() }, uri?.toString(), item.text?.toString())
        }
        val actions = DropPlan.plan(items, mimes)
        if (actions.isEmpty()) return false
        val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
        val permission = if (actions.any { it is DropAction.Picture } && activity != null) androidx.core.app.ActivityCompat.requestDragAndDropPermissions(activity, event) else null
        actions.forEachIndexed { i, action ->
            // Several items fan out a little so they do not hide each other.
            val at = DropPoint(page, x + i * CASCADE_PT / 2f, y + i * CASCADE_PT / 2f)
            when (action) {
                is DropAction.Picture -> insertImage(Uri.parse(action.uri), at)
                is DropAction.Words -> insertDroppedText(action.text, at)
            }
        }
        // Release once the pictures were queued: the reads run on this scope, so wait for them to start.
        scope.launch { kotlinx.coroutines.delay(PERMISSION_HOLD_MS); permission?.release() }
        return true
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
        moreFor?.let { box ->
            TextEditorDialog(box, colors(), onConfirm = ::confirmMore, onDismiss = { moreFor = null })
        }
        if (pickingFont) {
            val box = typing.editing?.session?.draft?.box
            app.bestnote.core.fonts.FontPickerDialog(
                null, box?.font,
                onSelect = { id ->
                    pickingFont = false
                    typing.act { it.setFont(id) }
                },
                onDismiss = { pickingFont = false }, allowDefault = true,
            )
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
        const val PERMISSION_HOLD_MS = 30_000L
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
    val view = canvas()
    DisposableEffect(view, tools) {
        view?.dropListener = PagedDocumentView.DropListener(tools::onDrop)
        onDispose { view?.dropListener = null }
    }
    tools.pickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    tools.takePhoto = { camera.launch(tools.cameraTarget()) }
    return tools
}
