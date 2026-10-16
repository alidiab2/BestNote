package app.bestnote.feature.editor.text

import app.bestnote.core.model.ImageElement
import app.bestnote.core.model.TextElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextGeometryTest {
    @Test fun caretAlreadyVisibleNeedsNoScroll() {
        assertEquals(0, CaretViewport.scrollFor(300f, 330f, 100f, 600f, 24f))
    }

    @Test fun caretBehindTheKeyboardScrollsUp() {
        // Keyboard + format bar leave 0..500 visible; the caret line ends at 560.
        assertEquals(84, CaretViewport.scrollFor(530f, 560f, 100f, 500f, 24f))
    }

    @Test fun caretUnderTheTopChromeScrollsDown() {
        assertEquals(-34, CaretViewport.scrollFor(90f, 120f, 100f, 600f, 24f))
    }

    @Test fun tooLittleRoomFavoursTheLineBottom() {
        assertEquals(16, CaretViewport.scrollFor(100f, 140f, 100f, 124f, 0f))
    }

    @Test fun boxGrowsRightFromTheTapUntilTheMargin() {
        val (l1, w1) = AutoGrow.frame(100f, 600f, 36f, 50f, 80f, rtl = false)
        assertEquals(100f, l1)
        assertEquals(80f, w1) // never narrower than the minimum
        val (l2, w2) = AutoGrow.frame(100f, 600f, 36f, 300f, 80f, rtl = false)
        assertEquals(100f, l2)
        assertEquals(300f, w2)
        val (l3, w3) = AutoGrow.frame(100f, 600f, 36f, 2000f, 80f, rtl = false)
        assertEquals(100f, l3)
        assertEquals(464f, w3) // stops at the right margin, then the text wraps
    }

    @Test fun arabicGrowsLeftFromTheTap() {
        val (l, w) = AutoGrow.frame(500f, 600f, 36f, 200f, 80f, rtl = true)
        assertEquals(300f, l)
        assertEquals(200f, w)
        val (l2, w2) = AutoGrow.frame(500f, 600f, 36f, 2000f, 80f, rtl = true)
        assertEquals(36f, l2)
        assertEquals(464f, w2)
    }

    @Test fun aTapNearTheFarEdgeStillLeavesRoom() {
        val (l, w) = AutoGrow.frame(590f, 600f, 36f, 10f, 80f, rtl = false)
        assertEquals(80f, w)
        assertEquals(484f, l)
    }

    @Test fun hitTestPicksTheTopmostUnlockedTextBox() {
        val under = TextElement("a", 100f, 100f, 100f, 40f, "under")
        val over = TextElement("b", 110f, 100f, 100f, 40f, "over")
        val locked = TextElement("c", 110f, 100f, 100f, 40f, "locked", locked = true)
        val image = ImageElement("d", 110f, 100f, 100f, 40f, "assets/x.jpg", 10, 10)
        assertEquals("b", TextHit.at(listOf(under, over, locked, image), 120f, 100f)?.id)
        assertEquals("a", TextHit.at(listOf(under, over), 55f, 100f)?.id)
        assertNull(TextHit.at(listOf(under), 300f, 300f))
        assertEquals("a", TextHit.at(listOf(under), 48f, 100f, slop = 6f)?.id)
    }

    @Test fun localPointsFollowRotation() {
        val box = TextElement("a", 100f, 100f, 100f, 40f, "x", rotation = (Math.PI / 2).toFloat())
        val (lx, ly) = TextHit.local(box, 100f, 100f)
        assertEquals(50f, lx, 1e-3f)
        assertEquals(20f, ly, 1e-3f)
        val (tx, ty) = TextHit.topCenter(box)
        assertEquals(120f, tx, 1e-3f)
        assertEquals(100f, ty, 1e-3f)
    }
}
