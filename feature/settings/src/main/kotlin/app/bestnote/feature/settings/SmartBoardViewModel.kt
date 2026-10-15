package app.bestnote.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bestnote.ai.vision.gemini.GeminiConfig
import app.bestnote.feature.settings.data.AiProvider
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
    val provider: AiProvider = AiProvider.Gemini,
    val stored: SmartBoardKeyState = SmartBoardKeyState(),
    /** What the user is typing; cleared as soon as it is saved. Never persisted or logged. */
    val keyDraft: String = "",
    val modelDraft: String = GeminiConfig.DEFAULT_MODEL,
    val testing: Boolean = false,
    val result: KeyTestResult? = null,
    val justSaved: Boolean = false,
) {
    val canSave: Boolean get() = keyDraft.isNotBlank() || modelDraft.trim() != stored.model
    val canTest: Boolean get() = !testing && (keyDraft.isNotBlank() || stored.hasKey)
}

@HiltViewModel
class SmartBoardViewModel @Inject constructor(private val store: SmartBoardKeyStore) : ViewModel() {
    private val _state = MutableStateFlow(SmartBoardUiState())
    val state: StateFlow<SmartBoardUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = store.load()
            _state.update { it.copy(stored = stored, modelDraft = stored.model) }
        }
    }

    fun onKeyChange(value: String) = _state.update { it.copy(keyDraft = value.trim(), result = null, justSaved = false) }

    fun onModelChange(value: String) = _state.update { it.copy(modelDraft = value, result = null, justSaved = false) }

    fun resetModel() = onModelChange(GeminiConfig.DEFAULT_MODEL)

    fun save() = viewModelScope.launch {
        val current = _state.value
        if (current.keyDraft.isNotBlank()) store.saveKey(current.keyDraft)
        val model = current.modelDraft.trim().ifEmpty { GeminiConfig.DEFAULT_MODEL }
        if (model != current.stored.model) store.saveModel(model)
        val stored = store.load()
        _state.update { it.copy(stored = stored, keyDraft = "", modelDraft = stored.model, justSaved = true) }
    }

    fun removeKey() = viewModelScope.launch {
        store.saveKey(null)
        val stored = store.load()
        _state.update { it.copy(stored = stored, keyDraft = "", result = null, justSaved = false) }
    }

    fun test() {
        if (_state.value.testing) return
        viewModelScope.launch {
            val current = _state.value
            _state.update { it.copy(testing = true, result = null) }
            val result = store.test(current.keyDraft.ifBlank { null }, current.modelDraft)
            _state.update { it.copy(testing = false, result = result) }
        }
    }
}
