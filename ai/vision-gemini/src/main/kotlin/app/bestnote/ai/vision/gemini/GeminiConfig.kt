package app.bestnote.ai.vision.gemini

/** Where requests go and how patient we are. Default: Google's endpoint with the user's own key. */
data class GeminiConfig(
    val baseUrl: String = "https://generativelanguage.googleapis.com/",
    /** Reasoning over a dense board can take a while. */
    val timeoutSeconds: Long = 240,
    /** Delay before the single retry of a 5xx / overloaded response. */
    val retryDelayMs: Long = 1_500,
    /** Faithful transcription, not creativity. */
    val temperature: Double = 0.2,
    val maxOutputTokens: Int = MAX_OUTPUT_TOKENS,
) {
    companion object {
        /**
         * Last-resort id when no live model list is available. The app normally resolves the model
         * from the key's own `GET v1beta/models` list ([GeminiModels], `ModelCatalog`).
         */
        const val DEFAULT_MODEL = "gemini-3.8-flash"

        /** The old hard-wired default; a stored value equal to it is treated as "automatic". */
        const val LEGACY_DEFAULT_MODEL = "gemini-2.5-flash"

        /**
         * Room for a full board plus low thinking (2.5 models share this budget). REQ-002: was 32,768, which let a
         * runaway answer bill four times a page; an answer lost entirely to thinking is retried once with 2×.
         */
        const val MAX_OUTPUT_TOKENS = 8_192

        /** The one retry after an empty, cut-off answer. */
        const val RETRY_OUTPUT_TOKENS = 16_384
    }
}

/**
 * The app's secure settings contract for this provider. The key lives only here (encrypted with
 * the Android Keystore, see [KeystoreGeminiSettings]); the provider reads it per request and
 * never logs, caches or persists it anywhere else.
 */
interface GeminiSettings {
    /** The user's own API key, or null when none is configured. */
    suspend fun apiKey(): String?

    /** The Gemini model id to call, e.g. [GeminiConfig.DEFAULT_MODEL]. */
    suspend fun model(): String = GeminiConfig.DEFAULT_MODEL
}
