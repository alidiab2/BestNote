package app.bestnote.feature.smartboard

import app.bestnote.ai.vision.NotesRequest
import app.bestnote.ai.vision.VisionImage
import app.bestnote.ai.vision.VisionProvider
import app.bestnote.engine.handwriting.FontComposer
import app.bestnote.engine.handwriting.NoteLayout
import app.bestnote.engine.handwriting.NotePages
import app.bestnote.engine.handwriting.TextMeasure
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteNormalizer
import app.bestnote.engine.layout.NoteTemplate
import app.bestnote.engine.layout.NotebookGlossary
import app.bestnote.engine.layout.ReservedLines
import app.bestnote.engine.layout.RuledPaper
import app.bestnote.engine.layout.TemplateChange

/**
 * Smart Board's template-first core, without Android: photos → the provider writes the template's
 * elements (each further photo is merged into the notes so far) → cached raw notes → normalised and
 * laid out in the template's look. [switchTemplate] re-draws the cached notes without an AI call when
 * only the look changed, and reads the photos again only when the AI instructions or elements did.
 */
internal class TemplateNotes(private val measure: TextMeasure) {
    /** The raw AI notes and the template they were asked for. */
    var raw: NoteDocument? = null
        private set
    var askedWith: NoteTemplate? = null
        private set

    /** AI calls made so far (for tests and diagnostics). */
    var aiCalls = 0
        private set

    /**
     * Reads [images] in order with [template]: the first photo (or [current], the page's notes on a
     * follow-up) is the base and every later photo is merged into it by the provider.
     */
    suspend fun read(provider: VisionProvider, images: List<VisionImage>, template: NoteTemplate, rtl: Boolean, current: NoteDocument? = null): NoteDocument {
        var doc = current
        for (image in images) {
            aiCalls++
            doc = provider.writeNotes(image, NotesRequest(template, rtl, doc))
        }
        val out = doc ?: NoteDocument(emptyList())
        raw = out
        askedWith = template
        return out
    }

    /** AI-13: replaces the cached notes after a refine or an undo (no AI call). */
    fun restore(doc: NoteDocument, template: NoteTemplate) {
        raw = doc
        askedWith = template
    }

    /**
     * Switches to [template]: [TemplateChange.LookOnly] keeps the cached notes (no call),
     * [TemplateChange.NeedsAi] reads [images] again.
     */
    suspend fun switchTemplate(provider: VisionProvider, images: List<VisionImage>, template: NoteTemplate, rtl: Boolean, current: NoteDocument? = null): TemplateChange {
        val before = askedWith ?: return TemplateChange.NeedsAi.also { read(provider, images, template, rtl, current) }
        val change = TemplateChange.between(before, template)
        if (change == TemplateChange.NeedsAi) read(provider, images, template, rtl, current)
        return change
    }

    /**
     * The cached notes as the page shows them in [template]: normalised (maths out of RTL, numbers,
     * glossary) and laid out. [fullGlossary]: the Glossary element lists the whole notebook glossary
     * plus this page's terms (inserted on demand), not only the page's new terms.
     */
    fun render(
        template: NoteTemplate,
        paper: RuledPaper,
        glossary: NotebookGlossary = NotebookGlossary(),
        fullGlossary: Boolean = false,
        reserved: ReservedLines = ReservedLines(),
        rtlHint: Boolean = false,
        penColor: Int = FontComposer.INK,
    ): NotePages {
        val base = raw ?: NoteDocument(emptyList())
        val shown = if (fullGlossary) {
            val t = template.copy(ai = template.ai.copy(glossary = true), elements = template.elements + app.bestnote.engine.layout.NoteElement.Glossary)
            NoteNormalizer.normalize(base.copy(terms = glossary.terms + base.terms), t, NotebookGlossary())
        } else {
            NoteNormalizer.normalize(base, template, glossary)
        }
        return NoteLayout.typeset(shown, template, paper, measure, rtlHint, penColor) { page, line -> reserved.contains(page, line) }
    }
}
