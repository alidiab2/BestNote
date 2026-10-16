package app.bestnote.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bestnote.feature.settings.data.DebugLogCollector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DebugUiState(
    val verboseAi: Boolean = false,
    val busy: Boolean = false,
    /** The collected log while the in-app viewer is open. */
    val viewing: String? = null,
)

/** One-shot UI actions the screen performs with an Android context. */
sealed interface DebugEvent {
    data class Share(val uri: Uri) : DebugEvent
    data class Copy(val text: String) : DebugEvent
}

@HiltViewModel
class DebugViewModel @Inject constructor(private val collector: DebugLogCollector) : ViewModel() {
    private val _state = MutableStateFlow(DebugUiState(verboseAi = collector.verboseAiLogging))
    val state: StateFlow<DebugUiState> = _state.asStateFlow()

    private val _events = Channel<DebugEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun setVerboseAi(on: Boolean) {
        collector.verboseAiLogging = on
        _state.update { it.copy(verboseAi = on) }
    }

    fun share() = run { text -> DebugEvent.Share(collector.export(text)) }

    fun copy() = run { text -> DebugEvent.Copy(text) }

    fun view() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val text = collector.collect()
            _state.update { it.copy(busy = false, viewing = text) }
        }
    }

    fun closeViewer() = _state.update { it.copy(viewing = null) }

    private fun run(event: suspend (String) -> DebugEvent) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                _events.send(event(_state.value.viewing ?: collector.collect()))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
