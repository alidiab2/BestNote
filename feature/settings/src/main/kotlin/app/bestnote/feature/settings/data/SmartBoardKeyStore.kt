package app.bestnote.feature.settings.data

import app.bestnote.ai.vision.VisionError
import app.bestnote.ai.vision.gemini.GeminiConfig
import app.bestnote.ai.vision.gemini.GeminiKeyChecker
import app.bestnote.ai.vision.gemini.KeystoreGeminiSettings
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** Smart Board providers. Only Gemini ships today; the rest are listed as "coming soon". */
enum class AiProvider(val available: Boolean) { Gemini(true), Claude(false), OpenAi(false) }

/** What Settings may know about the stored key: never the key itself, only whether it exists and its last 4 chars. */
data class SmartBoardKeyState(val hasKey: Boolean = false, val hint: String? = null, val model: String = GeminiConfig.DEFAULT_MODEL)

/** Outcome of "Test key". */
sealed interface KeyTestResult {
    data object Ok : KeyTestResult
    data object NoKey : KeyTestResult
    data object Invalid : KeyTestResult
    data object ModelUnavailable : KeyTestResult
    data object RateLimited : KeyTestResult
    data object Offline : KeyTestResult
    data object Failed : KeyTestResult

    companion object {
        fun from(error: Throwable): KeyTestResult = when (error) {
            is VisionError.MissingKey -> NoKey
            is VisionError.InvalidKey -> Invalid
            is VisionError.RateLimited -> RateLimited
            is VisionError.Network -> Offline
            is VisionError.BadResponse -> if ("not available" in (error.message ?: "")) ModelUnavailable else Failed
            else -> Failed
        }
    }
}

/**
 * Settings' view of the Smart Board key. The plain key only passes through [saveKey] and [test];
 * it is never returned, logged or kept in UI state after saving.
 */
interface SmartBoardKeyStore {
    suspend fun load(): SmartBoardKeyState
    suspend fun saveKey(key: String?)
    suspend fun saveModel(model: String)

    /** Tests [draftKey] when given, else the stored key, against [model]. */
    suspend fun test(draftKey: String?, model: String): KeyTestResult
}

/** Backed by the existing [KeystoreGeminiSettings] (AES-GCM, Android Keystore) and [GeminiKeyChecker]. */
class GeminiKeyStore @Inject constructor(
    private val settings: KeystoreGeminiSettings,
    private val checker: GeminiKeyChecker,
) : SmartBoardKeyStore {
    override suspend fun load() = SmartBoardKeyState(settings.hasApiKey, settings.apiKeyHint, settings.model())

    override suspend fun saveKey(key: String?) = settings.setApiKey(key)

    override suspend fun saveModel(model: String) = settings.setModel(model)

    override suspend fun test(draftKey: String?, model: String): KeyTestResult {
        val key = draftKey?.trim()?.takeIf { it.isNotEmpty() } ?: settings.apiKey() ?: return KeyTestResult.NoKey
        return try {
            checker.check(key, model.trim().ifEmpty { GeminiConfig.DEFAULT_MODEL })
            KeyTestResult.Ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KeyTestResult.from(e)
        }
    }
}
