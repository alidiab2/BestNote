package app.bestnote.engine.canvas.select

import app.bestnote.core.model.ImageElement
import app.bestnote.core.model.TextElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementTransformsTest {
    private val text = TextElement("t", 100f, 100f, 80f, 20f, "Hi", sizePt = 10f)
    private val image = ImageElement("i", 50f, 60f, 40f, 20f, "assets/img-a.jpg", 400, 200)

    @Test fun moveShiftsTheCenterOnly() {
        val t = ElementTransforms.transformed(image, Affine.translate(10f, -5f)) as ImageElement
        assertEquals(60f, t.cx, 1e-4f)
        assertEquals(55f, t.cy, 1e-4f)
        assertEquals(40f, t.width, 1e-4f)
        assertEquals(0f, t.rotation, 1e-4f)
    }

    @Test fun resizeScalesFrameAndTextSize() {
        val t = ElementTransforms.transformed(text, Affine.scaleAbout(2f, 0f, 0f)) as TextElement
        assertEquals(200f, t.cx, 1e-3f)
        assertEquals(160f, t.width, 1e-3f)
        assertEquals(40f, t.height, 1e-3f)
        assertEquals(20f, t.sizePt, 1e-3f)
    }

    @Test fun rotationTurnsTheElementAboutThePivot() {
        val t = ElementTransforms.transformed(image, Affine.rotateAbout((Math.PI / 2).toFloat(), 0f, 0f))
        assertEquals(-60f, t.cx, 1e-3f)
        assertEquals(50f, t.cy, 1e-3f)
        assertEquals((Math.PI / 2).toFloat(), t.rotation, 1e-4f)
    }

    @Test fun lockedElementsDoNotMove() {
        val locked = image.copy(locked = true)
        assertSame(locked, ElementTransforms.transformed(locked, Affine.translate(30f, 30f)))
    }

    @Test fun lockedElementsIgnoreTheLassoUntilUnlocked() {
        val around = LassoPolygon(floatArrayOf(0f, 200f, 200f, 0f), floatArrayOf(0f, 0f, 200f, 200f))
        assertTrue(ElementTransforms.lassoPicks(around, image))
        assertFalse(ElementTransforms.lassoPicks(around, image.copy(locked = true)))
        assertFalse(ElementTransforms.lassoPicks(around, text.copy(locked = true)))
        assertTrue(ElementTransforms.lassoPicks(around, image.copy(locked = true).withLocked(false)))
    }

    @Test fun hitTestFollowsRotation() {
        val turned = image.copy(rotation = (Math.PI / 2).toFloat()) // 40x20 frame now stands 20 wide, 40 tall
        assertTrue(ElementTransforms.contains(turned, 50f, 78f))
        assertFalse(ElementTransforms.contains(turned, 75f, 60f))
        val b = ElementTransforms.bounds(turned)
        assertEquals(20f, b.width, 1e-3f)
        assertEquals(40f, b.height, 1e-3f)
    }

    @Test fun lassoSelectsOnlyFramesItEncloses() {
        val around = LassoPolygon(floatArrayOf(0f, 120f, 120f, 0f), floatArrayOf(30f, 30f, 90f, 90f))
        assertTrue(ElementTransforms.selectedBy(around, image))
        assertFalse(ElementTransforms.selectedBy(around, text))
    }
}
