package app.bestnote.engine.layout.paper

import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperOrientation
import app.bestnote.core.model.PaperSizePreset
import app.bestnote.core.model.PaperSizes
import app.bestnote.core.model.PaperTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PaperGeometryTest {
    private val sizes = listOf(
        PageSize.A4,
        PaperSizes.of(PaperSizePreset.A5, PaperOrientation.Landscape),
        PaperSizes.Letter,
        PaperSizes.of(PaperSizePreset.FitToScreen, viewportAspect = 0.62f),
    )

    private fun rules(g: TemplateGeometry) = g.lines.filter { it.role == LineRole.Rule }.map { it.y0 }

    @Test fun ruledSpacingsAreTheStandardOnes() {
        for ((template, spacing) in listOf(
            PaperTemplate.RuledNarrow to 6.35f,
            PaperTemplate.Ruled to 7.1f,
            PaperTemplate.RuledWide to 8.7f,
        )) {
            val ys = rules(PaperGeometry.of(template, PageSize.A4))
            assertTrue(ys.size > 20)
            ys.zipWithNext { a, b -> assertEquals(spacing * PaperGeometry.MM, b - a, 1e-3f) }
            assertEquals(72f, ys.first(), 1e-3f) // one inch header on A4
            assertTrue(ys.last() <= PageSize.A4.heightPt - spacing * PaperGeometry.MM + 0.02f)
        }
        // Narrow rule fits more lines than wide rule.
        assertTrue(rules(PaperGeometry.of(PaperTemplate.RuledNarrow, PageSize.A4)).size > rules(PaperGeometry.of(PaperTemplate.RuledWide, PageSize.A4)).size)
    }

    @Test fun ruledHasOneMarginLine() {
        val margins = PaperGeometry.of(PaperTemplate.Ruled, PageSize.A4).lines.filter { it.role == LineRole.Margin }
        assertEquals(1, margins.size)
        assertEquals(PaperGeometry.marginX(PageSize.A4), margins.single().x0, 0f)
        assertEquals(0f, margins.single().y0, 0f)
        assertEquals(PageSize.A4.heightPt, margins.single().y1, 0f)
    }

    @Test fun everyLineStaysOnThePage() {
        for (size in sizes) for (t in PaperTemplate.entries) {
            val g = PaperGeometry.of(t, size)
            for (l in g.lines) {
                assertTrue("$t $size $l", l.x0 >= 0f && l.x1 <= size.widthPt && l.y0 >= 0f && l.y1 <= size.heightPt)
                assertTrue("$t $l is axis aligned", l.x0 == l.x1 || l.y0 == l.y1)
            }
            g.dots?.let { d ->
                assertTrue(d.left > 0f && d.top > 0f)
                assertTrue(d.left + (d.cols - 1) * d.spacing < size.widthPt)
                assertTrue(d.top + (d.rows - 1) * d.spacing < size.heightPt)
            }
        }
    }

    @Test fun blankIsEmpty() = assertEquals(TemplateGeometry.Empty, PaperGeometry.of(PaperTemplate.Blank, PageSize.A4))

    @Test fun gridIsCenteredWithFiveMillimetrePitch() {
        val g = PaperGeometry.of(PaperTemplate.Grid, PageSize.A4)
        val xs = g.lines.filter { !it.horizontal }.map { it.x0 }
        xs.zipWithNext { a, b -> assertEquals(PaperGeometry.GRID, b - a, 1e-3f) }
        assertEquals(xs.first(), PageSize.A4.widthPt - xs.last(), 1e-3f) // symmetric margins
        assertTrue(xs.first() >= PaperGeometry.GRID / 4)
    }

    @Test fun dotsMatchTheGrid() {
        val d = PaperGeometry.of(PaperTemplate.Dotted, PageSize.A4).dots!!
        assertEquals(PaperGeometry.GRID, d.spacing, 0f)
        assertEquals(41, d.cols) // 210 mm / 5 mm
        assertEquals(59, d.rows) // 297 mm / 5 mm
        assertNull(PaperGeometry.of(PaperTemplate.Grid, PageSize.A4).dots)
    }

    @Test fun cornellHasCueColumnAndSummary() {
        val size = PageSize.A4
        val dividers = PaperGeometry.of(PaperTemplate.Cornell, size).lines.filter { it.role == LineRole.Divider }
        assertEquals(3, dividers.size)
        val cue = dividers.single { !it.horizontal }
        assertEquals(size.widthPt * 0.3f, cue.x0, 1e-3f)
        assertEquals(PaperGeometry.cornellSummaryTop(size), cue.y1, 1e-3f)
        assertTrue(dividers.any { it.horizontal && it.y0 == PaperGeometry.cornellSummaryTop(size) && it.x1 == size.widthPt })
        // No writing line crosses the summary divider.
        val ys = PaperGeometry.of(PaperTemplate.Cornell, size).lines.filter { it.role == LineRole.Rule }.map { it.y0 }
        assertTrue(ys.none { abs(it - PaperGeometry.cornellSummaryTop(size)) < 3f })
    }

    @Test fun musicStavesHaveFiveEvenLines() {
        val g = PaperGeometry.of(PaperTemplate.MusicStaff, PageSize.A4)
        val n = PaperGeometry.staffCount(PageSize.A4)
        assertTrue(n in 10..14)
        assertEquals(n * 5, g.lines.size)
        g.lines.chunked(5).forEach { staff ->
            staff.zipWithNext { a, b -> assertEquals(PaperGeometry.STAFF_LINE_GAP, b.y0 - a.y0, 1e-3f) }
        }
        val gaps = g.lines.chunked(5).zipWithNext { a, b -> b.first().y0 - a.last().y0 }
        gaps.forEach { assertTrue(it >= PaperGeometry.STAFF_MIN_GAP - 1e-3f); assertEquals(gaps.first(), it, 1e-3f) }
    }

    @Test fun snappedLinesAreOnePhysicalPixelAtEveryZoom() {
        val g = PaperGeometry.of(PaperTemplate.Ruled, PageSize.A4)
        for (scale in listOf(0.25f, 0.5f, 1f, 1.37f, 2f, 2.625f, 3.5f, 8f)) {
            val px = rules(g).map { PixelSnap.center(it, scale) }
            // Pixel centers: a 1 px line covers exactly one row of physical pixels, never two half rows.
            px.forEach { assertEquals("scale $scale", 0.5f, it - kotlin.math.floor(it), 0f) }
            // Snapping moves a line by less than one pixel, so spacing error never accumulates.
            rules(g).zip(px).forEach { (pt, p) -> assertTrue(abs(pt * scale - p) <= 0.5f + 1e-3f) }
            if (scale >= 1f) {
                val pitch = PaperGeometry.COLLEGE * scale
                px.zipWithNext { a, b -> assertTrue(abs((b - a) - pitch) <= 1.001f) }
            }
        }
        assertEquals(0.5f, PixelSnap.centerIn(0f, 3f, 100), 0f)
        assertEquals(99.5f, PixelSnap.centerIn(595f, 1000f / 595f * 0.1f + 1f, 100), 0f)
    }
}
