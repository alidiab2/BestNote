package app.bestnote.engine.handwriting

import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteNormalizer
import app.bestnote.engine.layout.NoteSamples
import app.bestnote.engine.layout.NoteTemplate
import app.bestnote.engine.layout.NoteTemplates
import app.bestnote.engine.layout.PageLayout
import app.bestnote.engine.layout.RuledPaper
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Visual check WITHOUT AI calls: lays out recorded notes with a template and writes every page to
 * build/font-preview/render-<name>-<paper>-<layout>-<page>.png (real fonts through java.awt), the
 * one-column flow ("before") next to the packed notebook page ("after").
 */
class NotebookRenderTest {
    private val docs: List<Pair<String, NoteDocument>> = listOf(
        "demo" to NoteSamples.arabicCalculus(),
        "demo-he" to NoteSamples.hebrewPhysics(),
        "partial" to Fixtures.partialFractionsArabic(),
        "partial-he" to Fixtures.partialFractionsWithHebrewTerms(),
        "lhopital" to Fixtures.lHopitalArabic(),
        "english" to NoteSamples.englishCalculus(),
    )

    private fun render(name: String, doc: NoteDocument, template: NoteTemplate, paperTemplate: PaperTemplate, tag: String): NotePages {
        val paper = RuledPaper.of(paperTemplate, PageSize.A4)
        val pages = NoteLayout.typeset(NoteNormalizer.normalize(doc, template), template, paper, AwtMeasure)
        pages.pages.pages.forEachIndexed { i, p -> FontPreview.png(paper, p, "render-$name-${paperTemplate.name}-$tag-$i") }
        return pages
    }

    @Test fun `recorded notes render before and after on grid and ruled paper`() {
        val classic = NoteTemplates.byId("classic")
        val flow = classic.copy(look = classic.look.copy(layout = PageLayout.Flow))
        val study = NoteTemplates.byId(NoteTemplates.STUDY_COLUMNS)
        for ((name, doc) in docs) for (paper in listOf(PaperTemplate.Grid, PaperTemplate.Ruled)) {
            val before = render(name, doc, flow, paper, "flow")
            val after = render(name, doc, classic, paper, "packed")
            render(name, doc, study, paper, "dense")
            assertTrue("$name/$paper: packed uses no more pages", after.pages.pages.size <= before.pages.pages.size)
        }
    }
}
