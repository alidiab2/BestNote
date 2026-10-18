package app.bestnote.feature.library

import app.bestnote.core.common.SearchText
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.PageSearchHit
import app.bestnote.engine.recognition.ModelsState
import java.text.Collator
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Where the sidebar points. */
sealed interface LibraryDestination {
    data object Library : LibraryDestination
    data object Recent : LibraryDestination
    data object Favorites : LibraryDestination
    data object Quicknotes : LibraryDestination
    data object SmartBoard : LibraryDestination
    data object Trash : LibraryDestination
    data class InFolder(val folderId: String) : LibraryDestination
}

enum class LibraryLayout { Shelf, Grid, List }

/** Chips next to the "Notebooks" header. */
enum class NotebookFilter { All, ThisWeek, Favorites, Pdfs }

enum class SortOrder { Modified, Created, Name }

enum class Greeting { Morning, Afternoon, Evening }

/** Which empty state to draw when [LibraryUiState.items] is empty. */
enum class EmptyKind { NoNotebooks, EmptyFolder, NoFavorites, NoQuicknotes, NoRecent, NoPdfs, NoMatches, NoSearchResults }

/** One notebook as the library draws it. */
data class NotebookItem(
    val id: NotebookId,
    val title: String,
    val pageCount: Int,
    val updatedAtMillis: Long,
    val coverIndex: Int,
    val folder: Folder?,
    val favorite: Boolean,
    val quicknote: Boolean,
    /** Zero-based page the user was last on. */
    val lastPageIndex: Int,
    /** Cached page thumbnail (DOC-10), or null to draw the placeholder page. */
    val thumbnailPath: String? = null,
    val createdAtMillis: Long = updatedAtMillis,
    val coverStyle: CoverStyle = CoverStyle.Plain,
    val kind: NotebookKind = NotebookKind.Notebook,
    /** Top-level folder down to [folder], for breadcrumbs in search results. */
    val folderPath: List<Folder> = listOfNotNull(folder),
    /** LIB-11: encrypted on disk (it never has a page thumbnail). */
    val sealed: Boolean = false,
    /** LIB-11: sealed and not unlocked this session: drawn with a lock, left out of search, Recent and Continue writing. */
    val locked: Boolean = false,
    /** LIB-10: stored custom cover image, or null (always null while [locked]: a locked notebook keeps its cover hidden). */
    val coverImagePath: String? = null,
    /** LIB-10: colour tags (indexes into `CoverPalette.All`). */
    val tags: List<Int> = emptyList(),
) {
    /** Share of the notebook read/written so far, in 0..1. */
    val progress: Float get() = (lastPageIndex + 1f) / pageCount
}

/** A page found by the content search, with the notebook it belongs to. */
data class PageResult(val hit: PageSearchHit, val notebookTitle: String)

/** A folder with its notebook count and nesting depth (0 = top level), in sidebar order. */
data class FolderItem(val folder: Folder, val count: Int, val depth: Int = 0)

data class TrashItem(val id: NotebookId, val title: String, val coverIndex: Int, val deletedAtMillis: Long, val purgeAtMillis: Long)

/** User-controlled state of the screen (everything that is not data). */
data class LibraryControls(
    val destination: LibraryDestination = LibraryDestination.Library,
    val layout: LibraryLayout = LibraryLayout.Shelf,
    val filter: NotebookFilter = NotebookFilter.All,
    /** Only notebooks in folders of this color (index into `CoverPalette.All`), or null for any. */
    val folderColor: Int? = null,
    /** LIB-10: only notebooks with this colour tag (index into `CoverPalette.All`), or null for any. */
    val tag: Int? = null,
    val sort: SortOrder = SortOrder.Modified,
    val query: String = "",
    val selecting: Boolean = false,
    val selected: Set<NotebookId> = emptySet(),
)

