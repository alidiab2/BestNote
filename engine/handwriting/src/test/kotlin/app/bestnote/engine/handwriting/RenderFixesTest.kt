package app.bestnote.engine.handwriting

import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.layout.HandFont
import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteNormalizer
import app.bestnote.engine.layout.NoteTemplates
import app.bestnote.engine.layout.RuledPaper
import app.bestnote.engine.layout.WritingScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rendering bugs from the owner's screenshots (TPL-03): cases, |x|, maths in table cells, mixed Arabic + Hebrew, packing. */
class RenderFixesTest {
    private val grid = RuledPaper.of(PaperTemplate.Grid, PageSize.A4)
    private val classic = NoteTemplates.byId("classic")

    private fun write(doc: NoteDocument, paper: RuledPaper = grid) = NoteLayout.typeset(NoteNormalizer.normalize(doc, classic), classic, paper, AwtMeasure)

    private fun texts(p: NotePages) = p.pages.pages.flatMap { it.runs }.map { it.text }

    @Test fun `cases aligned and matrices parse into rows of cells`() {
        val cases = MathParser.parse("\\begin{cases} a+b=0 \\\\ 3a+2b=1 \\end{cases}").single().single() as MathParser.Matrix
        assertEquals(2, cases.rows.size)
        assertEquals("{", cases.left)
        val m = MathParser.parse("A=\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}").single().last() as MathParser.Matrix
        assertEquals(listOf(2, 2), m.rows.map { it.size })
        assertEquals("(" to ")", m.left to m.right)
        // A whole aligned formula: its rows are the formula's lines.
        assertEquals(2, MathParser.parse("\\begin{aligned} x &= 1 \\\\ y &= 2 \\end{aligned}").size)
        val abs = MathParser.parse("\\left| x+3 \\right| + \\lvert y \\rvert").single()
        assertEquals("|", (abs.first() as MathParser.Delim).left)
        assertTrue(MathParser.unknownCommands("\\begin{cases} a \\\\ b \\end{cases} \\left( x \\right)").isEmpty())
    }

    @Test fun `cases and absolute values are drawn, never written as words or slashes`() {
        val doc = NoteDocument(
            listOf(
                NoteBlock(NoteElement.Formula, math = "\\begin{cases} a+b=0 \\\\ 3a+2b=1 \\end{cases}"),
                NoteBlock(NoteElement.KeyResult, math = "\\ln|x+2|-\\ln\\left|x+3\\right|+c"),
            ),
        )
        val pages = write(doc)
        val t = texts(pages)
        assertTrue(t.toString(), t.none { "begin" in it || "cases" in it || "|" in it || "/" in it })
        // Each bar is a pen stroke: 4 bars + 2 cases brace + box ≥ 6 strokes besides the text.
        assertTrue(pages.pages.pages[0].ink.size >= 6)
    }

    @Test fun `bare LaTeX in table cells is laid out as maths`() {
        val doc = NoteDocument(listOf(NoteBlock(NoteElement.Table, rows = listOf(listOf("المقام", "الشكل"), listOf("عامل خطي", "\\frac{a}{x^0}"), listOf("عامل تربيعي", "\\frac{bx+c}{x^2}")))))
        val t = texts(write(doc))
        assertTrue(t.toString(), t.none { "\\" in it || "frac" in it || "{" in it })
        assertTrue("numerator and denominator written", t.contains("a") && t.any { it.startsWith("bx") })
    }

