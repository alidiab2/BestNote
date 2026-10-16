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

    /** v3 → v4: the manifest gains `recordings` (MEDIA-04). Absent means none, so only the version changes. */
    private val V3_TO_V4 = Step(from = 3, manifest = { it.withFormat(4) }, page = { it.withFormat(4) })

    /** v4 → v5: the manifest gains `extras` (notebook-level records such as Smart Board's). Absent means none. */
    private val V4_TO_V5 = Step(from = 4, manifest = { it.withFormat(5) }, page = { it.withFormat(5) })

    /**
     * v5 → v6: a page may have an edit journal `pages/<id>.jrnl` next to its chunk (DOC-11). The JSON is unchanged;
     * the bump makes a v5 app refuse the notebook rather than save over strokes that only live in a journal.
     */
    private val V5_TO_V6 = Step(from = 5, manifest = { it.withFormat(6) }, page = { it.withFormat(6) })

    /**
     * v6 → v7: a stroke may carry a shape fill (INK-11), stored in the `.ink` chunk (chunk v4). The JSON is unchanged;
     * the bump makes a v6 app refuse the notebook rather than save over fills it cannot see.
     */
    private val V6_TO_V7 = Step(from = 6, manifest = { it.withFormat(7) }, page = { it.withFormat(7) })

    /**
     * v7 → v8: text elements may carry `underline`, `strike`, `runs`, `list`, `numerals` and `lineSpacing` (TEXT-02).
     * All are optional with defaults that reproduce v7 text, so only the version changes; the bump makes a v7 app
     * refuse the notebook rather than save over formatting it cannot see.
     */
    private val V7_TO_V8 = Step(from = 7, manifest = { it.withFormat(8) }, page = { it.withFormat(8) })

    val steps: List<Step> = listOf(V1_TO_V2, V2_TO_V3, V3_TO_V4, V4_TO_V5, V5_TO_V6, V6_TO_V7, V7_TO_V8)

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
