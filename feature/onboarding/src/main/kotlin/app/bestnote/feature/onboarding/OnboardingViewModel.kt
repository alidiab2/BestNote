package app.bestnote.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bestnote.core.datastore.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val preferences: UserPreferencesRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun next() = _state.update { it.next() }

    fun back() = _state.update { it.back() }

    fun onContact(contact: Contact) = _state.update { it.onContact(contact) }

    fun chooseNoPen() = _state.update { it.chooseNoPen() }

    /** Skip or finish: applies what the pen check learned to the existing stylus settings, then marks the flow as seen. */
    fun finish() {
        val outcome = _state.value.pen.outcome
        viewModelScope.launch {
            when (outcome) {
                PenOutcome.PenSeen -> preferences.markPenSeen()
                PenOutcome.FingerDrawing -> preferences.setDrawWithFinger(true)
                PenOutcome.Unchanged -> Unit
            }
            preferences.setOnboardingDone(true)
        }
    }
}
