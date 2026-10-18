package app.bestnote.feature.smartboard

import app.bestnote.ai.vision.LookPatch
import app.bestnote.engine.layout.NoteDocument

/**
 * AI-13: what "Ask Smart Board…" can undo. Every refine (AI edit, template switch, font, colour) pushes
 * the page as it was, so each one is one undo step; [requests] keeps the last few things the user asked.
 */
internal class RefineSession(private val maxUndo: Int = MAX_UNDO, private val maxRequests: Int = MAX_REQUESTS) {
    /** The page before one refine: notes, look changes and template. */
    data class Snapshot(val notes: NoteDocument, val look: LookPatch, val templateId: String)

    private val undo = ArrayDeque<Snapshot>()
    private val asked = ArrayDeque<String>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val requests: List<String> get() = asked.toList()

    fun push(before: Snapshot) {
        undo.addLast(before)
        while (undo.size > maxUndo) undo.removeFirst()
    }

    fun pop(): Snapshot? = undo.removeLastOrNull()

    /** Remembers [request] (most recent first, no repeats). */
    fun remember(request: String) {
        val r = request.trim().takeIf { it.isNotEmpty() } ?: return
        asked.remove(r)
        asked.addFirst(r)
        while (asked.size > maxRequests) asked.removeLast()
    }

    fun clear() = undo.clear()

    private companion object {
        const val MAX_UNDO = 20
        const val MAX_REQUESTS = 5
    }
}
