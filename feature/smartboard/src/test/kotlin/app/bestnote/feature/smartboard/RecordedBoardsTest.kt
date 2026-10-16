package app.bestnote.feature.smartboard

import app.bestnote.ai.vision.AiDocumentParser
import app.bestnote.ai.vision.DemoVisionProvider
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.layout.RuledPaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recorded provider answers (Arabic, English, maths) written out by hand: renders PNGs and checks the page. */
class RecordedBoardsTest {
    private var n = 0
    private val pipeline = BoardToInk { "b${n++}" }

    private fun answer(name: String) = AiDocumentParser.parse(javaClass.getResource("/answers/$name")!!.readText()).document

    private val boards = listOf(
        "demo_en" to DemoVisionProvider.ENGLISH,
        "demo_ar" to DemoVisionProvider.ARABIC,
        "mixed_ar" to answer("full_mixed_board.json"),
        "derivatives_ar" to answer("optional_fields_only.json"),
        "integral_ar" to answer("truncated_arabic.json"),
        "newton_en" to answer("prose_and_fence.txt"),
        "calculus_en" to answer("english_calculus.json"),
    )

    @Test fun `every recorded board writes inside the page on every writable template`() {
        for ((name, doc) in boards) for (template in RuledPaper.writable) {
            val paper = RuledPaper.of(template, PageSize.A4)
            val ink = pipeline.write(doc, rtlHint = false, paper = paper)
            assertEquals("$name: dropped ${ink.dropReasons}", 0, ink.dropped)
            ink.pages.pages.forEachIndexed { i, page ->
                if (template == PaperTemplate.Ruled || template == PaperTemplate.Grid) PreviewPng.write(paper, page, "$name-${template.name}-$i")
                for (s in page) for (p in s.stroke.samples) {
                    assertTrue("$name/$template: ${p.x},${p.y} off the page", p.x in 0f..paper.width && p.y in 0f..paper.height)
                }
            }
        }
    }
}
