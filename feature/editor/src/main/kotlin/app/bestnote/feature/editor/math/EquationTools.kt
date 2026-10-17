package app.bestnote.feature.editor.math

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.bestnote.ai.math.EquationReader
import app.bestnote.ai.math.EquationReading
import app.bestnote.ai.math.EquationRecognizer
import app.bestnote.ai.vision.VisionError
import app.bestnote.core.model.EquationElement
import app.bestnote.core.model.PageElement
import app.bestnote.engine.canvas.document.EquationRendering
import app.bestnote.engine.canvas.document.PagedDocument
import app.bestnote.engine.canvas.document.PagedDocumentView
import app.bestnote.engine.mathtype.LatexEdit
import app.bestnote.engine.mathtype.LatexPalette
import app.bestnote.engine.mathtype.PaletteItem
import app.bestnote.feature.editor.InkConversion
import app.bestnote.feature.editor.InkSnapshot
import app.bestnote.feature.editor.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handwriting → typeset equations (MATH-01). Lasso → "Convert to equation" reads the selected maths with
 * [recognizer] (the AI provider today, on-device AI-10 later), shows the rendered result and its LaTeX for
 * confirmation, and puts an [EquationElement] in place of the ink (or under it) in one undo step. Tapping an
 * equation opens [EquationEditorSheet]: live preview, structure palette, raw LaTeX with highlighted errors,
 * and "rewrite by hand".
 */
