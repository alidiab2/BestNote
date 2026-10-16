package app.bestnote.ai.vision.providers

import app.bestnote.ai.vision.AiProviderKind
import app.bestnote.ai.vision.VisionError
import app.bestnote.ai.vision.VisionProvider
import app.bestnote.ai.vision.claude.ClaudeKeyChecker
import app.bestnote.ai.vision.claude.ClaudeVisionProvider
import app.bestnote.ai.vision.gemini.GeminiKeyChecker
import app.bestnote.ai.vision.gemini.GeminiVisionProvider
import app.bestnote.ai.vision.openai.OpenAiConfig
import app.bestnote.ai.vision.openai.OpenAiKeyChecker
import app.bestnote.ai.vision.openai.OpenAiVisionProvider
import okhttp3.OkHttpClient

/** Builds the [VisionProvider] the user picked in Settings → Smart Board AI, and tests connections. */
class AiProviders(private val http: OkHttpClient, val settings: KeystoreAiSettings) {
    val selected: AiProviderKind get() = settings.provider

    /** True when [kind] can be called: a key, or for a compatible server, a base URL (key optional). */
    suspend fun isConfigured(kind: AiProviderKind = selected): Boolean = when (kind) {
        AiProviderKind.OpenAiCompatible -> settings.baseUrl(kind) != null && settings.model(kind).isNotBlank()
        else -> settings.hasKey(kind)
    }

    fun create(kind: AiProviderKind = selected): VisionProvider = when (kind) {
        AiProviderKind.Gemini -> GeminiVisionProvider(http, settings.gemini)
        AiProviderKind.Claude -> ClaudeVisionProvider(http, settings.credentials(kind))
        AiProviderKind.OpenAi, AiProviderKind.OpenAiCompatible -> OpenAiVisionProvider(http, settings.credentials(kind))
    }

    /**
     * "Test connection" with [draftKey] (else the stored key), [model] and [baseUrl]. Returns
     * normally when the provider accepts them; throws the [VisionError] otherwise.
     */
    suspend fun test(kind: AiProviderKind, draftKey: String?, model: String, baseUrl: String?) {
        val key = draftKey?.trim()?.takeIf { it.isNotEmpty() } ?: settings.apiKey(kind)
        val id = model.trim().ifEmpty { AiProviderDefaults.model(kind) }
        when (kind) {
            AiProviderKind.Gemini -> GeminiKeyChecker(http).check(key ?: throw VisionError.MissingKey(), id)
            AiProviderKind.Claude -> ClaudeKeyChecker(http).check(key ?: throw VisionError.MissingKey(), id)
            AiProviderKind.OpenAi -> OpenAiKeyChecker(http).check(key, id, OpenAiConfig.OPENAI_BASE_URL)
            AiProviderKind.OpenAiCompatible -> {
                val base = baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: settings.baseUrl(kind)
                    ?: throw VisionError.BadResponse("no base URL set")
                OpenAiKeyChecker(http).check(key, id, base)
            }
        }
    }
}
