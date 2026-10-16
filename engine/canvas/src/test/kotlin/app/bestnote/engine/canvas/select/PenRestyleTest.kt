package app.bestnote.engine.canvas.select

import app.bestnote.core.model.AudioSync
import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.Tool
import app.bestnote.engine.ink.BrushCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** CANVAS-10: restyling swaps the pen and keeps color, size setting, fill and audio stamp. */
class PenRestyleTest {
    private val base = InkBrush(
        Tool.Pen, PenStyle.Fountain, 0xFF1E5AA8.toInt(), 6f, "fountain.v1", AudioSync("rec", 90), fillArgb = 0x80FFD54F.toInt(),
    )

    @Test fun everyPenTypeKeepsColorSizeFillAndAudio() {
        for (type in PenType.entries) {
            val r = StrokeEdits.restyled(base, type)
            assertEquals(type.tool, r.tool)
            assertEquals(type.style, r.penStyle)
            assertEquals(BrushCatalog.spec(type.tool, type.style)!!.family.key, r.family)
            assertEquals(base.colorArgb, r.colorArgb)
            assertEquals(base.size, r.size, 0f)
            assertEquals(base.fillArgb, r.fillArgb)
            assertEquals(base.audio, r.audio)
        }
    }

    @Test fun restyleIsReversible() {
        val there = StrokeEdits.restyled(base, PenType.Highlighter)
        assertNotEquals(base, there)
        assertEquals(base, StrokeEdits.restyled(there, PenType.Fountain))
    }

    @Test fun restylingToTheSamePenChangesNothing() {
        assertEquals(base, StrokeEdits.restyled(base, PenType.Fountain))
    }
}
