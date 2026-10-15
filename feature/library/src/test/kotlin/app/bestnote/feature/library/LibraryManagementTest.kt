package app.bestnote.feature.library

import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.PaperTemplate
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** LIB-02, LIB-04..07: notebook actions, folders, favorites, Quicknote, sort/filter and search. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryManagementTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_699_960_000_000L // 14 Nov 2023, 11:06 UTC
    private val clock = LibraryClock { now }
    private lateinit var store: InMemoryNotebookStore
    private lateinit var repo: StoreLibraryRepository
    private val metadata = InMemoryLibraryMetadata()
    private val quicknotes = QuicknoteRequests()
    private val defaultTz = TimeZone.getDefault()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        store = InMemoryNotebookStore()
        repo = StoreLibraryRepository(store, InMemoryNotebookStore(), clock = { now })
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        TimeZone.setDefault(defaultTz)
    }

    private fun viewModel() = LibraryViewModel(repo, metadata, { }, clock, quicknotes)

    private fun LibraryViewModel.titles() = state.value.items.map { it.title }

    // region LIB-02 new notebook

    @Test
    fun `create uses the sheet's cover, template and size, in the open folder`() = runTest {
        val vm = viewModel()
        val folderId = vm.createFolder("Uni", 0)!!
        vm.select(LibraryDestination.InFolder(folderId))
        vm.create(NewNotebookSpec("Calculus", CoverChoice(4, CoverStyle.Dots), PaperTemplate.Grid, PaperFormat(PaperSize.A5, landscape = true)))
        val open = vm.eventFlow.first { it is LibraryEvent.Open } as LibraryEvent.Open
        val item = vm.state.value.items.single()
        assertEquals(open.id, item.id)
        assertEquals("Calculus", item.title)
        assertEquals(4, item.coverIndex)
        assertEquals(CoverStyle.Dots, item.coverStyle)
        assertEquals(folderId, item.folder?.id)
        val page = store.read(item.id)!!.pages.single().page
        assertEquals(PaperTemplate.Grid, page.template)
        assertEquals(PaperSize.A5.heightPt, page.widthPt)
        assertEquals(PaperSize.A5.widthPt, page.heightPt)
    }

    @Test
    fun `template list covers every PaperTemplate once`() {
        assertEquals(PaperTemplate.entries.toSet(), PaperTemplates.all.toSet())
        assertEquals(PaperTemplate.entries.size, PaperTemplates.all.size)
        assertEquals(PaperTemplate.Blank, PaperTemplates.Quicknote)
    }

    // endregion

    // region Notebook actions

    @Test
    fun `rename trims and ignores blank titles`() = runTest {
        val nb = repo.create("Draft")
        val vm = viewModel()
        vm.rename(nb.id, "  Lecture 1 ")
        assertEquals(listOf("Lecture 1"), vm.titles())
        vm.rename(nb.id, "")
        assertEquals(listOf("Lecture 1"), vm.titles())
    }

    @Test
    fun `duplicate copies pages and metadata under a new id`() = runTest {
        val nb = repo.create("Physics", PaperTemplate.Dotted)
        metadata.put(nb.id, NotebookMeta(coverIndex = 2, coverStyle = CoverStyle.Stripes, favorite = true, lastPageIndex = 0))
        val vm = viewModel()
        now += 1_000
        vm.duplicate(nb.id, "Physics (copy)")
        val event = vm.eventFlow.first()
        assertEquals(LibraryEvent.Message(Notice.Duplicated, name = "Physics (copy)"), event)
        val copy = vm.state.value.items.first { it.title == "Physics (copy)" }
        assertNotEquals(nb.id, copy.id)
        assertEquals(2, copy.coverIndex)
        assertEquals(CoverStyle.Stripes, copy.coverStyle)
        assertTrue(copy.favorite)
        val pkg = store.read(copy.id)!!
        assertEquals(PaperTemplate.Dotted, pkg.pages.single().page.template)
        assertTrue(pkg.pages.all { it.page.notebookId == copy.id })
        assertEquals(2, vm.state.value.totalCount)
    }

    @Test
    fun `move to folder, back to library, and unknown folders are ignored`() = runTest {
        val a = repo.create("A")
        val b = repo.create("B")
        val vm = viewModel()
        val work = vm.createFolder("Work", 1)!!
        vm.moveToFolder(listOf(a.id, b.id), work)
        assertEquals(setOf(work), vm.state.value.items.map { it.folder?.id }.toSet())
        assertEquals(2, vm.state.value.folders.single().count)
        vm.moveToFolder(listOf(a.id), "nope")
        assertEquals(work, metadata.meta(a.id).folderId)
        vm.moveToFolder(listOf(a.id), null)
        assertNull(metadata.meta(a.id).folderId)
        assertEquals(1, vm.state.value.folders.single().count)
    }

    @Test
    fun `cover change applies to every selected notebook`() = runTest {
        val a = repo.create("A")
        val b = repo.create("B")
        val vm = viewModel()
        vm.setCover(listOf(a.id, b.id), CoverChoice(5, CoverStyle.Grid))
        assertTrue(vm.state.value.items.all { it.coverIndex == 5 && it.coverStyle == CoverStyle.Grid })
    }

    @Test
    fun `selection - toggle, select all, bulk favorite and bulk trash with undo`() = runTest {
        val a = repo.create("A")
        val b = repo.create("B")
        val c = repo.create("C")
        val vm = viewModel()
        vm.startSelection(a.id)
        vm.toggleSelected(b.id)
        assertEquals(setOf(a.id, b.id), vm.state.value.controls.selected)
        vm.toggleSelected(b.id)
        assertEquals(setOf(a.id), vm.state.value.controls.selected)
        vm.selectAll()
        assertEquals(setOf(a.id, b.id, c.id), vm.state.value.controls.selected)
        vm.toggleFavoriteSelection()
        assertTrue(vm.state.value.items.all { it.favorite })
        assertFalse(vm.state.value.controls.selecting)

        vm.moveToTrash(listOf(a.id, b.id))
        val trashed = vm.eventFlow.first { it is LibraryEvent.Trashed } as LibraryEvent.Trashed
        assertEquals(setOf(a.id, b.id), trashed.ids.toSet())
        assertEquals(listOf("C"), vm.titles())
        vm.restore(trashed.ids)
        assertEquals(3, vm.state.value.items.size)
        assertTrue("metadata survives the Trash", vm.state.value.items.all { it.favorite })
    }

    // endregion

    // region LIB-04 folders

    @Test
    fun `folders - create, subfolder tree, breadcrumb, edit`() = runTest {
        val vm = viewModel()
        val uni = vm.createFolder("University", 0)!!
        val work = vm.createFolder("Work", 1)!!
        val lectures = vm.createFolder("Lectures", 4, parentId = uni)!!
        assertNull(vm.createFolder("   ", 2))
        assertEquals(listOf("University" to 0, "Lectures" to 1, "Work" to 0), vm.state.value.folders.map { it.folder.name to it.depth })

        vm.select(LibraryDestination.InFolder(lectures))
        assertEquals(listOf(uni, lectures), vm.state.value.breadcrumb.map { it.id })
        vm.select(LibraryDestination.InFolder(uni))
        assertEquals(listOf("Lectures"), vm.state.value.subfolders.map { it.folder.name })

        vm.editFolder(work, "Job", 3)
        val job = vm.state.value.folders.first { it.folder.id == work }.folder
        assertEquals("Job" to 3, job.name to job.colorIndex)
        vm.editFolder(work, " ", 2) // blank name keeps the old one
        assertEquals("Job", vm.state.value.folders.first { it.folder.id == work }.folder.name)
    }

    @Test
    fun `deleting a folder never deletes notebooks - they move up a level`() = runTest {
        val nb = repo.create("Notes")
        val deep = repo.create("Deep")
        val vm = viewModel()
        val uni = vm.createFolder("University", 0)!!
        val lectures = vm.createFolder("Lectures", 1, parentId = uni)!!
        val week1 = vm.createFolder("Week 1", 2, parentId = lectures)!!
        vm.moveToFolder(listOf(nb.id), lectures)
        vm.moveToFolder(listOf(deep.id), week1)
        vm.select(LibraryDestination.InFolder(week1))

        vm.deleteFolder(lectures)
        assertEquals(listOf("University"), vm.state.value.folders.map { it.folder.name })
        assertEquals(uni, metadata.meta(nb.id).folderId)
        assertEquals(uni, metadata.meta(deep.id).folderId)
        assertEquals("open folder was deleted", LibraryDestination.InFolder(uni), vm.state.value.controls.destination)
        assertEquals(2, vm.state.value.totalCount)

        vm.deleteFolder(uni)
        assertNull(metadata.meta(nb.id).folderId)
        assertEquals(LibraryDestination.Library, vm.state.value.controls.destination)
    }

    @Test
    fun `new folder can take the notebooks being moved`() = runTest {
        val a = repo.create("A")
        val vm = viewModel()
        val id = vm.createFolder("Inbox", 2, moveIds = listOf(a.id))!!
        assertEquals(id, vm.state.value.items.single().folder?.id)
    }

    @Test
    fun `file metadata survives a restart and tolerates a corrupt file`() = runTest {
        val file = tmp.root.resolve("library/metadata.json")
        val first = FileLibraryMetadata(file)
        first.putFolder(Folder("f", "Folder", 2, parentId = null))
        first.update(NotebookId("n"), { it.copy(favorite = true, folderId = "f", coverStyle = CoverStyle.Dots) })

        val second = FileLibraryMetadata(file).apply { load() }
        assertEquals(listOf(Folder("f", "Folder", 2)), second.folders())
        assertEquals(NotebookMeta(folderId = "f", favorite = true, coverStyle = CoverStyle.Dots), second.meta(NotebookId("n")))

        file.writeText("{ not json")
        val third = FileLibraryMetadata(file).apply { load() }
        assertTrue(third.folders().isEmpty())
        assertTrue(tmp.root.resolve("library/metadata.json.corrupt").exists())
    }

    // endregion

    // region LIB-05 favorites and Quicknote

    @Test
    fun `favorites view and the favorite toggle`() = runTest {
        val a = repo.create("A")
        repo.create("B")
        val vm = viewModel()
        vm.select(LibraryDestination.Favorites)
        assertEquals(EmptyKind.NoFavorites, vm.state.value.emptyKind)
        vm.setFavorite(listOf(a.id), true)
        assertEquals(listOf("A"), vm.titles())
        assertEquals(1, vm.state.value.favoritesCount)
        vm.setFavorite(listOf(a.id), false)
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun `quicknote opens today's note and makes a new one the next day`() = runTest {
        val vm = viewModel()
        vm.quicknote("Quicknote · Nov 14")
        val first = (vm.eventFlow.first() as LibraryEvent.Open).id
        val item = vm.state.value.items.single()
        assertTrue(item.quicknote)
        assertEquals(PaperTemplate.Blank, store.read(first)!!.pages.single().page.template)

        now += TimeUnit.HOURS.toMillis(2)
        vm.quicknote("ignored")
        assertEquals(first, (vm.eventFlow.first() as LibraryEvent.Open).id)
        assertEquals(1, vm.state.value.totalCount)

        now += TimeUnit.DAYS.toMillis(1)
        vm.quicknote("Quicknote · Nov 15")
        val next = (vm.eventFlow.first() as LibraryEvent.Open).id
        assertNotEquals(first, next)
        assertEquals(2, vm.state.value.quicknotesCount)
    }

    @Test
    fun `launcher shortcut request is taken once`() = runTest {
        val vm = viewModel()
        quicknotes.request()
        assertTrue(vm.quicknoteRequested.value)
        vm.takeQuicknoteRequest("Quicknote")
        assertTrue(vm.eventFlow.first() is LibraryEvent.Open)
        assertFalse(vm.quicknoteRequested.value)
        vm.takeQuicknoteRequest("Quicknote") // nothing pending: no second note
        assertEquals(1, vm.state.value.totalCount)
    }

    // endregion

    // region LIB-06 sort and filter

    @Test
    fun `sort by modified, created and name`() = runTest {
        val b = repo.create("banana"); now += 10
        repo.create("Apple"); now += 10
        repo.create("cherry"); now += 10
        repo.rename(b.id, "banana") // most recently modified, created first
        val vm = viewModel()
        assertEquals(listOf("banana", "cherry", "Apple"), vm.titles())
        vm.setSort(SortOrder.Created)
        assertEquals(listOf("cherry", "Apple", "banana"), vm.titles())
        vm.setSort(SortOrder.Name)
        assertEquals(listOf("Apple", "banana", "cherry"), vm.titles())
    }

    @Test
    fun `name sort is locale aware for Arabic`() {
        val items = listOf("ياسمين", "باب", "أرنب").mapIndexed { i, t -> item(t, i) }
        assertEquals(listOf("أرنب", "باب", "ياسمين"), sortItems(items, SortOrder.Name, Locale.forLanguageTag("ar")).map { it.title })
    }

    @Test
    fun `filters - this week, favorites, PDFs, folder color, clear`() = runTest {
        val old = repo.create("Old")
        now += TimeUnit.DAYS.toMillis(30)
        val fav = repo.create("Fav")
        val pdf = repo.create("Paper.pdf")
        val vm = viewModel()
        val red = vm.createFolder("Red", 1)!!
        vm.createFolder("Blue", 0)
        vm.setFavorite(listOf(fav.id), true)
        vm.moveToFolder(listOf(old.id), red)
        metadata.update(pdf.id) { it.copy(kind = NotebookKind.Pdf) }
        vm.refresh()

        vm.setFilter(NotebookFilter.ThisWeek)
        assertEquals(setOf("Fav", "Paper.pdf"), vm.titles().toSet())
        vm.setFilter(NotebookFilter.Favorites)
        assertEquals(listOf("Fav"), vm.titles())
        vm.setFilter(NotebookFilter.Pdfs)
        assertEquals(listOf("Paper.pdf"), vm.titles())
        vm.clearFilters()
        assertEquals(listOf(0, 1), vm.state.value.folderColors)
        vm.setFolderColor(1)
        assertEquals(listOf("Old"), vm.titles())
        vm.setFolderColor(0)
        assertEquals(EmptyKind.NoMatches, vm.state.value.emptyKind)
        vm.setFolderColor(0) // tapping the active color again clears it
        assertNull(vm.state.value.controls.folderColor)
        assertEquals(3, vm.state.value.items.size)
    }

    @Test
    fun `empty kinds per destination`() = runTest {
        val vm = viewModel()
        assertEquals(EmptyKind.NoNotebooks, vm.state.value.emptyKind)
        val f = vm.createFolder("Empty", 0)!!
        vm.select(LibraryDestination.InFolder(f))
        assertEquals(EmptyKind.EmptyFolder, vm.state.value.emptyKind)
        vm.select(LibraryDestination.Quicknotes)
        assertEquals(EmptyKind.NoQuicknotes, vm.state.value.emptyKind)
        vm.select(LibraryDestination.Recent)
        assertEquals(EmptyKind.NoRecent, vm.state.value.emptyKind)
        repo.create("Something")
        vm.refresh()
        vm.select(LibraryDestination.Library)
        vm.setFilter(NotebookFilter.Pdfs)
        assertEquals(EmptyKind.NoPdfs, vm.state.value.emptyKind)
        vm.setQuery("zzz")
        assertEquals(EmptyKind.NoSearchResults, vm.state.value.emptyKind)
    }

    // endregion

    // region LIB-07 search

    @Test
    fun `search normalization folds Arabic letter forms, diacritics and case`() {
        assertEquals("احمد", normalizeForSearch("أحمد"))
        assertEquals("اسلام", normalizeForSearch("إسلام"))
        assertEquals("امال", normalizeForSearch("آمال"))
        assertEquals("مكتبه", normalizeForSearch("مكتبة"))
        assertEquals("مدرسه", normalizeForSearch("مَدْرَسَةٌ"))
        assertEquals("علي", normalizeForSearch("على"))
        assertEquals("كتاب", normalizeForSearch("كتــــاب"))
        assertEquals("creme brulee notes", normalizeForSearch("  Crème   Brûlée NOTES "))
    }

    @Test
    fun `search finds titles across folders with Arabic normalization, prefix matches first`() = runTest {
        val vm = viewModel()
        val folder = vm.createFolder("Uni", 0)!!
        val lecture = repo.create("ملاحظات المحاضرة")
        repo.create("مكتبة الجامعة")
        repo.create("Organic Chemistry")
        now += 10
        repo.create("Biochemistry")
        vm.refresh()
        vm.moveToFolder(listOf(lecture.id), folder)
        vm.select(LibraryDestination.Favorites) // search ignores the current view

        vm.setQuery("المحاضره")
        assertEquals(listOf("ملاحظات المحاضرة"), vm.titles())
        assertEquals(listOf("Uni"), vm.state.value.items.single().folderPath.map { it.name })
        vm.setQuery("مكتبه")
        assertEquals(listOf("مكتبة الجامعة"), vm.titles())
        vm.setQuery("CHEM")
        assertEquals(listOf("Organic Chemistry", "Biochemistry"), vm.titles())
        assertTrue(vm.state.value.searching)
        assertFalse(vm.state.value.showContinueWriting)
        vm.setQuery("")
        assertTrue(vm.state.value.items.isEmpty()) // back to Favorites
    }

    // endregion

    private fun item(title: String, i: Int) = NotebookItem(
        id = NotebookId("i$i"), title = title, pageCount = 1, updatedAtMillis = i.toLong(), coverIndex = 0,
        folder = null, favorite = false, quicknote = false, lastPageIndex = 0,
    )
}
