package app.bestnote.feature.editor

import app.bestnote.core.datastore.ToolbarDock
import app.bestnote.core.model.Tool
import app.bestnote.engine.ink.InkPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolboxTest {
    @Test fun everyPenRemembersItsOwnSettingsAcrossSwitches() {
        val box = Toolbox()
            .withColor(InkPalette.RED)
            .withSize(9f)
            .select(PenKind.Highlighter)
            .withOpacity(0.6f)
            .select(PenKind.Fountain)
        assertEquals(InkPalette.RED, box.current.colorArgb)
        assertEquals(9f, box.current.size)
        assertEquals(0.6f, box.settingsOf(PenKind.Highlighter).opacity)
        assertEquals(InkPalette.HIGHLIGHTER_YELLOW, box.settingsOf(PenKind.Highlighter).colorArgb)
    }

    @Test fun customColorTakesTheFirstDotAndKeepsFive() {
        val custom = 0xFF123456.toInt()
        val box = Toolbox().withColor(custom)
        val dots = box.quickColors()
        assertEquals(5, dots.size)
        assertEquals(custom, dots.first())
        assertFalse(0xFF7D6CA6.toInt() in dots) // the oldest dot made room
        // Picking a color already in the dots does not reorder them.
        assertEquals(dots, box.withColor(InkPalette.BLUE).quickColors())
    }

    @Test fun sizeAndOpacityAreClamped() {
        val box = Toolbox().withSize(500f).select(PenKind.Highlighter).withOpacity(0f)
        assertEquals(PenDefaults.sizeRange(PenKind.Fountain).endInclusive, box.settingsOf(PenKind.Fountain).size)
        assertEquals(PenDefaults.MIN_OPACITY, box.current.opacity)
    }

    @Test fun highlighterOpacityGoesIntoBrushAlpha() {
        val s = PenSettings(InkPalette.HIGHLIGHTER_YELLOW, 5f, opacity = 0.5f)
        assertEquals(0x7F, s.brushColorArgb ushr 24)
        assertEquals(InkPalette.HIGHLIGHTER_YELLOW and 0xFFFFFF, s.brushColorArgb and 0xFFFFFF)
    }

    @Test fun cycleSizeWalksPresetsAndWraps() {
        var box = Toolbox().withSize(3f)
        val seen = (1..5).map { box = box.cycleSize(); box.current.size }
        assertEquals(listOf(5f, 8f, 12f, 3f, 5f), seen)
    }

    @Test fun presetAppliesColorAndSize() {
        val preset = PenDefaults.presets(PenKind.Fountain)[2]
        val box = Toolbox().withPreset(preset)
        assertEquals(preset.colorArgb, box.current.colorArgb)
        assertEquals(preset.size, box.current.size)
    }

    @Test fun penKindsMapToCanvasTools() {
        assertEquals(Tool.Eraser, PenKind.Eraser.tool)
        assertTrue(PenKind.entries.filter { it != PenKind.Eraser }.all { it.inks })
    }

    @Test fun widthLabelInMillimeters() {
        // Fountain 5 units at 320 dpi = 5 / 12.6 px per mm ≈ 0.4 mm.
        assertEquals("0.4", PenDefaults.formatMm(PenDefaults.widthMm(PenKind.Fountain, 5f, 320)))
        assertEquals("12", PenDefaults.formatMm(12.3f))
    }

    @Test fun leftHandedMirrorsSideDocksOnly() {
        assertEquals(TraySide.Top, traySide(ToolbarDock.Top, leftHanded = true))
        assertEquals(TraySide.Left, traySide(ToolbarDock.Left, leftHanded = false))
        assertEquals(TraySide.Right, traySide(ToolbarDock.Left, leftHanded = true))
        assertEquals(TraySide.Left, traySide(ToolbarDock.Right, leftHanded = true))
    }
}
