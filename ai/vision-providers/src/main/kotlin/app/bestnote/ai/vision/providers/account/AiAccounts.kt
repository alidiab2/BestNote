package app.bestnote.ai.vision.providers.account

import app.bestnote.ai.vision.AiProviderKind
import app.bestnote.ai.vision.providers.KeystoreAiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.SecureRandom
import kotlin.coroutines.cancellation.CancellationException

/** Where a signed-in account's key lives. The key is only ever stored encrypted. */
interface AccountStore {
    fun isSignedIn(): Boolean
    fun keyHint(): String?
    suspend fun key(): String?

    /** Saves [key] as the account's key and makes the account's provider the active one. */
    suspend fun signIn(key: String)
    suspend fun signOut()
}

/** [AccountStore] over [KeystoreAiSettings] (AES-GCM with a non-exportable Android Keystore key). */
class KeystoreAccountStore(private val settings: KeystoreAiSettings, private val kind: AiProviderKind = AiProviderKind.OpenRouter) : AccountStore {
    override fun isSignedIn() = settings.isSignedIn(kind)
    override fun keyHint() = settings.keyHint(kind)
    override suspend fun key() = settings.apiKey(kind)

    override suspend fun signIn(key: String) {
        settings.setSignedInKey(kind, key)
        settings.setProvider(kind)
    }

    override suspend fun signOut() = settings.setSignedInKey(kind, null)
}

/** Where "Sign in with OpenRouter" is. */
sealed interface SignInPhase {
    data object Idle : SignInPhase

    /** The Custom Tab shows OpenRouter's page; we listen on the loopback callback. */
    data class Waiting(val url: String) : SignInPhase

    /** The browser came back with a code; trading it for the key. */
    data object Finishing : SignInPhase
    data object Done : SignInPhase
    data class Failed(val reason: SignInFailure) : SignInPhase
}

enum class SignInFailure { Cancelled, TimedOut, Denied, Rejected, Offline, Failed }

/**
 * AI-11 "Sign in with your AI account". Today one account type is legitimately available to a
 * third-party app: OpenRouter's OAuth PKCE (see docs/architecture/adr/0008-ai-account-sign-in.md).
 *
 * Flow: open a loopback listener → build the auth URL with a fresh PKCE pair and state → the UI
 * opens it in a Custom Tab → OpenRouter redirects the browser to `http://localhost:<port>/callback`
 * → the state is checked, the code is exchanged with the verifier → the key is stored encrypted
 * and OpenRouter becomes the active Smart Board provider. Runs in [scope] (app-wide) so it
 * survives the Settings screen going to the background while the browser is in front.
 */
