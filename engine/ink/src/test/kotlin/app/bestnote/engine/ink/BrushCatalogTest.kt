package app.bestnote.engine.ink

import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test fun penStylesPickFamilies() {
        assertEquals(InkFamily.PressurePen, BrushCatalog.spec(Tool.Pen, PenStyle.Fountain)!!.family)
        assertEquals(InkFamily.Marker, BrushCatalog.spec(Tool.Pen, PenStyle.Ball)!!.family)
        assertEquals(InkFamily.Highlighter, BrushCatalog.spec(Tool.Highlighter)!!.family)
    }

    @Test fun opacityScalesAlphaOnly() {
        assertEquals(0x661F1F1F, BrushCatalog.applyOpacity(0xFF1F1F1F.toInt(), 0.4f))
        assertEquals(0xFF123456.toInt(), BrushCatalog.applyOpacity(0xFF123456.toInt(), 1f))
    }
}
