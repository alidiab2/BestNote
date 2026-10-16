package app.bestnote.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionMenuPlacementTest {
    private fun y(selTop: Float, selBottom: Float, chromeBottom: Float = 300f, bottomChromeTop: Float = 2000f) =
        selectionMenuY(selTop, selBottom, menuHeight = 100f, aboveGap = 48f, belowGap = 16f, margin = 12f, chromeBottom = chromeBottom, containerHeight = 2000f, bottomChromeTop = bottomChromeTop)

    @Test fun `sits above the selection when there is room`() {
        assertEquals(1000f - 48f - 100f, y(1000f, 1200f), 0.01f)
    }

    @Test fun `flips below the selection when above would cover the pen tray`() {
        // above = 320 - 48 - 100 = 172 < chromeBottom 300
        assertEquals(500f + 16f, y(320f, 500f), 0.01f)
    }

    @Test fun `never goes under the chrome even when the selection is tall`() {
        // above = 100, below = 1900 + 16 does not fit above the bottom: pinned at the bottom of the free band
        val result = y(250f, 1990f)
        assertTrue(result >= 300f)
        assertEquals(1888f, result, 0.01f)
    }

    @Test fun `stays above the compact bottom tray`() {
        // Above would cover the top tray and below would cover the bottom one: pinned just above the bottom tray.
        val result = y(320f, 1700f, bottomChromeTop = 1650f)
        assertEquals(1550f, result, 0.01f)
    }
}
