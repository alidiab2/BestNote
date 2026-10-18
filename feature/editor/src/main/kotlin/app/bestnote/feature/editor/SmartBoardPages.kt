package app.bestnote.feature.editor

import androidx.ink.strokes.Stroke
import app.bestnote.ai.vision.NoteWire
import app.bestnote.ai.vision.SmartBoardGroup
import app.bestnote.ai.vision.SmartBoardRecord
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.RectPt
import app.bestnote.engine.canvas.document.PagedDocument
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.ReservedLines
import app.bestnote.engine.layout.RuledPaper

/**
 * TPL-01: what Smart Board's pages mean to the notebook. Every insert is recorded with its notes
 * (for follow-up photos and the notebook glossary); a follow-up re-lays out the insert's pages in ONE
 * undo step, removing only Smart Board's own writing and keeping the user's ink where it is.
 */
internal object SmartBoardPages {
    /** Fingerprint of a stroke as stored (see [SmartBoardRecord.strokeKey]). */
    fun key(m: InkStroke): String? = m.samples.firstOrNull()?.let { SmartBoardRecord.strokeKey(it.x, it.y, m.samples.size, m.brush.colorArgb) }

    /** Records an insert of [pageIds] written from [notes] in template [templateId]. */
    fun recordInsert(session: DocumentSession, pageIds: List<String>, insert: InkInsert) {
        val notes = insert.notes?.let(NoteWire::decodeStored) ?: return
        val group = SmartBoardGroup(
            templateId = insert.templateId ?: return,
            pageIds = pageIds,
            notes = notes,
            elementIds = insert.elements.flatten().mapTo(HashSet()) { it.id },
            strokeKeys = insert.pages.flatten().mapNotNullTo(HashSet(), ::key),
            photos = insert.photos.map { session.addImage(it, "jpg") },
        )
        session.saveSmartBoardRecord(session.smartBoardRecord().withInsert(group))
    }

    /** The Smart Board insert page [pageId] belongs to, or null. */
    fun groupOf(session: DocumentSession, pageId: String): SmartBoardGroup? = session.smartBoardRecord().groupFor(pageId)

    private fun isSmartBoard(session: DocumentSession, group: SmartBoardGroup, stroke: Stroke): Boolean =
        session.modelOf(stroke)?.let(::key)?.let { it in group.strokeKeys } == true

    /**
     * The lines of each page of [group] that hold the user's own writing (not Smart Board's), relative
     * to the group's first page, so the follow-up layout keeps them free.
     */
    fun reserved(document: PagedDocument, session: DocumentSession, group: SmartBoardGroup): ReservedLines {
        val out = HashMap<Int, Set<Int>>()
        for ((k, id) in group.pageIds.withIndex()) {
            val page = document.pages.firstOrNull { it.id == id } ?: continue
            val paper = runCatching { RuledPaper.of(page.page.template, page.page.size) }.getOrNull() ?: continue
            val boxes = ArrayList<RectPt>()
            for (s in page.strokes) if (!isSmartBoard(session, group, s)) {
                val m = session.modelOf(s) ?: continue
                val r = m.brush.size / 2
                boxes += RectPt(m.samples.minOf { it.x } - r, m.samples.minOf { it.y } - r, m.samples.maxOf { it.x } + r, m.samples.maxOf { it.y } + r)
            }
            for (e in page.elements) if (e.id !in group.elementIds) boxes += RectPt(e.cx - e.width / 2, e.cy - e.height / 2, e.cx + e.width / 2, e.cy + e.height / 2)
            out[k] = ReservedLines.of(paper, boxes)
        }
        return ReservedLines(out)
    }

    /**
     * A follow-up result: the group's pages lose Smart Board's old writing and get the new layout (page
     * by page); pages the new layout needs beyond them are inserted after the last one. Pages, ink and
     * text are ONE undo step. @return the indices of the pages written, or null when [insert] is not a
     * follow-up of a known insert.
     */
    fun applyFollowUp(document: PagedDocument, session: DocumentSession, insert: InkInsert, pages: List<List<Pair<InkStroke, Stroke>>>): List<Int>? {
        val first = insert.followUpPageIds.firstOrNull() ?: return null
        val record = session.smartBoardRecord()
        val group = record.groupFor(first) ?: return null
        val indices = group.pageIds.mapNotNull { id -> document.pages.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
        if (indices.isEmpty()) return null
        val written = ArrayList<Int>()
        document.asOneStep {
            for ((k, idx) in indices.withIndex()) {
                val page = document.pages[idx]
                val old = page.strokes.filter { isSmartBoard(session, group, it) }
                val kept = page.elements.filter { it.id !in group.elementIds }
                document.editContent(
                    idx,
                    removedStrokes = old,
                    addedStrokes = pages.getOrNull(k)?.map { it.second }.orEmpty(),
                    elements = kept + insert.elements.getOrNull(k).orEmpty(),
                )
                written += idx
            }
            val extra = pages.drop(indices.size)
            if (extra.isNotEmpty()) {
                val base = document.pages[indices.last()].page
                written += document.insertPages(indices.last() + 1, extra.map { p -> base.copy(template = insert.template) to p.map { it.second } }, insert.elements.drop(indices.size))
            }
        }
        val notes = insert.notes?.let(NoteWire::decodeStored) ?: NoteDocument(emptyList())
        val updated = group.copy(
            pageIds = written.map { document.pages[it].id },
            notes = notes,
            elementIds = insert.elements.flatten().mapTo(HashSet()) { it.id },
            strokeKeys = insert.pages.flatten().mapNotNullTo(HashSet(), ::key),
            // AI-13: the photos of the page as Smart Board sent them (same bytes → same asset path).
            photos = if (insert.photos.isEmpty()) group.photos else insert.photos.map { session.addImage(it, "jpg") },
        )
        session.saveSmartBoardRecord(record.replace(group, updated))
        return written
    }
}
