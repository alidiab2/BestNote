package app.bestnote.feature.settings

import app.bestnote.ai.vision.VisionError
import app.bestnote.feature.settings.data.KeyTestResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SmartBoardViewModelTest {
    private val store = FakeKeyStore()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun startsWithoutKey() {
        val vm = SmartBoardViewModel(store)
        assertFalse(vm.state.value.stored.hasKey)
        assertFalse(vm.state.value.canTest)
        assertFalse(vm.state.value.canSave)
        assertEquals("gemini-2.5-flash", vm.state.value.modelDraft)
    }

    @Test fun saveStoresKeyClearsDraftAndShowsOnlyHint() {
        val vm = SmartBoardViewModel(store)
        vm.onKeyChange("  fake-key-ABCD  ")
        assertTrue(vm.state.value.canSave)
        vm.save()
        assertEquals("fake-key-ABCD", store.key)
        val s = vm.state.value
        assertEquals("", s.keyDraft)
        assertTrue(s.stored.hasKey)
        assertEquals("…ABCD", s.stored.hint)
        assertTrue(s.justSaved)
    }

    @Test fun saveModelBlankFallsBackToDefault() {
        store.model = "custom-model"
        val vm = SmartBoardViewModel(store)
        assertEquals("custom-model", vm.state.value.modelDraft)
        vm.onModelChange("   ")
        vm.save()
        assertEquals("gemini-2.5-flash", store.model)
    }

    @Test fun testUsesDraftThenReportsResult() {
        val vm = SmartBoardViewModel(store)
        store.nextResult = KeyTestResult.Invalid
        store.gate = CompletableDeferred()
        vm.onKeyChange("fake-key")
        vm.test()
        assertTrue(vm.state.value.testing)
        assertFalse(vm.state.value.canTest)
        store.gate!!.complete(Unit)
        assertFalse(vm.state.value.testing)
        assertEquals(KeyTestResult.Invalid, vm.state.value.result)
        assertEquals("fake-key" to "gemini-2.5-flash", store.tested.single())
    }

    @Test fun testUsesStoredKeyWhenNoDraft() {
        store.key = "stored-fake"
        val vm = SmartBoardViewModel(store)
        vm.test()
        assertEquals(null to "gemini-2.5-flash", store.tested.single())
        assertEquals(KeyTestResult.Ok, vm.state.value.result)
    }

    @Test fun editingClearsResult() {
        store.key = "stored-fake"
        val vm = SmartBoardViewModel(store)
        vm.test()
        vm.onModelChange("other")
        assertEquals(null, vm.state.value.result)
    }

    @Test fun removeKey() {
        store.key = "stored-fake"
        val vm = SmartBoardViewModel(store)
        vm.removeKey()
        assertFalse(vm.state.value.stored.hasKey)
        assertEquals(null, store.key)
    }

    @Test fun errorMapping() {
        assertEquals(KeyTestResult.Invalid, KeyTestResult.from(VisionError.InvalidKey()))
        assertEquals(KeyTestResult.NoKey, KeyTestResult.from(VisionError.MissingKey()))
        assertEquals(KeyTestResult.RateLimited, KeyTestResult.from(VisionError.RateLimited(5)))
        assertEquals(KeyTestResult.Offline, KeyTestResult.from(VisionError.Network(IOException())))
        assertEquals(KeyTestResult.ModelUnavailable, KeyTestResult.from(VisionError.BadResponse("model x is not available: nope")))
        assertEquals(KeyTestResult.Failed, KeyTestResult.from(VisionError.BadResponse("HTTP 500")))
        assertEquals(KeyTestResult.Failed, KeyTestResult.from(IllegalStateException()))
    }
}
