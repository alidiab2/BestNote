package app.bestnote.feature.library

import android.content.Context
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.storage.NotebookStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Debug builds only: once per install, adds demo notebooks (one with an Arabic title, for RTL
 * checks) and gives them folders, favorites and reading positions. Never compiled into release.
 */
class DemoLibrarySeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: NotebookStore,
    private val index: RoomIndexMirror,
    private val metadata: LibraryMetadata,
    private val clock: LibraryClock,
) : LibrarySeeder {

    override suspend fun seed() {
        // Demo folders and covers are persisted once; after that they are the user's to change.
        val metaMarker = File(context.filesDir, META_SEEDED_MARKER)
        if (!metaMarker.exists()) {
            FOLDERS.forEach { metadata.putFolder(it) }
            DEMO.forEach { demo -> metadata.update(NotebookId(demo.id)) { demo.meta } }
            metaMarker.createNewFile()
        }
        // A marker, so trashing a demo notebook sticks and existing notebooks are never touched.
        val marker = File(context.filesDir, SEEDED_MARKER)
        if (marker.exists()) return
        val now = clock.now()
        DEMO.forEach { demo ->
            val updated = now - TimeUnit.MINUTES.toMillis(demo.minutesAgo)
            val notebook = Notebook(
                NotebookId(demo.id), demo.title, updated - TimeUnit.DAYS.toMillis(DEMO_AGE_DAYS), updated,
                demo.template, demo.pages,
            )
            store.write(StoreLibraryRepository.emptyPackage(notebook))
            index.upsert(notebook)
        }
        marker.createNewFile()
    }

    private class Demo(
        val id: String,
        val title: String,
        val pages: Int,
        val minutesAgo: Long,
        val meta: NotebookMeta,
        val template: PaperTemplate = PaperTemplate.Ruled,
    )

    private companion object {
        const val DEMO_AGE_DAYS = 40L
        const val SEEDED_MARKER = ".library-demo-seeded"
        const val META_SEEDED_MARKER = ".library-demo-meta-seeded"
        val FOLDERS = listOf(
            Folder("uni", "University", colorIndex = 0),
            Folder("work", "Work", colorIndex = 1),
            Folder("personal", "Personal", colorIndex = 2),
            Folder("ideas", "Ideas", colorIndex = 3),
            Folder("uni-lectures", "Lectures", colorIndex = 4, parentId = "uni"),
        )
        val DEMO = listOf(
            Demo("demo-organic-chem", "Organic Chemistry", 48, 25, NotebookMeta(0, folderId = "uni", favorite = true, lastPageIndex = 31)),
            Demo("demo-linear-algebra", "Linear Algebra", 36, 180, NotebookMeta(4, folderId = "uni", lastPageIndex = 12), PaperTemplate.Grid),
            Demo("demo-arabic", "ملاحظات المحاضرة", 22, 300, NotebookMeta(2, folderId = "uni-lectures", lastPageIndex = 9)),
            Demo("demo-meetings", "Team Meetings", 64, 1_440, NotebookMeta(1, folderId = "work", favorite = true, lastPageIndex = 60)),
            Demo("demo-roadmap", "Q4 Roadmap", 12, 2_900, NotebookMeta(5, folderId = "work", lastPageIndex = 4)),
            Demo("demo-sketchbook", "Sketchbook", 80, 4_300, NotebookMeta(3, CoverStyle.Dots, folderId = "ideas", lastPageIndex = 18), PaperTemplate.Blank),
            Demo("demo-journal", "Travel Journal", 30, 9_000, NotebookMeta(2, CoverStyle.Stripes, folderId = "personal", favorite = true, lastPageIndex = 27), PaperTemplate.Dotted),
            Demo("demo-reading", "Reading Log", 20, 14_000, NotebookMeta(4, folderId = "personal", lastPageIndex = 6)),
            Demo("demo-physics", "Physics II", 40, 20_000, NotebookMeta(0, CoverStyle.Grid, folderId = "uni", lastPageIndex = 39), PaperTemplate.Grid),
            Demo("demo-quick", "Grocery list", 1, 50, NotebookMeta(3, quicknote = true)),
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object LibrarySeederModule {
    @Provides
    fun seeder(impl: DemoLibrarySeeder): LibrarySeeder = impl
}
