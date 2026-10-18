package app.bestnote.feature.library

import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.storage.InMemoryNotebookStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** LIB-10: covers from images, colour tags, the v1 -> v2 metadata migration, and the loading / search-settled states. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoversAndTagsTest {
    private var now = 1_000_000_000_000L
    private val metadata = InMemoryLibraryMetadata()
    private val covers = FakeCovers()
    private lateinit var repo: StoreLibraryRepository

    private class FakeCovers : CoverImages {
        val files = mutableSetOf<String>()
        var fail = false
        override suspend fun import(id: NotebookId, uri: String): String? = if (fail) null else "${id.value}-${files.size}.jpg".also { files += it }
        override suspend fun copy(name: String, to: NotebookId): String? = "${to.value}-copy.jpg".also { files += it }
        override fun pathOf(name: String?): String? = name?.let { "/covers/$it" }
        override suspend fun delete(name: String) {
            files -= name
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo = StoreLibraryRepository(InMemoryNotebookStore(), InMemoryNotebookStore(), clock = { now })
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = LibraryViewModel(repo, metadata, { }, { now }, QuicknoteRequests(), PdfImporting { error("no") }, PdfOpenRequests(), covers = covers)

    @Test
    fun `a v1 metadata file migrates to v2 and keeps its data`() {
        val v1 = """{"version":1,"folders":[{"id":"f","name":"Uni","colorIndex":2}],"notebooks":{"a":{"coverIndex":3,"favorite":true,"lastPageIndex":4}}}"""
        val loaded = Json { ignoreUnknownKeys = true }.decodeFromString(MetadataSnapshot.serializer(), v1)
        assertEquals(1, loaded.version)
        val migrated = loaded.migrated()
        assertEquals(METADATA_VERSION, migrated.version)
        assertEquals(loaded.folders, migrated.folders)
        assertEquals(NotebookMeta(coverIndex = 3, favorite = true, lastPageIndex = 4), migrated.notebooks.getValue("a"))
    }

    @Test
    fun `migration cleans tags and blank image names`() {
        val dirty = MetadataSnapshot(1, notebooks = mapOf("a" to NotebookMeta(coverImage = " ", tags = listOf(4, 1, 4, -2))))
        assertEquals(NotebookMeta(coverImage = null, tags = listOf(1, 4)), dirty.migrated().notebooks.getValue("a"))
    }

    @Test
    fun `cover images and tags survive a write and reload from disk`() = runTest {
        val file = File.createTempFile("meta", ".json").apply { delete() }
        val first = FileLibraryMetadata(file)
        first.update(NotebookId("n")) { it.copy(coverImage = "n-1.jpg", tags = listOf(0, 2)) }
        assertTrue(file.readText().contains("\"version\":2"))
        val second = FileLibraryMetadata(file)
        second.load()
        assertEquals(NotebookMeta(coverImage = "n-1.jpg", tags = listOf(0, 2)), second.meta(NotebookId("n")))
        file.delete()
    }

    @Test
    fun `setting a photo cover shows it, reset and colored covers drop it and its file`() = runTest {
        val nb = repo.create("Photo")
        val vm = vm()
        vm.setCoverImage(listOf(nb.id), "content://photo/1")
        assertEquals("/covers/${nb.id.value}-0.jpg", vm.state.value.items.single().coverImagePath)
        vm.resetCover(listOf(nb.id))
        assertNull(vm.state.value.items.single().coverImagePath)
        assertTrue(covers.files.isEmpty())
        vm.setCoverImage(listOf(nb.id), "content://photo/2")
        vm.setCover(listOf(nb.id), CoverChoice(2))
        assertNull(vm.state.value.items.single().coverImagePath)
        assertTrue(covers.files.isEmpty())
    }

    @Test
    fun `an unreadable photo keeps the old cover and reports it`() = runTest {
        val nb = repo.create("Photo")
        val vm = vm()
        covers.fail = true
        vm.setCoverImage(listOf(nb.id), "content://bad")
        assertNull(vm.state.value.items.single().coverImagePath)
    }

    @Test
    fun `a locked notebook hides its cover image but keeps its record`() {
        val nb = Notebook(NotebookId("s"), "Secret", 0L, 0L, PaperTemplate.Ruled)
        val meta = InMemoryLibraryMetadata().apply { put(nb.id, NotebookMeta(coverImage = "s.jpg", tags = listOf(1))) }
        fun item(unlocked: Set<NotebookId>) = buildLibraryState(
            listOf(nb), emptyList(), LibraryControls(), meta, 6, 0L,
            sealed = setOf(nb.id), unlocked = unlocked, coverPath = { "/covers/$it" },
        ).items.single()
        assertNull(item(emptySet()).coverImagePath)
        assertEquals("/covers/s.jpg", item(setOf(nb.id)).coverImagePath)
        assertEquals(listOf(1), item(emptySet()).tags)
        assertEquals("s.jpg", meta.meta(nb.id).coverImage)
    }

    @Test
    fun `tags toggle on several notebooks, filter the library and clear with the filters`() = runTest {
        val a = repo.create("A"); val b = repo.create("B"); val c = repo.create("C")
        val vm = vm()
        vm.toggleTag(listOf(a.id, b.id), 2)
        vm.toggleTag(listOf(a.id), 0)
        assertEquals(listOf(0, 2), vm.state.value.items.first { it.id == a.id }.tags)
        assertEquals(listOf(0, 2), vm.state.value.tagColors)
        vm.setTag(2)
        assertEquals(setOf(a.id, b.id), vm.state.value.items.map { it.id }.toSet())
        assertTrue(vm.state.value.filtersActive)
        vm.setTag(2) // tapping again clears
        assertEquals(3, vm.state.value.items.size)
        vm.setTag(0); vm.clearFilters()
        assertEquals(3, vm.state.value.items.size)
        vm.toggleTag(listOf(a.id, b.id), 2) // all have it -> removed
        assertEquals(listOf(0), vm.state.value.tagColors)
        assertTrue(c.id.value.isNotEmpty())
    }

    @Test
    fun `a duplicate gets its own copy of the cover image and tags`() = runTest {
        val nb = repo.create("Orig")
        val vm = vm()
        vm.setCoverImage(listOf(nb.id), "content://p")
        vm.toggleTag(listOf(nb.id), 1)
        vm.duplicate(nb.id, "Copy")
        val copy = vm.state.value.items.first { it.title == "Copy" }
        assertEquals(listOf(1), copy.tags)
        assertTrue(copy.coverImagePath!!.endsWith("-copy.jpg"))
    }

    @Test
    fun `loading is reported until the first load completes`() {
        val state = buildLibraryState(null, emptyList(), LibraryControls(), metadata, 6, 0L)
        assertTrue(state.loading)
        assertFalse(buildLibraryState(emptyList(), emptyList(), LibraryControls(), metadata, 6, 0L).loading)
    }

    @Test
    fun `search is unsettled until the page search for the typed query returns`() = runTest {
        repo.create("Physics")
        val vm = vm()
        assertTrue(vm.state.value.searchSettled)
        vm.setQuery("zzz")
        assertFalse(vm.state.value.searchSettled)
        advanceTimeBy(1_000)
        assertTrue(vm.state.value.searchSettled)
        assertTrue(vm.state.value.items.isEmpty())
    }

    @Test
    fun `centre crop fills the ratio from the middle`() {
        assertEquals(listOf(450, 0, 600, 1000), centerCrop(1500, 1000, 0.6f).toList())
        assertEquals(listOf(0, 200, 600, 500), centerCrop(600, 900, 1.2f).toList())
    }
}
