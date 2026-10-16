package app.bestnote.ai.vision

import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NotebookGlossary

/**
 * Deterministic safety net for follow-up photos: the model is asked to merge, but a board
 * photographed again half-way through a lecture must never produce the same definition twice even
 * when the model repeats it. Elements are the same when their element type and words match after
 * normalisation (case, spacing, vowel points, punctuation).
 */
object NoteMerge {
    /** [doc] without later repeats of an earlier element (margin remark targets follow their element). */
    fun dedupe(doc: NoteDocument): NoteDocument {
        val seen = HashSet<String>()
        val newIndex = IntArray(doc.elements.size) { -1 }
        val out = ArrayList<NoteBlock>()
        for ((i, b) in doc.elements.withIndex()) {
            val k = key(b)
            if (k.isNotBlank() && !seen.add(k)) {
                newIndex[i] = out.indexOfFirst { key(it) == k }
                continue
            }
            newIndex[i] = out.size
            out += b
        }
        val retargeted = out.map { b -> b.target?.let { t -> b.copy(target = newIndex.getOrNull(t)?.takeIf { it >= 0 }) } ?: b }
        return NoteDocument(retargeted, NotebookGlossary().merge(doc.terms).first.terms)
    }

    /** Without a model: the page's notes, then the new board's elements that are not already there. */
    fun merge(current: NoteDocument, fresh: NoteDocument): NoteDocument {
        val shift = current.elements.size
        val moved = fresh.elements.map { b -> b.target?.let { b.copy(target = it + shift) } ?: b }
        return dedupe(NoteDocument(current.elements + moved, current.terms + fresh.terms))
    }

    private fun key(b: NoteBlock): String {
        val content = NotebookGlossary.key(
            b.words() + " " + (b.math ?: "") + b.steps.joinToString(" ") { it.math.orEmpty() } +
                b.parts.joinToString(" ") { it.steps.joinToString(" ") + it.result.orEmpty() } + b.columns.flatten().joinToString(" ") { key(it) },
        )
        return if (content.isBlank()) "" else b.element.name + "|" + content
    }
}
