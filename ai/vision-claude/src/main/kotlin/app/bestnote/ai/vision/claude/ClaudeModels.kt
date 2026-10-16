package app.bestnote.ai.vision.claude

import app.bestnote.ai.vision.AiModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime

/** Parses `GET /v1/models` (Models API): id, display name, creation time and `capabilities.image_input`. Pure. */
object ClaudeModels {
    private val json = Json { ignoreUnknownKeys = true }

    data class Page(val models: List<AiModel>, val lastId: String?, val hasMore: Boolean)

    fun parse(body: String): Page {
        val root = json.parseToJsonElement(body).jsonObject
        val models = (root["data"] as? JsonArray).orEmpty().mapNotNull { el ->
            val m = el as? JsonObject ?: return@mapNotNull null
            val id = m["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val created = m["created_at"]?.jsonPrimitive?.contentOrNull?.let { runCatching { OffsetDateTime.parse(it).toEpochSecond() }.getOrNull() }
            val vision = ((m["capabilities"] as? JsonObject)?.get("image_input") as? JsonObject)?.get("supported")?.jsonPrimitive?.booleanOrNull
            AiModel(id, m["display_name"]?.jsonPrimitive?.contentOrNull ?: id, created, vision)
        }
        return Page(
            models,
            root["last_id"]?.jsonPrimitive?.contentOrNull,
            root["has_more"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }
}
