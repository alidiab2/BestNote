package app.bestnote.feature.editor.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.bestnote.core.model.PageElement
import app.bestnote.core.model.TextElement
import app.bestnote.engine.canvas.document.PagedDocument
import app.bestnote.engine.canvas.document.PagedDocumentView

/**
 * The Text tool and typing on the page (TEXT-04). With the tool on, a tap on a page puts a caret there (a new box that
 * grows as you type) or into the text box under it; the words are typed right on the canvas by [CanvasTextEditor].
 * The whole edit is stored as one document undo step when typing ends.
 */
internal class TextTyping(
    private val canvas: () -> PagedDocumentView?,
    private val document: PagedDocument,
) {
    /** One box being typed into. [pageId] survives pages being added meanwhile. */
    class Editing(
        val pageId: String,
        val session: TextEditSession,
        val isNew: Boolean,
        /** New boxes: the tap's x (page points) the box grows from, and the box's top edge. */
        val anchorX: Float,
        val top: Float,
        /** Where the tap landed inside an existing box (its own frame, page points), to place the caret once laid out. */
        var tapLocal: Pair<Float, Float>?,
    )

    var textTool by mutableStateOf(false)
    var editing by mutableStateOf<Editing?>(null)
        private set

    /** What the on-canvas field shows (text, caret, IME composition). */
    var value by mutableStateOf(TextFieldValue())
        private set

    /** Bumped on every session change, so the editor and format bar redraw. */
    var version by mutableIntStateOf(0)
        private set

    /** Last place the Text tool was tapped (page index, x, y), where hardware typing starts a box. */
    private var lastTap: Triple<Int, Float, Float>? = null

    fun pageIndex(e: Editing): Int = document.pages.indexOfFirst { it.id == e.pageId }

    /** The Text tool's tap at ([x], [y]) page points on page [page]. */
    fun tapAt(page: Int, x: Float, y: Float) {
        commit()
        lastTap = Triple(page, x, y)
        val p = document.pages.getOrNull(page) ?: return
        val hit = TextHit.at(p.elements, x, y, slop = HIT_SLOP_PT)
        if (hit != null) {
            edit(page, hit, caret = hit.text.length, tapLocal = TextHit.local(hit, x, y))
        } else {
            startNew(page, x, y)
        }
    }

    /** Hardware typing with the Text tool on and nothing being edited: a box at the last tap, else mid-screen. */
    fun typeIntoNewBox(chars: String) {
        val view = canvas() ?: return
        if (editing == null) {
            val at = lastTap?.takeIf { it.first in document.pages.indices } ?: run {
                val index = view.currentPage.coerceAtLeast(0)
                val (w, h) = view.pageSize(index) ?: return
                Triple(index, w * NEW_BOX_LEFT, h * NEW_BOX_TOP)
            }
            startNew(at.first, at.second, at.third)
        }
        val e = editing ?: return
        e.session.type(chars)
        sync(dropComposition = true)
    }

    /** Edits the selected text box in place ("Edit text" in the selection menu). */
    fun editSelected() {
        val view = canvas() ?: return
        val box = view.selectedElement as? TextElement ?: return
        val index = document.pages.indexOfFirst { p -> p.elements.any { it.id == box.id } }
        if (index < 0) return
        view.clearSelection()
        edit(index, box, caret = box.text.length, tapLocal = null)
    }

    private fun startNew(page: Int, x: Float, y: Float) {
        val (pageW, _) = canvas()?.pageSize(page) ?: return
        val size = TextElement.DEFAULT_SIZE_PT
        val minW = size * MIN_WIDTH_EM
        val (left, w) = AutoGrow.frame(x, pageW, pageW * MARGIN, 0f, minW, rtl = false)
        val h = size * LINE_EM
        // The first line's middle lands on the tap.
        val top = y - h / 2f
        val box = TextElement(PageElement.newId(), left + w / 2f, top + h / 2f, w, h, "")
        begin(page, box, isNew = true, caret = 0, anchorX = x, top = top, tapLocal = null)
    }

    private fun edit(page: Int, box: TextElement, caret: Int, tapLocal: Pair<Float, Float>?) {
        begin(page, box, isNew = false, caret = caret, anchorX = box.cx - box.width / 2f, top = box.cy - box.height / 2f, tapLocal = tapLocal)
        canvas()?.setEditingElement(page, box.id)
    }

    private fun begin(page: Int, box: TextElement, isNew: Boolean, caret: Int, anchorX: Float, top: Float, tapLocal: Pair<Float, Float>?) {
        val id = document.pages.getOrNull(page)?.id ?: return
        canvas()?.clearSelection()
        editing = Editing(id, TextEditSession(box, caret), isNew, anchorX, top, tapLocal)
        sync(dropComposition = true)
    }

    /** The field changed (typing, IME, caret moves). */
    fun onValue(next: TextFieldValue) {
        val e = editing ?: return
        val changed = e.session.onInput(next.text, next.selection.start, next.selection.end, composing = next.composition != null)
        if (changed) sync(dropComposition = true) else value = next
        version++
    }

    /** After the session changed by itself (format bar, shortcut, undo): the field shows its text and caret. */
    fun sync(dropComposition: Boolean = true) {
        val d = editing?.session?.draft ?: return
        val composition = value.composition.takeUnless { dropComposition || value.text != d.text }
        value = TextFieldValue(d.text, TextRange(d.selStart, d.selEnd), composition)
        version++
    }

    /** Applies [block] to the session and refreshes the field. */
    fun act(block: (TextEditSession) -> Unit) {
        val e = editing ?: return
        block(e.session)
        sync(dropComposition = true)
    }

    /** New boxes: the frame chosen for the current width of the words (set by the editor as it lays out). */
    fun grow(left: Float, width: Float) {
        val e = editing ?: return
        val b = e.session.draft.box
        if (b.width == width && b.cx == left + width / 2f) return
        e.session.reframe(left, width)
    }

    /** Ends typing: stores the box (one undo step), drops an emptied one, and shows the page drawing again. */
    fun commit() {
        val e = editing ?: return
        editing = null
        value = TextFieldValue()
        version++
        val view = canvas()
        val index = pageIndex(e)
        view?.setEditingElement(-1, null)
        if (index < 0) return
        val result = e.session.result()
        val page = document.pages[index]
        if (e.isNew) {
            if (result.text.isBlank()) return
            val placed = view?.fitted(result) ?: result
            document.addElement(index, placed)
            return
        }
        val old = page.elements.firstOrNull { it.id == result.id } as? TextElement ?: return
        if (result.text.isBlank()) {
            document.replaceElements(index, page.elements.filterNot { it.id == old.id })
            return
        }
        val fitted = view?.fitted(result.copy(cx = old.cx, cy = old.cy, height = old.height)) ?: result
        if (fitted != old) document.replaceElements(index, page.elements.map { if (it.id == old.id) fitted else it })
    }

    companion object {
        /** Taps this close to a box's edge still land in it. */
        const val HIT_SLOP_PT = 6f
        const val MARGIN = 0.06f
        const val MIN_WIDTH_EM = 4f
        const val LINE_EM = 1.3f
        private const val NEW_BOX_LEFT = 0.12f
        private const val NEW_BOX_TOP = 0.3f
    }
}
