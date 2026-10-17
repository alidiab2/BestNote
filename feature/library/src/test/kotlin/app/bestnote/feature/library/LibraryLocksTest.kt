package app.bestnote.feature.library

import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.PageRect
import app.bestnote.core.model.PageSearchHit
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.SegmentKind
import app.bestnote.core.storage.FileNotebookStore
import app.bestnote.core.storage.InMemoryNotebookStore
import app.bestnote.core.storage.KeyWrap
import app.bestnote.core.storage.NotebookKeyring
import app.bestnote.core.storage.NotebookLockedException
import app.bestnote.core.storage.SealCrypto
import app.bestnote.core.storage.SealHeader
import app.bestnote.engine.recognition.PageSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** LIB-11: locked notebooks in the library — covers, search, Continue writing, Recent, folders and the Trash. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryLocksTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeLocks(sealed: Set<NotebookId> = emptySet(), unlocked: Set<NotebookId> = emptySet()) : LibraryLocks {
        override val sealed = MutableStateFlow(sealed)
        override val unlocked = MutableStateFlow(unlocked)
        override suspend fun refresh() = Unit
    }

    private val now = 1_000_000_000_000L

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun nb(id: String, title: String, updated: Long) = Notebook(NotebookId(id), title, updated, updated, PaperTemplate.Ruled)

    @Test fun lockedNotebooksAreLeftOutOfSearchRecentAndContinueWriting() {
        val locked = nb("a", "Secret notes", now)
        val open = nb("b", "Shared notes", now - 1_000)
        val plain = nb("c", "Plain notes", now - 2_000)
        val sealed = setOf(locked.id, open.id)
        val build = { controls: LibraryControls ->
            buildLibraryState(
                listOf(locked, open, plain), emptyList(), controls, InMemoryLibraryMetadata(), 8, now,
                thumbnails = mapOf(locked.id to "/t/a.webp", open.id to "/t/b.webp", plain.id to "/t/c.webp"),
                sealed = sealed, unlocked = setOf(open.id),
            )
        }
        val home = build(LibraryControls())
        assertEquals(listOf("a", "b", "c"), home.items.map { it.id.value }) // still on the shelf, with a lock
        val a = home.items.first()
        assertTrue(a.sealed && a.locked)
        assertNull(a.thumbnailPath)
        assertNull(home.items[1].thumbnailPath) // sealed notebooks never show a page, even unlocked
        assertFalse(home.items[1].locked)
        assertEquals("/t/c.webp", home.items[2].thumbnailPath)
        assertEquals(open.id, home.continueWith?.id)
        assertEquals(listOf("b", "c"), build(LibraryControls(query = "notes")).items.map { it.id.value })
        assertEquals(emptyList<String>(), build(LibraryControls(query = "secret")).items.map { it.id.value })
        assertEquals(listOf("b", "c"), build(LibraryControls(destination = LibraryDestination.Recent)).items.map { it.id.value })
    }

    @Test fun pageResultsNeverShowLockedNotebooks() = runTest {
        val store = InMemoryNotebookStore()
        val repo = StoreLibraryRepository(store, InMemoryNotebookStore(), clock = { now })
        val secret = repo.create("Secret")
        val plain = repo.create("Plain")
        fun hit(id: NotebookId) = PageSearchHit(id, "p0", 0, "word", SegmentKind.Ink, listOf(PageRect(0f, 0f, 1f, 1f)), null, 595f, 842f)
        val search = object : PageSearch {
            override suspend fun search(query: String, limit: Int) = listOf(hit(secret.id), hit(plain.id))
            override suspend fun highlights(notebookId: NotebookId, pageId: String, query: String) = emptyList<PageRect>()
        }
        val vm = LibraryViewModel(
            repo, InMemoryLibraryMetadata(), { }, { now }, QuicknoteRequests(), PdfImporting { error("no") }, PdfOpenRequests(), search,
            locks = FakeLocks(sealed = setOf(secret.id), unlocked = setOf(secret.id)),
        )
        vm.setQuery("word")
        advanceTimeBy(1_000)
        assertEquals(listOf("Plain"), vm.state.value.pageResults.map { it.notebookTitle })
    }

    @Test fun aLockedFolderUnlocksTogetherAndLocksNewArrivals() = runTest {
        val store = InMemoryNotebookStore()
        val repo = StoreLibraryRepository(store, InMemoryNotebookStore(), clock = { now })
        val metadata = InMemoryLibraryMetadata()
        metadata.setFolders(listOf(Folder("f", "Private", 0, locked = true), Folder("sub", "Sub", 1, parentId = "f"), Folder("g", "Open", 2)))
        val x = repo.create("X")
        val y = repo.create("Y")
        val z = repo.create("Z")
        val w = repo.create("W")
        metadata.put(x.id, NotebookMeta(folderId = "f"))
        metadata.put(y.id, NotebookMeta(folderId = "sub"))
        metadata.put(z.id, NotebookMeta(folderId = "g"))
        val locks = FakeLocks(sealed = setOf(x.id, y.id, z.id))
        val vm = LibraryViewModel(repo, metadata, { }, { now }, QuicknoteRequests(), PdfImporting { error("no") }, PdfOpenRequests(), locks = locks)
        assertEquals(setOf(x.id, y.id), vm.unlockGroup(y.id).toSet())
        assertEquals(listOf(z.id), vm.unlockGroup(z.id))
        assertEquals(setOf(x.id, y.id), vm.notebooksInFolder("f").toSet())
        vm.moveToFolder(listOf(w.id), "sub")
        val lock = vm.eventFlow.filterIsInstance<LibraryEvent.Lock>().first()
        assertEquals(listOf(w.id), lock.ids)
        assertEquals("Private", lock.folder)
    }

    @Test fun lockedNotebooksGoToTheTrashAndBackStillEncrypted() = runTest {
        val ring = NotebookKeyring()
        val store = FileNotebookStore(tmp.newFolder("notebooks"), keyring = ring)
        val trash = FileNotebookStore(tmp.newFolder("trash"), keyring = ring)
        var clock = now
        val repo = StoreLibraryRepository(store, trash, clock = { clock }, keyring = ring)
        val n = repo.create("Diary")
        val key = SealCrypto.newDataKey()
        val header = SealHeader(keyCheck = SealCrypto.keyCheck(key), wraps = listOf(KeyWrap(KeyWrap.KIND_DEVICE, "x")))
        assertTrue(store.seal(n.id, key, header))
        clock += 5_000
        assertTrue(repo.moveToTrash(n.id)) // no key needed: the directory moves as it is
        assertTrue(store.list().isEmpty())
        assertEquals(setOf(n.id), trash.sealedIds())
        assertEquals(clock, repo.trash().single().deletedAtMillis)
        assertTrue(repo.restore(n.id))
        assertEquals(setOf(n.id), store.sealedIds())
        try {
            store.read(n.id)
            fail("read while locked")
        } catch (_: NotebookLockedException) {
            // expected
        }
        // Rename needs the key; a copy of an unlocked locked notebook is locked too.
        assertNull(repo.rename(n.id, "Other"))
        ring.put(n.id, key, header)
        val copy = repo.duplicate(n.id, "Diary copy")!!
        assertTrue(copy.id in store.sealedIds())
        assertEquals("Other", repo.rename(n.id, "Other")?.title)
    }
}
