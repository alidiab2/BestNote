package app.bestnote.feature.settings

import androidx.lifecycle.ViewModel
import app.bestnote.ai.vision.NotesLanguage
import app.bestnote.ai.vision.providers.SmartBoardStyles
import app.bestnote.engine.layout.StyleTemplate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** Settings → Smart Board AI → Notes: the style and notes language new notebooks start with. */
@HiltViewModel
class SmartBoardNotesViewModel @Inject constructor(private val styles: SmartBoardStyles) : ViewModel() {
    data class State(val style: StyleTemplate, val language: NotesLanguage)

    private val state = MutableStateFlow(State(styles.defaultStyle, styles.defaultLanguage))
    val ui: StateFlow<State> = state.asStateFlow()

    fun setStyle(style: StyleTemplate) {
        styles.defaultStyle = style
        state.value = state.value.copy(style = style)
    }

    fun setLanguage(language: NotesLanguage) {
        styles.defaultLanguage = language
        state.value = state.value.copy(language = language)
    }
}
