package app.bestnote.core.database

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** DOC-02 on a real device/emulator: the bundled-driver Room index, FTS5 trigram search, migration. */
@RunWith(AndroidJUnit4::class)
class NotebookIndexTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: BestNoteDatabase

    private fun open() = BestNoteDatabase.configure(Room.databaseBuilder(context, BestNoteDatabase::class.java, NAME))

    @Before fun setUp() {
        context.deleteDatabase(NAME)
        db = open()
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(NAME)
    }

    private fun notebook(i: Int, title: String = "Notebook $i") =
        Notebook(NotebookId("id-$i"), title, 1_000L + i, 2_000L + i, pageCount = 1 + i % 40)

    @Test fun thousandNotebooksLoadUnder200ms() = runTest {
        val dao = db.notebookDao()
        repeat(1_000) { dao.upsert(notebook(it).toEntity().copy(folderId = "f${it % 10}", thumbnailPath = "/t/$it.webp")) }
        db.close()
        db = open() // cold: a fresh connection, nothing cached

        val start = System.nanoTime()
        val list = dao().observeAll().first()
        val ms = (System.nanoTime() - start) / 1e6
        Log.i("DOC-02", "Loaded ${list.size} notebooks in $ms ms (cold)")
        assertEquals(1_000, list.size)
        assertEquals("id-999", list.first().id)
        assertTrue("took $ms ms", ms < 200)

        val warmStart = System.nanoTime()
        dao().getAll()
        Log.i("DOC-02", "Loaded 1000 notebooks in ${(System.nanoTime() - warmStart) / 1e6} ms (warm)")
    }

    private fun dao() = db.notebookDao()

    @Test fun syncFromPackageKeepsIndexOnlyColumns() = runTest {
        dao().syncFromPackage(notebook(1))
        dao().markOpened("id-1", 77)
        dao().setThumbnail("id-1", "/thumb", 2_001)
        dao().moveToFolder("id-1", "folder")
        assertTrue(!dao().get("id-1")!!.thumbnailIsStale)
        dao().syncFromPackage(notebook(1, "Renamed").copy(updatedAtMillis = 9_999, pageCount = 3))
        val row = dao().get("id-1")!!
        assertEquals("Renamed", row.title)
        assertEquals(9_999, row.updatedAtMillis)
        assertEquals(3, row.pageCount)
        assertEquals(77L, row.lastOpenedAtMillis)
        assertEquals("folder", row.folderId)
        assertEquals("/thumb", row.thumbnailPath)
        assertTrue(row.thumbnailIsStale) // edited after the thumbnail was rendered
        assertEquals(listOf("id-1"), dao().observeRecent(10).first().map { it.id })
        assertEquals(listOf("id-1"), dao().observeInFolder("folder").first().map { it.id })
        assertTrue(dao().observeInFolder(null).first().isEmpty())
    }

    @Test fun trigramSearchFindsArabicAndSubstrings() = runTest {
        val search = NotebookSearch(db)
        dao().syncFromPackage(notebook(1, "محاضرة الفيزياء"))
        dao().syncFromPackage(notebook(2, "Physics lecture"))
        dao().syncFromPackage(notebook(3, "Chemistry \"lab\" 100%"))
        assertEquals(listOf("id-1"), search.search("فيزياء").map { it.id })
        assertEquals(listOf("id-2"), search.search("SICS").map { it.id })
        assertEquals(listOf("id-3"), search.search("\"lab\"").map { it.id }) // quotes are literal, not syntax
        assertEquals(listOf("id-3"), search.search("0%").map { it.id }) // short query: LIKE, % escaped
        assertEquals(listOf("id-3", "id-2"), search.search("c").map { it.id })

        dao().syncFromPackage(notebook(2, "Biology"))
        assertTrue(search.search("Physics").isEmpty())
        assertEquals(listOf("id-2"), search.search("biolog").map { it.id })
        dao().upsert(notebook(2, "Biology").toEntity()) // REPLACE must not duplicate FTS rows
        assertEquals(listOf("id-2"), search.search("biolog").map { it.id })
        dao().delete("id-2")
        assertTrue(search.search("biolog").isEmpty())
    }

    @Test fun deletingAFolderMovesItsContentsUp() = runTest {
        val folders = db.folderDao()
        folders.upsert(FolderEntity("a", "A", null, 1))
        folders.upsert(FolderEntity("b", "B", "a", 1))
        dao().syncFromPackage(notebook(1))
        dao().moveToFolder("id-1", "b")
        folders.delete("b")
        assertNull(folders.get("b"))
        assertEquals("a", dao().get("id-1")!!.folderId)
        folders.upsert(FolderEntity("c", "C", "a", 1))
        folders.delete("a")
        assertEquals(listOf("c"), folders.observeChildren(null).first().map { it.id })
        assertNull(dao().get("id-1")!!.folderId)
    }

    @Test fun migratesVersion1AndBackfillsSearch() = runTest {
        db.close()
        context.deleteDatabase(NAME)
        BundledSQLiteDriver().open(context.getDatabasePath(NAME).also { it.parentFile!!.mkdirs() }.path).use { c ->
            c.execSQL(
                "CREATE TABLE IF NOT EXISTS `notebooks` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL, " +
                    "`updatedAtMillis` INTEGER NOT NULL, `template` TEXT NOT NULL, `pageCount` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
            c.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            c.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'd70dddbb094a7f4c858caa78842511df')")
            c.execSQL("INSERT INTO notebooks VALUES ('old', 'دفتر قديم', 1, 2, 'Grid', 4)")
            c.execSQL("PRAGMA user_version = 1")
        }
        db = open()
        val row = dao().get("old")!!
        assertEquals("دفتر قديم", row.title)
        assertEquals(4, row.pageCount)
        assertNull(row.folderId)
        assertNull(row.lastOpenedAtMillis)
        assertEquals(listOf("old"), NotebookSearch(db).search("دفتر").map { it.id })
    }

    private companion object {
        const val NAME = "index-test.db"
    }
}
