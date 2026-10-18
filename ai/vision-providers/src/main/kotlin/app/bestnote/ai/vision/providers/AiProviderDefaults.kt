package app.bestnote.ai.vision.providers

import app.bestnote.ai.vision.AiProviderKind
import app.bestnote.ai.vision.ModelCatalog
import app.bestnote.ai.vision.openai.OpenAiConfig

/** Per-provider facts shown in Settings. Models come from each provider's live list (see [ModelCatalog]). */
object AiProviderDefaults {
    /** The newest-known model, used only before the live list has been fetched. */
    fun model(kind: AiProviderKind, preference: ModelCatalog.Preference = ModelCatalog.Preference.Quality): String =
        ModelCatalog.fallback(kind, preference)

    /** Where to get a key; null for compatible servers (depends on the server). */
    fun keyUrl(kind: AiProviderKind): String? = when (kind) {
        AiProviderKind.Gemini -> "https://aistudio.google.com/apikey"
        AiProviderKind.Claude -> "https://platform.claude.com/settings/keys"
        AiProviderKind.OpenAi -> "https://platform.openai.com/api-keys"
        AiProviderKind.OpenRouter -> "https://openrouter.ai/settings/keys"
        AiProviderKind.OpenAiCompatible -> null
    }

    /** Whether a key is required (a local Ollama needs none). */
    fun needsKey(kind: AiProviderKind) = kind != AiProviderKind.OpenAiCompatible

    val compatiblePresets: Map<String, String> get() = OpenAiConfig.COMPATIBLE_PRESETS
}
