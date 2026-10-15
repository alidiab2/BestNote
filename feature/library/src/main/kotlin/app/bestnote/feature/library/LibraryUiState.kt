package app.bestnote.feature.library

import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import java.util.Calendar
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
enum class NotebookFilter { All, ThisWeek, Favorites }

enum class Greeting { Morning, Afternoon, Evening }

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
) {
    /** Share of the notebook read/written so far, in 0..1. */
    val progress: Float get() = (lastPageIndex + 1f) / pageCount
}

data class FolderItem(val folder: Folder, val count: Int)

data class TrashItem(val id: NotebookId, val title: String, val coverIndex: Int, val deletedAtMillis: Long, val purgeAtMillis: Long)

/** User-controlled state of the screen (everything that is not data). */
data class LibraryControls(
    val destination: LibraryDestination = LibraryDestination.Library,
    val layout: LibraryLayout = LibraryLayout.Shelf,
    val filter: NotebookFilter = NotebookFilter.All,
    val query: String = "",
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
    val folders: List<FolderItem> = emptyList(),
    /** The notebooks for the current destination, filter and query, in display order. */
    val items: List<NotebookItem> = emptyList(),
    /** The most recently edited notebook, shown first under "Continue writing". */
    val continueWith: NotebookItem? = null,
    val trash: List<TrashItem> = emptyList(),
    /** Time of the latest save on this device, for the sidebar storage card. */
    val lastSavedAtMillis: Long? = null,
) {
    /** "Continue writing" only shows on the plain library view. */
    val showContinueWriting: Boolean
        get() = controls.destination == LibraryDestination.Library && controls.query.isBlank() && continueWith != null
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
): LibraryUiState {
    val folders = metadata.folders()
    val folderById = folders.associateBy { it.id }
    val all = notebooks.orEmpty().sortedByDescending { it.updatedAtMillis }.map { nb ->
        val meta = metadata.meta(nb.id)
        NotebookItem(
            id = nb.id,
            title = nb.title,
            pageCount = nb.pageCount,
            updatedAtMillis = nb.updatedAtMillis,
            coverIndex = (meta.coverIndex ?: defaultCoverIndex(nb.id, paletteSize)).mod(paletteSize),
            folder = meta.folderId?.let(folderById::get),
            favorite = meta.favorite,
            quicknote = meta.quicknote,
            lastPageIndex = (meta.lastPageIndex ?: (nb.pageCount - 1)).coerceIn(0, nb.pageCount - 1),
        )
    }
    val recentCutoff = now - RECENT_WINDOW_MILLIS
    val inDestination = when (val d = controls.destination) {
        LibraryDestination.Library -> all
        LibraryDestination.Recent -> all.filter { it.updatedAtMillis >= recentCutoff }
        LibraryDestination.Favorites -> all.filter { it.favorite }
        LibraryDestination.Quicknotes -> all.filter { it.quicknote }
        LibraryDestination.SmartBoard, LibraryDestination.Trash -> emptyList()
        is LibraryDestination.InFolder -> all.filter { it.folder?.id == d.folderId }
    }
    val filtered = when (controls.filter) {
        NotebookFilter.All -> inDestination
        NotebookFilter.ThisWeek -> inDestination.filter { it.updatedAtMillis >= recentCutoff }
        NotebookFilter.Favorites -> inDestination.filter { it.favorite }
    }
    val query = controls.query.trim()
    val items = if (query.isEmpty()) filtered else filtered.filter { it.title.contains(query, ignoreCase = true) }
    return LibraryUiState(
        loading = notebooks == null,
        controls = controls,
        greeting = greetingAt(now, timeZone),
        totalCount = all.size,
        favoritesCount = all.count { it.favorite },
        quicknotesCount = all.count { it.quicknote },
        trashCount = trash.size,
        folders = folders.map { f -> FolderItem(f, all.count { it.folder?.id == f.id }) },
        items = items,
        continueWith = all.firstOrNull(),
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
    )
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
