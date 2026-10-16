package app.bestnote.core.storage

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Upgrades older `.bnote` JSON (manifest and page metadata) to [BnoteFormat.VERSION] before it is
 * decoded, so readers only ever deal with the current schema (DOC-05). Each [Step] lifts one version
 * (`from` → `from + 1`) and must stamp the new `format`. Steps are pure functions on the JSON tree,
 * so a fixture of the old layout in the repo is enough to test them.
 *
 * To change the format: bump [BnoteFormat.VERSION], append a [Step] here and add a fixture under
 * `src/test/resources/fixtures/`.
 */
internal object FormatMigrations {
    class Step(val from: Int, val manifest: (JsonObject) -> JsonObject, val page: (JsonObject) -> JsonObject)

    /**
     * v1 → v2: every page's metadata states its paper colour. v1 drafts omitted it (white paper),
     * and readers had to know to default it.
     */
    private val V1_TO_V2 = Step(
        from = 1,
        manifest = { it.withFormat(2) },
        page = { page ->
            val withPaper = if ("paper" in page) page else JsonObject(page + ("paper" to JsonPrimitive("White")))
            withPaper.withFormat(2)
        },
    )

    /**
     * v2 → v3: pages may carry `elements` (text boxes and images, TEXT-01/MEDIA-01). v2 pages have none; the
     * list is stated so v3 readers never guess. An older app refuses v3 files instead of saving over elements it cannot see.
     */
    private val V2_TO_V3 = Step(
        from = 2,
        manifest = { it.withFormat(3) },
        page = { page ->
            val withElements = if ("elements" in page) page else JsonObject(page + ("elements" to kotlinx.serialization.json.JsonArray(emptyList())))
            withElements.withFormat(3)
        },
    )

    val steps: List<Step> = listOf(V1_TO_V2, V2_TO_V3)

    fun formatOf(json: JsonObject): Int = json["format"]?.jsonPrimitive?.int ?: throw CorruptChunkException("Missing format")

    fun upgradeManifest(json: JsonObject): JsonObject = upgrade(json) { step, obj -> step.manifest(obj) }

    fun upgradePage(json: JsonObject): JsonObject = upgrade(json) { step, obj -> step.page(obj) }

    private fun upgrade(json: JsonObject, apply: (Step, JsonObject) -> JsonObject): JsonObject {
        var current = json
        var version = formatOf(json)
        while (version < BnoteFormat.VERSION) {
            val step = steps.firstOrNull { it.from == version } ?: throw CorruptChunkException("No migration from format $version")
            current = apply(step, current)
            version = formatOf(current)
        }
        return current
    }

    private fun JsonObject.withFormat(v: Int) = JsonObject(this + ("format" to JsonPrimitive(v)))
}
