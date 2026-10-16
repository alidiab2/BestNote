package app.bestnote.engine.canvas.select

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectOrderTest {
    private class Obj(val name: String, val group: String? = null) {
        override fun toString() = name
    }

    private val a = Obj("a")
    private val b = Obj("b", "g1")
    private val c = Obj("c")
    private val d = Obj("d", "g1")
    private val e = Obj("e", "g2")
    private val all = listOf(a, b, c, d, e)

    @Test fun bringToFrontKeepsRelativeOrder() {
        assertEquals(listOf(a, c, e, b, d), ObjectOrder.reordered(all, listOf(d, b), toFront = true))
    }

    @Test fun sendToBackKeepsRelativeOrder() {
        assertEquals(listOf(c, e, a, b, d), ObjectOrder.reordered(all, listOf(e, c), toFront = false))
    }

    @Test fun nothingMovesWhenAlreadyAtThatEnd() {
        assertFalse(ObjectOrder.wouldMove(all, listOf(e), toFront = true))
        assertFalse(ObjectOrder.wouldMove(all, listOf(a), toFront = false))
        assertTrue(ObjectOrder.wouldMove(all, listOf(a), toFront = true))
    }

    @Test fun reorderingTwiceRestoresNothingByAccidentButIsStable() {
        val front = ObjectOrder.reordered(all, listOf(a), toFront = true)
        assertEquals(listOf(b, c, d, e, a), front)
        assertEquals(front, ObjectOrder.reordered(front, listOf(a), toFront = true))
    }

    @Test fun selectingOneMemberBringsTheWholeGroup() {
        assertEquals(listOf(b, d), ObjectOrder.withGroupMates(listOf(b), all, { it.group }))
        assertEquals(listOf(a, b, d), ObjectOrder.withGroupMates(listOf(a, d), all, { it.group }))
    }

    @Test fun ungroupedSelectionIsLeftAlone() {
        val chosen = listOf(a, c)
        assertEquals(chosen, ObjectOrder.withGroupMates(chosen, all, { it.group }))
    }

    @Test fun lockedGroupMatesAreNotPulledIn() {
        assertEquals(listOf(b), ObjectOrder.withGroupMates(listOf(b), all, { it.group }, { it !== d }))
    }

    @Test fun copiesOfAGroupGetTheirOwnGroup() {
        val r = ObjectOrder.Regrouper()
        val x = r.of("g1")
        assertEquals(x, r.of("g1"))
        assertNotEquals("g1", x)
        assertNotEquals(x, r.of("g2"))
        assertNull(r.of(null))
    }
}
