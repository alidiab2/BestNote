package app.bestnote.ai.vision.providers

import app.bestnote.ai.vision.AiProviderKind
import app.bestnote.ai.vision.claude.ClaudeConfig
import app.bestnote.ai.vision.gemini.GeminiConfig
import app.bestnote.ai.vision.openai.OpenAiConfig

/** Per-provider defaults shown in Settings. Model ids are suggestions; the user may type any id. */
object AiProviderDefaults {
    fun model(kind: AiProviderKind): String = when (kind) {
        AiProviderKind.Gemini -> GeminiConfig.DEFAULT_MODEL
        AiProviderKind.Claude -> ClaudeConfig.DEFAULT_MODEL
        AiProviderKind.OpenAi -> OpenAiConfig.DEFAULT_MODEL
        // No sensible universal default: depends on the server (e.g. "qwen2.5vl:7b" on Ollama).
        AiProviderKind.OpenAiCompatible -> ""
    }

    fun suggestions(kind: AiProviderKind): List<String> = when (kind) {
        AiProviderKind.Gemini -> listOf(GeminiConfig.DEFAULT_MODEL, "gemini-2.5-pro", "gemini-2.5-flash-lite")
        AiProviderKind.Claude -> ClaudeConfig.SUGGESTED_MODELS
        AiProviderKind.OpenAi -> OpenAiConfig.SUGGESTED_MODELS
        AiProviderKind.OpenAiCompatible -> listOf("google/gemini-2.5-flash", "meta-llama/llama-4-scout-17b-16e-instruct", "qwen2.5vl:7b")
    }

    /** Where to get a key; null for compatible servers (depends on the server). */
    fun keyUrl(kind: AiProviderKind): String? = when (kind) {
        AiProviderKind.Gemini -> "https://aistudio.google.com/apikey"
        AiProviderKind.Claude -> "https://platform.claude.com/settings/keys"
        AiProviderKind.OpenAi -> "https://platform.openai.com/api-keys"
        AiProviderKind.OpenAiCompatible -> null
    }

    /** Whether a key is required (a local Ollama needs none). */
    fun needsKey(kind: AiProviderKind) = kind != AiProviderKind.OpenAiCompatible

    val compatiblePresets: Map<String, String> get() = OpenAiConfig.COMPATIBLE_PRESETS
}
