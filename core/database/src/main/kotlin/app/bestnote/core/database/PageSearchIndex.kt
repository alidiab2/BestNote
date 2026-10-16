package app.bestnote.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomDatabase
import androidx.room.RoomRawQuery
import androidx.room.Transaction
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.bestnote.core.common.SearchText
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.PageRect
import app.bestnote.core.model.PageSearchHit
import app.bestnote.core.model.SegmentKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One searchable piece of a page (REC-02): a recognized handwritten word, a typed text box or a line of
 * PDF text, with its box in page points. [text] is what is shown; [folded] is what is matched (the
 * recognizer's candidates joined, folded by [SearchText.fold]).
 */
@Entity(tableName = "search_segments", indices = [Index("notebookId", "pageId")])
data class SearchSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val notebookId: String,
    val pageId: String,
    val kind: String,
    val text: String,
    val folded: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val rect: PageRect get() = PageRect(left, top, right, bottom)
}

/**
 * What the index knows about one page: [signature] is a hash of its ink and elements when it was read,
 * so an unchanged page is skipped. [inkIndexed] is false while its handwriting could not be recognized yet
 * (the models were not on the device); only its typed text is searchable then.
 */
@Entity(tableName = "search_pages", primaryKeys = ["notebookId", "pageId"])
data class SearchPageEntity(
    val notebookId: String,
    val pageId: String,
    val pageIndex: Int,
    val signature: String,
    val inkIndexed: Boolean,
    val indexedAtMillis: Long,
    val widthPt: Float,
    val heightPt: Float,
    val thumbnailPath: String? = null,
)

/** The `updatedAtMillis` of the notebook the index last caught up with, and whether all its ink was read. */
@Entity(tableName = "search_notebooks")
data class SearchNotebookEntity(
    @PrimaryKey val notebookId: String,
    val indexedUpdatedAtMillis: Long,
    val inkComplete: Boolean,
)

@Dao
interface SearchDao {
    @Query("SELECT * FROM search_pages WHERE notebookId = :notebookId")
    suspend fun pages(notebookId: String): List<SearchPageEntity>

    @Query("SELECT * FROM search_pages WHERE notebookId = :notebookId AND pageId = :pageId")
    suspend fun page(notebookId: String, pageId: String): SearchPageEntity?

    @Query("SELECT * FROM search_notebooks")
    suspend fun notebooks(): List<SearchNotebookEntity>

    @Query("SELECT * FROM search_segments WHERE notebookId = :notebookId AND pageId = :pageId")
    suspend fun segments(notebookId: String, pageId: String): List<SearchSegmentEntity>

    @Query("SELECT COUNT(*) FROM search_pages WHERE inkIndexed = 0")
    suspend fun pagesWaitingForInk(): Int

    @Query("SELECT COUNT(*) FROM search_segments")
    suspend fun segmentCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPage(page: SearchPageEntity)

    @Insert
    suspend fun insertSegments(segments: List<SearchSegmentEntity>)

    @Query("DELETE FROM search_segments WHERE notebookId = :notebookId AND pageId = :pageId")
    suspend fun deleteSegments(notebookId: String, pageId: String)

