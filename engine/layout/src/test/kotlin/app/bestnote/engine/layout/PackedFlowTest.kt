package app.bestnote.engine.layout

import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PackedFlowTest {
    private val paper = RuledPaper.of(PaperTemplate.Ruled, PageSize.A4)
    private val lines = paper.lineCount

    private fun rows(vararg l: Int) = l.map { PackedFlow.Row(it) }

    /** A topic: a 2-line heading and a body of [body] one-line rows (or given rows). */
    private fun topic(i: Int, body: List<PackedFlow.Row>) = listOf(
        PackedFlow.PackBlock("h$i", { rows(2) }, topic = true, keepWithNext = true),
        PackedFlow.PackBlock("b$i", { body }),
    )

    private fun lecture(topics: Int, seed: Int = 7): List<PackedFlow.PackBlock> {
        val r = java.util.Random(seed.toLong())
        return listOf(PackedFlow.PackBlock("title", { rows(2) }, span = true, keepWithNext = true)) +
            (0 until topics).flatMap { i -> topic(i, List(3 + r.nextInt(9)) { PackedFlow.Row(1 + r.nextInt(2)) }) }
    }

    /** Lines used per (page, column), counting separators: the deepest line written. */
    private fun used(result: PackedFlow.Result): Map<Pair<Int, Int>, Int> =
        result.slots.filter { it.column >= 0 }.groupBy { it.page to it.column }.mapValues { (_, s) -> s.maxOf { it.firstLine + ((it.frame.bottom - it.frame.top) / paper.lineGap).toInt() } }

    @Test fun `pages are filled before the next one starts`() {
        val result = PackedFlow(paper, rtl = false).place(lecture(30))
        assertTrue("several pages", result.pageCount >= 2)
        val per = used(result)
        for (page in 0 until result.pageCount - 1) {
            val fill = (0 until result.columns).sumOf { per[page to it] ?: 0 } / (result.columns * lines.toFloat())
            assertTrue("page $page filled ${"%.2f".format(fill)}", fill >= 0.7f)
        }
    }

    @Test fun `the last columns are balanced`() {
        val result = PackedFlow(paper, rtl = false).place(lecture(3))
        assertEquals(1, result.pageCount)
        val per = used(result)
        val a = per[0 to 0]!!
        val b = per[0 to 1]!!
        assertTrue("columns $a and $b end level", abs(a - b) <= 4)
        assertTrue("half a page, not a full column", maxOf(a, b) < lines)
    }

    @Test fun `right to left notes start in the right column`() {
        val ltr = PackedFlow(paper, rtl = false).place(lecture(8))
        val rtl = PackedFlow(paper, rtl = true).place(lecture(8))
        val first = { r: PackedFlow.Result -> r.slots.first { it.key == "h0" } }
        val second = { r: PackedFlow.Result -> r.slots.first { it.column == 1 } }
        assertTrue(first(ltr).frame.left < second(ltr).frame.left)
        assertTrue(first(rtl).frame.left > second(rtl).frame.left)
        // The vertical divider sits between the columns.
        val divider = rtl.rules.first { it.x0 == it.x1 }
        assertTrue(divider.x0 > second(rtl).frame.right && divider.x0 < first(rtl).frame.left)
    }

    @Test fun `no orphan or widow lines and no heading at a column's foot`() {
        for (seed in 1..20) {
            val blocks = lecture(14, seed)
            val result = PackedFlow(paper, rtl = seed % 2 == 0).place(blocks)
            for ((key, slots) in result.slots.groupBy { it.key }) {
                if (slots.size < 2) continue
                // A split block: every piece has at least 2 lines.
                for (s in slots) assertTrue("seed $seed: $key piece of ${s.rowTo - s.rowFrom} row(s)", (s.frame.bottom - s.frame.top) / paper.lineGap > 1.5f)
            }
            for (h in result.slots.filter { it.key.startsWith("h") }) {
                val body = result.slots.first { it.key == "b" + h.key.drop(1) }
                assertEquals("seed $seed: ${h.key} with its body", h.page to h.column, body.page to body.column)
            }
        }
    }

    @Test fun `topics are divided by dashed rules, the title spans the page`() {
        val result = PackedFlow(paper, rtl = false).place(lecture(6))
        val title = result.slots.first { it.key == "title" }
        assertEquals(-1, title.column)
        assertEquals(paper.textRight - paper.textLeft, title.frame.right - title.frame.left, 0.01f)
        val horizontal = result.rules.filter { it.y0 == it.y1 }
        assertTrue("a rule between topics", horizontal.size >= 4)
        // Rules never cross writing.
        for (r in horizontal) for (s in result.slots.filter { it.page == r.page }) {
            val crosses = r.y0 > s.frame.top + 0.1f && r.y0 < s.frame.bottom - 0.1f && r.x1 > s.frame.left && r.x0 < s.frame.right
            assertFalse("rule at ${r.y0} crosses ${s.key}", crosses)
        }
    }

    @Test fun `a short block is never split to balance the columns`() {
        val blocks = listOf(PackedFlow.PackBlock("d", { rows(1, 1, 1, 1, 1) }, keepWhole = true))
        val result = PackedFlow(paper, rtl = false).place(blocks)
        assertEquals(1, result.slots.size)
    }

    @Test fun `the user's own lines are skipped`() {
        val reserved = setOf(4, 5, 6)
        val result = PackedFlow(paper, rtl = false, reserved = { page, line -> page == 0 && line in reserved }).place(lecture(6))
        for (s in result.slots.filter { it.page == 0 }) {
            val n = ((s.frame.bottom - s.frame.top) / paper.lineGap).toInt()
            assertTrue("${s.key} on lines ${s.firstLine}..${s.firstLine + n - 1}", (s.firstLine until s.firstLine + n).none { it in reserved })
        }
    }

    @Test fun `dense notes on a wide page get three columns, grid lines become writing lines`() {
        val wide = RuledPaper.of(PaperTemplate.Ruled, PageSize(842f, 595f))
        assertEquals(3, PackedFlow(wide, rtl = false, density = Density.Dense).columns)
        assertEquals(2, PackedFlow(paper, rtl = false, density = Density.Dense).columns)
        val grid = RuledPaper.of(PaperTemplate.Grid, PageSize.A4)
        assertEquals(grid.lineGap / 2, PackedFlow.paperFor(grid, Density.Normal).lineGap, 0.001f)
        assertEquals(grid.lineGap, PackedFlow.paperFor(grid, Density.Airy).lineGap, 0.001f)
        assertEquals(paper, PackedFlow.paperFor(paper, Density.Dense))
    }

    @Test fun `short equations share a line, glossary pairs repeating the notes are dropped`() {
        val doc = NoteDocument(
            listOf(
                NoteBlock(NoteElement.Formula, math = "a=1"),
                NoteBlock(NoteElement.Formula, math = "b=-1"),
                NoteBlock(NoteElement.Formula, math = "\\int \\frac{1}{x+2}\\,dx = \\ln|x+2|+c"),
                NoteBlock(NoteElement.StepSolution, steps = listOf(NoteStep(math = "x=2"), NoteStep(math = "y=3"), NoteStep(math = "z=x+y", why = "sum"))),
            ),
        )
        val tidy = PackedNotes.tidy(doc).elements
        assertEquals(3, tidy.size)
        assertEquals("a=1,\\quad b=-1", tidy[0].math)
        assertEquals(listOf("x=2,\\quad y=3", "z=x+y"), tidy[2].steps.map { it.math })

        val same = NoteDocument(listOf(NoteBlock(NoteElement.Paragraph, text = "نص")), terms = listOf(GlossaryTerm("التكامل", "التكامل"), GlossaryTerm("المقام", "المقام ")))
        val out = NoteNormalizer.normalize(same, NoteTemplates.byId("classic"))
        assertTrue("no glossary when no pair differs", out.elements.none { it.element == NoteElement.Glossary })
        val mixed = same.copy(terms = same.terms + GlossaryTerm("البسط", "מונה"))
        assertEquals(listOf("البسط"), NoteNormalizer.normalize(mixed, NoteTemplates.byId("classic")).elements.last().terms.map { it.term })
        // A term that already carries its translation in brackets is written once.
        val echo = same.copy(terms = listOf(GlossaryTerm("قاعدة لوبيتال (כלל לופיטל)", "כלל לופיטל")))
        assertEquals("قاعدة لوبيتال", NoteNormalizer.normalize(echo, NoteTemplates.byId("classic")).elements.last().terms.single().term)
    }
}
