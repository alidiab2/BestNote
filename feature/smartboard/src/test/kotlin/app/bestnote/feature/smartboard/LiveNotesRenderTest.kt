package app.bestnote.feature.smartboard

import app.bestnote.ai.vision.AiDocument
import app.bestnote.ai.vision.BoardRequest
import app.bestnote.ai.vision.DemoVisionProvider
import app.bestnote.ai.vision.NoteWire
import app.bestnote.ai.vision.NotesRequest
import app.bestnote.ai.vision.VisionImage
import app.bestnote.ai.vision.VisionProvider
import kotlinx.coroutines.runBlocking
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteTemplates
import app.bestnote.engine.layout.PageLayout
import app.bestnote.engine.layout.RuledPaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real DeepSeek answers (scripts/agent/ai-live.sh on the owner's L'Hôpital board, saved as notes.json)
 * written through the app's own path ([TemplateNotes.render]) with no AI call, one column ("before")
 * and packed ("after"), to build/smartboard-preview/live-*.png.
 */
class LiveNotesRenderTest {
    private fun notes(name: String): NoteDocument = NoteWire.parse(javaClass.getResource("/notes/$name.json")!!.readText())

    private val fixtures = listOf(
        "live_lhopital_classic_ar_he" to "classic",
        "live_lhopital_classic_ar" to "classic",
        "live_lhopital_study_ar_he" to NoteTemplates.STUDY_COLUMNS,
    )

    /** A provider that answers with the recorded notes (no network). */
    private class Recorded(val doc: NoteDocument) : VisionProvider {
        private val demo = DemoVisionProvider(latencyMs = 0)
        override suspend fun reconstructBoard(image: VisionImage, request: BoardRequest): AiDocument = demo.reconstructBoard(image, request)
        override suspend fun beautify(selection: VisionImage, widthPt: Float, heightPt: Float, language: String?) = demo.beautify(selection, widthPt, heightPt, language)
        override suspend fun writeNotes(image: VisionImage, request: NotesRequest): NoteDocument = doc
    }

    private fun rendered(doc: NoteDocument, templateId: String, packed: Boolean, paper: RuledPaper) = TemplateNotes(AwtFonts).let { n ->
        val base = NoteTemplates.byId(templateId)
        val template = if (packed) base else base.copy(look = base.look.copy(layout = PageLayout.Flow))
        runBlocking { n.read(Recorded(doc), listOf(VisionImage(ByteArray(0), "image/jpeg")), template, rtl = true) }
        n.render(template, paper, rtlHint = true)
    }

    @Test fun `recorded live answers pack onto fewer pages and keep every element`() {
        for ((name, template) in fixtures) {
            val doc = notes(name)
            assertTrue("$name parsed", doc.elements.size >= 8)
            for (paperTemplate in listOf(PaperTemplate.Grid, PaperTemplate.Ruled)) {
                val paper = RuledPaper.of(paperTemplate, PageSize.A4)
                val before = rendered(doc, template, packed = false, paper)
                val after = rendered(doc, template, packed = true, paper)
                before.pages.pages.forEachIndexed { i, p -> TypesetPng.png(paper, p, "live-$name-${paperTemplate.name}-flow-$i", dir = "build/smartboard-preview") }
                after.pages.pages.forEachIndexed { i, p -> TypesetPng.png(paper, p, "live-$name-${paperTemplate.name}-packed-$i", dir = "build/smartboard-preview") }
                assertTrue("$name/$paperTemplate: ${after.pages.pages.size} ≤ ${before.pages.pages.size} pages", after.pages.pages.size <= before.pages.pages.size)
                assertTrue("$name/$paperTemplate: at most half the pages", after.pages.pages.size <= maxOf(1, (before.pages.pages.size + 1) / 2))
                // Every element of the notes is written (split elements count once).
                assertEquals(after.doc.elements.size, after.placed.map { it.second.key }.distinct().size)
                val text = after.pages.pages.flatMap { it.runs }.joinToString(" ") { it.text }
                assertTrue("$name: no LaTeX source on the page", !text.contains("\\frac") && !text.contains("\\lim") && !text.contains("begin"))
            }
        }
        // Without a terms language the model now leaves the glossary out (no repeated words).
        assertTrue(notes("live_lhopital_classic_ar").let { d -> d.allTerms().isEmpty() && d.elements.none { it.element == NoteElement.Glossary } })
    }
}
