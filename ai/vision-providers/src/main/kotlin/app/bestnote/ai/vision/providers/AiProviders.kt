package app.bestnote.ai.vision.providers

import app.bestnote.ai.vision.AiModel
import app.bestnote.ai.vision.AiProviderKind
import app.bestnote.ai.vision.ModelCatalog
import app.bestnote.ai.vision.gemini.GeminiSettings
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
        // The Gemini key stays in its own store; the model is resolved like every other provider's.
        AiProviderKind.Gemini -> GeminiVisionProvider(
            http,
            object : GeminiSettings {
                override suspend fun apiKey() = settings.gemini.apiKey()
                override suspend fun model() = settings.model(AiProviderKind.Gemini)
            },
        )
        AiProviderKind.Claude -> ClaudeVisionProvider(http, settings.credentials(kind))
        AiProviderKind.OpenAi, AiProviderKind.OpenAiCompatible -> OpenAiVisionProvider(http, settings.credentials(kind))
    }

    /**
     * "Test connection" with [draftKey] (else the stored key), [model] and [baseUrl]. Returns
     * normally when the provider accepts them; throws the [VisionError] otherwise.
     */
    suspend fun test(kind: AiProviderKind, draftKey: String?, model: String, baseUrl: String?) {
        val key = draftKey?.trim()?.takeIf { it.isNotEmpty() } ?: settings.apiKey(kind)
        val id = model.trim().let { if (ModelCatalog.isAuto(it)) ModelCatalog.resolve(kind, it, settings.cachedModels(kind)) else it }
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

    /**
     * Fetches [kind]'s live model list with [draftKey] (else the stored key) and caches it. An
     * automatic model choice then resolves to the newest suitable entry. Throws [VisionError].
     */
    suspend fun refreshModels(kind: AiProviderKind, draftKey: String? = null, baseUrl: String? = null): List<AiModel> {
        val key = draftKey?.trim()?.takeIf { it.isNotEmpty() } ?: settings.apiKey(kind)
        val models = when (kind) {
            AiProviderKind.Gemini -> GeminiKeyChecker(http).listModels(key ?: throw VisionError.MissingKey())
            AiProviderKind.Claude -> ClaudeKeyChecker(http).listModels(key ?: throw VisionError.MissingKey())
            AiProviderKind.OpenAi -> OpenAiKeyChecker(http).listModels(key, OpenAiConfig.OPENAI_BASE_URL)
            AiProviderKind.OpenAiCompatible -> OpenAiKeyChecker(http).listModels(
                key,
                baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: settings.baseUrl(kind) ?: throw VisionError.BadResponse("no base URL set"),
            )
        }
        settings.setCachedModels(kind, models)
        return models
    }

    /** [refreshModels] when the cache is older than [maxAgeMs]; failures keep the old cache. */
    suspend fun refreshModelsIfStale(kind: AiProviderKind = selected, maxAgeMs: Long = DAY_MS): List<AiModel> {
        if (System.currentTimeMillis() - settings.modelsFetchedAt(kind) < maxAgeMs) return settings.cachedModels(kind)
        val canList = if (kind == AiProviderKind.OpenAiCompatible) settings.baseUrl(kind) != null else settings.hasKey(kind)
        if (!canList) return settings.cachedModels(kind)
        return runCatching { refreshModels(kind) }.getOrElse { settings.cachedModels(kind) }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