data class LibraryUiState(
    val loading: Boolean = true,
    val controls: LibraryControls = LibraryControls(),
    val greeting: Greeting = Greeting.Evening,
    /** Every notebook in the library (sidebar "Library" count, subtitle). */
    val totalCount: Int = 0,
    val favoritesCount: Int = 0,
    val quicknotesCount: Int = 0,
    val trashCount: Int = 0,
    /** All folders as a tree, flattened depth-first. */
    val folders: List<FolderItem> = emptyList(),
    /** The notebooks for the current destination, filters and query, in display order. */
    val items: List<NotebookItem> = emptyList(),
    /** The most recently edited notebook, shown first under "Continue writing". */
    val continueWith: NotebookItem? = null,
    val trash: List<TrashItem> = emptyList(),
    /** Time of the latest save on this device, for the sidebar storage card. */
    val lastSavedAtMillis: Long? = null,
    /** Folder destination: top-level folder down to the open one. */
    val breadcrumb: List<Folder> = emptyList(),
    /** Folder destination: its direct subfolders. */
    val subfolders: List<FolderItem> = emptyList(),
    /** Folder colors in use, for the color filter. */
    val folderColors: List<Int> = emptyList(),
    /** LIB-10: colour tags in use on notebooks, for the tag filter. */
    val tagColors: List<Int> = emptyList(),
    /** Whether the page search for the typed query has finished (title matches are instant, page matches follow). */
    val searchSettled: Boolean = true,
    val emptyKind: EmptyKind = EmptyKind.NoNotebooks,
    /** Pages whose handwriting, typed text or PDF text match the query (REC-03). */
    val pageResults: List<PageResult> = emptyList(),
    /** Whether the handwriting models are on the device (REC-01); until then handwriting is not searchable. */
    val models: ModelsState = ModelsState(),
) {
    val searching: Boolean get() = controls.query.isNotBlank()

    /** "Continue writing" only shows on the plain library view. */
    val showContinueWriting: Boolean
        get() = controls.destination == LibraryDestination.Library && !searching && !controls.selecting && continueWith != null

    val filtersActive: Boolean get() = controls.filter != NotebookFilter.All || controls.folderColor != null || controls.tag != null
}

internal val RECENT_WINDOW_MILLIS: Long = TimeUnit.DAYS.toMillis(7)

/** Pure state builder, kept separate from the ViewModel so it is trivially unit-testable. */
internal fun buildLibraryState(
    notebooks: List<Notebook>?,
    trash: List<TrashedNotebook>,
    controls: LibraryControls,
    metadata: LibraryMetadata,
    paletteSize: Int,
    now: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
    thumbnails: Map<NotebookId, String> = emptyMap(),
    locale: Locale = Locale.getDefault(),
    sealed: Set<NotebookId> = emptySet(),
    unlocked: Set<NotebookId> = emptySet(),
    coverPath: (String?) -> String? = { null },
): LibraryUiState {
    val folders = metadata.folders()
    val folderById = folders.associateBy { it.id }
    val all = notebooks.orEmpty().sortedByDescending { it.updatedAtMillis }.map { nb ->
        val meta = metadata.meta(nb.id)
        val folder = meta.folderId?.let(folderById::get)
        NotebookItem(
            id = nb.id,
            title = nb.title,
            pageCount = nb.pageCount,
            updatedAtMillis = nb.updatedAtMillis,
            coverIndex = (meta.coverIndex ?: defaultCoverIndex(nb.id, paletteSize)).mod(paletteSize),
            folder = folder,
            favorite = meta.favorite,
            quicknote = meta.quicknote,
            lastPageIndex = (meta.lastPageIndex ?: (nb.pageCount - 1)).coerceIn(0, nb.pageCount - 1),
            thumbnailPath = thumbnails[nb.id].takeIf { nb.id !in sealed },
            createdAtMillis = nb.createdAtMillis,
            coverStyle = meta.coverStyle,
            kind = meta.kind,
            folderPath = if (folder == null) emptyList() else folderPath(folder.id, folderById),
            sealed = nb.id in sealed,
            locked = nb.id in sealed && nb.id !in unlocked,
            coverImagePath = if (nb.id in sealed && nb.id !in unlocked) null else coverPath(meta.coverImage),
            tags = meta.tags.map { it.mod(paletteSize) }.distinct().sorted(),
        )
    }
    val recentCutoff = now - RECENT_WINDOW_MILLIS
    val destination = controls.destination
    val query = normalizeForSearch(controls.query)
    val items: List<NotebookItem>
    val emptyKind: EmptyKind
    if (query.isNotEmpty()) {
        // Search spans the whole library: title matches, those starting with the query first. Locked notebooks stay out.
        val hits = all.filter { !it.locked }.mapNotNull { item ->
            val title = normalizeForSearch(item.title)
            when {
                title.startsWith(query) || title.split(' ').any { it.startsWith(query) } -> 0 to item
                title.contains(query) -> 1 to item
                else -> null
            }
        }
        items = hits.sortedWith(compareBy<Pair<Int, NotebookItem>> { it.first }.thenByDescending { it.second.updatedAtMillis }).map { it.second }
        emptyKind = EmptyKind.NoSearchResults
    } else {
        val inDestination = when (destination) {
            LibraryDestination.Library -> all
            LibraryDestination.Recent -> all.filter { it.updatedAtMillis >= recentCutoff && !it.locked }
            LibraryDestination.Favorites -> all.filter { it.favorite }
            LibraryDestination.Quicknotes -> all.filter { it.quicknote }
            LibraryDestination.SmartBoard, LibraryDestination.Trash -> emptyList()
            is LibraryDestination.InFolder -> all.filter { it.folder?.id == destination.folderId }
        }
        val filtered = inDestination.filter { item ->
            val byKind = when (controls.filter) {
                NotebookFilter.All -> true
                NotebookFilter.ThisWeek -> item.updatedAtMillis >= recentCutoff
                NotebookFilter.Favorites -> item.favorite
                NotebookFilter.Pdfs -> item.kind == NotebookKind.Pdf
            }
            byKind && (controls.folderColor == null || item.folder?.colorIndex == controls.folderColor) &&
                (controls.tag == null || controls.tag in item.tags)
        }
        items = sortItems(filtered, controls.sort, locale)
        emptyKind = when {
            inDestination.isNotEmpty() -> if (controls.filter == NotebookFilter.Pdfs && controls.folderColor == null && controls.tag == null) EmptyKind.NoPdfs else EmptyKind.NoMatches
            destination is LibraryDestination.InFolder -> EmptyKind.EmptyFolder
            destination == LibraryDestination.Favorites -> EmptyKind.NoFavorites
            destination == LibraryDestination.Quicknotes -> EmptyKind.NoQuicknotes
            destination == LibraryDestination.Recent -> EmptyKind.NoRecent
            else -> EmptyKind.NoNotebooks
        }
    }
    val counts = all.groupingBy { it.folder?.id }.eachCount()
    val tree = folderTree(folders, counts)
    val openFolder = (destination as? LibraryDestination.InFolder)?.folderId
    return LibraryUiState(
        loading = notebooks == null,
        controls = controls,
        greeting = greetingAt(now, timeZone),
        totalCount = all.size,
        favoritesCount = all.count { it.favorite },
        quicknotesCount = all.count { it.quicknote },
        trashCount = trash.size,
        folders = tree,
        items = items,
        continueWith = all.firstOrNull { !it.locked },
        trash = trash.map {
            TrashItem(
                id = it.notebook.id,
                title = it.notebook.title,
                coverIndex = (metadata.meta(it.notebook.id).coverIndex ?: defaultCoverIndex(it.notebook.id, paletteSize)).mod(paletteSize),
                deletedAtMillis = it.deletedAtMillis,
                purgeAtMillis = it.deletedAtMillis + StoreLibraryRepository.TRASH_RETENTION_MILLIS,
            )
        },
        lastSavedAtMillis = all.maxOfOrNull { it.updatedAtMillis },
        breadcrumb = folderPath(openFolder, folderById),
        subfolders = if (openFolder == null) emptyList() else tree.filter { it.folder.parentId == openFolder },
        tagColors = all.filter { !it.locked }.flatMap { it.tags }.distinct().sorted(),
        folderColors = folders.map { it.colorIndex.mod(paletteSize) }.distinct().sorted(),
        emptyKind = emptyKind,
    )
}

