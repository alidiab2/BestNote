package app.bestnote.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bestnote.ai.vision.AiModel
import app.bestnote.ai.vision.AiProviderKind
import app.bestnote.ai.vision.ModelCatalog
import app.bestnote.feature.settings.data.KeyTestResult
import app.bestnote.feature.settings.data.SmartBoardKeyState
import app.bestnote.feature.settings.data.SmartBoardKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SmartBoardUiState(
    val provider: AiProviderKind = AiProviderKind.Gemini,
    val stored: SmartBoardKeyState = SmartBoardKeyState(),
    /** What the user is typing; cleared as soon as it is saved. Never persisted or logged. */
    val keyDraft: String = "",
    /** A model id typed by hand (for ids the list does not show). */
    val customModelDraft: String = "",
    val baseUrlDraft: String = "",
    val testing: Boolean = false,
    val result: KeyTestResult? = null,
    val justSaved: Boolean = false,
    val loadingModels: Boolean = false,
    /** Why the last list fetch failed; null when it worked or did not run. */
    val modelsError: KeyTestResult? = null,
    /** Narrows a long list (OpenRouter lists hundreds). */
    val modelFilter: String = "",
) {
    val compatible: Boolean get() = provider == AiProviderKind.OpenAiCompatible
    val autoBest: String get() = stored.autoModel(provider, ModelCatalog.Preference.Quality)
    val autoFast: String get() = stored.autoModel(provider, ModelCatalog.Preference.Fast)

    /** Favourites first, then recents, without duplicates. */
    val pinned: List<String> get() = (stored.favorites + stored.recents).distinct()

    val filteredModels: List<AiModel>
        get() = modelFilter.trim().lowercase().let { f ->
            if (f.isEmpty()) stored.models else stored.models.filter { f in it.id.lowercase() || f in it.displayName.lowercase() }
        }

    val canSave: Boolean
        get() = keyDraft.isNotBlank() || customModelDraft.isNotBlank() || (compatible && baseUrlDraft.trim() != stored.baseUrl)

    val canTest: Boolean
        get() = !testing && (keyDraft.isNotBlank() || stored.hasKey || (compatible && baseUrlDraft.isNotBlank()))

    val canListModels: Boolean
        get() = !loadingModels && (keyDraft.isNotBlank() || stored.hasKey || (compatible && baseUrlDraft.isNotBlank()))
}

@HiltViewModel
class SmartBoardViewModel @Inject constructor(private val store: SmartBoardKeyStore) : ViewModel() {
    private val _state = MutableStateFlow(SmartBoardUiState())
    val state: StateFlow<SmartBoardUiState> = _state.asStateFlow()
    private var listing: Job? = null

    init {
        viewModelScope.launch {
            show(store.selected())
            refreshModels() // the live list on every open of the picker
        }
    }

    private suspend fun show(kind: AiProviderKind, justSaved: Boolean = false, keepResult: Boolean = false) {
        val stored = store.load(kind)
        _state.update {
            it.copy(
                provider = kind,
                stored = stored,
                keyDraft = "",
                customModelDraft = "",
                baseUrlDraft = stored.baseUrl,
                result = if (keepResult) it.result else null,
                justSaved = justSaved,
                modelsError = if (it.provider == kind) it.modelsError else null,
                modelFilter = if (it.provider == kind) it.modelFilter else "",
            )
        }
    }

    private suspend fun reload() {
        val stored = store.load(_state.value.provider)
        _state.update { it.copy(stored = stored) }
    }

    /** The AI account signed in or out (AI-11): show whichever provider is active now, with its live list. */
    fun reloadSelected() {
        listing?.cancel()
        viewModelScope.launch {
            _state.update { it.copy(loadingModels = false, modelsError = null) }
            show(store.selected())
            refreshModels()
        }
    }

    fun selectProvider(kind: AiProviderKind) {
        if (kind == _state.value.provider) return
        listing?.cancel()
        viewModelScope.launch {
            store.select(kind)
            _state.update { it.copy(loadingModels = false, modelsError = null) }
            show(kind)
            refreshModels()
        }
    }

    fun onKeyChange(value: String) = _state.update { it.copy(keyDraft = value.trim(), result = null, justSaved = false) }

    fun onCustomModelChange(value: String) = _state.update { it.copy(customModelDraft = value, result = null, justSaved = false) }

    fun onBaseUrlChange(value: String) = _state.update { it.copy(baseUrlDraft = value.trim(), result = null, justSaved = false) }

    fun onFilterChange(value: String) = _state.update { it.copy(modelFilter = value) }

    /** Picks a model right away: an id, "" (automatic best) or [ModelCatalog.AUTO_FAST]. */
    fun selectModel(model: String) = viewModelScope.launch {
        val kind = _state.value.provider
        store.saveModel(kind, model)
        reload()
        _state.update { it.copy(result = null, justSaved = true) }
    }

    fun toggleFavorite(id: String) = viewModelScope.launch {
        store.toggleFavorite(_state.value.provider, id)
        reload()
    }

    /** Fetches the provider's live model list (also used after a new key is typed). */
    fun refreshModels() {
        val current = _state.value
        if (!current.canListModels) return
        listing?.cancel()
        listing = viewModelScope.launch {
            _state.update { it.copy(loadingModels = true, modelsError = null) }
            val result = store.refreshModels(
                current.provider,
                current.keyDraft.ifBlank { null },
                current.baseUrlDraft.takeIf { current.compatible && it.isNotBlank() },
            )
            reload()
            _state.update { it.copy(loadingModels = false, modelsError = result.takeIf { r -> r != KeyTestResult.Ok }) }
        }
    }

    fun save() = viewModelScope.launch {
        val current = _state.value
        val kind = current.provider
        val newKey = current.keyDraft.isNotBlank()
        if (newKey) store.saveKey(kind, current.keyDraft)
        if (current.customModelDraft.isNotBlank()) store.saveModel(kind, current.customModelDraft.trim())
        if (current.compatible && current.baseUrlDraft != current.stored.baseUrl) store.saveBaseUrl(kind, current.baseUrlDraft)
        show(kind, justSaved = true)
        if (newKey || current.compatible) refreshModels()
    }

    fun removeKey() = viewModelScope.launch {
        val kind = _state.value.provider
        store.saveKey(kind, null)
        show(kind)
    }

    fun test() {
        if (_state.value.testing) return
        viewModelScope.launch {
            val current = _state.value
            _state.update { it.copy(testing = true, result = null) }
            val result = store.test(
                current.provider,
                current.keyDraft.ifBlank { null },
                current.customModelDraft.ifBlank { current.stored.resolvedModel },
                current.baseUrlDraft.takeIf { current.compatible && it.isNotBlank() },
            )
            _state.update { it.copy(testing = false, result = result) }
        }
    }
}
