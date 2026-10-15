/**
 * :core:database — the Room index of the library: notebook metadata for fast listing,
 * sorting, folders, thumbnails and title search. Page content (strokes, assets) lives in `.bnote`
 * packages handled by :core:storage; this database only indexes them and can be rebuilt from
 * them ([NotebookRepository.reconcileIndex], ADR-0004).
 *
 * Opened with the bundled SQLite driver, so FTS5 with the `trigram` tokenizer (Arabic and
 * partial-word title search, [NotebookSearch]) is available on every device.
 */
package app.bestnote.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RawQuery
import androidx.room.RoomDatabase
import androidx.room.RoomRawQuery
import androidx.room.Transaction
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.PaperTemplate
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

@Entity(
    tableName = "notebooks",
    indices = [Index("updatedAtMillis"), Index("lastOpenedAtMillis"), Index("folderId")],
)
data class NotebookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** [PaperTemplate] name; stored as text so enum reordering never corrupts data. */
    val template: String,
    val pageCount: Int,
    /** [FolderEntity.id], or null for the library root. */
    @ColumnInfo(defaultValue = "NULL") val folderId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val lastOpenedAtMillis: Long? = null,
    /** Cached cover image (DOC-10); may be missing on disk, it is only a cache. */
    @ColumnInfo(defaultValue = "NULL") val thumbnailPath: String? = null,
    /** The [updatedAtMillis] the thumbnail was rendered from; older than [updatedAtMillis] = stale. */
    @ColumnInfo(defaultValue = "NULL") val thumbnailUpdatedAtMillis: Long? = null,
)

fun NotebookEntity.toModel() = Notebook(
    id = NotebookId(id),
    title = title,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    template = PaperTemplate.entries.firstOrNull { it.name == template } ?: PaperTemplate.Ruled,
    pageCount = pageCount.coerceAtLeast(1),
)

fun Notebook.toEntity() = NotebookEntity(
    id = id.value,
    title = title,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    template = template.name,
    pageCount = pageCount,
)

/** True when the cached thumbnail is missing or older than the notebook's last edit. */
val NotebookEntity.thumbnailIsStale: Boolean
    get() = thumbnailPath == null || app.bestnote.core.storage.ThumbnailCache.isStale(thumbnailUpdatedAtMillis, updatedAtMillis)

@Dao
interface NotebookDao {
    @Query("SELECT * FROM notebooks ORDER BY updatedAtMillis DESC")
    fun observeAll(): Flow<List<NotebookEntity>>

    /** Notebooks directly in [folderId] (null = library root), newest first. */
    @Query("SELECT * FROM notebooks WHERE folderId IS :folderId ORDER BY updatedAtMillis DESC")
    fun observeInFolder(folderId: String?): Flow<List<NotebookEntity>>

    /** Recently opened notebooks, most recent first. */
    @Query("SELECT * FROM notebooks WHERE lastOpenedAtMillis IS NOT NULL ORDER BY lastOpenedAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks ORDER BY updatedAtMillis DESC")
    suspend fun getAll(): List<NotebookEntity>

    @Query("SELECT * FROM notebooks WHERE id = :id")
    fun observe(id: String): Flow<NotebookEntity?>

    @Query("SELECT * FROM notebooks WHERE id = :id")
    suspend fun get(id: String): NotebookEntity?

    /** Inserts or fully replaces a row (including folder, last-opened and thumbnail columns). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(notebook: NotebookEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(notebook: NotebookEntity): Long

    @Query(
        "UPDATE notebooks SET title = :title, createdAtMillis = :createdAtMillis, updatedAtMillis = :updatedAtMillis, " +
            "template = :template, pageCount = :pageCount WHERE id = :id",
    )
    suspend fun updateContentMetadata(id: String, title: String, createdAtMillis: Long, updatedAtMillis: Long, template: String, pageCount: Int)

    /**
     * Mirrors a notebook's package metadata into the index, keeping index-only columns (folder,
     * last opened, thumbnail) of an existing row.
     */
    @Transaction
    suspend fun syncFromPackage(notebook: Notebook) {
        val row = notebook.toEntity()
        if (insertIfMissing(row) == -1L) {
            updateContentMetadata(row.id, row.title, row.createdAtMillis, row.updatedAtMillis, row.template, row.pageCount)
        }
    }

    @Query("UPDATE notebooks SET lastOpenedAtMillis = :atMillis WHERE id = :id")
    suspend fun markOpened(id: String, atMillis: Long)

    @Query("UPDATE notebooks SET thumbnailPath = :path, thumbnailUpdatedAtMillis = :renderedFromUpdatedAt WHERE id = :id")
    suspend fun setThumbnail(id: String, path: String?, renderedFromUpdatedAt: Long?)

    @Query("UPDATE notebooks SET folderId = :folderId WHERE id = :id")
    suspend fun moveToFolder(id: String, folderId: String?)

    @Query("DELETE FROM notebooks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM notebooks")
    suspend fun count(): Int

    /** For [NotebookSearch]'s FTS queries, which Room cannot verify at compile time. */
    @RawQuery
    suspend fun rawNotebooks(query: RoomRawQuery): List<NotebookEntity>
}

@Database(
    entities = [NotebookEntity::class, FolderEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class BestNoteDatabase : RoomDatabase() {
    abstract fun notebookDao(): NotebookDao
    abstract fun folderDao(): FolderDao

    companion object {
        const val NAME = "bestnote.db"

        /** The production configuration; tests pass an in-memory [builder]. */
        fun configure(builder: Builder<BestNoteDatabase>): BestNoteDatabase = builder
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addCallback(NotebookSearch.Callback)
            .build()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): BestNoteDatabase =
        BestNoteDatabase.configure(Room.databaseBuilder(context, BestNoteDatabase::class.java, BestNoteDatabase.NAME))

    @Provides
    fun notebookDao(db: BestNoteDatabase): NotebookDao = db.notebookDao()

    @Provides
    fun folderDao(db: BestNoteDatabase): FolderDao = db.folderDao()

    @Provides
    fun notebookSearch(db: BestNoteDatabase): NotebookSearch = NotebookSearch(db)
}
