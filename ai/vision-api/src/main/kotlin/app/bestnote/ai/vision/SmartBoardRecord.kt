package app.bestnote.ai.vision

import app.bestnote.engine.layout.GlossaryTerm
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NotebookGlossary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One Smart Board insert: the pages it wrote ([pageIds], storage ids, stable across reorders), the
 * template and the element document it was written from ([notes], the compact format), and what of
 * those pages is Smart Board's own writing ([elementIds] text boxes, [strokeKeys] pen-stroke
 * fingerprints) so a follow-up photo can re-lay it out without touching the user's own ink.
 */
data class SmartBoardGroup(
    val templateId: String,
    val pageIds: List<String>,
    val notes: NoteDocument,
    val elementIds: Set<String> = emptySet(),
    val strokeKeys: Set<String> = emptySet(),
)

/**
 * What Smart Board stores with a notebook (`smartboard.json` beside the pages, see
 * `NotebookPackage.extras`): the notebook-level [glossary] that every page's terms accumulate into,
 * and every insert ([groups]) for follow-up photos.
 */
data class SmartBoardRecord(val glossary: NotebookGlossary = NotebookGlossary(), val groups: List<SmartBoardGroup> = emptyList()) {
    /** The insert page [pageId] belongs to, or null for a page Smart Board did not write. */
    fun groupFor(pageId: String): SmartBoardGroup? = groups.lastOrNull { pageId in it.pageIds }

    /** After inserting [group]: its terms join the notebook glossary (deduplicated). */
    fun withInsert(group: SmartBoardGroup): SmartBoardRecord =
        SmartBoardRecord(glossary.merge(group.notes.terms).first, groups + group)

    /** After a follow-up re-wrote [old] as [new]. */
    fun replace(old: SmartBoardGroup, new: SmartBoardGroup): SmartBoardRecord =
        SmartBoardRecord(glossary.merge(new.notes.terms).first, groups.map { if (it === old || it == old) new else it })

    /** Forgets inserts whose pages are all gone (deleted pages). */
    fun prune(livePageIds: Set<String>): SmartBoardRecord = copy(groups = groups.mapNotNull { g ->
        val live = g.pageIds.filter { it in livePageIds }
        if (live.isEmpty()) null else g.copy(pageIds = live)
    })

    fun encode(): ByteArray = buildJsonObject {
        put("format", FORMAT)
        put("glossary", JsonArray(glossary.terms.map { JsonArray(listOf(JsonPrimitive(it.term), JsonPrimitive(it.translation))) }))
        put(
            "groups",
            JsonArray(
                groups.map { g ->
                    buildJsonObject {
                        put("template", g.templateId)
                        put("pages", JsonArray(g.pageIds.map(::JsonPrimitive)))
                        put("elements", JsonArray(g.elementIds.map(::JsonPrimitive)))
                        put("strokes", JsonArray(g.strokeKeys.map(::JsonPrimitive)))
                        put("notes", NoteWire.encodeTree(g.notes))
                    }
                },
            ),
        )
    }.toString().encodeToByteArray()

    companion object {
        /** The notebook extra this record is stored in. */
        const val EXTRA = "smartboard.json"
        const val FORMAT = 1

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Reads a stored record; anything unreadable (or a newer format) is an empty record, never a crash. */
        fun decode(bytes: ByteArray?): SmartBoardRecord {
            if (bytes == null || bytes.isEmpty()) return SmartBoardRecord()
            return runCatching {
                val o = json.parseToJsonElement(bytes.decodeToString()) as JsonObject
                if ((o["format"]?.jsonPrimitive?.intOrNull ?: 1) > FORMAT) return SmartBoardRecord()
                val glossary = (o["glossary"] as? JsonArray).orEmpty().mapNotNull { t ->
                    val a = t as? JsonArray ?: return@mapNotNull null
                    GlossaryTerm(a.getOrNull(0)?.jsonPrimitive?.content ?: return@mapNotNull null, a.getOrNull(1)?.jsonPrimitive?.content ?: return@mapNotNull null)
                }
                val groups = (o["groups"] as? JsonArray).orEmpty().mapNotNull { el ->
                    val g = el as? JsonObject ?: return@mapNotNull null
                    fun list(k: String) = (g[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                    SmartBoardGroup(
                        templateId = g["template"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        pageIds = list("pages"),
                        notes = (g["notes"] as? JsonObject)?.let { NoteWire.decodeStored(it.toString()) } ?: NoteDocument(emptyList()),
                        elementIds = list("elements").toSet(),
                        strokeKeys = list("strokes").toSet(),
                    )
                }
                SmartBoardRecord(NotebookGlossary(glossary), groups)
            }.getOrElse { SmartBoardRecord() }
        }

        /**
         * Fingerprint of a pen stroke Smart Board wrote (first point, point count, colour), so the stroke is
         * recognised again in the notebook after a reload. [x], [y] in page points.
         */
        fun strokeKey(x: Float, y: Float, points: Int, color: Int): String = "%.1f,%.1f,%d,%08x".format(java.util.Locale.ROOT, x, y, points, color)
    }
}
