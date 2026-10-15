package app.bestnote.feature.settings

import app.bestnote.core.datastore.ChromeWarmth
import app.bestnote.core.datastore.FingerDrawing
import app.bestnote.core.datastore.HoverButtonAction
import app.bestnote.core.datastore.ToolbarDock
import app.bestnote.core.datastore.UserPreferences
import app.bestnote.core.datastore.UserPreferencesRepository
import app.bestnote.feature.settings.data.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val store = FakeDataStore(UserPreferences())
    private val locale = FakeLocale()
    private val storage = FakeStorage()
    private lateinit var vm: SettingsViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = SettingsViewModel(UserPreferencesRepository(store), locale, storage)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun updatesArePersisted() = runTest(UnconfinedTestDispatcher()) {
        backgroundScope.launch { vm.preferences.collect {} }
        vm.updateStylus { it.copy(fingerDrawing = FingerDrawing.Never) }
        vm.update { it.copy(hoverButtonAction = HoverButtonAction.Off, toolbarDock = ToolbarDock.Right, hoverDoubleClickUndo = true) }
        vm.updateAppearance { it.copy(chromeWarmth = ChromeWarmth.Neutral) }
        vm.updateGestures { it.copy(pinchToZoom = false) }
        assertEquals(FingerDrawing.Never, store.value.stylus.fingerDrawing)
        assertEquals(HoverButtonAction.Off, store.value.hoverButtonAction)
        assertEquals(ToolbarDock.Right, store.value.toolbarDock)
        assertEquals(true, store.value.hoverDoubleClickUndo)
        assertEquals(true, store.value.stylusOnly)
        assertEquals(ChromeWarmth.Neutral, store.value.appearance.chromeWarmth)
        assertFalse(store.value.gestures.pinchToZoom)
        assertEquals(store.value, vm.preferences.value)
    }

    @Test fun storageMeasuredOnStartAndCleared() = runTest {
        assertEquals(1_250L, vm.storage.value.usage?.totalBytes)
        vm.clearThumbnails()
        assertEquals(1, storage.cleared)
        assertEquals(1_000L, vm.storage.value.usage?.totalBytes)
        assertFalse(vm.storage.value.clearing)
    }

    @Test fun trashRetention() = runTest {
        vm.updateStorage { it.copy(trashRetentionDays = 90) }
        assertEquals(90, store.value.storage.trashRetentionDays)
    }

    @Test fun languageGoesThroughLocaleController() {
        assertEquals(AppLanguage.System, vm.language.value)
        vm.setLanguage(AppLanguage.Arabic)
        assertEquals(AppLanguage.Arabic, locale.language)
        assertEquals(AppLanguage.Arabic, vm.language.value)
    }

    @Test fun selection() {
        assertNull(vm.selected.value)
        vm.select(SettingsCategory.SmartBoard)
        assertEquals(SettingsCategory.SmartBoard, vm.selected.value)
        vm.select(null)
        assertNull(vm.selected.value)
    }
}