internal fun sortItems(items: List<NotebookItem>, sort: SortOrder, locale: Locale = Locale.getDefault()): List<NotebookItem> = when (sort) {
    SortOrder.Modified -> items.sortedByDescending { it.updatedAtMillis }
    SortOrder.Created -> items.sortedByDescending { it.createdAtMillis }
    SortOrder.Name -> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        items.sortedWith { a, b -> collator.compare(a.title.trim(), b.title.trim()) }
    }
}

/** Folders depth-first (children right under their parent), each with its own notebook count. */
internal fun folderTree(folders: List<Folder>, counts: Map<String?, Int>): List<FolderItem> {
    val ids = folders.mapTo(HashSet()) { it.id }
    val children = folders.groupBy { f -> f.parentId?.takeIf { it in ids && it != f.id } }
    val out = ArrayList<FolderItem>(folders.size)
    fun walk(parent: String?, depth: Int) {
        children[parent].orEmpty().forEach { f ->
            if (out.none { it.folder.id == f.id }) {
                out += FolderItem(f, counts[f.id] ?: 0, depth)
                walk(f.id, depth + 1)
            }
        }
    }
    walk(null, 0)
    return out
}

/** Folds a title or query for matching (see [SearchText.fold]). */
fun normalizeForSearch(text: String): String = SearchText.fold(text)

/** True if [a] and [b] fall on the same calendar day in [timeZone]. */
internal fun sameDay(a: Long, b: Long, timeZone: TimeZone): Boolean {
    val ca = Calendar.getInstance(timeZone).apply { timeInMillis = a }
    val cb = Calendar.getInstance(timeZone).apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

internal fun greetingAt(now: Long, timeZone: TimeZone): Greeting {
    val hour = Calendar.getInstance(timeZone).apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in MORNING_START until AFTERNOON_START -> Greeting.Morning
        in AFTERNOON_START until EVENING_START -> Greeting.Afternoon
        else -> Greeting.Evening
    }
}

private const val MORNING_START = 5
private const val AFTERNOON_START = 12
private const val EVENING_START = 17
