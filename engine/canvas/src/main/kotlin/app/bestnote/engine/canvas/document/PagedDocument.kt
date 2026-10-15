package app.bestnote.engine.canvas.document

import androidx.ink.strokes.Stroke
import app.bestnote.core.model.Page
import app.bestnote.engine.canvas.CanvasEdit
import app.bestnote.engine.canvas.Command
import app.bestnote.engine.canvas.CommandStack
import app.bestnote.engine.canvas.StrokeSplice
import app.bestnote.engine.canvas.StrokeTarget

/** One page of a [PagedDocument]: its paper ([page]) and its finished strokes in page-local points. */
class DocumentPage internal constructor(page: Page, val id: String = newPageId()) {
    var page: Page = page
        internal set

    internal val mutableStrokes = ArrayList<Stroke>()

    /** Finished strokes in drawing order, in page points (origin at the page's top-left). */
    val strokes: List<Stroke> get() = mutableStrokes
}

private const val ID_LENGTH = 12

/** A fresh storage id for a page (matches the `.bnote` page id pattern). */
internal fun newPageId(): String = "page-" + java.util.UUID.randomUUID().toString().take(ID_LENGTH)

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
    class PageEdit internal constructor(private val page: DocumentPage, private val edit: CanvasEdit) : Command<PagedDocument> {
        override val retainedBytes: Long get() = edit.retainedBytes
        override fun apply(target: PagedDocument) = target.applyEdit(page, edit, forward = true)
        override fun revert(target: PagedDocument) = target.applyEdit(page, edit, forward = false)
    }

    /** A page and its paper at one moment, so page order, count and paper changes can be undone as one step. */
    internal class Slot(val page: DocumentPage, val paper: Page)

    /** An undoable change of the page list: insert, delete, move, duplicate, rotate or new paper. */
    private class Restructure(val before: List<Slot>, val after: List<Slot>) : Command<PagedDocument> {
        override val retainedBytes: Long get() = SLOT_BYTES * (before.size + after.size).toLong()
        override fun apply(target: PagedDocument) = target.install(after)
        override fun revert(target: PagedDocument) = target.install(before)
    }

    private val _pages = ArrayList<DocumentPage>()
    val pages: List<DocumentPage> get() = _pages
    val size: Int get() = _pages.size

    private val history = CommandStack<PagedDocument>()
    private val listeners = ArrayList<Listener>()

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    /** Draws the raster background of pages that have one ([Page.pdf]); set before [reset]. */
    var backgrounds: PageBackgrounds? = null

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
    fun reset(pages: List<Page>, ids: List<String>? = null, strokes: (Int) -> Collection<Stroke> = { emptyList() }) {
        _pages.clear()
        pages.forEachIndexed { i, p ->
            val page = DocumentPage(p.copy(index = i), ids?.getOrNull(i) ?: newPageId())
            _pages += page.apply { mutableStrokes += strokes(i) }
        }
        isLoaded = true
        history.clear()
        listeners.forEach { it.onPagesChanged() }
        notifyHistory()
    }

    /**
     * Appends a page with the same paper (template, color, size) as the last page, or [template]
     * when given. Undoable. @return the new page's index, or -1 when there is no paper to copy.
     */
    fun addPage(template: Page? = null): Int {
        val base = template ?: _pages.lastOrNull()?.page ?: blankPage ?: return -1
        return insertPage(_pages.size, base)
    }

    /** Inserts a blank page with [paper] at [at] (0..size). Undoable. @return its index. */
    fun insertPage(at: Int, paper: Page): Int {
        val index = at.coerceIn(0, _pages.size)
        // A new blank page next to a PDF page is plain paper: it must not repeat the PDF page's background.
        restructure { _pages.add(index, DocumentPage(paper.copy(index = index, pdf = null))) }
        return index
    }

    /** Changes page [index]'s paper or size; ink stays where it is in page points. Undoable. */
    fun updatePage(index: Int, transform: (Page) -> Page) {
        val p = _pages[index]
        val next = transform(p.page).copy(index = index)
        if (next == p.page) return
        restructure { p.page = next }
    }

    /**
     * Deletes the pages at [indices] (ink included) as one undoable step. A notebook keeps at least
     * one page, so deleting every page keeps the first of them. @return the pages removed.
     */
    fun deletePages(indices: Collection<Int>): Int {
        val gone = indices.filter { it in _pages.indices }.toSortedSet().toMutableSet()
        if (gone.size == _pages.size) gone.remove(gone.first())
        if (gone.isEmpty()) return 0
        restructure { _pages.removeAll(gone.map { _pages[it] }.toSet()) }
        return gone.size
    }

    /** Moves page [from] so it ends up at index [to]. Undoable. */
    fun movePage(from: Int, to: Int) {
        if (from !in _pages.indices || to !in _pages.indices || from == to) return
        restructure { _pages.add(to, _pages.removeAt(from)) }
    }

    /** Puts a copy (paper and ink) of each page in [indices] right after its original. Undoable. @return the copies' indices. */
    fun duplicatePages(indices: Collection<Int>): List<Int> {
        val sources = indices.filter { it in _pages.indices }.toSortedSet().toList()
        if (sources.isEmpty()) return emptyList()
        val copies = ArrayList<DocumentPage>()
        restructure {
            for (i in sources.asReversed()) {
                val original = _pages[i]
                val copy = DocumentPage(original.page).apply { mutableStrokes += original.strokes }
                copies += copy
                _pages.add(i + 1, copy)
            }
        }
        return copies.map { _pages.indexOf(it) }.sorted()
    }

    /** Turns the paper of each page in [indices] a quarter turn (width and height swap). Undoable. */
    fun rotatePages(indices: Collection<Int>) {
        val targets = indices.filter { it in _pages.indices }.toSet()
        if (targets.isEmpty()) return
        restructure {
            // A PDF page keeps its orientation: its raster and ink are laid out in the PDF's own page points.
            for (i in targets) _pages[i].let { p -> if (p.page.pdf == null) p.page = p.page.copy(widthPt = p.page.heightPt, heightPt = p.page.widthPt) }
        }
    }

    private fun snapshot() = _pages.map { Slot(it, it.page) }

    /** Runs [change], renumbers, and records the before/after as one undo step. */
    private fun restructure(change: () -> Unit) {
        val before = snapshot()
        change()
        _pages.forEachIndexed { i, p -> if (p.page.index != i) p.page = p.page.copy(index = i) }
        isLoaded = true
        val after = snapshot()
        history.record(Restructure(before, after))
        listeners.forEach { it.onPagesChanged() }
        notifyHistory()
    }

    private fun install(slots: List<Slot>) {
        _pages.clear()
        slots.forEachIndexed { i, s ->
            s.page.page = s.paper.copy(index = i)
            _pages += s.page
        }
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
        history.record(PageEdit(_pages[index], CanvasEdit.Added(strokes.toList())))
        contentChanged(index)
        notifyHistory()
    }

    /** Strokes for several pages added as one undo step (a Smart Board insert). */
    fun addStrokes(byPage: Map<Int, Collection<Stroke>>) {
        val changed = byPage.filterValues { it.isNotEmpty() }
        val edits = changed.map { (index, strokes) ->
            _pages[index].mutableStrokes += strokes
            PageEdit(_pages[index], CanvasEdit.Added(strokes.toList()))
        }
        if (edits.isEmpty()) return
        history.record(edits.singleOrNull() ?: Batch(edits))
        changed.keys.forEach(::contentChanged)
        notifyHistory()
    }

    /** Several page edits undone and redone together. */
    data class Batch(val edits: List<PageEdit>) : Command<PagedDocument> {
        override val retainedBytes: Long get() = edits.sumOf { it.retainedBytes }
        override fun apply(target: PagedDocument) = edits.forEach { it.apply(target) }
        override fun revert(target: PagedDocument) = edits.asReversed().forEach { it.revert(target) }
    }

    /** Removes [strokes] from page [index] now, without history; [commitStrokeEdit] records the gesture. */
    internal fun removeStrokes(index: Int, strokes: Collection<Stroke>) {
        if (strokes.isEmpty()) return
        val gone = strokes.toHashSet()
        _pages[index].mutableStrokes.removeAll { it in gone }
        contentChanged(index)
    }

    /**
     * One eraser step on page [index], without history: [removed] strokes go, and each key of
     * [replacements] is swapped for its pieces at the same z-position. Listeners hear about it once.
     */
    internal fun eraseStep(index: Int, removed: Collection<Stroke>, replacements: Map<Stroke, List<Stroke>>) {
        if (removed.isEmpty() && replacements.isEmpty()) return
        val list = _pages[index].mutableStrokes
        if (removed.isNotEmpty()) {
            val gone = removed.toHashSet()
            list.removeAll { it in gone }
        }
        if (replacements.isNotEmpty()) {
            val next = ArrayList<Stroke>(list.size + replacements.size)
            for (s in list) next += replacements[s] ?: listOf(s)
            list.clear()
            list.addAll(next)
        }
        contentChanged(index)
    }

    /** A copy of page [index]'s strokes now: what [commitStrokeEdit] diffs against at the end of a gesture. */
    internal fun strokesSnapshot(index: Int): List<Stroke> = _pages[index].strokes.toList()

    /** Records everything that changed on page [index] since [before] as one exact, undoable step. */
    internal fun commitStrokeEdit(index: Int, before: List<Stroke>) {
        val page = _pages.getOrNull(index) ?: return
        val splice = StrokeSplice.diff(before, page.strokes) ?: return
        history.record(PageEdit(page, CanvasEdit.Spliced(splice)))
        notifyHistory()
    }

    /** Erases every stroke of page [index] as one undoable step. */
    fun clearPage(index: Int) {
        val all = _pages.getOrNull(index)?.strokes?.toList().orEmpty()
        if (all.isEmpty()) return
        removeStrokes(index, all)
        commitStrokeEdit(index, all)
    }

    fun undo() {
        history.undo(this) ?: return
        notifyHistory()
    }

    fun redo() {
        history.redo(this) ?: return
        notifyHistory()
    }

    private fun applyEdit(page: DocumentPage, edit: CanvasEdit, forward: Boolean) {
        val index = _pages.indexOf(page)
        if (index < 0) return
        val target = object : StrokeTarget {
            override fun add(strokes: Collection<Stroke>) {
                this@PagedDocument._pages[index].mutableStrokes += strokes
            }

            override fun remove(strokes: Collection<Stroke>) {
                val gone = strokes.toHashSet()
                this@PagedDocument._pages[index].mutableStrokes.removeAll { it in gone }
            }

            override fun splice(splice: StrokeSplice<Stroke>, forward: Boolean) {
                val list = this@PagedDocument._pages[index].mutableStrokes
                if (forward) splice.apply(list) else splice.revert(list)
            }
        }
        if (forward) edit.apply(target) else edit.revert(target)
        contentChanged(index)
    }

    private companion object {
        const val SLOT_BYTES = 64L
    }

    private fun contentChanged(index: Int) = listeners.forEach { it.onPageContentChanged(index) }

    private fun notifyHistory() = listeners.forEach { it.onHistoryChanged(history.canUndo, history.canRedo) }
}
