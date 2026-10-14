/**
 * :core:database — the Room index of the library: notebook metadata for fast listing,
 * sorting and search. Page content (strokes, assets) lives in `.bnote` containers handled by
 * :core:storage; this database only indexes them.
 */
package app.bestnote.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.PaperTemplate
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

@Entity(tableName = "notebooks")
data class NotebookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** [PaperTemplate] name; stored as text so enum reordering never corrupts data. */
    val template: String,
    val pageCount: Int,
)

fun NotebookEntity.toModel() = Notebook(
    id = NotebookId(id),
    title = title,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    template = PaperTemplate.entries.firstOrNull { it.name == template } ?: PaperTemplate.Ruled,
    pageCount = pageCount,
)

fun Notebook.toEntity() = NotebookEntity(
    id = id.value,
    title = title,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    template = template.name,
    pageCount = pageCount,
)

@Dao
interface NotebookDao {
    @Query("SELECT * FROM notebooks ORDER BY updatedAtMillis DESC")
    fun observeAll(): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE id = :id")
    fun observe(id: String): Flow<NotebookEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(notebook: NotebookEntity)

    @Query("DELETE FROM notebooks WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [NotebookEntity::class], version = 1, exportSchema = true)
abstract class BestNoteDatabase : RoomDatabase() {
    abstract fun notebookDao(): NotebookDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): BestNoteDatabase =
        Room.databaseBuilder(context, BestNoteDatabase::class.java, "bestnote.db").build()

    @Provides
    fun notebookDao(db: BestNoteDatabase): NotebookDao = db.notebookDao()
}
