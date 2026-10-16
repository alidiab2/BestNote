package app.bestnote.ai.vision.gemini

import app.bestnote.ai.vision.AiModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parses `GET v1beta/models`. Keeps text+image models that support `generateContent`. Pure. */
object GeminiModels {
    private val json = Json { ignoreUnknownKeys = true }

    /** Not for reading a board: speech, image/video generation, embeddings, live audio, Gemma (no system instruction). */
    private val EXCLUDE = Regex("""tts|image|imagen|veo|embedding|aqa|live|audio|transcribe|translate|omni|robotics|gemma|learnlm|computer-use""")

    data class Page(val models: List<AiModel>, val nextPageToken: String?)

    fun parse(body: String): Page {
        val root = json.parseToJsonElement(body).jsonObject
        val models = (root["models"] as? JsonArray).orEmpty().mapNotNull { el ->
            val m = el as? JsonObject ?: return@mapNotNull null
            val id = m["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/") ?: return@mapNotNull null
            val methods = (m["supportedGenerationMethods"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if ("generateContent" !in methods || EXCLUDE.containsMatchIn(id.lowercase())) return@mapNotNull null
            AiModel(id = id, displayName = m["displayName"]?.jsonPrimitive?.contentOrNull ?: id)
        }
        // "gemini-2.5-pro-002" next to "gemini-2.5-pro": keep the alias only.
        val ids = models.map { it.id }.toSet()
        val deduped = models.filterNot { m -> Regex("""-\d{3}$""").find(m.id)?.let { m.id.removeRange(it.range) in ids } == true }
        return Page(deduped, root["nextPageToken"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() })
    }
}