class AiAccounts(
    private val auth: OpenRouterAuth,
    private val store: AccountStore,
    private val scope: CoroutineScope,
    /** Deep link that brings the app back from the browser (`<applicationId>://ai-account/done`). */
    private val returnUri: String,
    private val openReceiver: () -> LoopbackReceiver = LoopbackReceiver::open,
    private val random: SecureRandom = SecureRandom(),
    private val timeoutMs: Long = SIGN_IN_TIMEOUT_MS,
    /** After a successful sign-in, e.g. load the account's live model list. */
    private val onSignedIn: suspend () -> Unit = {},
) {
    private val _phase = MutableStateFlow<SignInPhase>(SignInPhase.Idle)
    val phase: StateFlow<SignInPhase> = _phase.asStateFlow()
    private var job: Job? = null
    private var receiver: LoopbackReceiver? = null

    val signedIn: Boolean get() = store.isSignedIn()
    val keyHint: String? get() = store.keyHint()

    /**
     * Starts a sign-in and returns the URL to open, or null when no local port could be opened.
     * A sign-in already running is replaced.
     */
    suspend fun startOpenRouter(): String? {
        cancel()
        val listener = try {
            withContext(Dispatchers.IO) { openReceiver() }
        } catch (e: IOException) {
            _phase.value = SignInPhase.Failed(SignInFailure.Failed)
            return null
        }
        receiver = listener
        val pkce = Pkce.create(random)
        val state = Pkce.randomToken(random)
        val url = auth.authUrl(listener.callbackUrl(), pkce, state)
        _phase.value = SignInPhase.Waiting(url)
        job = scope.launch {
            val outcome = listener.await(timeoutMs) { target ->
                when (val r = auth.parseCallback(target, state)) {
                    is CallbackResult.Code -> r to page(ok = true)
                    is CallbackResult.Error, CallbackResult.StateMismatch -> r to page(ok = false)
                    CallbackResult.NotCallback -> null
                }
            }
            if (!isActive) return@launch // cancelled: cancel() already reported it
            _phase.value = finish(outcome, pkce)
        }
        return url
    }

    private suspend fun finish(outcome: CallbackResult?, pkce: PkcePair): SignInPhase = when (outcome) {
        null -> SignInPhase.Failed(SignInFailure.TimedOut)
        is CallbackResult.Error -> SignInPhase.Failed(SignInFailure.Denied)
        CallbackResult.StateMismatch, CallbackResult.NotCallback -> SignInPhase.Failed(SignInFailure.Rejected)
        is CallbackResult.Code -> {
            _phase.value = SignInPhase.Finishing
            try {
                store.signIn(auth.exchange(outcome.code, pkce.verifier))
                runCatching { onSignedIn() }
                SignInPhase.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountError) {
                SignInPhase.Failed(failureOf(e))
            }
        }
    }

    /** The user backed out (closed the tab and tapped Cancel, or left Settings). */
    fun cancel() {
        job?.cancel()
        job = null
        receiver?.close()
        receiver = null
        if (_phase.value is SignInPhase.Waiting || _phase.value is SignInPhase.Finishing) _phase.value = SignInPhase.Failed(SignInFailure.Cancelled)
    }

    /** Forget the last result (after the UI showed it). */
    fun acknowledge() {
        if (_phase.value is SignInPhase.Done || _phase.value is SignInPhase.Failed) _phase.value = SignInPhase.Idle
    }

    /** Removes the key from this device. The key itself can be deleted on openrouter.ai ([manageUrl]). */
    suspend fun signOut() {
        cancel()
        store.signOut()
        _phase.value = SignInPhase.Idle
    }

    /** Credits and usage of the signed-in key. Throws [AccountError]. */
    suspend fun status(): AccountStatus = auth.status(store.key() ?: throw AccountError.KeyRevoked())

    /** OpenRouter's page for this key (delete it, set a limit); only its owner can open it. */
    suspend fun manageUrl(): String? = store.key()?.let { "${OpenRouterAuth.SITE}/keys/${OpenRouterAuth.keyHash(it)}" }

    /** The page the browser shows after the redirect, with a button back to the app. */
    internal fun page(ok: Boolean): String {
        val (en, ar) = if (ok) "Signed in. Return to BestNote." to "تم تسجيل الدخول. ارجع إلى BestNote."
        else "Sign-in didn’t finish. Return to BestNote and try again." to "لم يكتمل تسجيل الدخول. ارجع إلى BestNote وحاول مرة أخرى."
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            |<title>BestNote</title><style>body{font-family:sans-serif;background:#F6F4EF;color:#1E2433;display:flex;min-height:90vh;
            |align-items:center;justify-content:center;text-align:center;padding:16px}@media(prefers-color-scheme:dark){body{background:#161412;color:#EEEBE4}}
            |a{display:inline-block;margin-top:20px;padding:12px 24px;border-radius:12px;background:#2A4BD7;color:#fff;text-decoration:none;font-weight:600}</style></head>
            |<body><div><p>$en</p><p dir="rtl">$ar</p><a href="$returnUri">BestNote</a></div>
            |<script>setTimeout(function(){location.href="$returnUri"},300)</script></body></html>""".trimMargin()
    }

    companion object {
        /** OpenRouter codes expire after 10 minutes; nobody needs longer to sign in. */
        const val SIGN_IN_TIMEOUT_MS = 10L * 60 * 1000

        fun failureOf(e: AccountError): SignInFailure = when (e) {
            is AccountError.Cancelled -> SignInFailure.Cancelled
            is AccountError.Offline -> SignInFailure.Offline
            is AccountError.StateMismatch, is AccountError.Rejected, is AccountError.KeyRevoked -> SignInFailure.Rejected
            is AccountError.BadResponse -> SignInFailure.Failed
        }
    }
}
