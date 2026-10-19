package app.bestnote.feature.smartboard

import app.bestnote.ai.vision.AiDocument
import app.bestnote.ai.vision.BoardRequest
import app.bestnote.ai.vision.DemoVisionProvider
import app.bestnote.ai.vision.NotePrompts
import app.bestnote.ai.vision.NotesRequest
import app.bestnote.ai.vision.VisionImage
import app.bestnote.ai.vision.VisionProvider
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.handwriting.TextMeasure
import app.bestnote.engine.layout.GlossaryTerm
import app.bestnote.engine.layout.HandFont
import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteTemplates
import app.bestnote.engine.layout.NotebookGlossary
import app.bestnote.engine.layout.RuledPaper
import app.bestnote.engine.layout.TemplateChange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateNotesTest {
    private object Fake : TextMeasure {
        override fun width(text: String, font: HandFont, sizePt: Float) = text.length * sizePt * 0.45f
        override fun ascent(font: HandFont) = 0.8f
        override fun descent(font: HandFont) = 0.25f
    }

    /** The offline demo, counting how often the "AI" is asked and what it was told. */
    private class Counting : VisionProvider {
        val demo = DemoVisionProvider(latencyMs = 0)
        val requests = ArrayList<NotesRequest>()
        override suspend fun reconstructBoard(image: VisionImage, request: BoardRequest): AiDocument = demo.reconstructBoard(image, request)
        override suspend fun beautify(selection: VisionImage, widthPt: Float, heightPt: Float, language: String?) = demo.beautify(selection, widthPt, heightPt, language)
        override suspend fun writeNotes(image: VisionImage, request: NotesRequest): NoteDocument {
            requests += request
            return demo.writeNotes(image, request)
        }
    }

    private val photo = VisionImage(ByteArray(0), "image/jpeg")
    private val paper = RuledPaper.of(PaperTemplate.Ruled, PageSize.A4)
    private val classic = NoteTemplates.byId("classic")

    @Test fun `switching to a template that only changes the look re-draws without an AI call`() = runBlocking {
        val provider = Counting()
        val notes = TemplateNotes(Fake)
        notes.read(provider, listOf(photo), classic, rtl = false)
        val first = notes.render(classic, paper)
        assertEquals(1, provider.requests.size)

        val colorful = NoteTemplates.byId("colorful")
        assertEquals(TemplateChange.LookOnly, notes.switchTemplate(provider, listOf(photo), colorful, rtl = false))
        assertEquals("no new AI call", 1, provider.requests.size)
        val second = notes.render(colorful, paper)
        assertEquals(first.doc, second.doc)
        assertNotSame(first.pages, second.pages)

        // New AI instructions (the user's note) → the board is read again with them.
        val withNote = colorful.copy(ai = colorful.ai.copy(note = "write in Hebrew"))
        assertEquals(TemplateChange.NeedsAi, notes.switchTemplate(provider, listOf(photo), withNote, rtl = false))
        assertEquals(2, provider.requests.size)
        assertEquals("write in Hebrew", provider.requests.last().template.ai.note)

        // Other allowed elements → read again too.
        val fewer = withNote.copy(elements = withNote.elements - NoteElement.Table)
        assertEquals(TemplateChange.NeedsAi, notes.switchTemplate(provider, listOf(photo), fewer, rtl = false))
        assertEquals(3, provider.requests.size)
    }

    @Test fun `several photos are merged into one list, a follow-up merges into the page's notes`() = runBlocking {
        val provider = Counting()
        val notes = TemplateNotes(Fake)
        val doc = notes.read(provider, listOf(photo, photo), classic, rtl = true)
        assertEquals(2, provider.requests.size)
        assertEquals(null, provider.requests[0].current)
        assertTrue("second photo carries the notes so far", provider.requests[1].current != null)
        assertEquals("repeat removed", 1, doc.elements.count { it.element == NoteElement.Definition })

        val page = NoteDocument(doc.elements.take(3), doc.terms)
        val follow = TemplateNotes(Fake).read(provider, listOf(photo), classic, rtl = true, current = page)
        assertEquals(page, provider.requests.last().current)
        assertTrue(follow.elements.size > page.elements.size)
    }

    @Test fun `the page's new glossary terms are written at its end, the whole notebook glossary on demand`() = runBlocking {
        val notes = TemplateNotes(Fake)
        notes.read(Counting(), listOf(photo), classic, rtl = true)
        val notebook = NotebookGlossary(listOf(GlossaryTerm("المشتقة", "נגזרת"), GlossaryTerm("السرعة", "מהירות")))
        val page = notes.render(classic, paper, notebook).doc
        val glossary = page.elements.last()
        assertEquals(NoteElement.Glossary, glossary.element)
        assertTrue(glossary.terms.none { it.term == "المشتقة" })
        val full = notes.render(classic, paper, notebook, fullGlossary = true).doc.elements.last()
        assertTrue(full.terms.any { it.term == "السرعة" } && full.terms.any { it.term == "المماس" })
    }

    /**
     * REQ-002: a model that answers what it is asked: [NEW] elements per photo (the photo's number is
     * its first byte). Counts calls, the follow-up prompt's size and the elements written.
     */
    private class Board : VisionProvider {
        var calls = 0
        var written = 0
        val promptChars = ArrayList<Int>()
        override suspend fun reconstructBoard(image: VisionImage, request: BoardRequest): AiDocument = error("not used")
        override suspend fun beautify(selection: VisionImage, widthPt: Float, heightPt: Float, language: String?): AiDocument = error("not used")
        override suspend fun writeNotes(image: VisionImage, request: NotesRequest): NoteDocument {
            calls++
            promptChars += NotePrompts.user(request).length
            val photo = image.bytes.firstOrNull()?.toInt() ?: 0
            val lang = request.template.ai.notesLanguage ?: "board"
            val fresh = List(NEW) { j -> NoteBlock(NoteElement.Paragraph, text = "[$lang] photo $photo point $j " + "detail ".repeat(30)) }
            // The second photo repeats the first one's opening point: merged once.
            val repeat = if (photo == 1) listOf(NoteBlock(NoteElement.Paragraph, text = "[$lang] photo 0 point 0 " + "detail ".repeat(30))) else emptyList()
            written += fresh.size + repeat.size
            return NoteDocument(repeat + fresh)
        }

        companion object {
            const val NEW = 4
        }
    }

    private fun photos(n: Int) = List(n) { VisionImage(byteArrayOf(it.toByte()), "image/jpeg") }

    @Test fun `six photos cost six answers the size of one photo each, merged here`() = runBlocking {
        val board = Board()
        val doc = TemplateNotes(Fake).read(board, photos(6), classic, rtl = false)
        assertEquals(6, board.calls)
        assertEquals("every photo's points, the repeat once", 6 * Board.NEW, doc.elements.size)
        assertEquals("output grows with the photos, not their square", 6 * Board.NEW + 1, board.written)
        assertTrue(doc.elements.first().text!!.startsWith("[board] photo 0 point 0"))
        assertTrue(doc.elements.last().text!!.startsWith("[board] photo 5 point 3"))
        // The follow-up prompt is a short outline, not all the notes so far.
        assertTrue("prompts ${board.promptChars}", board.promptChars.max() < 3_000)
    }

    @Test fun `the same photos read again come from memory`() = runBlocking {
        val board = Board()
        val notes = TemplateNotes(Fake)
        val english = classic.copy(ai = classic.ai.copy(notesLanguage = "en"))
        val hebrew = classic.copy(ai = classic.ai.copy(notesLanguage = "he"))
        val keys = listOf("board-a", "board-b")
        val tag = "openai|gpt-test"
        notes.read(board, photos(2), english, rtl = false, imageKeys = keys, cacheTag = tag)
        notes.read(board, photos(2), hebrew, rtl = false, imageKeys = keys, cacheTag = tag)
        assertEquals(4, board.calls)
        // Language switched back and forth twice: no new call, the right language each time.
        val back = notes.read(board, photos(2), english, rtl = false, imageKeys = keys, cacheTag = tag)
        assertTrue(back.elements.all { it.text!!.startsWith("[en]") })
        assertTrue(notes.read(board, photos(2), hebrew, rtl = false, imageKeys = keys, cacheTag = tag).elements.all { it.text!!.startsWith("[he]") })
        assertEquals(4, board.calls)
        assertEquals(4, notes.aiCalls)

        // Only the photo that changed is read again.
        notes.read(board, photos(2), english, rtl = false, imageKeys = listOf("board-a", "board-c"), cacheTag = tag)
        assertEquals(5, board.calls)
        // Another model, or no keys (Regenerate, demo): asked afresh.
        notes.read(board, photos(2), english, rtl = false, imageKeys = keys, cacheTag = "openai|other")
        assertEquals(7, board.calls)
        notes.read(board, photos(2), english, rtl = false)
        assertEquals(9, board.calls)
    }
}
