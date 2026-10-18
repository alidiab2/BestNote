package app.bestnote.feature.settings.data

import app.bestnote.ai.vision.AiProviderKind
import app.bestnote.ai.vision.providers.AiProviders
import app.bestnote.ai.vision.providers.account.AccountStatus
import app.bestnote.ai.vision.providers.account.AiAccounts
import app.bestnote.ai.vision.providers.account.SignInPhase
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Settings' view of "Sign in with your AI account" (AI-11). Never exposes the key itself. */
interface AiAccountGateway {
    val phase: StateFlow<SignInPhase>
    fun signedIn(): Boolean
    fun keyHint(): String?

    /** Starts a sign-in; returns the page to open in a Custom Tab, or null when it could not start. */
    suspend fun start(): String?
    fun cancel()
    fun acknowledge()
    suspend fun signOut()

    /** Throws [app.bestnote.ai.vision.providers.account.AccountError]. */
    suspend fun status(): AccountStatus
    suspend fun manageUrl(): String?

    /** Whether Smart Board, equations and Design with AI currently use the account. */
    fun inUse(): Boolean
    suspend fun use()
}

class AccountsGateway @Inject constructor(private val accounts: AiAccounts, private val providers: AiProviders) : AiAccountGateway {
    override val phase get() = accounts.phase
    override fun signedIn() = accounts.signedIn
    override fun keyHint() = accounts.keyHint
    override suspend fun start() = accounts.startOpenRouter()
    override fun cancel() = accounts.cancel()
    override fun acknowledge() = accounts.acknowledge()
    override suspend fun signOut() = accounts.signOut()
    override suspend fun status() = accounts.status()
    override suspend fun manageUrl() = accounts.manageUrl()
    override fun inUse() = providers.selected == AiProviderKind.OpenRouter
    override suspend fun use() = providers.settings.setProvider(AiProviderKind.OpenRouter)
}
