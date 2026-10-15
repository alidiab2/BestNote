package app.bestnote.engine.canvas.document

import androidx.ink.strokes.Stroke
import app.bestnote.core.model.Page
import app.bestnote.engine.canvas.CanvasEdit
import app.bestnote.engine.canvas.Command
import app.bestnote.engine.canvas.CommandStack
import app.bestnote.engine.canvas.StrokeTarget

/** One page of a [PagedDocument]: its paper ([page]) and its finished strokes in page-local points. */
class DocumentPage internal constructor(page: Page) {
    var page: Page = page
        internal set

    internal val mutableStrokes = ArrayList<Stroke>()

    /** Finished strokes in drawing order, in page points (origin at the page's top-left). */
    val strokes: List<Stroke> get() = mutableStrokes
}

/**
 * The content of a paged notebook (CANVAS-07): ordered pages, each owning its ink, plus one undo
 * history for the whole document. Lives outside any View (e.g. in a ViewModel) so it survives
 * configuration changes; [PagedDocumentView] renders it. Main thread only.
 */
class PagedDocument {
    /** Observes structural and content changes. All callbacks run on the main thread. */
    interface Listener {
        /** Pages were added, removed or changed paper/size: layout must be recomputed. */
        fun onPagesChanged() {}

        /** The strokes of page [index] changed. */
        fun onPageContentChanged(index: Int) {}

        fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) {}
    }

    /** An undoable stroke edit on one page. */
    data class PageEdit(val page: Int, val edit: CanvasEdit) : Command<PagedDocument> {
        override val retainedBytes: Long get() = edit.retainedBytes
        override fun apply(target: PagedDocument) = target.applyEdit(page, edit, forward = true)
        override fun revert(target: PagedDocument) = target.applyEdit(page, edit, forward = false)
    }

    private val _pages = ArrayList<DocumentPage>()
    val pages: List<DocumentPage> get() = _pages
    val size: Int get() = _pages.size

    private val history = CommandStack<PagedDocument>()
    private val listeners = ArrayList<Listener>()

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    /** Paper for the first page when "+ New page" is used on an empty document. */
    var blankPage: Page? = null

    /** True once [reset] has supplied pages (an empty document shows only the add-page card). */
    var isLoaded: Boolean = false
        private set

    fun addListener(l: Listener) {
        if (l !in listeners) listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    /** Replaces every page (e.g. after loading from storage) and clears the history. */
    fun reset(pages: List<Page>, strokes: (Int) -> Collection<Stroke> = { emptyList() }) {
        _pages.clear()
        pages.forEachIndexed { i, p -> _pages += DocumentPage(p.copy(index = i)).apply { mutableStrokes += strokes(i) } }
        isLoaded = true
        history.clear()
        listeners.forEach { it.onPagesChanged() }
        notifyHistory()
    }

    /**
     * Appends a page with the same paper (template, color, size) as the last page, or [template]
     * when given. @return the new page's index, or -1 when there is no paper to copy.
     */
    fun addPage(template: Page? = null): Int {
        val base = template ?: _pages.lastOrNull()?.page ?: blankPage ?: return -1
        val index = _pages.size
        _pages += DocumentPage(base.copy(index = index))
        isLoaded = true
        listeners.forEach { it.onPagesChanged() }
        return index
    }

    /** Changes page [index]'s paper or size; ink stays where it is in page points. */
    fun updatePage(index: Int, transform: (Page) -> Page) {
        val p = _pages[index]
        val next = transform(p.page).copy(index = index)
        if (next == p.page) return
        p.page = next
        listeners.forEach { it.onPagesChanged() }
    }

    /** Replaces the strokes of page [index] without touching the history (loading). */
    fun setStrokes(index: Int, strokes: Collection<Stroke>) {
        _pages[index].mutableStrokes.apply { clear(); addAll(strokes) }
        contentChanged(index)
    }

    fun addStrokes(index: Int, strokes: Collection<Stroke>) {
        if (strokes.isEmpty()) return
        _pages[index].mutableStrokes += strokes
        history.record(PageEdit(index, CanvasEdit.Added(strokes.toList())))
        contentChanged(index)
        notifyHistory()
    }

    /** Removes [strokes] from page [index] now; [commitErase] records the gesture as one undo step. */
    internal fun removeStrokes(index: Int, strokes: Collection<Stroke>) {
        if (strokes.isEmpty()) return
        val gone = strokes.toHashSet()
        _pages[index].mutableStrokes.removeAll { it in gone }
        contentChanged(index)
    }

    internal fun commitErase(index: Int, erased: List<Stroke>) {
        if (erased.isEmpty()) return
        history.record(PageEdit(index, CanvasEdit.Erased(erased)))
        notifyHistory()
    }

    /** Erases every stroke of page [index] as one undoable step. */
    fun clearPage(index: Int) {
        val all = _pages.getOrNull(index)?.strokes?.toList().orEmpty()
        if (all.isEmpty()) return
        removeStrokes(index, all)
        commitErase(index, all)
    }

    fun undo() {
        history.undo(this) ?: return
        notifyHistory()
    }

    fun redo() {
        history.redo(this) ?: return
        notifyHistory()
    }

    private fun applyEdit(index: Int, edit: CanvasEdit, forward: Boolean) {
        if (index !in _pages.indices) return
        val target = object : StrokeTarget {
            override fun add(strokes: Collection<Stroke>) {
                this@PagedDocument._pages[index].mutableStrokes += strokes
            }

            override fun remove(strokes: Collection<Stroke>) {
                val gone = strokes.toHashSet()
                this@PagedDocument._pages[index].mutableStrokes.removeAll { it in gone }
            }
        }
        if (forward) edit.apply(target) else edit.revert(target)
        contentChanged(index)
    }

    private fun contentChanged(index: Int) = listeners.forEach { it.onPageContentChanged(index) }

    private fun notifyHistory() = listeners.forEach { it.onHistoryChanged(history.canUndo, history.canRedo) }
}
