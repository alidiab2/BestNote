package app.bestnote.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A library folder. Folders nest through [parentId] (null = library root). Index-only: not in `.bnote` packages. */
@Entity(tableName = "folders", indices = [Index("parentId")])
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String? = null,
    val createdAtMillis: Long,
)

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE parentId IS :parentId ORDER BY name COLLATE NOCASE")
    fun observeChildren(parentId: String?): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun get(id: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: FolderEntity)

    @Query("UPDATE folders SET parentId = :newParent WHERE parentId = :oldParent")
    suspend fun reparentChildren(oldParent: String, newParent: String?)

    @Query("UPDATE notebooks SET folderId = :newFolder WHERE folderId = :oldFolder")
    suspend fun refileNotebooks(oldFolder: String, newFolder: String?)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteRow(id: String)

    /** Deletes a folder; its notebooks and sub-folders move up to its parent (nothing is lost). */
    @Transaction
    suspend fun delete(id: String) {
        val parent = get(id)?.parentId
        reparentChildren(id, parent)
        refileNotebooks(id, parent)
        deleteRow(id)
    }
}