    @Test fun `a mixed Arabic and Hebrew line is ordered by Unicode bidi, each script in its own hand`() {
        val title = "التكامل بالكسور الجزئية (אינטגרל בשברים חלקיים)"
        val pieces = LineBidi.visual(title, rtl = true)
        val hebrew = pieces.filter { it.script == WritingScript.Hebrew }
        val arabic = pieces.filter { it.script == WritingScript.Arabic }
        assertTrue(hebrew.isNotEmpty() && arabic.isNotEmpty())
        assertTrue("no piece mixes scripts", pieces.none { p -> WritingScript.hasRtl(p.text) && p.text.any { WritingScript.of(it) == WritingScript.Hebrew } && p.text.any { WritingScript.of(it) == WritingScript.Arabic } })
        // Right to left: the Arabic (read first) is on the right, the Hebrew after it on the left.
        assertTrue(pieces.indexOfFirst { it.script == WritingScript.Hebrew } < pieces.indexOfFirst { it.script == WritingScript.Arabic })
        // The Hebrew words are one right-to-left run in reading order (the renderer writes it right to left),
        // with the closing bracket; the opening one ends the Arabic run: both mirror around the Hebrew.
        assertTrue(hebrew.all { it.rtl })
        assertEquals("אינטגרל בשברים חלקיים)", hebrew.joinToString("") { it.text })
        assertTrue(arabic.last().text.trimEnd().endsWith("("))

        // An English term and a number inside an Arabic sentence keep their own order.
        val mixed = LineBidi.visual("السرعة speed of light هي 300 km", rtl = true)
        val latin = mixed.filter { it.script == WritingScript.Latin }.map { it.text.trim() }
        assertTrue(latin.toString(), latin.contains("speed of light") || latin.joinToString(" ").contains("speed of light"))

        // Written: Hebrew runs in the Hebrew hand, Arabic runs in the Arabic hand.
        val runs = write(NoteDocument(listOf(NoteBlock(NoteElement.Title, text = title)))).pages.pages[0].runs
        assertTrue(runs.filter { r -> r.text.any { WritingScript.of(it) == WritingScript.Hebrew } }.all { it.font == HandFont.GveretLevin })
        assertTrue(runs.filter { r -> r.text.any { WritingScript.of(it) == WritingScript.Arabic } }.all { it.font == HandFont.ArefRuqaa })
    }

    @Test fun `glossary without a second language shows one column or none`() {
        val doc = Fixtures.partialFractionsArabic()
        assertTrue("identical pairs dropped", write(doc).doc.elements.none { it.element == NoteElement.Glossary })
        val he = write(Fixtures.partialFractionsWithHebrewTerms())
        assertTrue(texts(he).contains("מכנה"))
    }

    @Test fun `the owner's partial fractions board packs onto one page in two balanced columns`() {
        for (paper in listOf(grid, RuledPaper.of(PaperTemplate.Ruled, PageSize.A4))) {
            val pages = write(Fixtures.partialFractionsWithHebrewTerms(), paper)
            assertEquals("one page on ${paper.template}", 1, pages.pages.pages.size)
            val packed = PackedLayoutCheck(pages)
            packed.assertInsidePage()
            packed.assertColumnsDoNotOverlap()
            // Right to left: the first heading is in the right column.
            val first = pages.placed.first { it.second.block.element == NoteElement.Heading }.second
            assertTrue(first.frame.left > (paper.textLeft + paper.textRight) / 2 - 1f)
        }
        // One column flow took three pages for the same notes on grid paper.
        val flow = classic.copy(look = classic.look.copy(layout = app.bestnote.engine.layout.PageLayout.Flow))
        val before = NoteLayout.typeset(NoteNormalizer.normalize(Fixtures.partialFractionsArabic(), flow), flow, grid, AwtMeasure)
        assertTrue(before.pages.pages.size >= 2)
    }

    @Test fun `no lone line on a new page`() {
        // A long lecture: every page after the first starts with at least two lines of writing, every block piece too.
        val doc = NoteDocument((0 until 6).flatMap { Fixtures.lHopitalArabic().elements.drop(1) })
        val pages = write(doc)
        assertTrue(pages.pages.pages.size >= 2)
        for ((key, slots) in pages.placed.groupBy { it.second.key }) {
            if (slots.size < 2) continue
            for ((_, p) in slots) assertTrue("$key piece ${p.rows}", (p.frame.bottom - p.frame.top) / pages.paper.lineGap > 1.5f)
        }
    }

    /** Geometry checks on a packed page. */
    private class PackedLayoutCheck(val pages: NotePages) {
        fun assertInsidePage() {
            for (page in pages.pages.pages) for (r in page.runs) {
                assertTrue("'${r.text}' inside the page", r.left >= -1f && r.left + r.width <= pages.paper.width + 1f && r.baseline <= pages.paper.height)
            }
        }

        fun assertColumnsDoNotOverlap() {
            val slots = pages.placed.map { it.second }
            for (i in slots.indices) for (j in i + 1 until slots.size) {
                val a = slots[i].frame
                val b = slots[j].frame
                if (pages.placed[i].first != pages.placed[j].first) continue
                val ox = minOf(a.right, b.right) - maxOf(a.left, b.left)
                val oy = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
                assertFalse("${slots[i].key} and ${slots[j].key} overlap", ox > 1f && oy > 1f)
            }
        }
    }
}
