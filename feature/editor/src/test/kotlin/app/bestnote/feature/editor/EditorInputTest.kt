package app.bestnote.feature.editor

import app.bestnote.core.datastore.FingerDrawing
import app.bestnote.core.datastore.GesturePreferences
import app.bestnote.core.datastore.StylusPreferences
import app.bestnote.core.datastore.ToolbarDock
import app.bestnote.core.datastore.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorInputTest {
    @Test fun defaultsAreAutoStylusOnly() {
        val input = fingerInput(UserPreferences())
        assertFalse(input.stylusOnly)
        assertTrue(input.autoStylusOnly)
        assertFalse(input.penSeen)
        assertTrue(input.twoFingerTapUndo)
        assertTrue(input.threeFingerTapRedo)
    }

    @Test fun penSeenAndGesturesComeFromSettings() {
        val prefs = UserPreferences(
            stylus = StylusPreferences(penSeen = true),
            gestures = GesturePreferences(twoFingerTapUndo = false, threeFingerTapRedo = false),
        )
        val input = fingerInput(prefs)
        assertTrue(input.penSeen)
        assertFalse(input.twoFingerTapUndo)
        assertFalse(input.threeFingerTapRedo)
    }

    @Test fun drawWithFingerOverridesAutoMode() {
        val input = fingerInput(UserPreferences(stylus = StylusPreferences(fingerDrawing = FingerDrawing.Always, penSeen = true)))
        assertFalse(input.stylusOnly)
        assertFalse(input.autoStylusOnly)
    }

    @Test fun legacyNeverIsStylusOnly() {
        assertTrue(fingerInput(UserPreferences(stylus = StylusPreferences(fingerDrawing = FingerDrawing.Never))).stylusOnly)
    }

    @Test fun dockForInvertsTraySide() {
        for (left in listOf(false, true)) {
            for (dock in ToolbarDock.entries) {
                assertEquals(dock, dockFor(traySide(dock, left), left))
            }
        }
    }

    @Test fun dropSideNearestEdge() {
        val w = 1600f
        val h = 2400f
        assertEquals(TraySide.Top, dropSide(800f, 150f, w, h))
        assertEquals(TraySide.Top, dropSide(500f, 400f, w, h))
        assertEquals(TraySide.Left, dropSide(120f, 1200f, w, h))
        assertEquals(TraySide.Right, dropSide(1500f, 1200f, w, h))
        assertEquals(TraySide.Left, dropSide(80f, 300f, w, h))
    }

    @Test fun fingersNeverDrawBeforeSettingsLoad() {
        assertTrue(EditorUiState().input.stylusOnly)
    }
}
