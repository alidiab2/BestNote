package app.bestnote.feature.smartboard

import app.bestnote.ai.vision.NoteMerge
import app.bestnote.ai.vision.NotePrompts
import app.bestnote.ai.vision.NotesRequest
import app.bestnote.ai.vision.VisionImage
import app.bestnote.ai.vision.VisionProvider
import app.bestnote.engine.handwriting.FontComposer
import app.bestnote.engine.handwriting.NoteLayout
import app.bestnote.engine.handwriting.NotePages
import app.bestnote.engine.handwriting.TextMeasure
import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteNormalizer
import app.bestnote.engine.layout.NoteTemplate
import app.bestnote.engine.layout.NotebookGlossary
import app.bestnote.engine.layout.ReservedLines
import app.bestnote.engine.layout.RuledPaper
import app.bestnote.engine.layout.TemplateChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Smart Board's template-first core, without Android: photos → the provider writes the template's
 * elements (each further photo adds what is new, merged here) → cached raw notes → normalised and
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
     * follow-up) is the base; for every later photo the provider writes only what is new (it gets a
     * short outline of the notes so far) and [NoteMerge.merge] adds it here, so the output per photo
     * stays the size of that photo, not of all the notes before it (REQ-002).
     *
     * [imageKeys]: a stable id per photo (e.g. a hash of the flattened board, not of the canary-stamped
     * bytes). With them, an answer already read for the same photo, prompt and [cacheTag] (provider +
     * model) comes from memory: switching the language or the template back, or reading again after
     * going back to the photos, costs no AI call. Null (demo, Regenerate) always asks the provider.
     */
    suspend fun read(
        provider: VisionProvider,
        images: List<VisionImage>,
        template: NoteTemplate,
        rtl: Boolean,
        current: NoteDocument? = null,
        imageKeys: List<String>? = null,
        cacheTag: String = "",
        figures: (doc: NoteDocument, photo: Int) -> NoteDocument = { d, _ -> d },
    ): NoteDocument {
        var doc = current?.takeIf { !it.isEmpty }
        for ((i, image) in images.withIndex()) {
            val request = NotesRequest(template, rtl, doc)
            val key = imageKeys?.getOrNull(i)?.let { cacheKey(cacheTag, it, request) }
            val answer = key?.let { synchronized(cache) { cache[it] } } ?: run {
                aiCalls++
                // Drawings already on the page stay where they are: a Diagram that only points back at one ("ref") is dropped.
                withoutRefs(provider.writeNotes(image, request)).also { a -> key?.let { synchronized(cache) { cache[it] = a } } }
            }
            // Redrawing grows with the drawings: never on the main thread.
            val fresh = withContext(Dispatchers.Default) { figures(answer, i) }
            doc = if (doc == null) fresh else NoteMerge.merge(doc, fresh)
        }
        val out = doc ?: NoteDocument(emptyList())
        raw = out
        askedWith = template
        return out
    }

    /** Answers already read (REQ-002), least recently used dropped first. In memory only, for this screen. */
    private val cache = object : LinkedHashMap<String, NoteDocument>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NoteDocument>?) = size > CACHE_SIZE
    }

    private fun cacheKey(tag: String, image: String, request: NotesRequest): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (part in listOf(tag, image, NotePrompts.system(request), NotePrompts.user(request))) {
            md.update(part.toByteArray(Charsets.UTF_8))
            md.update(0)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun withoutRefs(doc: NoteDocument): NoteDocument {
        fun keep(b: NoteBlock) = !(b.element == NoteElement.Diagram && b.figure?.ref != null && b.figure?.box == null)
        fun fix(b: NoteBlock): NoteBlock = if (b.columns.isEmpty()) b else b.copy(columns = b.columns.map { c -> c.filter(::keep).map(::fix) })
        return doc.copy(elements = doc.elements.filter(::keep).map(::fix))
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
    suspend fun switchTemplate(
        provider: VisionProvider,
        images: List<VisionImage>,
        template: NoteTemplate,
        rtl: Boolean,
        current: NoteDocument? = null,
        figures: (doc: NoteDocument, photo: Int) -> NoteDocument = { d, _ -> d },
    ): TemplateChange {
        val before = askedWith ?: return TemplateChange.NeedsAi.also { read(provider, images, template, rtl, current, figures = figures) }
        val change = TemplateChange.between(before, template)
        if (change == TemplateChange.NeedsAi) read(provider, images, template, rtl, current, figures = figures)
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

    private companion object {
        const val CACHE_SIZE = 32
    }
}
