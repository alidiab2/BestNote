package app.bestnote.core.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Merges a backed-up `library/metadata.json` (folders, covers, favorites) into the device's own at
 * restore time, working on raw JSON so `:core:storage` needs no library types.
 *
 * Folders: the device's, plus any the backup has that the device lacks. Per-notebook entries: the
 * backup's for notebooks that were restored or are unknown here, the device's for the rest.
 */
object LibraryMetadataMerge {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** @return the merged document; [backup] verbatim when there is no usable [local]. */
    fun merge(local: String?, backup: String, restoredIds: Set<String>): String {
        val theirs = parse(backup) ?: return local ?: backup
        val mine = local?.let(::parse) ?: return backup
        val folders = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        for (f in mine["folders"]?.jsonArray.orEmpty()) f.jsonObject["id"]?.jsonPrimitive?.content?.let { folders[it] = f }
        for (f in theirs["folders"]?.jsonArray.orEmpty()) f.jsonObject["id"]?.jsonPrimitive?.content?.let { folders.putIfAbsent(it, f) }
        val myNotebooks = mine["notebooks"]?.jsonObject.orEmpty()
        val notebooks = LinkedHashMap(myNotebooks)
        for ((id, meta) in theirs["notebooks"]?.jsonObject.orEmpty()) {
            if (id in restoredIds || id !in myNotebooks) notebooks[id] = meta
        }
        val out = JsonObject(
            mine + mapOf(
                "version" to (mine["version"] ?: JsonPrimitive(1)),
                "folders" to JsonArray(folders.values.toList()),
                "notebooks" to JsonObject(notebooks),
            ),
        )
        return json.encodeToString(JsonObject.serializer(), out)
    }

    private fun parse(text: String): JsonObject? = try {
        json.parseToJsonElement(text).jsonObject
    } catch (_: kotlinx.serialization.SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
