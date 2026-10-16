package app.bestnote.ai.vision.openai

import app.bestnote.ai.vision.AiModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parses `GET /v1/models` from OpenAI and compatible servers. OpenAI lists every model it has, so
 * only chat models are kept and dated snapshots are folded into their alias; OpenRouter's
 * `architecture.input_modalities` (when present) says whether a model reads images. Pure.
 */
object OpenAiModels {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val NOT_CHAT = Regex("""embed|tts|whisper|audio|realtime|transcribe|moderation|dall-e|image|search|davinci|babbage|instruct|codex|computer-use|rerank|guard""")
    private val OPENAI_CHAT = Regex("""^(gpt-|o\d|chatgpt-)""")
    private val SNAPSHOT = Regex("""-\d{4}-\d{2}-\d{2}$""")

    fun parse(body: String, official: Boolean): List<AiModel> {
        val root = json.parseToJsonElement(body)
        val data = ((root as? JsonObject)?.get("data") ?: (root as? JsonObject)?.get("models") ?: root) as? JsonArray ?: return emptyList()
        val models = data.mapNotNull { el ->
            val m = el as? JsonObject ?: return@mapNotNull null
            val id = (m["id"] ?: m["name"])?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val low = id.lowercase()
            if (NOT_CHAT.containsMatchIn(low)) return@mapNotNull null
            if (official && !OPENAI_CHAT.containsMatchIn(low)) return@mapNotNull null
            val modalities = ((m["architecture"] as? JsonObject)?.get("input_modalities") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            AiModel(
                id = id,
                displayName = (m["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { m["id"] != null } ?: id,
                created = (m["created"] as? JsonPrimitive)?.longOrNull,
                vision = modalities?.let { "image" in it },
            )
        }
        val ids = models.map { it.id }.toSet()
        return models.filterNot { SNAPSHOT.find(it.id)?.let { s -> it.id.removeRange(s.range) in ids } == true }
    }
}
