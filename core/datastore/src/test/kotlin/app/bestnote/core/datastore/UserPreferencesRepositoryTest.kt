package app.bestnote.core.datastore

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserPreferencesRepositoryTest {
    private val store = FakeDataStore(UserPreferences())
    private val repo = UserPreferencesRepository(store)

    @Test fun defaults() = runTest {
        val prefs = repo.preferences.first()
        assertEquals(SideButtonAction.TemporaryEraser, prefs.stylus.sideButtonHold)
        assertEquals(HoverButtonAction.QuickPalette, prefs.hoverButtonAction)
        assertEquals(FingerDrawing.WhenNoPen, prefs.stylus.fingerDrawing)
        assertEquals(30, prefs.storage.trashRetentionDays)
        assertFalse(prefs.stylusOnly)
        assertFalse(prefs.dynamicColor)
    }

    @Test fun sectionUpdatesOnlyTouchTheirSection() = runTest {
        repo.updateStylus { it.copy(sideButtonHold = SideButtonAction.Lasso, hoverCursor = false) }
        repo.updateGestures { it.copy(threeFingerTapRedo = false) }
        val prefs = store.value
        assertEquals(SideButtonAction.Lasso, prefs.stylus.sideButtonHold)
        assertFalse(prefs.stylus.hoverCursor)
        assertFalse(prefs.gestures.threeFingerTapRedo)
        assertEquals(WritingPreferences(), prefs.writing)
        assertEquals(AppearancePreferences(), prefs.appearance)
    }

    @Test fun penSizeIsClamped() = runTest {
        repo.updateWriting { it.copy(size = 500f) }
        assertEquals(WritingPreferences.MAX_PEN_SIZE, repo.writing.first().size)
        repo.updateWriting { it.copy(size = -1f) }
        assertEquals(WritingPreferences.MIN_PEN_SIZE, repo.writing.first().size)
    }

    @Test fun trashRetentionNeverNegative() = runTest {
        repo.updateStorage { it.copy(trashRetentionDays = -5) }
        assertEquals(0, repo.storage.first().trashRetentionDays)
    }

    @Test fun stylusOnlyShortcutMapsToFingerDrawing() = runTest {
        repo.setStylusOnly(true)
        assertTrue(store.value.stylusOnly)
        assertEquals(FingerDrawing.Never, store.value.stylus.fingerDrawing)
        repo.setStylusOnly(false)
        assertEquals(FingerDrawing.WhenNoPen, store.value.stylus.fingerDrawing)
    }

    @Test fun themeShortcuts() = runTest {
        repo.setThemeMode(ThemeMode.Dark)
        repo.setDynamicColor(true)
        assertEquals(ThemeMode.Dark, store.value.themeMode)
        assertTrue(store.value.dynamicColor)
    }

    @Test fun sectionFlowEmitsOnlyOnChange() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<StylusPreferences>()
        val job = launch { repo.stylus.take(2).toList(seen) }
        repo.updateGestures { it.copy(pinchToZoom = false) } // unrelated: no emission
        repo.updateStylus { it.copy(tiltShading = false) }
        job.join()
        assertEquals(listOf(StylusPreferences(), StylusPreferences(tiltShading = false)), seen)
    }
}
