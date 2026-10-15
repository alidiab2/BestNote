package app.bestnote.engine.handwriting

import app.bestnote.core.model.BlockId
import app.bestnote.core.model.CalloutTone
import app.bestnote.core.model.InkSemantic
import app.bestnote.core.model.PageBlock
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.ProofStep
import app.bestnote.core.model.RectPt
import app.bestnote.core.model.Vec2
import app.bestnote.core.model.VectorDiagram
import app.bestnote.core.model.VectorShape
import app.bestnote.engine.layout.FlowItem
import app.bestnote.engine.layout.NotebookPalette
import app.bestnote.engine.layout.RegionRole
import app.bestnote.engine.layout.RuledFlow
import app.bestnote.engine.layout.RuledPaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageComposerTest {
    private val zero = RectPt(0f, 0f, 0f, 0f)

    private fun lecture(): List<PageBlock> = listOf(
        PageBlock.Heading(BlockId("title"), zero, 1, "Inclined plane"),
        PageBlock.Callout(BlockId("def"), zero, CalloutTone.Definition, null, "The normal force is perpendicular to the surface the block rests on."),
        PageBlock.Diagram(
            BlockId("fig"), zero,
            VectorDiagram(
                RectPt(0f, 0f, 200f, 100f),
                listOf(
                    VectorShape.Polyline(listOf(Vec2(0f, 100f), Vec2(200f, 100f), Vec2(200f, 0f)), closed = true),
                    VectorShape.Arrow(Vec2(120f, 50f), Vec2(120f, 95f)),
                    VectorShape.Label(Vec2(126f, 90f), "mg"),
                ),
            ),
        ),
        PageBlock.Math(BlockId("law"), zero, "F_{\\parallel} = mg\\sin\\theta", boxed = true, semantic = InkSemantic.Essential),
        PageBlock.MarginNote(BlockId("note"), zero, "check: 30° gives mg/2", BlockId("law")),
        PageBlock.Proof(BlockId("proof"), zero, null, listOf(ProofStep("a = \\frac{F}{m}", null, "Newton's second law"), ProofStep("a = g\\sin\\theta", null, null))),
        PageBlock.Bullets(BlockId("list"), zero, listOf("friction acts up the slope", "the block slides when tan θ > μ")),
        PageBlock.Paragraph(BlockId("p"), zero, "Without friction every block slides, whatever its mass. ".repeat(6)),
    )

    private data class Result(val paper: RuledPaper, val placed: List<Pair<Int, PageBlock>>, val pages: HandwrittenPages)

    private fun write(blocks: List<PageBlock> = lecture(), rtl: Boolean = false, seed: Long = 1L): Result {
        val paper = RuledPaper.of(PaperTemplate.Ruled, PageSize.A4)
        val composer = PageComposer(paper, HandwritingStyle(seed = seed), rtl)
        val byKey = blocks.associateBy { it.id.value }
        val items = blocks.map {
            FlowItem(it.id.value, if (it is PageBlock.MarginNote) RegionRole.Margin else RegionRole.Main, keepWithNext = it is PageBlock.Heading, anchorKey = (it as? PageBlock.MarginNote)?.anchor?.value)
        }
        val slots = RuledFlow(paper, composer.measurer { byKey[it] }, rtl).place(items)
        val placed = slots.map { s -> s.placement.pageIndex to reframe(byKey.getValue(s.placement.key), s.placement.frame) }
        return Result(paper, placed, composer.compose(placed))
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

    private fun bounds(strokes: List<WrittenStroke>): RectPt? =
        RectPt.ofPoints(strokes.flatMap { s -> s.stroke.samples.map { Vec2(it.x, it.y) } })

    @Test fun `every block is written, inside the page and the text column`() {
        for (rtl in listOf(false, true)) {
            val r = write(rtl = rtl)
            r.pages.pages.forEachIndexed { i, page -> PreviewRender.png(r.paper, page, "lecture-${if (rtl) "rtl" else "ltr"}-$i") }
            assertTrue(r.pages.strokeCount > 200)
            for ((page, block) in r.placed) {
                val ink = r.pages.pages[page].filter { it.block == block.id.value }
                assertTrue("${block.id} wrote nothing", ink.isNotEmpty())
                if (block is PageBlock.MarginNote) continue // its arrow reaches into the main column
                val b = bounds(ink)!!
                assertTrue("${block.id} left of the margin line: ${b.left} (rtl=$rtl)", b.left >= r.paper.marginX)
                assertTrue("${block.id} past the edge: ${b.right}", b.right <= r.paper.width - 4f)
                assertTrue("${block.id} above its frame", b.top >= block.frame.top - 0.15f * r.paper.lineGap)
                assertTrue("${block.id} below its frame", b.bottom <= block.frame.bottom + 0.6f * r.paper.lineGap)
            }
        }
    }

    @Test fun `blocks do not overlap each other`() {
        val r = write()
        val main = r.placed.filter { it.second !is PageBlock.MarginNote }
        for ((i, a) in main.withIndex()) for (b in main.drop(i + 1)) {
            if (a.first != b.first) continue
            val ia = bounds(r.pages.pages[a.first].filter { it.block == a.second.id.value })!!.inset(0.5f)
            val ib = bounds(r.pages.pages[b.first].filter { it.block == b.second.id.value })!!.inset(0.5f)
            assertFalse("${a.second.id} overlaps ${b.second.id}: $ia / $ib", ia.intersects(ib))
        }
    }

    @Test fun `paragraph writing sits on the ruled lines`() {
        val r = write()
        val (page, p) = r.placed.first { it.second is PageBlock.Paragraph }
        val gap = r.paper.lineGap
        val ink = r.pages.pages[page].filter { it.block == p.id.value }
        // Most samples of written text lie in the band between a ruled line and the x-height above it.
        val samples = ink.flatMap { it.stroke.samples }
        val onBand = samples.count { s ->
            val below = (s.y - r.paper.firstLine) / gap
            val frac = below - kotlin.math.floor(below)
            frac > 0.55f || frac < 0.08f
        }
        assertTrue("only $onBand of ${samples.size} samples near a ruled line", onBand > samples.size * 0.8f)
    }

    @Test fun `key formula is boxed in red and margin note is green`() {
        val r = write()
        val all = r.pages.pages.flatten()
        assertTrue(all.any { it.block == "law" && it.stroke.brush.colorArgb == NotebookPalette.ESSENTIAL.toInt() })
        assertTrue(all.filter { it.block == "note" }.all { it.stroke.brush.colorArgb == NotebookPalette.ANNOTATION.toInt() })
    }

    @Test fun `the same seed writes the same page and timing runs forward`() {
        val a = write(seed = 9L).pages.pages[0]
        val b = write(seed = 9L).pages.pages[0]
        assertEquals(a.size, b.size)
        assertEquals(a.last().stroke.samples.last(), b.last().stroke.samples.last())
        for (i in 1 until a.size) assertTrue(a[i].startMs >= a[i - 1].endMs)
        a.forEach { s -> s.stroke.samples.zipWithNext().forEach { (x, y) -> assertTrue(y.timeMillis >= x.timeMillis) } }
    }

    @Test fun `measured lines match what is written`() {
        val r = write()
        for ((_, b) in r.placed) {
            val lines = Math.round(b.frame.height / r.paper.lineGap) + 1
            assertTrue(lines >= 1)
        }
    }

    @Test fun `arabic text is written right to left from the right edge`() {
        val blocks = listOf(PageBlock.Paragraph(BlockId("ar"), zero, "القوة تساوي الكتلة في التسارع"))
        val r = write(blocks, rtl = true)
        val ink = r.pages.pages[0]
        PreviewRender.png(r.paper, ink, "arabic")
        val b = bounds(ink)!!
        assertTrue(b.right > r.paper.textRight - 3 * r.paper.lineGap)
        assertTrue(ink.first().stroke.samples.first().x > ink.last().stroke.samples.first().x)
    }
}
