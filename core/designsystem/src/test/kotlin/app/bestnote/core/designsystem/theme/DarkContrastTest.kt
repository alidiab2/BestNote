package app.bestnote.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DS-11: the dark theme's key pairs meet WCAG AA (4.5:1 text, 3:1 icons, borders and swatch rings). */
class DarkContrastTest {
    private val scheme = DarkAtelierScheme
    private val colors = DarkBestNoteColors

    /** Every chrome surface text and icons can sit on in dark mode. */
    private val surfaces = mapOf(
        "background" to scheme.background,
        "surface" to scheme.surface,
        "sidebar" to colors.sidebar,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest" to scheme.surfaceContainerHighest,
        "deskCenter" to colors.deskCenter,
    )

    private fun assertAtLeast(min: Float, fg: Color, bg: Color, what: String) {
        val ratio = contrastRatio(fg, bg)
        assertTrue("$what is %.2f:1, needs %.1f:1".format(ratio, min), ratio >= min)
    }

    @Test fun `text roles reach 4_5 to 1 on every dark surface`() {
        val text = mapOf(
            "onSurface" to scheme.onSurface,
            "onSurfaceVariant" to scheme.onSurfaceVariant,
            "muted" to colors.muted,
            "primary" to scheme.primary,
            "error" to scheme.error,
            "success" to colors.success,
        )
        for ((t, fg) in text) for ((s, bg) in surfaces) assertAtLeast(ContrastAA.TEXT, fg, bg, "$t on $s")
    }

    @Test fun `container pairs are readable`() {
        assertAtLeast(ContrastAA.TEXT, scheme.onPrimaryContainer, scheme.primaryContainer, "onPrimaryContainer")
        assertAtLeast(ContrastAA.TEXT, scheme.onSecondaryContainer, scheme.secondaryContainer, "onSecondaryContainer")
        assertAtLeast(ContrastAA.TEXT, scheme.onErrorContainer, scheme.errorContainer, "onErrorContainer")
        assertAtLeast(ContrastAA.TEXT, colors.onWarning, colors.warning, "onWarning")
        assertAtLeast(ContrastAA.TEXT, colors.onStrongButton, colors.strongButton, "strong button")
        assertAtLeast(ContrastAA.TEXT, scheme.onPrimary, scheme.primary, "onPrimary")
    }

    @Test fun `outline and swatch ring reach 3 to 1 on the chrome`() {
        assertAtLeast(ContrastAA.NON_TEXT, scheme.outline, scheme.surface, "outline on surface")
        assertAtLeast(ContrastAA.NON_TEXT, scheme.outline, scheme.background, "outline on background")
        for ((s, bg) in surfaces) assertAtLeast(ContrastAA.NON_TEXT, colors.swatchOutline, bg, "swatch ring on $s")
    }

    @Test fun `disabled icons stay discernible`() {
        // ~38% of on-surface (ChromeIconButton's DisabledAlpha) on the chrome pills.
        assertAtLeast(ContrastAA.NON_TEXT, scheme.onSurface.copy(alpha = 0.38f), scheme.surface, "disabled icon")
    }

    @Test fun `navy and black ink need a ring on the dark tray, and get one that reads`() {
        val navy = InkPalette.Black
        assertTrue(needsSwatchOutline(navy, scheme.surface))
        assertTrue(needsSwatchOutline(CoverPalette.Black, scheme.background))
        assertFalse(needsSwatchOutline(Color(0xFFF7D84A), scheme.surface))
        assertAtLeast(ContrastAA.NON_TEXT, navy.legibleOn(scheme.surface), scheme.surface, "lifted navy")
        assertAtLeast(ContrastAA.NON_TEXT, CoverPalette.Navy.legibleOn(scheme.surfaceContainerHigh), scheme.surfaceContainerHigh, "lifted navy folder")
    }

    @Test fun `dark surfaces stay warm, never pure black`() {
        for ((s, c) in surfaces) {
            assertTrue("$s is pure black", c != Color.Black)
            assertTrue("$s is not warm", c.red >= c.blue)
        }
    }

    @Test fun `contrast ratio matches WCAG reference values`() {
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.01f)
        assertEquals(1f, contrastRatio(Color.White, Color.White), 0.001f)
        assertEquals(4.48f, contrastRatio(Color(0xFF777777), Color.White), 0.02f)
        assertEquals(Color.White, Color.White.legibleOn(Color.Black))
    }
}
