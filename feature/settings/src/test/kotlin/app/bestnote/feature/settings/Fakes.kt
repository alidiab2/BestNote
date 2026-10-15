package app.bestnote.feature.settings

import androidx.datastore.core.DataStore
import app.bestnote.feature.settings.data.AppLanguage
import app.bestnote.feature.settings.data.AppLocaleController
import app.bestnote.feature.settings.data.KeyTestResult
import app.bestnote.feature.settings.data.SmartBoardKeyState
import app.bestnote.feature.settings.data.SmartBoardKeyStore
import app.bestnote.feature.settings.data.StorageInspector
import app.bestnote.feature.settings.data.StorageUsage
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
class FakeKeyStore(var key: String? = null, var model: String = "gemini-2.5-flash") : SmartBoardKeyStore {
    val tested = mutableListOf<Pair<String?, String>>()
    var nextResult: KeyTestResult = KeyTestResult.Ok
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun load() = SmartBoardKeyState(key != null, key?.takeLast(4)?.let { "…$it" }, model)
    override suspend fun saveKey(key: String?) {
        this.key = key?.trim()?.takeIf { it.isNotEmpty() }
    }
    override suspend fun saveModel(model: String) {
        this.model = model
    }
    override suspend fun test(draftKey: String?, model: String): KeyTestResult {
        tested += draftKey to model
        gate?.await()
        return if (draftKey == null && key == null) KeyTestResult.NoKey else nextResult
    }
}
