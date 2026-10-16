package app.bestnote.engine.layout

import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteNormalizerTest {
    private val classic = NoteTemplates.byId("classic")

    @Test fun `short atoms stay inline, fractions limits and sums leave right-to-left sentences`() {
        listOf("x", "e^x", "30!", "V_0", "\\Delta t", "-3", "x^{2}").forEach { assertTrue(it, NoteNormalizer.isShortAtom(it)) }
        listOf("\\frac{1}{x}", "\\lim_{x\\to0} f", "\\sum_i a_i", "\\sqrt{2}", "a+b", "a-b", "x = 2", "a/b", "x \\le 3").forEach {
            assertFalse(it, NoteNormalizer.isShortAtom(it))
        }
    }

    @Test fun `a limit inside an Arabic sentence becomes its own Formula line right after it`() {
        val doc = NoteDocument(
            listOf(
                NoteBlock(NoteElement.Paragraph, text = "نهاية \\(\\lim_{x\\to\\infty}\\frac{x^{30}}{e^x}\\) تساوي صفرا لأن \\(e^x\\) أسرع."),
                NoteBlock(NoteElement.Paragraph, text = "the limit \\(\\frac{1}{x}\\) stays inline in English"),
            ),
        )
        val out = NoteNormalizer.mathOutOfRtl(doc).elements
        assertEquals(3, out.size)
        assertFalse(out[0].text!!.contains("\\lim"))
        assertTrue("short atom kept", out[0].text!!.contains("\\(e^x\\)"))
        assertEquals(NoteElement.Formula, out[1].element)
        assertEquals("\\lim_{x\\to\\infty}\\frac{x^{30}}{e^x}", out[1].math)
        assertTrue(out[2].text!!.contains("\\frac{1}{x}"))
    }

    @Test fun `hebrew step keeps its words and gets the moved formula as its own maths`() {
        val doc = NoteDocument(listOf(NoteBlock(NoteElement.StepSolution, steps = listOf(NoteStep(text = "נציב \\(\\frac{a}{b}\\) בנוסחה", why = "הגדרה")))))
        val step = NoteNormalizer.mathOutOfRtl(doc).elements.single().steps.single()
        assertEquals("נציב בנוסחה", step.text)
        assertEquals("\\frac{a}{b}", step.math)
        assertEquals("הגדרה", step.why)
    }

    @Test fun `margin remark keeps pointing at its element when formulas are inserted before it`() {
        val doc = NoteDocument(
            listOf(
                NoteBlock(NoteElement.Paragraph, text = "שבר \\(\\frac{1}{2}\\) כאן"),
                NoteBlock(NoteElement.KeyResult, math = "a=b"),
                NoteBlock(NoteElement.MarginRemark, text = "!", target = 1),
            ),
        )
        val out = NoteNormalizer.mathOutOfRtl(doc).elements
        assertEquals(NoteElement.KeyResult, out[out.single { it.element == NoteElement.MarginRemark }.target!!].element)
    }

    @Test fun `only the template's elements survive, mapped to the closest allowed one`() {
        val t = classic.copy(elements = setOf(NoteElement.Title, NoteElement.Note, NoteElement.Formula))
        val doc = NoteDocument(listOf(NoteBlock(NoteElement.Title, text = "T"), NoteBlock(NoteElement.Warning, text = "w"), NoteBlock(NoteElement.Table, rows = listOf(listOf("a")))))
        val out = NoteNormalizer.allowedOnly(doc, t.elements).elements
        assertEquals(listOf(NoteElement.Title, NoteElement.Note), out.map { it.element })
    }

    @Test fun `numbered formulas and derivations are numbered in order, inside columns too`() {
        val doc = NoteNormalizer.number(NoteSamples.hebrewPhysics())
        val numbers = doc.flatten().filter { it.element == NoteElement.NumberedFormula || it.element == NoteElement.Derivation }.map { it.number }
        assertEquals(listOf(1, 2, 3), numbers)
        assertEquals("①", NoteNormalizer.circled(1))
        assertEquals("⑳", NoteNormalizer.circled(20))
        assertEquals(3, NoteNormalizer.lastNumber(doc))
        assertEquals(listOf(4, 5, 6), NoteNormalizer.number(NoteSamples.hebrewPhysics(), start = 4).flatten().mapNotNull { it.number })
    }

    @Test fun `glossary accumulates without duplicates across spellings`() {
        var g = NotebookGlossary()
        val (g1, added1) = g.merge(listOf(GlossaryTerm("المشتقة", "נגזרת"), GlossaryTerm("النهاية", "גבול")))
        assertEquals(2, added1.size)
        g = g1
        // Same term with tashkeel, extra spaces, other case or punctuation: not new.
        val (g2, added2) = g.merge(listOf(GlossaryTerm("المُشتقّة ", "נגזרת!"), GlossaryTerm("Limit", "גבול"), GlossaryTerm("limit.", "x"), GlossaryTerm("", "y")))
        assertEquals(listOf("Limit"), added2.map { it.term })
        assertEquals(3, g2.terms.size)
        assertEquals(NotebookGlossary.key("נִגְזֶרֶת"), NotebookGlossary.key("נגזרת"))
    }

    @Test fun `only the page's new terms appear in its Glossary element at the end`() {
        val notebook = NotebookGlossary(listOf(GlossaryTerm("المشتقة", "נגזרת")))
        val doc = NoteNormalizer.normalize(NoteSamples.arabicCalculus(), classic, notebook)
        val last = doc.elements.last()
        assertEquals(NoteElement.Glossary, last.element)
        assertFalse(last.terms.any { it.term == "المشتقة" })
        assertEquals(4, doc.terms.size) // the page still reports all its terms for the notebook glossary
        val noElement = NoteNormalizer.normalize(NoteSamples.arabicCalculus(), classic.copy(elements = classic.elements - NoteElement.Glossary), notebook)
        assertTrue(noElement.elements.none { it.element == NoteElement.Glossary })
        val off = NoteNormalizer.normalize(NoteSamples.arabicCalculus(), classic.copy(ai = classic.ai.copy(glossary = false)), notebook)
        assertTrue(off.elements.none { it.element == NoteElement.Glossary })
    }

    @Test fun `ruled flow never covers the user's own lines`() {
        val paper = RuledPaper.of(PaperTemplate.Ruled, PageSize.A4)
        val flow = RuledFlow(paper, { _, _ -> 2 * paper.lineGap }, reserved = { page, line -> page == 0 && line in 3..6 })
        val slots = flow.place(List(6) { FlowItem("b$it", RegionRole.Main) })
        for (s in slots.filter { it.placement.pageIndex == 0 }) {
            assertTrue((s.firstLine until s.firstLine + s.lines).none { it in 3..6 })
        }
        assertEquals(7, slots[1].firstLine)
    }

    @Test fun `diagram kinds are drawn from data`() {
        val fbd = DiagramKinds.freeBody(listOf(DiagramKinds.Force("N", 90f), DiagramKinds.Force("mg", 270f)))!!
        assertEquals(2, fbd.shapes.count { it is app.bestnote.core.model.VectorShape.Arrow })
        val flow = DiagramKinds.flow(listOf("start", "yes", "no"), listOf(DiagramKinds.Edge(0, 1, "כן"), DiagramKinds.Edge(0, 2, "לא")))!!
        assertEquals(3, flow.shapes.count { it is app.bestnote.core.model.VectorShape.Rect })
        assertNull(DiagramKinds.flow(emptyList(), emptyList()))
        assertTrue(DiagramKinds.axes("t", "v", listOf(0f to 0f, 1f to 1f)).shapes.any { it is app.bestnote.core.model.VectorShape.Polyline })
    }

    @Test fun `study columns is a built-in template with the study elements`() {
        val t = NoteTemplates.byId(NoteTemplates.STUDY_COLUMNS)
        assertTrue(t.builtIn)
        assertTrue(NoteElement.Columns in t.elements && NoteElement.WorkedExample in t.elements)
        assertNull(t.look.paper)
        assertFalse(NoteElement.Columns in classic.elements)
    }

    @Test fun `user ink becomes reserved lines and survives the route encoding`() {
        val paper = RuledPaper.of(PaperTemplate.Ruled, PageSize.A4)
        val y = paper.lineY(4)
        val lines = ReservedLines.of(paper, listOf(app.bestnote.core.model.RectPt(100f, y - paper.lineGap * 0.6f, 200f, y + 1f)))
        assertEquals(setOf(4), lines)
        val r = ReservedLines(mapOf(0 to setOf(4, 5), 2 to setOf(1)))
        assertEquals(r, ReservedLines.decode(r.encode()))
        assertTrue(ReservedLines.decode(null).isEmpty)
    }
}
