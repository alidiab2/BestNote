package app.bestnote.engine.ink

import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the pure spec mapping; creating real Brushes needs ink's native library (device tests). */
class BrushCatalogTest {
    @Test fun everyInkingToolHasASpec() {
        Tool.entries.filter { it.inks }.forEach { tool ->
            PenStyle.entries.forEach { style -> assertNotNull("$tool/$style", BrushCatalog.spec(tool, style)) }
        }
        assertNull(BrushCatalog.spec(Tool.Eraser))
        assertNull(BrushCatalog.spec(Tool.Lasso))
    }

    @Test fun threePenStylesUseThreeDistinctFamilies() {
        val families = PenStyle.entries.map { BrushCatalog.spec(Tool.Pen, it)!!.family }
        assertEquals(listOf(InkFamily.Fountain, InkFamily.Ballpoint, InkFamily.BrushPen), families)
        assertEquals(InkFamily.Highlighter, BrushCatalog.spec(Tool.Highlighter)!!.family)
        assertEquals(InkFamily.Pencil, BrushCatalog.spec(Tool.Pencil)!!.family)
    }

    @Test fun brushPenIsWiderAndBallpointFinerThanFountain() {
        val fountain = BrushCatalog.spec(Tool.Pen, PenStyle.Fountain)!!.sizeScale
        assertTrue(BrushCatalog.spec(Tool.Pen, PenStyle.Brush)!!.sizeScale > fountain)
        assertTrue(BrushCatalog.spec(Tool.Pen, PenStyle.Ball)!!.sizeScale < fountain)
    }

    @Test fun pressureRangesMatchPenCharacter() {
        // Ballpoint is near-constant, brush pen spans a wide range.
        assertTrue(BrushTuning.Ballpoint.MIN_PRESSURE_WIDTH >= 0.8f)
        assertTrue(BrushTuning.BrushPen.MIN_PRESSURE_WIDTH <= 0.2f)
        assertTrue(BrushTuning.BrushPen.TAPER_TIP_WIDTH < 0.5f)
    }

    @Test fun highlighterIsTranslucentChisel() {
        val spec = BrushCatalog.spec(Tool.Highlighter)!!
        assertTrue(spec.opacity in 0.45f..0.55f)
        assertTrue(BrushTuning.Highlighter.CHISEL_SCALE_X < BrushTuning.Highlighter.CHISEL_SCALE_Y)
        val alpha = BrushCatalog.applyOpacity(InkPalette.HIGHLIGHTER_YELLOW, spec.opacity) ushr 24
        assertTrue(alpha in 115..140)
    }

    @Test fun inkPensAreOpaque() {
        PenStyle.entries.forEach { assertEquals(1f, BrushCatalog.spec(Tool.Pen, it)!!.opacity) }
    }

    @Test fun familyClientIdsRoundTripAndAreUnique() {
        InkFamily.entries.forEach { assertEquals(it, InkFamily.fromClientId(it.clientId)) }
        assertEquals(InkFamily.entries.size, InkFamily.entries.map { it.clientId }.toSet().size)
        assertNull(InkFamily.fromClientId(null))
        assertNull(InkFamily.fromClientId("ink-stock:pressure-pen"))
    }

    @Test fun opacityScalesAlphaOnly() {
        assertEquals(0x661F1F1F, BrushCatalog.applyOpacity(0xFF1F1F1F.toInt(), 0.4f))
        assertEquals(0xFF123456.toInt(), BrushCatalog.applyOpacity(0xFF123456.toInt(), 1f))
    }

    @Test fun paletteMatchesAtelierSpec() {
        assertEquals(0xFF1E2433.toInt(), InkPalette.BLACK)
        assertEquals(0xFF2A4BD7.toInt(), InkPalette.BLUE)
        assertEquals(0xFFD9462B.toInt(), InkPalette.RED)
        assertEquals(0xFF2F7D57.toInt(), InkPalette.GREEN)
        assertEquals(0xFFF7D84A.toInt(), InkPalette.HIGHLIGHTER_YELLOW)
    }
}