    @Query("DELETE FROM search_pages WHERE notebookId = :notebookId AND pageId = :pageId")
    suspend fun deletePage(notebookId: String, pageId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNotebook(notebook: SearchNotebookEntity)

    @Query("DELETE FROM search_segments WHERE notebookId = :notebookId")
    suspend fun deleteNotebookSegments(notebookId: String)

    @Query("DELETE FROM search_pages WHERE notebookId = :notebookId")
    suspend fun deleteNotebookPages(notebookId: String)

    @Query("DELETE FROM search_notebooks WHERE notebookId = :notebookId")
    suspend fun deleteNotebookState(notebookId: String)

    /** Replaces everything the index holds for one page, in one transaction. */
    @Transaction
    suspend fun replacePage(page: SearchPageEntity, segments: List<SearchSegmentEntity>) {
        deleteSegments(page.notebookId, page.pageId)
        if (segments.isNotEmpty()) insertSegments(segments)
        upsertPage(page)
    }

    /** Forgets pages of [notebookId] that are not in [keep] any more (deleted or moved to the trash). */
    @Transaction
    suspend fun retainPages(notebookId: String, keep: Set<String>) {
        pages(notebookId).filter { it.pageId !in keep }.forEach {
            deleteSegments(notebookId, it.pageId)
            deletePage(notebookId, it.pageId)
        }
    }

    @Transaction
    suspend fun removeNotebook(notebookId: String) {
        deleteNotebookSegments(notebookId)
        deleteNotebookPages(notebookId)
        deleteNotebookState(notebookId)
    }

    /** For [PageSearchIndex]'s FTS queries, which Room cannot verify at compile time. */
    @RawQuery
    suspend fun rawSegments(query: RoomRawQuery): List<SearchSegmentEntity>
}

/** A page that contains every word of a query, with the pieces that matched. */
data class PageMatch(val notebookId: String, val pageId: String, val segments: List<SearchSegmentEntity>, val score: Float)

/** Groups segments that matched single query words into pages that match the whole query, best first. */
object PageMatcher {
    /**
     * @param perToken for each query word, the segments that contain it. A page needs a segment for every word
     *   (the words may sit in different segments). Typed text outranks handwriting, whole words outrank parts.
     */
    fun match(perToken: Map<String, List<SearchSegmentEntity>>): List<PageMatch> {
        if (perToken.isEmpty() || perToken.values.any { it.isEmpty() }) return emptyList()
        val pages = LinkedHashMap<Pair<String, String>, MutableMap<String, MutableList<SearchSegmentEntity>>>()
        for ((token, segments) in perToken) {
            for (s in segments) pages.getOrPut(s.notebookId to s.pageId) { LinkedHashMap() }.getOrPut(token) { ArrayList() }.add(s)
        }
        return pages.filter { it.value.size == perToken.size }.map { (key, found) ->
            val score = found.entries.sumOf { (token, segs) -> segs.maxOf { quality(token, it) }.toDouble() }.toFloat()
            PageMatch(key.first, key.second, found.values.flatten().distinctBy { it.id }, score)
        }.sortedByDescending { it.score }
    }

    private fun quality(token: String, s: SearchSegmentEntity): Float {
        val words = s.folded.split(' ')
        val base = when {
            token in words -> 3f
            words.any { it.startsWith(token) } -> 2f
            else -> 1f
        }
        return base + if (s.kind == SegmentKind.Text.name) 0.5f else 0f
    }
}

/**
 * FTS5 (`trigram`, so Arabic and partial words work) over [SearchSegmentEntity.folded]. External-content, kept in sync
 * by triggers; created on open next to the title index. Words shorter than three letters fall back to `LIKE`.
 */
object PageSearchSchema : RoomDatabase.Callback() {
    const val FTS_TABLE = "search_fts"

    private val CREATE = listOf(
        "CREATE VIRTUAL TABLE IF NOT EXISTS $FTS_TABLE USING fts5(folded, content = 'search_segments', content_rowid = 'id', tokenize = 'trigram')",
        "CREATE TRIGGER IF NOT EXISTS search_fts_insert AFTER INSERT ON search_segments BEGIN " +
            "INSERT INTO $FTS_TABLE(rowid, folded) VALUES (new.id, new.folded); END",
        "CREATE TRIGGER IF NOT EXISTS search_fts_delete AFTER DELETE ON search_segments BEGIN " +
            "INSERT INTO $FTS_TABLE($FTS_TABLE, rowid, folded) VALUES ('delete', old.id, old.folded); END",
        // A notebook that leaves the library (trash, delete) leaves the index. REPLACE upserts don't fire delete triggers.
        "CREATE TRIGGER IF NOT EXISTS search_notebook_delete AFTER DELETE ON notebooks BEGIN " +
            "DELETE FROM search_segments WHERE notebookId = old.id; DELETE FROM search_pages WHERE notebookId = old.id; " +
            "DELETE FROM search_notebooks WHERE notebookId = old.id; END",
    )

