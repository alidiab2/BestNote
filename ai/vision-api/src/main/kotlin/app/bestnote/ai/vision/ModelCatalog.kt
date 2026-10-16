package app.bestnote.ai.vision

import kotlinx.serialization.Serializable

/** A model a provider's list endpoint returned, reduced to what the picker and the default need. */
@Serializable
data class AiModel(
    val id: String,
    val displayName: String = id,
    /** Seconds since epoch when the provider says (OpenAI `created`, Anthropic `created_at`); else null. */
    val created: Long? = null,
    /** False only when the provider says the model cannot take images. Null = not stated. */
    val vision: Boolean? = null,
) {
    val tier: ModelCatalog.Tier get() = ModelCatalog.tierOf(id, displayName)
}

/**
 * Picks models by family and version instead of hard-wiring ids that age out. Ids are parsed
 * into a numeric version ("gemini-3.8-flash" → 3.8, "claude-opus-5-5" → 5.5, "gpt-6.1-sol" → 6.1)
 * and a tier from well-known family words. The default for board reconstruction is the newest
 * [Tier.Quality] vision model; [Preference.Fast] picks the newest [Tier.Fast] one.
 */
object ModelCatalog {
    enum class Tier {
        /** Highest-cost research tier (Claude Fable / Mythos): never chosen automatically. */
        Frontier,

        /** Pro-class: best board reconstruction (Gemini Pro / flagship, Claude Opus, GPT flagship). */
        Quality,

        /** Mid tier (Claude Sonnet, OpenAI's balanced model). */
        Balanced,

        /** Fast and cheap (Flash, Haiku, mini). */
        Fast,

        /** Even lighter (Flash-Lite, nano). Only picked when nothing else exists. */
        Lite,
    }

    enum class Preference { Quality, Fast }

    /** Stored instead of a model id to mean "always the newest fast model". Blank means newest quality. */
    const val AUTO_FAST = "@fast"

    fun isAuto(model: String?) = model.isNullOrBlank() || model == AUTO_FAST

    fun preferenceOf(model: String?) = if (model == AUTO_FAST) Preference.Fast else Preference.Quality

    /** Newest known ids, used only when the list call fails or has not run yet (checked 2026-10). */
    fun fallback(kind: AiProviderKind, preference: Preference): String = when (kind) {
        AiProviderKind.Gemini -> if (preference == Preference.Fast) "gemini-3.8-flash" else "gemini-4-argon"
        AiProviderKind.Claude -> if (preference == Preference.Fast) "claude-haiku-5-5" else "claude-opus-5-5"
        AiProviderKind.OpenAi -> if (preference == Preference.Fast) "gpt-6-luna" else "gpt-6-astra"
        // Depends entirely on the server; the user names the model.
        AiProviderKind.OpenAiCompatible -> ""
    }

    /** The model to call for [stored] (an id, blank, or [AUTO_FAST]) given the cached [models]. */
    fun resolve(kind: AiProviderKind, stored: String?, models: List<AiModel>): String {
        if (!isAuto(stored)) return stored!!.trim()
        val preference = preferenceOf(stored)
        return pick(models, preference)?.id ?: fallback(kind, preference)
    }

    /** Newest usable model for [preference], or null when [models] has nothing suitable. */
    fun pick(models: List<AiModel>, preference: Preference): AiModel? {
        val usable = models.filter { it.vision != false && it.tier != Tier.Frontier }
        val order = if (preference == Preference.Fast) {
            listOf(Tier.Fast, Tier.Balanced, Tier.Lite, Tier.Quality)
        } else {
            listOf(Tier.Quality, Tier.Balanced, Tier.Fast, Tier.Lite)
        }
        for (tier in order) {
            usable.filter { it.tier == tier }.maxWithOrNull(NEWEST)?.let { return it }
        }
        return null
    }

    /** Newest first: version, then stable before preview, then creation time, then shorter id (an alias over a snapshot). */
    val NEWEST: Comparator<AiModel> = compareBy<AiModel>({ VersionKey(version(it.id)) })
        .thenBy { !isPreview(it.id) }
        .thenBy { it.created ?: 0L }
        .thenByDescending { it.id.length }

    /** Picker order: newest first within Quality, Balanced, Fast, Lite, Frontier. */
    fun sorted(models: List<AiModel>): List<AiModel> =
        models.sortedWith(compareBy<AiModel> { it.tier.pickerRank() }.then(NEWEST.reversed()))

    private fun Tier.pickerRank() = when (this) {
        Tier.Quality -> 0
        Tier.Balanced -> 1
        Tier.Fast -> 2
        Tier.Lite -> 3
        Tier.Frontier -> 4
    }

    fun tierOf(id: String, displayName: String = id): Tier {
        val words = (id + " " + displayName).lowercase()
        fun has(vararg w: String) = w.any { Regex("(^|[^a-z])$it([^a-z]|$)").containsMatchIn(words) }
        return when {
            has("fable", "mythos") -> Tier.Frontier
            has("lite", "nano") || words.contains("flash-lite") -> Tier.Lite
            has("flash", "haiku", "mini", "luna", "small", "fast", "instant", "turbo") -> Tier.Fast
            has("sonnet", "sol", "medium") -> Tier.Balanced
            else -> Tier.Quality // pro, opus, argon, astra, ultra, large, a bare "gpt-6"…
        }
    }

    /** Numbers in the id that form its version: "claude-opus-5-5" → [5,5]; dates and sizes are ignored. */
    fun version(id: String): List<Int> {
        val core = id.lowercase()
            .substringAfterLast('/') // "models/…", "google/gemini-…"
            .replace(Regex("""-?(20\d{2})-?(\d{2})-?(\d{2})$"""), "") // snapshot date
            .replace(Regex("""-\d{3,}$"""), "") // "-001"
            .replace(Regex("""\d+(\.\d+)?[bkm]\b"""), "") // parameter counts like 70b
        return Regex("""\d+""").findAll(core).map { it.value.toInt() }.take(3).toList()
    }

    fun isPreview(id: String) = Regex("preview|exp|experimental|beta|-latest").containsMatchIn(id.lowercase())

    private class VersionKey(val parts: List<Int>) : Comparable<VersionKey> {
        override fun compareTo(other: VersionKey): Int {
            for (i in 0 until maxOf(parts.size, other.parts.size)) {
                val c = (parts.getOrNull(i) ?: 0).compareTo(other.parts.getOrNull(i) ?: 0)
                if (c != 0) return c
            }
            return 0
        }
    }
}
