package app.bestnote.core.storage

import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PaperColor
import app.bestnote.core.model.PaperTemplate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
internal data class ManifestJson(
    val format: Int,
    val generator: String = "BestNote",
    val notebook: NotebookJson,
    val pages: List<String>,
    val assets: List<String> = emptyList(),
)

@Serializable
internal data class NotebookJson(
    val id: String,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val template: String,
    val pageCount: Int,
)

@Serializable
internal data class PageJson(
    val format: Int,
    val id: String,
    val index: Int,
    val template: String,
    val widthPt: Float,
    val heightPt: Float,
    val ink: String,
    /** [PaperColor] name. Added after v1 shipped as a draft: optional, unknown names read as White. */
    val paper: String = "White",
)

/**
 * Maps a [NotebookPackage] to the `.bnote` v1 entry layout (archive path → bytes) and back.
 * Shared by the unpacked directory store and the zip archive so both stay byte-identical.
 */
object BnoteFormat {
    const val VERSION = 1
    const val MANIFEST = "manifest.json"
    const val PAGES_DIR = "pages/"
    const val ASSETS_DIR = "assets/"

    private val INK_NAME = Regex("[A-Za-z0-9_-]{1,64}\\.ink")

    internal val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun pageMetaPath(pageId: String) = "$PAGES_DIR$pageId.json"
    fun pageInkPath(pageId: String) = "$PAGES_DIR$pageId.ink"

    /** Asset keys must be relative paths under `assets/` without `..` segments. */
    fun requireValidAssetPath(path: String) {
        require(
            path.startsWith(ASSETS_DIR) && path.length > ASSETS_DIR.length && !path.contains('\\') &&
                path.split('/').none { it == ".." || it == "." || it.isEmpty() },
        ) { "Invalid asset path '$path'" }
    }

    /** All entries of [pkg]; [MANIFEST] is the last key so writers can commit it last. */
    fun toEntries(pkg: NotebookPackage): LinkedHashMap<String, ByteArray> {
        pkg.assets.keys.forEach(::requireValidAssetPath)
        val out = LinkedHashMap<String, ByteArray>()
        for (p in pkg.pages) {
            val meta = PageJson(VERSION, p.id, p.page.index, p.page.template.name, p.page.widthPt, p.page.heightPt, "${p.id}.ink", p.page.paperColor.name)
            out[pageMetaPath(p.id)] = json.encodeToString(PageJson.serializer(), meta).encodeToByteArray()
            out[pageInkPath(p.id)] = p.strokes
        }
        pkg.assets.forEach { (k, v) -> out[k] = v }
        out[MANIFEST] = encodeManifest(pkg).encodeToByteArray()
        return out
    }

    /** Rebuilds a package, loading each entry lazily through [entry] (null = missing). */
    fun fromEntries(entry: (String) -> ByteArray?): NotebookPackage {
        val manifest = parseManifest(entry(MANIFEST) ?: throw CorruptChunkException("Missing $MANIFEST"))
        val nid = NotebookId(manifest.notebook.id)
        val pages = manifest.pages.map { pageId ->
            if (!PageContent.PAGE_ID_PATTERN.matches(pageId)) throw CorruptChunkException("Invalid page id '$pageId'")
            val metaBytes = entry(pageMetaPath(pageId)) ?: throw CorruptChunkException("Missing page $pageId")
            val meta = decode("page $pageId", metaBytes) { json.decodeFromString(PageJson.serializer(), it) }
            if (meta.format > VERSION) throw UnsupportedFormatVersionException(meta.format, VERSION, "Page")
            if (!INK_NAME.matches(meta.ink)) throw CorruptChunkException("Invalid ink name '${meta.ink}'")
            val ink = entry(PAGES_DIR + meta.ink) ?: throw CorruptChunkException("Missing ink for page $pageId")
            val page = construct("page $pageId") { Page(nid, meta.index, template(meta.template), meta.widthPt, meta.heightPt, paperColor(meta.paper)) }
            construct("page $pageId") { PageContent(page, ink, pageId) }
        }
        val assets = manifest.assets.associateWith { path ->
            construct("asset") { requireValidAssetPath(path) }
            entry(path) ?: throw CorruptChunkException("Missing asset $path")
        }
        return construct("notebook") { NotebookPackage(manifest.notebook.toModel(), pages, assets) }
    }

    /** Notebook metadata from a manifest, for listing without reading pages. */
    fun readNotebook(manifestBytes: ByteArray): Notebook = parseManifest(manifestBytes).notebook.toModel()

    internal fun parseManifest(bytes: ByteArray): ManifestJson {
        val text = bytes.decodeToString()
        // Check the version before the schema, so a future manifest layout is refused rather than "corrupt".
        val format = decode(MANIFEST, text) { json.parseToJsonElement(it).jsonObject["format"]!!.jsonPrimitive.int }
        if (format > VERSION) throw UnsupportedFormatVersionException(format, VERSION, "Notebook format")
        if (format < 1) throw CorruptChunkException("Bad format $format")
        return decode(MANIFEST, text) { json.decodeFromString(ManifestJson.serializer(), it) }
    }

    private fun encodeManifest(pkg: NotebookPackage): String {
        val n = pkg.notebook
        val m = ManifestJson(
            format = VERSION,
            notebook = NotebookJson(n.id.value, n.title, n.createdAtMillis, n.updatedAtMillis, n.template.name, n.pageCount),
            pages = pkg.pages.map { it.id },
            assets = pkg.assets.keys.toList(),
        )
        return json.encodeToString(ManifestJson.serializer(), m)
    }

    private fun NotebookJson.toModel() = construct("notebook") {
        Notebook(NotebookId(id), title, createdAtMillis, updatedAtMillis, template(template), pageCount)
    }

    private fun template(name: String) =
        PaperTemplate.entries.firstOrNull { it.name == name } ?: throw CorruptChunkException("Unknown template '$name'")

    /** Paper color is cosmetic: an unknown name (from a newer app) falls back to white instead of failing. */
    private fun paperColor(name: String) = PaperColor.entries.firstOrNull { it.name == name } ?: PaperColor.White

    private fun <T> decode(what: String, bytes: ByteArray, block: (String) -> T) = decode(what, bytes.decodeToString(), block)

    private fun <T> decode(what: String, text: String, block: (String) -> T): T = try {
        block(text)
    } catch (e: SerializationException) {
        throw CorruptChunkException("Malformed $what", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptChunkException("Malformed $what", e)
    } catch (e: NullPointerException) {
        throw CorruptChunkException("Malformed $what", e)
    }

    private fun <T> construct(what: String, block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw CorruptChunkException("Invalid $what: ${e.message}", e)
    }
}
