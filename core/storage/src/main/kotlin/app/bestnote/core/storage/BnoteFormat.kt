package app.bestnote.core.storage

import app.bestnote.core.model.CropRect
import app.bestnote.core.model.ImageElement
import app.bestnote.core.model.AudioRecording
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PageElement
import app.bestnote.core.model.PaperColor
import app.bestnote.core.model.PdfPageRef
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.ListStyle
import app.bestnote.core.model.Numerals
import app.bestnote.core.model.ParagraphFormat
import app.bestnote.core.model.ParagraphStyle
import app.bestnote.core.model.Paragraphs
import app.bestnote.core.model.RunStyle
import app.bestnote.core.model.TextAlignment
import app.bestnote.core.model.TextRun
import app.bestnote.core.model.TextRuns
import app.bestnote.core.model.TextElement
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@Serializable
internal data class ManifestJson(
    val format: Int,
    val generator: String = "BestNote",
    val notebook: NotebookJson,
    val pages: List<String>,
    val assets: List<String> = emptyList(),
    /** Voice recordings (MEDIA-04, format 3); each one's audio is the asset `assets/audio/<id>.m4a`. */
    val recordings: List<RecordingJson> = emptyList(),
    /** Names of the notebook's extras (format 5), each stored at `extras/<name>`. */
    val extras: List<String> = emptyList(),
)

/**
 * The plaintext stub manifest of a sealed (locked) notebook (LIB-11): what the locked cover needs plus the
 * wrapped keys. [format] is [BnoteFormat.SEALED_FORMAT], newer than any plain layout an older app knows, so an
 * old app refuses the notebook ("update the app") instead of misreading it; [pages] is always empty.
 */
@Serializable
internal data class SealedManifestJson(
    val format: Int,
    val generator: String = "BestNote",
    val notebook: NotebookJson,
    val pages: List<String> = emptyList(),
    val sealed: SealHeader,
)

@Serializable
internal data class RecordingJson(
    val id: String,
    val title: String? = null,
    val startedAtMillis: Long,
    val durationMillis: Long,
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
    /** PDF background (PDF-01): archive path of the PDF asset and the page in it. Absent for ordinary pages. */
    val pdfAsset: String? = null,
    val pdfPage: Int = 0,
    /** Text boxes and images (TEXT-01, MEDIA-01), bottom to top. Absent in files written before them. */
    val elements: List<ElementJson> = emptyList(),
)

/** One page element. [type] is `image` or `text`; fields of the other kind stay at their defaults. */
@Serializable
internal data class ElementJson(
    val type: String,
    val id: String,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val rot: Float = 0f,
    val locked: Boolean = false,
    // image
    val asset: String? = null,
    val srcW: Int = 0,
    val srcH: Int = 0,
    val crop: List<Float>? = null,
    // text
    val text: String? = null,
    val size: Float = TextElement.DEFAULT_SIZE_PT,
    val color: Int = TextElement.DEFAULT_COLOR,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val align: String = "Start",
    val font: String? = null,
    // text formatting (v8)
    val underline: Boolean = false,
    val strike: Boolean = false,
    val runs: List<RunJson> = emptyList(),
    val list: String = "None",
    val numerals: String = "Latin",
    val lineSpacing: Float = TextElement.DEFAULT_LINE_SPACING,
    /** Group id (CANVAS-14); absent for ungrouped elements and in older files. */
    val group: String? = null,
    /** Per-paragraph formats (v9, TEXT-04); absent = every paragraph plain. */
    val paras: List<ParaJson> = emptyList(),
)

/** One paragraph's format: [st] a [ParagraphStyle] name, [list] a [ListStyle] name or null (the box's), [ind] indent level. */
@Serializable
internal data class ParaJson(
    val st: String = "Body",
    val list: String? = null,
    val ind: Int = 0,
)

/** One styled part of a text element: characters [s] until [e]. Flags are only ever "on" additions. */
@Serializable
internal data class RunJson(
    val s: Int,
    val e: Int,
    val b: Boolean = false,
    val i: Boolean = false,
    val u: Boolean = false,
    val x: Boolean = false,
    val color: Int? = null,
    val font: String? = null,
    val size: Float? = null,
)

/**
 * Maps a [NotebookPackage] to the `.bnote` v1 entry layout (archive path → bytes) and back.
 * Shared by the unpacked directory store and the zip archive so both stay byte-identical.
 */
object BnoteFormat {
    /** Current layout. History: 1 = DOC-01 draft, 2 = page metadata always carries `paper`, 3 = pages carry `elements`, 4 = manifest lists audio `recordings`, 5 = manifest lists `extras`, 6 = pages may have an edit journal `pages/<id>.jrnl`, 7 = strokes may carry a shape fill, 8 = text elements may carry underline/strike, style runs, lists and line spacing, 9 = text elements may carry paragraph formats (see [FormatMigrations]). */
    const val VERSION = 9

