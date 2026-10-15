package app.bestnote.engine.stylus

import app.bestnote.engine.stylus.StylusEventMapper as M
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StylusEventMapperTest {
    private val pen = FakePointer(id = 0, toolType = M.TOOL_TYPE_STYLUS, x = 10f, y = 20f, pressure = 0.7f, tilt = 0.4f, orientation = -1.2f)

    @Test fun downMapsToolPressureTiltOrientation() {
        val e = M.map(FakePointerEvent(M.ACTION_DOWN, listOf(pen))).single()
        assertEquals(StylusAction.Down, e.action)
        assertEquals(PointerTool.Stylus, e.tool)
        assertEquals(0.7f, e.latest.pressure)
        assertEquals(0.4f, e.latest.tiltRad)
        assertEquals(-1.2f, e.latest.orientationRad)
        assertEquals(1, e.samples.size)
    }

    @Test fun moveIncludesHistoricalSamplesOldestFirst() {
        val moving = pen.copy(history = listOf(Triple(1f, 1f, 0.1f), Triple(2f, 2f, 0.2f)))
        val e = M.map(FakePointerEvent(M.ACTION_MOVE, listOf(moving))).single()
        assertEquals(listOf(1f, 2f, 10f), e.samples.map { it.x })
        assertTrue(e.samples.zipWithNext().all { (a, b) -> a.timeMillis <= b.timeMillis })
    }

    @Test fun pointerDownReportsOnlyTheNewPointer() {
        val finger = FakePointer(id = 7, toolType = M.TOOL_TYPE_FINGER, x = 0f, y = 0f)
        val e = M.map(FakePointerEvent(M.ACTION_POINTER_DOWN, listOf(pen, finger), actionIndex = 1)).single()
        assertEquals(7, e.pointerId)
        assertEquals(PointerTool.Finger, e.tool)
    }

    @Test fun sideButtonAndHover() {
        val e = M.map(FakePointerEvent(M.ACTION_HOVER_MOVE, listOf(pen), buttonState = M.BUTTON_STYLUS_PRIMARY)).single()
        assertTrue(e.isHover)
        assertTrue(e.primaryButtonPressed)
        assertFalse(e.secondaryButtonPressed)
    }

    @Test fun canceledFlagTurnsUpIntoCancel() {
        val e = M.map(FakePointerEvent(M.ACTION_UP, listOf(pen), flags = M.FLAG_CANCELED)).single()
        assertEquals(StylusAction.Cancel, e.action)
        assertTrue(e.canceled)
    }

    @Test fun eraserEndAndPressureClamp() {
        val eraser = pen.copy(toolType = M.TOOL_TYPE_ERASER, pressure = 1.4f)
        val e = M.map(FakePointerEvent(M.ACTION_DOWN, listOf(eraser))).single()
        assertEquals(PointerTool.Eraser, e.tool)
        assertEquals(1f, e.latest.pressure)
    }

    @Test fun everyPointerGetsItsOwnHistoryBatch() {
        val a = pen.copy(history = listOf(Triple(1f, 1f, 0.1f), Triple(2f, 2f, 0.2f)))
        val b = FakePointer(id = 3, toolType = M.TOOL_TYPE_FINGER, x = 50f, y = 50f, history = listOf(Triple(30f, 30f, 1f), Triple(40f, 40f, 1f)))
        val events = M.map(FakePointerEvent(M.ACTION_MOVE, listOf(a, b)))
        assertEquals(listOf(0, 3), events.map { it.pointerId })
        assertEquals(listOf(1f, 2f, 10f), events[0].samples.map { it.x })
        assertEquals(listOf(30f, 40f, 50f), events[1].samples.map { it.x })
        assertEquals(listOf(992L, 996L, 1_000L), events[0].samples.map { it.timeMillis })
    }

    @Test fun hoverMoveBatchesHistoryButDownDoesNot() {
        val moving = pen.copy(history = listOf(Triple(1f, 1f, 0f)))
        assertEquals(2, M.map(FakePointerEvent(M.ACTION_HOVER_MOVE, listOf(moving))).single().samples.size)
        assertEquals(1, M.map(FakePointerEvent(M.ACTION_DOWN, listOf(moving))).single().samples.size)
    }

    @Test fun tiltAndOrientationSurviveHistory() {
        val moving = pen.copy(history = listOf(Triple(1f, 1f, 0.1f)))
        val e = M.map(FakePointerEvent(M.ACTION_MOVE, listOf(moving))).single()
        assertTrue(e.samples.all { it.tiltRad == 0.4f && it.orientationRad == -1.2f })
    }

    @Test fun toolResolution() {
        assertEquals(PointerTool.Stylus, M.toolOf(M.TOOL_TYPE_STYLUS))
        assertEquals(PointerTool.Eraser, M.toolOf(M.TOOL_TYPE_ERASER))
        assertEquals(PointerTool.Finger, M.toolOf(M.TOOL_TYPE_FINGER))
        assertEquals(PointerTool.Mouse, M.toolOf(M.TOOL_TYPE_MOUSE))
        assertEquals(PointerTool.Unknown, M.toolOf(0))
    }

    @Test fun legacySamsungButtonActionsMapToStandardOnesWithButton() {
        val expected = mapOf(
            M.SAMSUNG_ACTION_PEN_DOWN to StylusAction.Down,
            M.SAMSUNG_ACTION_PEN_MOVE to StylusAction.Move,
            M.SAMSUNG_ACTION_PEN_UP to StylusAction.Up,
        )
        for ((code, action) in expected) {
            val e = M.map(FakePointerEvent(code, listOf(pen))).single()
            assertEquals(action, e.action)
            assertTrue(e.primaryButtonPressed)
        }
    }

    @Test fun unknownActionsAreDropped() {
        assertTrue(M.map(FakePointerEvent(8 /* ACTION_SCROLL */, listOf(pen))).isEmpty())
    }
}
