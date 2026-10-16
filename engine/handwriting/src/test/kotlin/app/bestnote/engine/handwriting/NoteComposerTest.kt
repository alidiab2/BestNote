package app.bestnote.engine.handwriting

import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.layout.GlossaryTerm
import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteNormalizer
import app.bestnote.engine.layout.NotePart
import app.bestnote.engine.layout.NoteSamples
import app.bestnote.engine.layout.NoteStep
import app.bestnote.engine.layout.NoteTemplates
import app.bestnote.engine.layout.RuledPaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NoteComposerTest {
    private val paper = RuledPaper.of(PaperTemplate.Ruled, PageSize.A4)
    private val classic = NoteTemplates.byId("classic")
    private val study = NoteTemplates.byId(NoteTemplates.STUDY_COLUMNS)

    private fun write(doc: NoteDocument, template: app.bestnote.engine.layout.NoteTemplate = classic, normalize: Boolean = true): NotePages =
        NoteLayout.typeset(if (normalize) NoteNormalizer.normalize(doc, template) else doc, template, paper, AwtMeasure)

    /** Text boxes of different elements never overlap (each element's writing stays on its own lines). */
    private fun assertNoOverlap(pages: NotePages) {
        for (page in pages.pages.pages) {
            val boxes = page.runs.map { r ->
                val a = AwtMeasure.ascent(r.font) * r.sizePt * 0.75f
                val d = AwtMeasure.descent(r.font) * r.sizePt * 0.3f
                r to floatArrayOf(r.left, r.baseline - a, r.left + r.width, r.baseline + d)
            }
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                val (ra, a) = boxes[i]
                val (rb, b) = boxes[j]
                if (ra.block == rb.block) continue
                val ox = minOf(a[2], b[2]) - maxOf(a[0], b[0])
                val oy = minOf(a[3], b[3]) - maxOf(a[1], b[1])
                assertTrue("'${ra.text}' (${ra.block}) overlaps '${rb.text}' (${rb.block})", ox <= 0.5f || oy <= 0.5f)
            }
            for (r in page.runs) assertTrue("${r.text} inside the page", r.left >= 0f && r.left + r.width <= paper.width + 1f)
        }
    }

    @Test fun `every element lays out in Arabic, Hebrew and English without overlaps`() {
        val everything = NoteDocument(
            listOf(
                NoteBlock(NoteElement.Title, text = "Title"),
                NoteBlock(NoteElement.Heading, text = "Heading"),
                NoteBlock(NoteElement.Paragraph, text = "A paragraph with \\(x^2\\) inline and more words to wrap across the line a few times over."),
                NoteBlock(NoteElement.Bullets, items = listOf("one", "two \\(e^x\\)")),
                NoteBlock(NoteElement.Definition, head = "Term", text = "what it means"),
                NoteBlock(NoteElement.Theorem, head = "Pythagoras", text = "\\(a^2+b^2=c^2\\) in a right triangle"),
                NoteBlock(NoteElement.Formula, math = "\\int_0^1 x\\,dx = \\frac{1}{2}"),
                NoteBlock(NoteElement.KeyResult, math = "E = mc^2"),
                NoteBlock(NoteElement.Example, text = "an example", steps = listOf(NoteStep(math = "1+1=2"))),
                NoteBlock(NoteElement.StepSolution, steps = listOf(NoteStep(math = "x+1=3", why = "given"), NoteStep(text = "subtract 1", math = "x=2", why = "algebra"))),
                NoteBlock(NoteElement.Note, text = "a note"),
                NoteBlock(NoteElement.Warning, text = "careful"),
                NoteBlock(NoteElement.MarginRemark, text = "aside", target = 7),
                NoteBlock(NoteElement.Comparison, rows = listOf(listOf("A", "B"), listOf("fast", "slow"))),
                NoteBlock(NoteElement.Table, rows = listOf(listOf("x", "y", "z"), listOf("1", "2", "3"))),
                NoteBlock(NoteElement.Summary, items = listOf("first", "second")),
                NoteBlock(NoteElement.Glossary, terms = listOf(GlossaryTerm("limit", "גבול"))),
                NoteBlock(NoteElement.Diagram, diagram = app.bestnote.engine.layout.DiagramKinds.axes("x", "y")),
                NoteBlock(NoteElement.NumberedFormula, math = "v = v_0 + at"),
                NoteBlock(NoteElement.VariableLegend, rows = listOf(listOf("v", "speed", "m/s"))),
                NoteBlock(NoteElement.Derivation, steps = listOf(NoteStep(math = "a = \\frac{\\Delta v}{\\Delta t}"), NoteStep(math = "v = v_0 + at"))),
                NoteBlock(NoteElement.WorkedExample, text = "A car.", rows = listOf(listOf("a", "2", "m/s^2")), parts = listOf(NotePart("speed?", listOf("v = 2\\cdot 5"), "v = 10\\,(m/s)"))),
                NoteBlock(NoteElement.StarNote, text = "remember"),
                NoteBlock(NoteElement.Columns, columns = listOf(listOf(NoteBlock(NoteElement.Paragraph, text = "left")), listOf(NoteBlock(NoteElement.Paragraph, text = "right")))),
            ),
        )
        val all = study.copy(elements = NoteElement.entries.toSet())
        val english = write(everything, all)
        assertNoOverlap(english)
        // Every element was placed, and every text-bearing one wrote words.
        assertEquals(everything.elements.size, english.placed.map { it.second.key }.distinct().size)
        val written = english.pages.pages.flatMap { p -> p.runs.map { it.block } }.toSet()
        val silent = english.placed.map { it.second }.filter { it.key !in written && it.block.element != NoteElement.Diagram }
        assertTrue("no words from ${silent.map { it.block.element }}", silent.isEmpty())
        FontPreview.png(paper, english.pages.pages[0], "elements-en")
        assertNoOverlap(write(NoteSamples.arabicCalculus()))
        assertNoOverlap(write(NoteSamples.hebrewPhysics(), study))
    }

    @Test fun `samples render for previews`() {
        val (p1, ar) = NoteLayout.previewPage(classic, AwtMeasure)
        FontPreview.png(p1, ar, "sample-arabic")
        val (p2, he) = NoteLayout.previewPage(study, AwtMeasure, fallbackPaper = PaperTemplate.Grid)
        FontPreview.png(p2, he, "sample-hebrew-study")
        assertTrue(ar.runs.isNotEmpty() && he.runs.isNotEmpty())
        assertTrue("circled formula numbers", he.runs.any { it.text == "①" } && he.runs.any { it.text == "②" })
        assertTrue("hebrew part letters", he.runs.any { it.text.startsWith("א") })
    }

    @Test fun `right-to-left notes start at the right edge, columns too`() {
        val pages = write(NoteSamples.arabicCalculus())
        assertTrue(pages.rtl)
        val title = pages.pages.pages[0].runs.first { it.text.contains("المشتقة") }
        assertTrue(abs(title.left + title.width - paper.textRight) < paper.lineGap * 1.5f)

        val cols = NoteDocument(
            listOf(NoteBlock(NoteElement.Columns, columns = listOf(listOf(NoteBlock(NoteElement.Paragraph, text = "ראשון")), listOf(NoteBlock(NoteElement.Paragraph, text = "שני"))))),
        )
        val runs = write(cols, study).pages.pages[0].runs
        assertTrue("first column on the right", runs.first { it.text == "ראשון" }.left > runs.first { it.text == "שני" }.left)
    }

    @Test fun `a derivation is aligned at the equals sign`() {
        val doc = NoteDocument(listOf(NoteBlock(NoteElement.Derivation, number = 1, steps = listOf(NoteStep(math = "a = b + c"), NoteStep(math = "a\\Delta t = \\Delta v"), NoteStep(math = "V_t = V_0 + a t")))))
        val runs = write(doc, study, normalize = false).pages.pages[0].runs
        val eq = runs.filter { it.text == "=" }
        assertEquals(runs.map { it.text + "@" + it.left }.toString(), 3, eq.size)
        assertTrue(eq.map { it.left }.toString(), eq.all { abs(it.left - eq[0].left) < 0.5f })
        assertTrue(runs.map { it.text }.toString(), runs.any { it.text == "①" })
    }

    @Test fun `inline atoms never overlap the lines above and below`() {
        val tall = NoteDocument(
            listOf(
                NoteBlock(NoteElement.Paragraph, text = "first line"),
                NoteBlock(NoteElement.Paragraph, text = "growth \\(e^{x^{2^{n}}}\\) and \\(a_{i_{j}}\\) inline, and \\(\\frac{a}{b}\\) too"),
                NoteBlock(NoteElement.Paragraph, text = "last line"),
            ),
        )
        assertNoOverlap(write(tall, normalize = false))
    }
}