    override fun onOpen(connection: SQLiteConnection) {
        val exists = connection.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$FTS_TABLE'").use { it.step() }
        connection.execSQL("BEGIN IMMEDIATE")
        try {
            CREATE.forEach(connection::execSQL)
            if (!exists) connection.execSQL("INSERT INTO $FTS_TABLE($FTS_TABLE) VALUES ('rebuild')")
            connection.execSQL("COMMIT")
        } catch (e: Exception) {
            connection.execSQL("ROLLBACK")
            throw e
        }
    }
}

/** Searches the page index (REC-03): [search] finds pages, [highlights] the boxes of one page to mark. */
@Singleton
class PageSearchIndex @Inject constructor(private val db: BestNoteDatabase) {
    private val dao get() = db.searchDao()

    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): List<PageSearchHit> {
        val tokens = SearchText.tokens(query)
        if (tokens.isEmpty()) return emptyList()
        val matches = PageMatcher.match(tokens.associateWith { segmentsWith(it) }).take(limit)
        return matches.mapNotNull { m ->
            val page = dao.page(m.notebookId, m.pageId) ?: return@mapNotNull null
            val first = m.segments.first()
            PageSearchHit(
                notebookId = NotebookId(m.notebookId),
                pageId = m.pageId,
                pageIndex = page.pageIndex,
                snippet = snippet(m.segments, tokens),
                kind = SegmentKind.entries.firstOrNull { it.name == first.kind } ?: SegmentKind.Ink,
                highlights = m.segments.map { it.rect },
                thumbnailPath = page.thumbnailPath,
                pageWidthPt = page.widthPt,
                pageHeightPt = page.heightPt,
            )
        }
    }

    suspend fun highlights(notebookId: NotebookId, pageId: String, query: String): List<PageRect> {
        val tokens = SearchText.tokens(query)
        if (tokens.isEmpty()) return emptyList()
        val segments = dao.segments(notebookId.value, pageId)
        return segments.filter { s -> tokens.any { it in s.folded } }.map { it.rect }
    }

    private suspend fun segmentsWith(token: String): List<SearchSegmentEntity> =
        if (token.codePointCount(0, token.length) >= TRIGRAM) {
            dao.rawSegments(RoomRawQuery(FTS_QUERY) { it.bindText(1, phrase(token)); it.bindLong(2, SEGMENT_LIMIT) })
        } else {
            dao.rawSegments(RoomRawQuery(LIKE_QUERY) { it.bindText(1, "%${NotebookSearch.escapeLike(token)}%"); it.bindLong(2, SEGMENT_LIMIT) })
        }

    private fun snippet(segments: List<SearchSegmentEntity>, tokens: List<String>): String =
        segments.sortedWith(compareBy({ it.top }, { it.left })).map { it.text.trim() }.filter { it.isNotEmpty() }.distinct().take(SNIPPET_PIECES).joinToString(" … ")

    companion object {
        private const val DEFAULT_LIMIT = 40
        private const val TRIGRAM = 3
        private const val SEGMENT_LIMIT = 2_000L
        private const val SNIPPET_PIECES = 4
        private const val FTS_QUERY =
            "SELECT * FROM search_segments WHERE id IN (SELECT rowid FROM ${PageSearchSchema.FTS_TABLE} WHERE ${PageSearchSchema.FTS_TABLE} MATCH ?) LIMIT ?"
        private const val LIKE_QUERY = "SELECT * FROM search_segments WHERE folded LIKE ? ESCAPE '\\' LIMIT ?"

        internal fun phrase(text: String) = NotebookSearch.phrase(text)
    }
}
