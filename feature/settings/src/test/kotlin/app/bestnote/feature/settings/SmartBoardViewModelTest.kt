package app.bestnote.feature.settings

import app.bestnote.ai.vision.AiModel
import app.bestnote.ai.vision.AiProviderKind
import app.bestnote.ai.vision.ModelCatalog
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
    private val geminiList = listOf("gemini-2.5-flash", "gemini-3.8-flash", "gemini-4-argon", "gemini-3.1-pro-preview").map { AiModel(it, it.uppercase()) }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun startsWithoutKeyOnAutomaticModel() {
        val vm = SmartBoardViewModel(store)
        val s = vm.state.value
        assertFalse(s.stored.hasKey)
        assertFalse(s.canTest)
        assertFalse(s.canSave)
        assertEquals("", s.stored.model)
        // No key: no list call, newest-known fallback.
        assertTrue(store.listCalls.isEmpty())
        assertEquals("gemini-3.8-flash", s.autoBest)
    }

    @Test fun opensWithTheLiveListAndResolvesAutomaticModels() {
        store.key = "stored-fake"
        store.listed = geminiList
        val vm = SmartBoardViewModel(store)
        assertEquals(listOf(AiProviderKind.Gemini), store.listCalls)
        val s = vm.state.value
        assertEquals(4, s.stored.models.size)
        assertEquals("gemini-4-argon", s.autoBest)
        assertEquals("gemini-3.8-flash", s.autoFast)
        assertEquals("gemini-4-argon", s.stored.resolvedModel)
        assertEquals("GEMINI-4-ARGON", s.stored.displayName("gemini-4-argon"))
    }

    @Test fun pickingModelsSavesAtOnceAndRemembersRecentsAndFavourites() {
        store.key = "stored-fake"
        store.listed = geminiList
        val vm = SmartBoardViewModel(store)
        vm.selectModel(ModelCatalog.AUTO_FAST)
        assertEquals("gemini-3.8-flash", vm.state.value.stored.resolvedModel)
        vm.selectModel("gemini-2.5-flash")
        assertEquals("gemini-2.5-flash", store.model)
        vm.toggleFavorite("gemini-3.1-pro-preview")
        assertEquals(listOf("gemini-3.1-pro-preview", "gemini-2.5-flash"), vm.state.value.pinned)
        vm.onFilterChange("pro")
        assertEquals(listOf("gemini-3.1-pro-preview"), vm.state.value.filteredModels.map { it.id })
    }

    @Test fun listFailureIsShownAndKeepsTheFallback() {
        store.key = "stored-fake"
        store.listResult = KeyTestResult.Invalid
        val vm = SmartBoardViewModel(store)
        assertEquals(KeyTestResult.Invalid, vm.state.value.modelsError)
        assertEquals("gemini-3.8-flash", vm.state.value.stored.resolvedModel)
    }

    @Test fun saveStoresKeyClearsDraftAndRefreshesTheList() {
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
        assertEquals(listOf(AiProviderKind.Gemini), store.listCalls)
    }

    @Test fun customModelIdIsSaved() {
        val vm = SmartBoardViewModel(store)
        vm.onCustomModelChange(" gemini-5-experimental ")
        vm.save()
        assertEquals("gemini-5-experimental", store.model)
    }

    @Test fun testUsesDraftAndTheResolvedModel() {
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
        assertEquals("fake-key" to "gemini-3.8-flash", store.tested.single())
    }

    @Test fun removeKey() {
        store.key = "stored-fake"
        val vm = SmartBoardViewModel(store)
        vm.removeKey()
        assertFalse(vm.state.value.stored.hasKey)
        assertEquals(null, store.key)
    }

    @Test fun switchingProviderLoadsItsOwnSettings() {
        store.key = "gemini-fake"
        val vm = SmartBoardViewModel(store)
        vm.selectProvider(AiProviderKind.Claude)
        assertEquals(AiProviderKind.Claude, store.selected)
        val s = vm.state.value
        assertEquals(AiProviderKind.Claude, s.provider)
        assertFalse(s.stored.hasKey)
        assertEquals("claude-opus-5-5", s.autoBest)
        vm.onKeyChange("claude-fake-WXYZ")
        vm.save()
        assertEquals("claude-fake-WXYZ", store.keyOf(AiProviderKind.Claude))
        assertEquals("gemini-fake", store.key)
        assertEquals("…WXYZ", vm.state.value.stored.hint)
    }

    @Test fun compatibleServerSavesBaseUrlAndTestsWithoutKey() {
        val vm = SmartBoardViewModel(store)
        vm.selectProvider(AiProviderKind.OpenAiCompatible)
        assertFalse(vm.state.value.canTest)
        vm.onBaseUrlChange(" http://10.0.2.2:11434/v1/ ")
        vm.onCustomModelChange("qwen2.5vl:7b")
        assertTrue(vm.state.value.canTest)
        vm.test()
        assertEquals("http://10.0.2.2:11434/v1/", store.testedBaseUrls.single())
        assertEquals("qwen2.5vl:7b", store.tested.single().second)
        vm.save()
        assertEquals("http://10.0.2.2:11434/v1/", store.states[AiProviderKind.OpenAiCompatible]!!.baseUrl)
        assertEquals("qwen2.5vl:7b", vm.state.value.stored.model)
    }

    @Test fun errorMapping() {
        assertEquals(KeyTestResult.Invalid, KeyTestResult.from(VisionError.InvalidKey()))
        assertEquals(KeyTestResult.NoKey, KeyTestResult.from(VisionError.MissingKey()))
        assertEquals(KeyTestResult.RateLimited, KeyTestResult.from(VisionError.RateLimited(5)))
        assertEquals(KeyTestResult.Offline, KeyTestResult.from(VisionError.Network(IOException())))
        assertEquals(KeyTestResult.ModelUnavailable, KeyTestResult.from(VisionError.ModelUnavailable("x", "nope")))
        assertEquals(KeyTestResult.NoBaseUrl, KeyTestResult.from(VisionError.BadResponse("no base URL set")))
        assertEquals(KeyTestResult.Failed, KeyTestResult.from(VisionError.BadResponse("HTTP 500")))
        assertEquals(KeyTestResult.Failed, KeyTestResult.from(IllegalStateException()))
    }
}
