package app.bestnote.feature.library

import app.bestnote.core.model.NotebookId
import app.bestnote.core.storage.InMemoryNotebookStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private var now = 1_000_000_000_000L
    private val clock = LibraryClock { now }
    private lateinit var store: InMemoryNotebookStore
    private lateinit var trashStore: InMemoryNotebookStore
    private lateinit var repo: StoreLibraryRepository
    private val metadata = InMemoryLibraryMetadata()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store = InMemoryNotebookStore()
        trashStore = InMemoryNotebookStore()
        repo = StoreLibraryRepository(store, trashStore, clock = { now })
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(seeder: LibrarySeeder = LibrarySeeder { }) = LibraryViewModel(repo, metadata, seeder, clock)

    @Test
    fun `loads notebooks newest first and picks the last one to continue`() = runTest {
        repo.create("Old"); now += 1_000
        repo.create("New")
        val state = viewModel().state.value
        assertFalse(state.loading)
        assertEquals(listOf("New", "Old"), state.items.map { it.title })
        assertEquals("New", state.continueWith?.title)
        assertEquals(2, state.totalCount)
        assertTrue(state.showContinueWriting)
    }

    @Test
    fun `seeder runs before the first load`() = runTest {
        val vm = viewModel(seeder = { repo.create("Seeded") })
        assertEquals(listOf("Seeded"), vm.state.value.items.map { it.title })
    }

    @Test
    fun `search filters by title, case-insensitively, and hides continue writing`() = runTest {
        repo.create("Organic Chemistry"); repo.create("Linear Algebra"); repo.create("ملاحظات المحاضرة")
        val vm = viewModel()
        vm.setQuery("  chem ")
        assertEquals(listOf("Organic Chemistry"), vm.state.value.items.map { it.title })
        assertFalse(vm.state.value.showContinueWriting)
        vm.setQuery("المحاضرة")
        assertEquals(listOf("ملاحظات المحاضرة"), vm.state.value.items.map { it.title })
    }

    @Test
    fun `destinations and filters use metadata`() = runTest {
        val fav = repo.create("Fav")
        val quick = repo.create("Quick")
        val old = repo.create("Old")
        metadata.setFolders(listOf(Folder("uni", "University", 0)))
        metadata.put(fav.id, NotebookMeta(favorite = true, folderId = "uni"))
        metadata.put(quick.id, NotebookMeta(quicknote = true))
        now += TimeUnit.DAYS.toMillis(10)
        repo.rename(fav.id, "Fav") // touched recently
        val vm = viewModel()
        vm.select(LibraryDestination.Favorites)
        assertEquals(listOf("Fav"), vm.state.value.items.map { it.title })
        vm.select(LibraryDestination.Quicknotes)
        assertEquals(listOf("Quick"), vm.state.value.items.map { it.title })
        vm.select(LibraryDestination.Recent)
        assertEquals(listOf("Fav"), vm.state.value.items.map { it.title })
        vm.select(LibraryDestination.InFolder("uni"))
        assertEquals(listOf("Fav"), vm.state.value.items.map { it.title })
        assertEquals(1, vm.state.value.folders.single().count)
        vm.select(LibraryDestination.Library)
        vm.setFilter(NotebookFilter.ThisWeek)
        assertEquals(listOf("Fav"), vm.state.value.items.map { it.title })
        vm.setFilter(NotebookFilter.All)
        assertEquals(3, vm.state.value.items.size)
        assertTrue(old.id in vm.state.value.items.map { it.id })
    }

    @Test
    fun `create emits an open event`() = runTest {
        val vm = viewModel()
        vm.create("Untitled notebook")
        val event = vm.eventFlow.first()
        assertTrue(event is LibraryEvent.Open)
        assertEquals("Untitled notebook", vm.state.value.items.single().title)
    }

    @Test
    fun `rename updates the title`() = runTest {
        val nb = repo.create("Draft")
        val vm = viewModel()
        vm.rename(nb.id, "  Final  ")
        assertEquals("Final", vm.state.value.items.single().title)
        vm.rename(nb.id, "   ") // blank titles are ignored
        assertEquals("Final", vm.state.value.items.single().title)
    }

    @Test
    fun `move to trash then undo restores the notebook with its content`() = runTest {
        val nb = repo.create("Keep me")
        val vm = viewModel()
        vm.moveToTrash(nb.id)
        assertEquals(LibraryEvent.Trashed(nb.id, "Keep me"), vm.eventFlow.first())
        assertTrue(vm.state.value.items.isEmpty())
        assertEquals(1, vm.state.value.trashCount)
        val trashed = vm.state.value.trash.single()
        assertEquals(now + StoreLibraryRepository.TRASH_RETENTION_MILLIS, trashed.purgeAtMillis)

        vm.restore(nb.id)
        assertEquals(listOf("Keep me"), vm.state.value.items.map { it.title })
        assertEquals(0, vm.state.value.trashCount)
        val pkg = store.read(nb.id)!!
        assertNull(pkg.assets[StoreLibraryRepository.DELETED_AT_ASSET])
        assertEquals(1, pkg.pages.size)
    }

    @Test
    fun `trash older than 30 days is purged on start`() = runTest {
        val old = repo.create("Old")
        val recent = repo.create("Recent")
        repo.moveToTrash(old.id)
        now += TimeUnit.DAYS.toMillis(20)
        repo.moveToTrash(recent.id)
        now += TimeUnit.DAYS.toMillis(11)
        val vm = viewModel()
        assertEquals(listOf(recent.id), vm.state.value.trash.map { it.id })
        assertNull(trashStore.read(old.id))
    }

    @Test
    fun `cover colors are stable and progress follows the last page`() = runTest {
        val nb = repo.create("A")
        metadata.put(nb.id, NotebookMeta(lastPageIndex = 0))
        val first = viewModel().state.value.items.single()
        val again = viewModel().state.value.items.single()
        assertEquals(first.coverIndex, again.coverIndex)
        assertEquals(1f, first.progress) // single page
        assertEquals(defaultCoverIndex(NotebookId("x"), 6), defaultCoverIndex(NotebookId("x"), 6))
    }

    @Test
    fun `cached thumbnails reach the items that have one`() = runTest {
        val withThumb = repo.create("Drawn")
        val without = repo.create("Blank")
        repo = StoreLibraryRepository(store, trashStore, clock = { now }, thumbnailSource = { ids ->
            ids.filter { it == withThumb.id }.associateWith { "/thumbs/${it.value}.webp" }
        })
        val items = viewModel().state.value.items.associateBy { it.id }
        assertEquals("/thumbs/${withThumb.id.value}.webp", items.getValue(withThumb.id).thumbnailPath)
        assertNull(items.getValue(without.id).thumbnailPath)
    }

    @Test
    fun `greeting follows the hour`() {
        val utc = TimeZone.getTimeZone("UTC")
        val day = TimeUnit.DAYS.toMillis(1) * 20_000
        assertEquals(Greeting.Morning, greetingAt(day + TimeUnit.HOURS.toMillis(8), utc))
        assertEquals(Greeting.Afternoon, greetingAt(day + TimeUnit.HOURS.toMillis(14), utc))
        assertEquals(Greeting.Evening, greetingAt(day + TimeUnit.HOURS.toMillis(20), utc))
        assertEquals(Greeting.Evening, greetingAt(day + TimeUnit.HOURS.toMillis(2), utc))
    }

    @Test
    fun `a thousand notebooks build state quickly`() {
        val many = (0 until 1_000).map { i ->
            app.bestnote.core.model.Notebook(NotebookId("n$i"), "Notebook $i", i.toLong(), i.toLong(), pageCount = 1 + i % 50)
        }
        val start = System.nanoTime()
        val state = buildLibraryState(many, emptyList(), LibraryControls(query = "9"), metadata, 6, now)
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue(state.items.isNotEmpty())
        assertTrue("took $millis ms", millis < 500)
    }
}
