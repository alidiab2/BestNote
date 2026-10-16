package app.bestnote.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bestnote.ai.vision.AiProviderKind
import app.bestnote.ai.vision.providers.AiProviderDefaults
import app.bestnote.feature.settings.data.KeyTestResult
import app.bestnote.feature.settings.data.SmartBoardKeyState
import app.bestnote.feature.settings.data.SmartBoardKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val modelDraft: String = AiProviderDefaults.model(AiProviderKind.Gemini),
    val baseUrlDraft: String = "",
    val testing: Boolean = false,
    val result: KeyTestResult? = null,
    val justSaved: Boolean = false,
) {
    val defaultModel: String get() = AiProviderDefaults.model(provider)
    val suggestions: List<String> get() = AiProviderDefaults.suggestions(provider)
    val compatible: Boolean get() = provider == AiProviderKind.OpenAiCompatible

    val canSave: Boolean
        get() = keyDraft.isNotBlank() || modelDraft.trim() != stored.model || (compatible && baseUrlDraft.trim() != stored.baseUrl)

    val canTest: Boolean
        get() = !testing && (keyDraft.isNotBlank() || stored.hasKey || (compatible && baseUrlDraft.isNotBlank()))
}

@HiltViewModel
class SmartBoardViewModel @Inject constructor(private val store: SmartBoardKeyStore) : ViewModel() {
    private val _state = MutableStateFlow(SmartBoardUiState())
    val state: StateFlow<SmartBoardUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { show(store.selected()) }
    }

    private suspend fun show(kind: AiProviderKind, justSaved: Boolean = false) {
        val stored = store.load(kind)
        _state.update {
            it.copy(
                provider = kind,
                stored = stored,
                keyDraft = "",
                modelDraft = stored.model,
                baseUrlDraft = stored.baseUrl,
                result = null,
                justSaved = justSaved,
            )
        }
    }

    fun selectProvider(kind: AiProviderKind) {
        if (kind == _state.value.provider) return
        viewModelScope.launch {
            store.select(kind)
            show(kind)
        }
    }

    fun onKeyChange(value: String) = _state.update { it.copy(keyDraft = value.trim(), result = null, justSaved = false) }

    fun onModelChange(value: String) = _state.update { it.copy(modelDraft = value, result = null, justSaved = false) }

    fun onBaseUrlChange(value: String) = _state.update { it.copy(baseUrlDraft = value.trim(), result = null, justSaved = false) }

    fun resetModel() = onModelChange(_state.value.defaultModel)

    fun save() = viewModelScope.launch {
        val current = _state.value
        val kind = current.provider
        if (current.keyDraft.isNotBlank()) store.saveKey(kind, current.keyDraft)
        val model = current.modelDraft.trim().ifEmpty { current.defaultModel }
        if (model != current.stored.model) store.saveModel(kind, model)
        if (current.compatible && current.baseUrlDraft != current.stored.baseUrl) store.saveBaseUrl(kind, current.baseUrlDraft)
        show(kind, justSaved = true)
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
                current.modelDraft,
                current.baseUrlDraft.takeIf { current.compatible && it.isNotBlank() },
            )
            _state.update { it.copy(testing = false, result = result) }
        }
    }
}