internal class EquationTools(
    private val context: Context,
    private val scope: CoroutineScope,
    private val canvas: () -> PagedDocumentView?,
    private val document: PagedDocument,
    private val recognizer: EquationRecognizer?,
    private val onSelecting: () -> Unit,
    private val message: suspend (Int) -> Unit,
) {
    enum class Step { Idle, NoProvider, Reading, Confirm }

    var step by mutableStateOf(Step.Idle)
        private set

    /** The LaTeX shown for confirmation (editable) and what the recognizer answered. */
    var draft by mutableStateOf(TextFieldValue(""))
    var raw by mutableStateOf("")
        private set

    /** The recognizer's answer was unusable as is; the reason is shown and the user can fix [draft]. */
    var problem by mutableStateOf<String?>(null)
        private set

    /** Keep the handwriting on the page and put the equation under it. */
    var keepInk by mutableStateOf(false)

    /** The equation being edited in the sheet, or null when the sheet is closed. */
    var editing by mutableStateOf<EquationElement?>(null)
        private set

    var editText by mutableStateOf(TextFieldValue(""))
    var editSize by mutableStateOf(EquationElement.DEFAULT_SIZE_PT)

    /** "Rewrite by hand": the pad is open, and a reading is in progress. */
    var rewriting by mutableStateOf(false)
    var rewriteBusy by mutableStateOf(false)
        private set
    val pad = PadInk()

    private var pending: PagedDocumentView.SelectedInk? = null

    // ---- convert -------------------------------------------------------------------------------------------------

    fun convert() {
        val selected = canvas()?.selectedInk() ?: return
        pending = selected
        problem = null
        keepInk = false
        scope.launch {
            if (recognizer?.isAvailable() != true) {
                step = Step.NoProvider
                return@launch
            }
            read(selected)
        }
    }

    /** No recognizer: write the LaTeX by hand instead of reading the ink. */
    fun typeInstead() {
        draft = TextFieldValue("")
        raw = ""
        problem = null
        step = Step.Confirm
    }

    private suspend fun read(selected: PagedDocumentView.SelectedInk) {
        val rec = recognizer ?: return
        step = Step.Reading
        try {
            val png = withContext(Dispatchers.Default) { InkSnapshot.png(selected.strokes) }
            if (png == null) {
                message(R.string.equation_no_math)
                dismiss()
                return
            }
            when (val r = EquationReader.read(rec, png)) {
                is EquationReading.Ok -> {
                    raw = r.raw
                    setDraft(r.latex)
                    step = Step.Confirm
                }
                is EquationReading.Invalid -> {
                    raw = r.raw
                    problem = r.reason
                    setDraft(r.raw.trim())
                    step = Step.Confirm
                }
                EquationReading.NotMath -> {
                    message(R.string.equation_no_math)
                    dismiss()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            android.util.Log.w("BestNoteEquation", "Reading the equation failed: ${e.javaClass.simpleName}")
            message(failureMessage(e))
            dismiss()
        }
    }

    private fun setDraft(latex: String) {
        draft = TextFieldValue(latex, TextRange(latex.length))
    }

    fun dismiss() {
        step = Step.Idle
        pending = null
    }

    /** Puts the confirmed equation on the page: in place of the ink, or under it with [keepInk]. One undo step. */
    fun insert() {
        val latex = draft.text.trim()
        val selected = pending
        val view = canvas()
        if (latex.isEmpty() || view == null) return dismiss()
        val index = selected?.pageIndex ?: view.currentPage.coerceAtLeast(0)
        val page = document.pages.getOrNull(index) ?: return dismiss()
        val color = selected?.strokes?.firstOrNull()?.let { InkConversion.brushFromInk(it.brush).colorArgb or OPAQUE } ?: EquationElement.DEFAULT_COLOR
        val size = selected?.let { sizeToMatch(latex, it.box.height) } ?: EquationElement.DEFAULT_SIZE_PT
        val (w, h) = EquationRendering.frameSize(latex, size)
        val (cx, cy) = when {
            selected == null -> view.pageSize(index)?.let { (pw, ph) -> pw / 2f to ph * NEW_TOP } ?: (w to h)
            keepInk -> selected.box.left + w / 2f to selected.box.bottom + h / 2f
            else -> selected.box.centerX to selected.box.centerY
        }
        val eq = EquationElement(PageElement.newId(), cx, cy, w, h, latex, sizePt = size, colorArgb = color)
        val removed = if (selected != null && !keepInk) selected.strokes else emptyList()
        document.editContent(index, removedStrokes = removed, elements = page.elements + eq)
        view.selectElement(index, eq.id)
        onSelecting()
        dismiss()
    }

    /** A size at which [latex] is about as tall as the handwriting it replaces. */
    private fun sizeToMatch(latex: String, inkHeight: Float): Float {
        val l = EquationRendering.layout(latex, EquationElement.DEFAULT_SIZE_PT)
        if (l.height <= 0f) return EquationElement.DEFAULT_SIZE_PT
        return (EquationElement.DEFAULT_SIZE_PT * inkHeight / l.height).coerceIn(MIN_CONVERTED_PT, MAX_CONVERTED_PT)
    }

    // ---- edit ----------------------------------------------------------------------------------------------------

    /** Opens the editor for [e] (a tap on it, or Edit in the selection menu). */
    fun edit(e: EquationElement) {
        if (e.locked) return
        editing = e
        editText = TextFieldValue(e.latex, TextRange(e.latex.length))
        editSize = e.sizePt
        rewriting = false
        pad.clear()
    }

    fun editSelected() {
        (canvas()?.selectedElement as? EquationElement)?.let(::edit)
    }

    fun closeEditor() {
        editing = null
        rewriting = false
        pad.clear()
    }

    /** Applies the editor's LaTeX and size to the selected equation as one undo step, keeping its center. */
    fun applyEdit() {
        val latex = editText.text.trim()
        val view = canvas()
        if (latex.isNotEmpty() && view != null) {
            view.updateSelectedElement { old ->
                if (old is EquationElement) EquationRendering.fitted(old.copy(latex = latex, sizePt = editSize)) else old
            }
        }
        closeEditor()
    }

    /** Inserts a palette template at the caret / around the selection of the editor's field. */
    fun insertTemplate(item: PaletteItem) {
        editText = insertInto(editText, item.template)
    }

    private fun insertInto(v: TextFieldValue, template: String): TextFieldValue {
        val edit = LatexPalette.insert(LatexEdit(v.text, v.selection.min, v.selection.max), template)
        return TextFieldValue(edit.text, TextRange(edit.start, edit.end))
    }

    /** Moves the caret to the next empty slot. */
    fun nextSlot() {
        val e = LatexPalette.nextSlot(LatexEdit(editText.text, editText.selection.min, editText.selection.max)) ?: return
        editText = editText.copy(selection = TextRange(e.start, e.end))
    }

    fun copyLatex(latex: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("LaTeX", latex))
        scope.launch { message(R.string.equation_copied) }
    }

    fun copySelectedLatex() {
        (canvas()?.selectedElement as? EquationElement)?.let { copyLatex(it.latex) }
    }

    /** "Rewrite by hand": reads the pad and puts its LaTeX in place of the field's selection (or at the caret). */
    fun readPad(strokeWidthPx: Float) {
        val rec = recognizer
        if (rec == null) {
            scope.launch { message(R.string.equation_needs_ai) }
            return
        }
        if (pad.isEmpty || rewriteBusy) return
        rewriteBusy = true
        scope.launch {
            try {
                if (!rec.isAvailable()) {
                    message(R.string.equation_needs_ai)
                    return@launch
                }
                val png = withContext(Dispatchers.Default) { pad.png(strokeWidthPx) } ?: return@launch
                when (val r = EquationReader.read(rec, png)) {
                    is EquationReading.Ok -> {
                        editText = insertInto(editText, r.latex)
                        pad.clear()
                        rewriting = false
                    }
                    is EquationReading.Invalid -> {
                        editText = insertInto(editText, r.raw.trim())
                        pad.clear()
                        rewriting = false
                    }
                    EquationReading.NotMath -> message(R.string.equation_no_math)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                android.util.Log.w("BestNoteEquation", "Reading the pad failed: ${e.javaClass.simpleName}")
                message(failureMessage(e))
            } finally {
                rewriteBusy = false
            }
        }
    }

    private fun failureMessage(e: Exception) = when (e) {
        is VisionError.InvalidKey, is VisionError.MissingKey -> R.string.convert_ai_key
        is VisionError.ImageNotSeen -> R.string.tidy_error_no_image
        else -> R.string.equation_failed
    }

    private companion object {
        const val OPAQUE = 0xFF000000.toInt()
        const val MIN_CONVERTED_PT = 14f
        const val MAX_CONVERTED_PT = 72f
        const val NEW_TOP = 0.3f
    }
}

@Composable
internal fun rememberEquationTools(
    canvas: () -> PagedDocumentView?,
    document: PagedDocument,
    recognizer: EquationRecognizer?,
    onSelecting: () -> Unit,
    snackbar: SnackbarHostState,
): EquationTools {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    return remember(document, recognizer) {
        EquationTools(context, scope, canvas, document, recognizer, onSelecting) { id ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(resources.getString(id))
        }
    }
}
