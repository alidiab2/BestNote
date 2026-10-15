package app.bestnote.engine.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkLayersTest {
    private data class S(val name: String, val highlighter: Boolean, val argb: Int)

    private val yellow = BrushCatalog.applyOpacity(InkPalette.HIGHLIGHTER_YELLOW, 0.5f)
    private val green = 0x8034C759.toInt()

    private fun layers(vararg s: S) = InkLayers.of(s.toList(), { it.highlighter }, { it.argb })

    @Test fun highlightersGoUnderInkEvenWhenDrawnLater() {
        val l = layers(S("ink1", false, InkPalette.BLACK), S("hi", true, yellow), S("ink2", false, InkPalette.BLUE))
        assertEquals(listOf("hi"), l.highlights.single().items.map { it.name })
        assertEquals(listOf("ink1", "ink2"), l.ink.map { it.name })
    }

    @Test fun sameColorHighlightsShareOneLayerSoOverlapsKeepOneTone() {
        val l = layers(S("a", true, yellow), S("b", true, yellow), S("c", true, yellow))
        val group = l.highlights.single()
        assertEquals(3, group.items.size)
        assertEquals(yellow ushr 24, group.alpha)
        assertEquals(0xFF, group.opaqueColorArgb ushr 24)
        assertEquals(yellow and 0xFFFFFF, group.opaqueColorArgb and 0xFFFFFF)
    }

    @Test fun differentColorsGetSeparateLayersInFirstUseOrder() {
        val l = layers(S("g", true, green), S("y", true, yellow), S("g2", true, green))
        assertEquals(listOf(listOf("g", "g2"), listOf("y")), l.highlights.map { g -> g.items.map { it.name } })
    }

    @Test fun mostOpaqueStrokeSetsTheLayerAlpha() {
        val faint = (0x40 shl 24) or (yellow and 0xFFFFFF)
        val l = layers(S("faint", true, faint), S("normal", true, yellow))
        assertEquals(yellow ushr 24, l.highlights.single().alpha)
    }

    @Test fun inkOnlyPageHasNoHighlightLayers() {
        val l = layers(S("a", false, InkPalette.RED))
        assertTrue(l.highlights.isEmpty())
        assertEquals(1, l.ink.size)
    }
}
