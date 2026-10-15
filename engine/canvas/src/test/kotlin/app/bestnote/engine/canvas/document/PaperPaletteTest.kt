package app.bestnote.engine.canvas.document

import app.bestnote.core.model.PaperColor
import app.bestnote.engine.ink.InkPalette
import app.bestnote.engine.layout.paper.LineRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperPaletteTest {
    @Test fun approvedPaperColors() {
        assertEquals(0xFFFFFEFB.toInt(), PaperColors.White.page)
        assertEquals(0xFFDCE5F1.toInt(), PaperColors.White.line(LineRole.Rule))
        assertEquals(0x73E27869, PaperColors.White.line(LineRole.Margin)) // rgba(226,120,105,.45)
        assertFalse(PaperColors.White.isDark)
        assertFalse(PaperColors.Cream.isDark)
        assertTrue(PaperColors.of(PaperColor.Dark).isDark)
    }

    @Test fun darkPaperLinesAreLighterThanThePage() {
        val dark = PaperColors.Dark
        for (role in LineRole.entries) {
            assertTrue(role.name, PaperColors.luminance(dark.line(role) or 0xFF000000.toInt()) > PaperColors.luminance(dark.page))
        }
        for (paper in listOf(PaperColors.White, PaperColors.Cream)) for (role in LineRole.entries) {
            assertTrue(role.name, PaperColors.luminance(paper.line(role) or 0xFF000000.toInt()) < PaperColors.luminance(paper.page))
        }
    }

    @Test fun inkPaletteStaysReadableOnDarkPaper() {
        val dark = PaperColors.Dark
        for (ink in InkPalette.inks + InkPalette.GRAPHITE) {
            val shown = PaperInk.colorOn(ink, dark)
            assertTrue("%08X".format(shown), PaperColors.luminance(shown) - PaperColors.luminance(dark.page) > 0.35f)
            // Symmetric: back on white paper the original color returns.
            assertEquals(ink, PaperInk.colorOn(shown, PaperColors.White))
        }
        assertEquals(InkPalette.BLUE, PaperInk.colorOn(InkPalette.BLUE, PaperColors.White))
        assertEquals(InkPalette.BLUE, PaperInk.colorOn(InkPalette.BLUE, PaperColors.Cream))
    }

    @Test fun unknownColorsKeepAlphaAndOnlyDarkOnesAreLifted() {
        val translucentNavy = 0x80101030.toInt()
        val shown = PaperInk.colorOn(translucentNavy, PaperColors.Dark)
        assertEquals(0x80, shown ushr 24)
        assertTrue(PaperColors.luminance(shown or 0xFF000000.toInt()) > 0.5f)
        assertEquals(InkPalette.HIGHLIGHTER_YELLOW, PaperInk.colorOn(InkPalette.HIGHLIGHTER_YELLOW, PaperColors.Dark))
    }
}
