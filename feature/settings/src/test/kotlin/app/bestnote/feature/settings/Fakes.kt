package app.bestnote.feature.settings

import androidx.datastore.core.DataStore
import app.bestnote.feature.settings.data.AppLanguage
import app.bestnote.feature.settings.data.AppLocaleController
import app.bestnote.feature.settings.data.KeyTestResult
import app.bestnote.feature.settings.data.SmartBoardKeyState
import app.bestnote.feature.settings.data.SmartBoardKeyStore
import app.bestnote.feature.settings.data.StorageInspector
import app.bestnote.feature.settings.data.StorageUsage
import app.bestnote.ai.vision.AiProviderKind
import app.bestnote.ai.vision.providers.AiProviderDefaults
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeDataStore<T>(initial: T) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<T> = state
    val value: T get() = state.value
    override suspend fun updateData(transform: suspend (t: T) -> T): T = transform(state.value).also { state.value = it }
}

class FakeLocale(override val supported: Boolean = true) : AppLocaleController {
    var language = AppLanguage.System
    override fun current() = language
    override fun set(language: AppLanguage) {
        this.language = language
    }
}

class FakeStorage : StorageInspector {
    var usage = StorageUsage(notebooksBytes = 1_000, thumbnailsBytes = 200, cacheBytes = 50, notebooksPath = "/data/notebooks")
    var cleared = 0
    override suspend fun usage() = usage
    override suspend fun clearThumbnails() {
        cleared++
        usage = usage.copy(thumbnailsBytes = 0, cacheBytes = 0)
    }
}

/** Records keys only to assert on them; a real store never exposes the key. */
/** Gemini fields are the plain [key]/[model]; other providers live in [others]. */
class FakeKeyStore(var key: String? = null, var model: String = "gemini-2.5-flash") : SmartBoardKeyStore {
    val tested = mutableListOf<Pair<String?, String>>()
    val testedBaseUrls = mutableListOf<String?>()
    var nextResult: KeyTestResult = KeyTestResult.Ok
    var gate: CompletableDeferred<Unit>? = null
    var selected = AiProviderKind.Gemini
    val others = mutableMapOf<AiProviderKind, SmartBoardKeyState>()
    private val otherKeys = mutableMapOf<AiProviderKind, String?>()

    override suspend fun selected() = selected
    override suspend fun select(kind: AiProviderKind) {
        selected = kind
    }

    override suspend fun load(kind: AiProviderKind) =
        if (kind == AiProviderKind.Gemini) SmartBoardKeyState(key != null, key?.takeLast(4)?.let { "…$it" }, model)
        else others[kind] ?: SmartBoardKeyState(model = AiProviderDefaults.model(kind))

    override suspend fun saveKey(kind: AiProviderKind, key: String?) {
        val k = key?.trim()?.takeIf { it.isNotEmpty() }
        if (kind == AiProviderKind.Gemini) this.key = k else {
            otherKeys[kind] = k
            others[kind] = load(kind).copy(hasKey = k != null, hint = k?.takeLast(4)?.let { "…$it" })
        }
    }

    override suspend fun saveModel(kind: AiProviderKind, model: String) {
        if (kind == AiProviderKind.Gemini) this.model = model else others[kind] = load(kind).copy(model = model)
    }

    override suspend fun saveBaseUrl(kind: AiProviderKind, url: String) {
        others[kind] = load(kind).copy(baseUrl = url)
    }

    fun keyOf(kind: AiProviderKind) = if (kind == AiProviderKind.Gemini) key else otherKeys[kind]

    override suspend fun test(kind: AiProviderKind, draftKey: String?, model: String, baseUrl: String?): KeyTestResult {
        tested += draftKey to model
        testedBaseUrls += baseUrl
        gate?.await()
        return if (draftKey == null && keyOf(kind) == null && kind != AiProviderKind.OpenAiCompatible) KeyTestResult.NoKey else nextResult
    }
}
