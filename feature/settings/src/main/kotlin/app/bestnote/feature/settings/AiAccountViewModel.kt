package app.bestnote.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bestnote.ai.vision.providers.account.AccountError
import app.bestnote.ai.vision.providers.account.AccountStatus
import app.bestnote.ai.vision.providers.account.SignInFailure
import app.bestnote.ai.vision.providers.account.SignInPhase
import app.bestnote.feature.settings.data.AiAccountGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** Credits line under the signed-in account. */
sealed interface AccountStatusUi {
    data object Loading : AccountStatusUi
    data class Ready(val status: AccountStatus) : AccountStatusUi

    /** The key was deleted on openrouter.ai: sign in again. */
    data object Revoked : AccountStatusUi
    data object Unavailable : AccountStatusUi
}

data class AiAccountUiState(
    val signedIn: Boolean = false,
    val keyHint: String? = null,
    val inUse: Boolean = false,
    val phase: SignInPhase = SignInPhase.Idle,
    val status: AccountStatusUi? = null,
) {
    val busy: Boolean get() = phase is SignInPhase.Waiting || phase is SignInPhase.Finishing
    val failure: SignInFailure? get() = (phase as? SignInPhase.Failed)?.reason
}

/** Settings → Smart Board AI → AI account: "Sign in with OpenRouter", status, sign out (AI-11). */
@HiltViewModel
class AiAccountViewModel @Inject constructor(private val gateway: AiAccountGateway) : ViewModel() {
    private val _state = MutableStateFlow(AiAccountUiState())
    val state: StateFlow<AiAccountUiState> = _state.asStateFlow()

    private val _open = Channel<String>(Channel.BUFFERED)

    /** URLs for the UI to open in a Custom Tab (the sign-in page, or the key's page on OpenRouter). */
    val open = _open.receiveAsFlow()

    /** Bumped when the account changed, so the provider section below reloads. */
    private val _changed = MutableStateFlow(0)
    val changed: StateFlow<Int> = _changed.asStateFlow()

    init {
        refresh(loadStatus = true)
        viewModelScope.launch {
            gateway.phase.collect { phase ->
                _state.update { it.copy(phase = phase) }
                if (phase == SignInPhase.Done) {
                    refresh(loadStatus = true)
                    _changed.update { it + 1 }
                }
            }
        }
    }

    private fun refresh(loadStatus: Boolean) {
        val signedIn = gateway.signedIn()
        _state.update { it.copy(signedIn = signedIn, keyHint = gateway.keyHint(), inUse = gateway.inUse(), status = if (signedIn) it.status else null) }
        if (signedIn && loadStatus) loadStatus()
    }

    fun loadStatus() = viewModelScope.launch {
        _state.update { it.copy(status = AccountStatusUi.Loading) }
        val status = try {
            AccountStatusUi.Ready(gateway.status())
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountError.KeyRevoked) {
            AccountStatusUi.Revoked
        } catch (e: AccountError) {
            AccountStatusUi.Unavailable
        }
        _state.update { it.copy(status = status) }
    }

    fun signIn() = viewModelScope.launch {
        gateway.start()?.let { _open.send(it) }
    }

    fun cancel() = gateway.cancel()

    fun dismissResult() = gateway.acknowledge()

    fun signOut() = viewModelScope.launch {
        gateway.signOut()
        refresh(loadStatus = false)
        _changed.update { it + 1 }
    }

    fun use() = viewModelScope.launch {
        gateway.use()
        refresh(loadStatus = false)
        _changed.update { it + 1 }
    }

    fun manage() = viewModelScope.launch {
        gateway.manageUrl()?.let { _open.send(it) }
    }

    /** The provider section picked another provider (or this one). */
    fun onProviderChanged() = refresh(loadStatus = false)
}