    /**
     * Format of a sealed notebook's stub manifest (LIB-11). Sealed notebooks are recognised by their `sealed`
     * key, not by this number; it only has to be newer than what older apps read, so they refuse the notebook.
     * Plain notebooks keep [VERSION].
     */
    const val SEALED_FORMAT = 9
    const val MANIFEST = "manifest.json"
    const val PAGES_DIR = "pages/"
    const val ASSETS_DIR = "assets/"
    const val EXTRAS_DIR = "extras/"

    private const val SEALED_KEY = "sealed"
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
        require(pkg.recordings.all { it.assetPath in pkg.assets }) { "Every recording needs its audio asset" }
        val out = LinkedHashMap<String, ByteArray>()
        for (p in pkg.pages) {
            val meta = PageJson(VERSION, p.id, p.page.index, p.page.template.name, p.page.widthPt, p.page.heightPt, "${p.id}.ink", p.page.paperColor.name, p.page.pdf?.asset, p.page.pdf?.pageIndex ?: 0, p.elements.map(::elementJson))
            out[pageMetaPath(p.id)] = json.encodeToString(PageJson.serializer(), meta).encodeToByteArray()
            out[pageInkPath(p.id)] = p.strokes
        }
        pkg.assets.forEach { (k, v) -> out[k] = v }
        pkg.extras.forEach { (k, v) -> out[EXTRAS_DIR + k] = v }
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
            val meta = decode("page $pageId", metaBytes) { parsePage(it) }
            if (!INK_NAME.matches(meta.ink)) throw CorruptChunkException("Invalid ink name '${meta.ink}'")
            val ink = entry(PAGES_DIR + meta.ink) ?: throw CorruptChunkException("Missing ink for page $pageId")
            val page = construct("page $pageId") { Page(nid, meta.index, template(meta.template), meta.widthPt, meta.heightPt, paperColor(meta.paper), pdfRef(meta)) }
            construct("page $pageId") { PageContent(page, ink, pageId, meta.elements.mapNotNull(::elementOrNull)) }
        }
        val assets = manifest.assets.associateWith { path ->
            construct("asset") { requireValidAssetPath(path) }
            entry(path) ?: throw CorruptChunkException("Missing asset $path")
        }
        // A recording whose audio is gone is dropped rather than failing the whole notebook.
        val recordings = manifest.recordings.mapNotNull { r ->
            construct("recording") { AudioRecording(r.id, r.title, r.startedAtMillis, r.durationMillis) }.takeIf { it.assetPath in assets }
        }
        // An extra that is missing or badly named is skipped: extras are never worth failing the notebook for.
        val extras = manifest.extras.filter { NotebookPackage.EXTRA_NAME.matches(it) }.mapNotNull { name -> entry(EXTRAS_DIR + name)?.let { name to it } }.toMap()
        return construct("notebook") { NotebookPackage(manifest.notebook.toModel(), pages, assets, recordings, extras) }
    }

    /** Notebook metadata from a manifest (plain, or a sealed notebook's stub), for listing without reading pages. */
    fun readNotebook(manifestBytes: ByteArray): Notebook =
        if (isSealedManifest(manifestBytes)) readSealedStub(manifestBytes).first else parseManifest(manifestBytes).notebook.toModel()

    /** True when [manifestBytes] is the stub of a sealed notebook (LIB-11). Damaged JSON is not a stub. */
    fun isSealedManifest(manifestBytes: ByteArray): Boolean = try {
        SEALED_KEY in json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject
    } catch (_: SerializationException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    /** The cover facts and header of a sealed notebook's stub manifest. */
    fun readSealedStub(manifestBytes: ByteArray): Pair<Notebook, SealHeader> {
        val stub = decode(MANIFEST, manifestBytes) { json.decodeFromString(SealedManifestJson.serializer(), it) }
        return stub.notebook.toModel() to stub.sealed
    }

    /** The stub manifest written for a sealed notebook: cover facts only, no pages. */
    fun encodeSealedStub(notebook: Notebook, header: SealHeader): ByteArray {
        val stub = SealedManifestJson(
            format = SEALED_FORMAT,
            notebook = NotebookJson(notebook.id.value, notebook.title, notebook.createdAtMillis, notebook.updatedAtMillis, notebook.template.name, notebook.pageCount),
            sealed = header,
        )
        return json.encodeToString(SealedManifestJson.serializer(), stub).encodeToByteArray()
    }

    internal fun parseManifest(bytes: ByteArray): ManifestJson {
        val text = bytes.decodeToString()
        // Check the version before the schema, so a future manifest layout is refused rather than "corrupt".
        val tree = decode(MANIFEST, text) { json.parseToJsonElement(it).jsonObject }
        // A sealed stub has no pages: reading it as a plain notebook would show it empty.
        if (SEALED_KEY in tree) throw NotebookLockedException(null, "Sealed notebook: unlock it first")
        val format = decode(MANIFEST, text) { FormatMigrations.formatOf(tree) }
        if (format > VERSION) throw UnsupportedFormatVersionException(format, VERSION, "Notebook format")
        if (format < 1) throw CorruptChunkException("Bad format $format")
        return decode(MANIFEST, text) { json.decodeFromJsonElement(ManifestJson.serializer(), FormatMigrations.upgradeManifest(tree)) }
    }

    private fun parsePage(text: String): PageJson {
        val tree = json.parseToJsonElement(text).jsonObject
        val format = FormatMigrations.formatOf(tree)
        if (format > VERSION) throw UnsupportedFormatVersionException(format, VERSION, "Page")
        if (format < 1) throw CorruptChunkException("Bad page format $format")
        return json.decodeFromJsonElement(PageJson.serializer(), FormatMigrations.upgradePage(tree))
    }

    private fun encodeManifest(pkg: NotebookPackage): String {
        val n = pkg.notebook
        val m = ManifestJson(
            format = VERSION,
            notebook = NotebookJson(n.id.value, n.title, n.createdAtMillis, n.updatedAtMillis, n.template.name, n.pageCount),
            pages = pkg.pages.map { it.id },
            assets = pkg.assets.keys.toList(),
            recordings = pkg.recordings.map { RecordingJson(it.id, it.title, it.startedAtMillis, it.durationMillis) },
            extras = pkg.extras.keys.sorted(),
        )
        return json.encodeToString(ManifestJson.serializer(), m)
    }

    private fun NotebookJson.toModel() = construct("notebook") {
        Notebook(NotebookId(id), title, createdAtMillis, updatedAtMillis, template(template), pageCount)
    }

    private fun template(name: String) =
        PaperTemplate.entries.firstOrNull { it.name == name } ?: throw CorruptChunkException("Unknown template '$name'")

    /** A PDF reference needs its asset to be a valid, listed asset path; otherwise the page is plain paper. */
    private fun pdfRef(meta: PageJson): PdfPageRef? {
        val asset = meta.pdfAsset ?: return null
        requireValidAssetPath(asset)
        return PdfPageRef(asset, meta.pdfPage)
    }

    private fun elementJson(e: PageElement): ElementJson = when (e) {
        is ImageElement -> ElementJson(
            "image", e.id, e.cx, e.cy, e.width, e.height, e.rotation, e.locked,
            asset = e.asset, srcW = e.srcWidth, srcH = e.srcHeight, crop = listOf(e.crop.left, e.crop.top, e.crop.right, e.crop.bottom), group = e.group,
        )
        is TextElement -> ElementJson(
            "text", e.id, e.cx, e.cy, e.width, e.height, e.rotation, e.locked,
            text = e.text, size = e.sizePt, color = e.colorArgb, bold = e.bold, italic = e.italic, align = e.align.name, font = e.font,
            underline = e.underline, strike = e.strike, list = e.list.name, numerals = e.numerals.name, lineSpacing = e.lineSpacing,
            runs = e.runs.map { RunJson(it.start, it.end, it.style.bold, it.style.italic, it.style.underline, it.style.strike, it.style.colorArgb, it.style.font, it.style.sizePt) },
            group = e.group,
            paras = Paragraphs.normalized(e.paragraphs, e.text).map { ParaJson(it.style.name, it.list?.name, it.indent) },
        )
    }

    /** An element, or null for one this version cannot show (unknown type from a newer app, or unusable numbers). */
    private fun elementOrNull(j: ElementJson): PageElement? = try {
        when (j.type) {
            "image" -> {
                val asset = j.asset ?: return null
                requireValidAssetPath(asset)
                val c = j.crop?.takeIf { it.size == 4 }?.let { CropRect.sanitized(it[0], it[1], it[2], it[3]) } ?: CropRect()
                ImageElement(j.id, j.cx, j.cy, j.w, j.h, asset, j.srcW, j.srcH, c, j.rot, j.locked, j.group)
            }
            "text" -> TextElement(
                j.id, j.cx, j.cy, j.w, j.h, j.text.orEmpty(), j.size, j.color, j.bold, j.italic,
                TextAlignment.entries.firstOrNull { it.name == j.align } ?: TextAlignment.Start, j.rot, j.locked, j.font,
                j.underline, j.strike,
                TextRuns.sanitized(
                    j.runs.filter { it.s >= 0 && it.e > it.s }.map { TextRun(it.s, it.e, RunStyle(it.b, it.i, it.u, it.x, it.color, it.font, it.size?.takeIf { z -> z > 0f })) },
                    j.text.orEmpty().length,
                ),
                ListStyle.entries.firstOrNull { it.name == j.list } ?: ListStyle.None,
                Numerals.entries.firstOrNull { it.name == j.numerals } ?: Numerals.Latin,
                j.lineSpacing.coerceIn(TextElement.MIN_LINE_SPACING, TextElement.MAX_LINE_SPACING),
                j.group,
                Paragraphs.normalized(
                    j.paras.map { p ->
                        ParagraphFormat(
                            ParagraphStyle.entries.firstOrNull { it.name == p.st } ?: ParagraphStyle.Body,
                            p.list?.let { name -> ListStyle.entries.firstOrNull { it.name == name } },
                            p.ind.coerceIn(0, ParagraphFormat.MAX_INDENT),
                        )
                    },
                    j.text.orEmpty(),
                ),
            )
            else -> null
        }
    } catch (_: IllegalArgumentException) {
        null
    }

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
