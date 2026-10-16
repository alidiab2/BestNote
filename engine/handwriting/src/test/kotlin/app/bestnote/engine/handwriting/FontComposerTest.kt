package app.bestnote.engine.handwriting

import app.bestnote.core.model.BlockId
import app.bestnote.core.model.CalloutTone
import app.bestnote.core.model.InkSemantic
import app.bestnote.core.model.PageBlock
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.ProofStep
import app.bestnote.core.model.RectPt
import app.bestnote.core.model.Tool
import app.bestnote.engine.layout.FlowItem
import app.bestnote.engine.layout.NoteRole
import app.bestnote.engine.layout.RegionRole
import app.bestnote.engine.layout.RuledFlow
import app.bestnote.engine.layout.RuledPaper
import app.bestnote.engine.layout.StyleTemplate
import app.bestnote.engine.layout.StyleTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FontComposerTest {
    private val zero = RectPt(0f, 0f, 0f, 0f)

    private fun lecture(arabic: Boolean = false): List<PageBlock> = if (!arabic) {
        listOf(
            PageBlock.Heading(BlockId("title"), zero, 1, "Limits at infinity"),
            PageBlock.Heading(BlockId("h"), zero, 2, "Growth rates"),
            PageBlock.Callout(BlockId("def"), zero, CalloutTone.Definition, "Limit", "We write \\( \\lim_{x \\to \\infty} f(x) = L \\) when f gets close to L."),
            PageBlock.Math(BlockId("law"), zero, "\\lim_{x \\to \\infty} \\frac{x^{30}}{e^x + x^2 + 10} = 0", boxed = true, semantic = InkSemantic.Essential),
            PageBlock.MarginNote(BlockId("note"), zero, "exp beats every power", BlockId("law")),
            PageBlock.Callout(BlockId("ex"), zero, CalloutTone.Example, null, "\\( \\frac{x^2}{e^x} \\to 0 \\) and \\( \\sqrt{x} \\) grows slowly."),
            PageBlock.Proof(BlockId("proof"), zero, null, listOf(ProofStep("\\frac{x^{30}}{e^x} \\le 30!/x", null, "Taylor"), ProofStep("\\to 0", null, null))),
            PageBlock.Callout(BlockId("warn"), zero, CalloutTone.Warning, null, "Don't cancel x before taking the limit."),
            PageBlock.Bullets(BlockId("list"), zero, listOf("polynomials < exponentials", "logs < polynomials")),
        )
    } else {
        listOf(
            PageBlock.Heading(BlockId("title"), zero, 1, "المتتاليات الحسابية"),
            PageBlock.Callout(BlockId("def"), zero, CalloutTone.Definition, "متتالية حسابية", "الفرق بين كل حدين متتاليين ثابت ويسمى \\( d \\)"),
            PageBlock.Math(BlockId("an"), zero, "a_n = a_1 + (n-1)d", boxed = true, semantic = InkSemantic.Essential),
            PageBlock.MarginNote(BlockId("note"), zero, "n يبدأ من 1", BlockId("an")),
            PageBlock.Callout(BlockId("ex"), zero, CalloutTone.Example, null, "2, 5, 8, 11, … حيث d = 3"),
            PageBlock.Bullets(BlockId("list"), zero, listOf("if d > 0 the sequence increases", "إذا كان d < 0 فهي متناقصة")),
        )
    }

    private class Out(val paper: RuledPaper, val placed: List<Pair<Int, PageBlock>>, val pages: TypesetPages)

    private fun write(style: StyleTemplate, arabic: Boolean = false, template: PaperTemplate = style.paper ?: PaperTemplate.Ruled): Out {
        val paper = RuledPaper.of(template, PageSize.A4)
        val composer = FontComposer(paper, style, AwtMeasure, rtl = arabic)
        val blocks = lecture(arabic)
        val byKey = blocks.associateBy { it.id.value }
        val items = blocks.map {
            FlowItem(it.id.value, if (it is PageBlock.MarginNote) RegionRole.Margin else RegionRole.Main, keepWithNext = it is PageBlock.Heading, anchorKey = (it as? PageBlock.MarginNote)?.anchor?.value)
        }
        val slots = RuledFlow(paper, composer.measurer { byKey[it] }, arabic) { if (byKey[it.key] is PageBlock.Heading) style.spaceBeforeHeading else 0 }.place(items)
        val placed = slots.map { s -> s.placement.pageIndex to reframe(byKey.getValue(s.placement.key), s.placement.frame) }
        return Out(paper, placed, composer.compose(placed))
    }

    private fun reframe(b: PageBlock, f: RectPt): PageBlock = when (b) {
        is PageBlock.Heading -> b.copy(frame = f)
        is PageBlock.Paragraph -> b.copy(frame = f)
        is PageBlock.Bullets -> b.copy(frame = f)
        is PageBlock.Callout -> b.copy(frame = f)
        is PageBlock.Math -> b.copy(frame = f)
        is PageBlock.Proof -> b.copy(frame = f)
        is PageBlock.MarginNote -> b.copy(frame = f)
        is PageBlock.Diagram -> b.copy(frame = f)
    }

    @Test fun `every style writes every block on the ruled lines in its role colours`() {
        for (style in StyleTemplates.all) for (arabic in listOf(false, true)) {
            val out = write(style, arabic)
            out.pages.pages.forEachIndexed { i, p -> FontPreview.png(out.paper, p, "${style.id}-${if (arabic) "ar" else "en"}-$i") }
            val runs = out.pages.pages.flatMap { it.runs }
            assertTrue(runs.isNotEmpty())
            // Text sits on ruled lines: every baseline of body text is a ruled line (maths scripts aside).
            val bodySize = out.paper.lineGap * style.bodyScale
            for (r in runs.filter { abs(it.sizePt - bodySize) < 0.01f }) {
                val k = (r.baseline - out.paper.firstLine) / out.paper.lineGap
                assertTrue("${style.id}: '${r.text}' off the lines (${r.baseline})", abs(k - Math.round(k)) < 0.02f)
            }
            for (r in runs) assertTrue("${style.id}: '${r.text}' off the page", r.left >= 0f && r.left + r.width <= out.paper.width + 1f)
            // Role colours: title, definition, margin note and the boxed result in the style's colours.
            fun colorOf(block: String) = runs.first { it.block == block }.color
            style.role(NoteRole.Title).color?.let { assertEquals(style.id, it.toInt(), colorOf("title")) }
            style.role(NoteRole.Definition).color?.let { assertEquals(style.id, it.toInt(), colorOf("def")) }
            style.role(NoteRole.Margin).color?.let { assertEquals(style.id, it.toInt(), colorOf("note")) }
            val result = style.role(NoteRole.Result)
            result.color?.let { c ->
                val boxBlock = if (arabic) "an" else "law"
                assertEquals(style.id, c.toInt(), colorOf(boxBlock))
            }
            // Nothing writes the words "true"/"false" (a flag leaking into text).
            assertTrue(runs.none { it.text.trim() == "true" })
        }
    }

    @Test fun `colorful highlights headings under the text and cheat sheet is denser`() {
        val colorful = write(StyleTemplates.Colorful)
        assertTrue(colorful.pages.pages[0].ink.any { it.brush.tool == Tool.Highlighter })
        val classic = write(StyleTemplates.Classic, template = PaperTemplate.RuledNarrow)
        val dense = write(StyleTemplates.CheatSheet)
        fun lastLine(o: Out) = o.placed.maxOf { it.second.frame.bottom }
        assertTrue(lastLine(dense) <= lastLine(classic))
    }

    @Test fun `arabic lines start at the right and english inside them stays in order`() {
        val out = write(StyleTemplates.Classic, arabic = true)
        val runs = out.pages.pages[0].runs
        val title = runs.first { it.block == "title" }
        assertTrue(title.rtl && title.left + title.width > out.paper.width * 0.6f)
        // "if d > 0 the sequence increases": one left-to-right run, not reversed words.
        assertTrue(runs.any { it.text.startsWith("if d") && !it.rtl })
    }

    @Test fun `maths is real maths, not flattened text`() {
        val runs = write(StyleTemplates.Classic).pages.pages[0].runs
        assertTrue(runs.none { '↑' in it.text || "\\frac" in it.text || "rac{" in it.text })
        // The fraction's numerator is written smaller than the body, above the line.
        assertTrue(runs.any { it.text.contains("30") && it.sizePt < runs.first { r -> r.block == "def" }.sizePt })
    }
}
