package app.bestnote.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PaperSizesTest {
    @Test fun presetsInBothOrientations() {
        assertEquals(PageSize(595f, 842f), PaperSizes.of(PaperSizePreset.A4))
        assertEquals(PageSize(842f, 595f), PaperSizes.of(PaperSizePreset.A4, PaperOrientation.Landscape))
        assertEquals(PageSize(595f, 420f), PaperSizes.of(PaperSizePreset.A5, PaperOrientation.Landscape))
        assertEquals(PageSize(612f, 792f), PaperSizes.of(PaperSizePreset.Letter))
    }

    @Test fun fitToScreenFollowsTheViewport() {
        val tall = PaperSizes.of(PaperSizePreset.FitToScreen, viewportAspect = 1.6f)
        assertEquals(595f, tall.widthPt, 0f)
        assertEquals(952f, tall.heightPt, 0.01f)
        // Orientation never flips a fit-to-screen page: it already has the screen's shape.
        val wide = PaperSizes.of(PaperSizePreset.FitToScreen, PaperOrientation.Portrait, viewportAspect = 0.625f)
        assertEquals(PageSize(595f, 371.875f), wide)
        assertEquals(PaperSizes.A4, PaperSizes.of(PaperSizePreset.FitToScreen, viewportAspect = Float.NaN))
    }

    @Test fun customIsClampedAndOriented() {
        val custom = PaperSizes.of(PaperSizePreset.Custom, PaperOrientation.Landscape, custom = PageSize(10f, 9000f))
        assertEquals(PageSize(PaperSizes.MAX_SIDE_PT, PaperSizes.MIN_SIDE_PT), custom)
    }

    @Test fun presetDetection() {
        assertEquals(PaperSizePreset.A5, PaperSizes.presetOf(PageSize(595f, 420f)))
        assertEquals(PaperSizePreset.Letter, PaperSizes.presetOf(PageSize(612.4f, 791.8f)))
        assertEquals(PaperSizePreset.Custom, PaperSizes.presetOf(PageSize(500f, 500f)))
        assertEquals(PaperOrientation.Landscape, PaperSizes.orientationOf(PageSize(842f, 595f)))
    }

    @Test fun pageCarriesPaperColor() {
        val page = Page(NotebookId("n"), 0, PaperTemplate.Cornell, 420f, 595f, PaperColor.Dark)
        assertEquals(PageSize(420f, 595f), page.size)
        assertEquals(PaperColor.White, Page(NotebookId("n"), 1).paperColor)
    }
}
