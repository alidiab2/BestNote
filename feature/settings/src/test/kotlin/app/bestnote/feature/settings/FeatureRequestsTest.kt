package app.bestnote.feature.settings

import app.bestnote.feature.settings.data.FeatureRequestMarkdown
import app.bestnote.feature.settings.data.RequestSync
import app.bestnote.feature.settings.data.SyncProblem
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import app.bestnote.feature.settings.data.FileFeatureRequestStore
import app.bestnote.feature.settings.data.RequestContextProvider
import app.bestnote.feature.settings.data.RequestKind
import app.bestnote.feature.settings.data.RequestStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureRequestsTest {
    @get:Rule val tmp = TemporaryFolder()
    private var clock = 1_760_000_000_000L
    private val stamp = RequestContextProvider { "1.0 (1) debug abc" to "samsung SM-X810, Android 14" }

    private fun store() = FileFeatureRequestStore(tmp.root, stamp) { clock }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    // Main is deliberately not reset: a ViewModel's scope may still finish a write after the test body ends.

    @Test fun addNumbersRequestsAndStampsContext() = runTest {
        val s = store()
        val a = s.add(RequestKind.Feature, "  Dark canvas ", " details ")!!
        val b = s.add(RequestKind.Bug, "Crash on export", "")!!
        assertEquals(1, a.number)
        assertEquals("REQ-001", a.ref)
        assertEquals(2, b.number)
        assertEquals("Dark canvas", a.title)
        assertEquals("details", a.details)
        assertEquals("samsung SM-X810, Android 14", a.device)
        assertEquals(listOf(1, 2), s.requests.value.map { it.number })
    }

    @Test fun blankTitleIsRejected() = runTest {
        val s = store()
        assertNull(s.add(RequestKind.Feature, "   ", "body"))
        assertTrue(s.requests.value.isEmpty())
        assertFalse(tmp.root.resolve("requests.json").exists())
    }

    @Test fun survivesRestartAndNeverReusesANumber() = runTest {
        val s = store()
        s.add(RequestKind.Feature, "one", "")
        s.add(RequestKind.Feature, "two", "")
        s.delete(2)
        val again = store()
        assertEquals(listOf("one"), again.requests.value.map { it.title })
        // Number 2 was deleted but 1 remains, so the next is 2 again; only the max matters.
        assertEquals(2, again.add(RequestKind.Improvement, "three", "")!!.number)
    }

    @Test fun statusChangeIsPersistedAndMovesToDone() = runTest {
        val s = store()
        s.add(RequestKind.Feature, "one", "")
        s.setStatus(1, RequestStatus.Done)
        assertEquals(RequestStatus.Done, store().requests.value.single().status)
        val md = s.markdown()
        assertTrue(md.indexOf("## Open") < md.indexOf("## Done"))
        assertTrue(md.substringAfter("## Done").contains("REQ-001: one"))
        assertTrue(md.substringBefore("## Done").contains("_None._"))
    }

    @Test fun markdownFileIsWrittenOnEveryChange() = runTest {
        val s = store()
        s.add(RequestKind.Bug, "Pen lag", "Only in dark mode")
        val md = tmp.root.resolve("requests.md").readText()
        assertTrue(md.contains("### REQ-001: Pen lag"))
        assertTrue(md.contains("- type: bug"))
        assertTrue(md.contains("Only in dark mode"))
        assertTrue(md.contains("- device: samsung SM-X810, Android 14"))
        s.delete(1)
        assertTrue(tmp.root.resolve("requests.md").readText().contains("0 open, 0 done"))
    }

    @Test fun corruptFileStartsEmptyInsteadOfCrashing() = runTest {
        tmp.root.resolve("requests.json").writeText("{ not json")
        assertTrue(store().requests.value.isEmpty())
    }

    @Test fun markdownListsNewestFirst() {
        val s = store()
        runTest {
            s.add(RequestKind.Feature, "old", "")
            s.add(RequestKind.Feature, "new", "")
        }
        val md = FeatureRequestMarkdown.render(s.requests.value)
        assertTrue(md.indexOf("REQ-002: new") < md.indexOf("REQ-001: old"))
    }

    @Test fun viewModelSavesClearsTheFormAndKeepsTheKind() = runTest {
        val s = store()
        val vm = RequestsViewModel(s, FakeSharer, FakeGitHubSettings(), FakeSink(), FakeCompressor)
        vm.setKind(RequestKind.Bug)
        vm.setTitle("   ")
        vm.send()
        assertTrue(s.requests.value.isEmpty())
        vm.setTitle("Fix eraser")
        vm.setDetails("it skips strokes")
        vm.send()
        s.requests.first { it.isNotEmpty() } // the store writes on Dispatchers.IO
        assertEquals("Fix eraser", s.requests.value.single().title)
        assertEquals(RequestKind.Bug, vm.draft.value.kind)
        assertEquals("", vm.draft.value.title)
        vm.toggleDone(s.requests.value.single())
        assertEquals(RequestStatus.Done, s.requests.first { it.single().status == RequestStatus.Done }.single().status)
        vm.toggleDone(s.requests.value.single())
        assertEquals(RequestStatus.Open, s.requests.first { it.single().status == RequestStatus.Open }.single().status)
    }

    @Test fun imagesAreStoredNextToTheRequestAndDeletedWithIt() = runTest {
        val s = store()
        val r = s.add(RequestKind.Bug, "Pen lag", "", listOf(byteArrayOf(1, 2), byteArrayOf(3)))!!
        assertEquals(listOf("REQ-001-1.jpg", "REQ-001-2.jpg"), r.images)
        assertEquals(listOf<Byte>(1, 2), s.imageFile("REQ-001-1.jpg").readBytes().toList())
        assertTrue(s.markdown().contains("- screenshots: images/REQ-001-1.jpg, images/REQ-001-2.jpg"))
        s.delete(1)
        assertFalse(s.imageFile("REQ-001-1.jpg").exists())
    }

    @Test fun atMostFourImagesAreKept() = runTest {
        val r = store().add(RequestKind.Bug, "x", "", List(7) { byteArrayOf(it.toByte()) })!!
        assertEquals(4, r.images.size)
    }

    @Test fun syncSendsUnsentRequestsOnceAndTakesClosedStateBack() = runTest {
        val s = store()
        s.add(RequestKind.Feature, "one", "", listOf(byteArrayOf(9)))
        s.add(RequestKind.Bug, "two", "")
        val sink = FakeSink()
        val first = RequestSync(s, sink).run()
        assertEquals(2, first.sent)
        assertEquals(listOf(1, 2), s.requests.value.map { it.issueNumber })
        assertEquals(listOf("REQ-001-1.jpg"), sink.submitted.first().second)
        sink.closed[2] = true // closed on GitHub: the fix has shipped
        assertEquals(0, RequestSync(s, sink).run().sent)
        assertEquals(2, sink.submitted.size)
        assertEquals(listOf(RequestStatus.Open, RequestStatus.Done), s.requests.value.map { it.status })
    }

    @Test fun syncStopsOnFirstProblemAndKeepsTheRestLocal() = runTest {
        val s = store()
        s.add(RequestKind.Feature, "one", "")
        s.add(RequestKind.Feature, "two", "")
        val sink = FakeSink().apply { failWith = SyncProblem.Offline }
        val result = RequestSync(s, sink).run()
        assertEquals(SyncProblem.Offline, result.problem)
        assertEquals(0, result.sent)
        assertTrue(s.requests.value.all { it.issueNumber == null })
        sink.failWith = null
        assertEquals(2, RequestSync(s, sink).run().sent)
    }

    @Test fun savingAndSendingFromTheViewModelUsesTheSink() = runTest {
        val s = store()
        val sink = FakeSink()
        val vm = RequestsViewModel(s, FakeSharer, FakeGitHubSettings(token = "ghp_secret_1234"), sink, FakeCompressor)
        vm.setTitle("Add rulers")
        vm.send()
        realTime { s.requests.first { it.singleOrNull()?.issueNumber != null } }
        assertEquals(1, sink.submitted.size)
    }

    @Test fun connectKeepsTheTokenOnlyWhenTheRepoAnswers() = runTest {
        val settings = FakeGitHubSettings()
        val sink = FakeSink().apply { failWith = SyncProblem.BadToken }
        val vm = RequestsViewModel(store(), FakeSharer, settings, sink, FakeCompressor)
        vm.connect("ghp_bad", "me/repo")
        realTime { vm.github.first { it.problem == SyncProblem.BadToken } }
        assertNull(settings.token())
        assertFalse(vm.github.value.connected)
        sink.failWith = null
        vm.connect("ghp_good1234", "me/repo")
        realTime { vm.github.first { it.connected } }
        assertEquals("ghp_good1234", settings.token())
        assertEquals("me/repo", vm.github.value.repo)
        assertEquals("…1234", vm.github.value.tokenHint)
    }


    /** The store writes on Dispatchers.IO, so wait in real time (runTest's virtual clock would skip the timeout). */
    private suspend fun <T> realTime(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(5_000) { block() } }
}
