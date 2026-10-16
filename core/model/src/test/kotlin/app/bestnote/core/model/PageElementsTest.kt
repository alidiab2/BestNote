package app.bestnote.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageElementsTest {
    private fun image(rotation: Float = 0f) = ImageElement("i", cx = 100f, cy = 100f, width = 200f, height = 100f, asset = "assets/img-a.jpg", srcWidth = 2000, srcHeight = 1000, rotation = rotation)

    @Test fun cropKeepsRemainingPartInPlace() {
        // Keep the right half: the frame halves and its center moves right by a quarter of the old width.
        val c = image().cropped(CropRect(0.5f, 0f, 1f, 1f))
        assertEquals(100f, c.width, 1e-3f)
        assertEquals(100f, c.height, 1e-3f)
        assertEquals(150f, c.cx, 1e-3f)
        assertEquals(100f, c.cy, 1e-3f)
    }

    @Test fun cropFollowsRotation() {
        val c = image(rotation = (Math.PI / 2).toFloat()).cropped(CropRect(0.5f, 0f, 1f, 1f))
        // Local +x points down the page after a quarter turn.
        assertEquals(100f, c.cx, 1e-3f)
        assertEquals(150f, c.cy, 1e-3f)
    }

    @Test fun cropThenUncropRestoresFrame() {
        val a = image()
        val b = a.cropped(CropRect(0.2f, 0.1f, 0.7f, 0.9f)).cropped(CropRect())
        assertEquals(a.cx, b.cx, 1e-3f)
        assertEquals(a.cy, b.cy, 1e-3f)
        assertEquals(a.width, b.width, 1e-3f)
        assertEquals(a.height, b.height, 1e-3f)
    }

    @Test fun quarterTurnsWrap() {
        var i = image()
        repeat(4) { i = i.turnedQuarter() }
        assertEquals(0f, i.rotation, 1e-5f)
    }

    @Test fun sanitizedCropFallsBackToFull() {
        assertTrue(CropRect.sanitized(0.8f, 0f, 0.2f, 1f).isFull)
        assertEquals(CropRect(0f, 0.25f, 1f, 1f), CropRect.sanitized(-3f, 0.25f, 9f, 1f))
    }
}
