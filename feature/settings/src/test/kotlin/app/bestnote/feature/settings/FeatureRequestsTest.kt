package app.bestnote.feature.settings

import app.bestnote.feature.settings.data.Attachment
import app.bestnote.feature.settings.data.DraftText
import app.bestnote.feature.settings.data.FeatureRequest
import app.bestnote.feature.settings.data.FeatureRequestMarkdown
import app.bestnote.feature.settings.data.FileFeatureRequestStore
import app.bestnote.feature.settings.data.PendingAttachment
import app.bestnote.feature.settings.data.ReadResult
import app.bestnote.feature.settings.data.RequestContextProvider
import app.bestnote.feature.settings.data.RequestEdit
import app.bestnote.feature.settings.data.RequestKind
import app.bestnote.feature.settings.data.RequestPriority
import app.bestnote.feature.settings.data.RequestStatus
import app.bestnote.feature.settings.data.RequestSync
import app.bestnote.feature.settings.data.SyncProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    private fun jpg(vararg b: Byte, name: String = "shot.jpg") = PendingAttachment(name, "image/jpeg", b)
    private fun edit(r: FeatureRequest, title: String = r.title, add: List<PendingAttachment> = emptyList(), remove: Set<String> = emptySet()) =
        RequestEdit(r.kind, r.priority, title, r.details, add, remove)

    // Main is deliberately never reset: a ViewModel's scope may still finish a write after the test body ends.
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

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
        assertEquals(RequestPriority.Normal, a.priority)
        assertEquals(listOf(1, 2), s.requests.value.map { it.number })
    }

    @Test fun blankTitleIsRejected() = runTest {
        val s = store()
        assertNull(s.add(RequestKind.Feature, "   ", "body"))
        assertTrue(s.requests.value.isEmpty())
        assertFalse(tmp.root.resolve("requests.json").exists())
    }

    @Test fun survivesRestartAndTakesNextNumberAfterTheHighest() = runTest {
        val s = store()
        s.add(RequestKind.Feature, "one", "")
        s.add(RequestKind.Feature, "two", "")
        s.delete(2)
        val again = store()
        assertEquals(listOf("one"), again.requests.value.map { it.title })
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
        s.add(RequestKind.Bug, "Pen lag", "Only in dark mode", RequestPriority.High)
        val md = tmp.root.resolve("requests.md").readText()
        assertTrue(md.contains("### REQ-001: Pen lag"))
        assertTrue(md.contains("- type: bug"))
        assertTrue(md.contains("- priority: high"))
        assertTrue(md.contains("Only in dark mode"))
        assertTrue(md.contains("- device: samsung SM-X810, Android 14"))
        s.delete(1)
        assertTrue(tmp.root.resolve("requests.md").readText().contains("0 open, 0 done"))
    }

    @Test fun corruptFileStartsEmptyInsteadOfCrashing() = runTest {
        tmp.root.resolve("requests.json").writeText("{ not json")
        assertTrue(store().requests.value.isEmpty())
    }

    @Test fun markdownPutsHighPriorityFirstThenNewest() = runTest {
        val s = store()
        s.add(RequestKind.Feature, "old", "")
        s.add(RequestKind.Feature, "new", "")
        s.add(RequestKind.Feature, "urgent but oldest in line", "", RequestPriority.High)
        val md = FeatureRequestMarkdown.render(s.requests.value)
        assertTrue(md.indexOf("REQ-003") < md.indexOf("REQ-002"))
        assertTrue(md.indexOf("REQ-002") < md.indexOf("REQ-001"))
    }

    // ---- attachments

    @Test fun anyFileIsStoredNextToTheRequestWithItsOriginalName() = runTest {
        val s = store()
        val r = s.add(RequestKind.Bug, "Crash", "", attachments = listOf(jpg(1, 2), PendingAttachment("log file (1).txt", "text/plain", "boom".toByteArray())))!!
        assertEquals(listOf("REQ-001-1-shot.jpg", "REQ-001-2-log_file__1.txt"), r.attachments.map { it.file })
        assertEquals(listOf("shot.jpg", "log file (1).txt"), r.attachments.map { it.name })
        assertEquals(listOf(true, false), r.attachments.map { it.isImage })
        assertEquals("boom", s.file(r.attachments[1].file).readText())
        assertTrue(s.markdown().contains("files/REQ-001-2-log_file__1.txt (log file (1).txt)"))
        s.delete(1)
        assertFalse(s.file(r.attachments[0].file).exists())
    }

    @Test fun atMostEightFilesAndNoneOverTenMegabytes() = runTest {
        val many = store().add(RequestKind.Bug, "x", "", attachments = List(11) { jpg(it.toByte()) })!!
        assertEquals(8, many.attachments.size)
        val big = PendingAttachment("big.zip", "application/zip", ByteArray(10 * 1024 * 1024 + 1))
        assertTrue(store().add(RequestKind.Bug, "y", "", attachments = listOf(big))!!.attachments.isEmpty())
    }

    @Test fun safeNamesKeepTheExtensionAndDropAwkwardCharacters() {
        assertEquals("report.pdf", FileFeatureRequestStore.safeName("report.pdf"))
        assertEquals("my_file.png", FileFeatureRequestStore.safeName("my file.png"))
        assertEquals("file", FileFeatureRequestStore.safeName("..."))
        assertEquals("file.pdf", FileFeatureRequestStore.safeName("ملاحظة.pdf"))
        assertEquals("archive.tar.gz", FileFeatureRequestStore.safeName("archive.tar.gz"))
        assertEquals("shot.jpg", FileFeatureRequestStore.safeName("/storage/emulated/0/shot.jpg"))
    }

    @Test fun firstVersionScreenshotsAreMovedIntoAttachments() = runTest {
        tmp.root.resolve("images").mkdirs()
        tmp.root.resolve("images/REQ-001-1.jpg").writeBytes(byteArrayOf(1, 2, 3))
        tmp.root.resolve("requests.json").writeText(
            """{"requests":[{"number":1,"createdAt":5,"kind":"Bug","title":"old","images":["REQ-001-1.jpg"]}],"version":1}""",
        )
        val r = store().requests.value.single()
        assertTrue(r.images.isEmpty())
        assertEquals("REQ-001-1.jpg", r.attachments.single().file)
        assertTrue(r.attachments.single().isImage)
        assertEquals(3L, r.attachments.single().size)
        assertTrue(tmp.root.resolve("files/REQ-001-1.jpg").exists())
        // And a later file in the same request does not reuse that sequence number.
        val s = store()
        val edited = s.update(1, edit(r, add = listOf(jpg(9))))!!
        assertEquals("REQ-001-2-shot.jpg", edited.attachments.last().file)
    }

    // ---- editing

    @Test fun editChangesTextAddsAndRemovesFilesAndMarksSentRequestsUnsynced() = runTest {
        val s = store()
        val r = s.add(RequestKind.Feature, "Rulers", "old text", attachments = listOf(jpg(1), jpg(2, name = "b.jpg")))!!
        s.recordSent(1, 9, "https://github.com/me/requests/issues/9", r.attachments.associate { it.file to "u/${it.file}" }, at = 0)
        assertFalse(s.requests.value.single().unsynced)
        clock += 1000
        val gone = r.attachments[0].file
        val e = s.update(1, RequestEdit(RequestKind.Bug, RequestPriority.High, " Rulers v2 ", "new text", listOf(PendingAttachment("n.pdf", "application/pdf", byteArrayOf(5))), setOf(gone)))!!
        assertEquals("Rulers v2", e.title)
        assertEquals("new text", e.details)
        assertEquals(RequestKind.Bug, e.kind)
        assertEquals(RequestPriority.High, e.priority)
        assertEquals(clock, e.updatedAt)
        assertTrue(e.unsynced)
        assertEquals(listOf("REQ-001-2-b.jpg", "REQ-001-3-n.pdf"), e.attachments.map { it.file })
        assertFalse(s.file(gone).exists())
        assertEquals(e, store().requests.value.single())
    }

    @Test fun editOfAnUnsentRequestStaysUnsyncedFalseAndNoChangeIsNoOp() = runTest {
        val s = store()
        val r = s.add(RequestKind.Feature, "a", "b")!!
        assertFalse(s.update(1, edit(r, "a2"))!!.unsynced)
        val same = s.requests.value.single()
        clock += 1000
        assertEquals(same, s.update(1, edit(same)))
        assertEquals(same.updatedAt, s.requests.value.single().updatedAt)
    }

    @Test fun editRejectsBlankTitleAndMissingRequest() = runTest {
        val s = store()
        val r = s.add(RequestKind.Feature, "a", "")!!
        assertNull(s.update(1, edit(r, "  ")))
        assertNull(s.update(42, edit(r)))
        assertEquals("a", s.requests.value.single().title)
    }

    @Test fun editMadeWhileSendingIsStillPushedAfterwards() = runTest {
        val s = store()
        val r = s.add(RequestKind.Feature, "a", "")!!
        clock += 1000
        s.update(1, edit(r, "a, edited while sending"))
        // The send began before the edit (at = old updatedAt = 0), so the edit must stay pending.
        s.recordSent(1, 4, "u", emptyMap(), at = r.updatedAt)
        assertTrue(s.requests.value.single().unsynced)
    }

    // ---- bulk and draft

    @Test fun deleteDoneRemovesOnlyFinishedRequestsAndTheirFiles() = runTest {
        val s = store()
        val keep = s.add(RequestKind.Feature, "keep", "", attachments = listOf(jpg(1)))!!
        val drop = s.add(RequestKind.Feature, "drop", "", attachments = listOf(jpg(2)))!!
        s.setStatus(2, RequestStatus.Done)
        assertEquals(listOf(2), s.deleteDone().map { it.number })
        assertEquals(listOf(1), s.requests.value.map { it.number })
        assertTrue(s.file(keep.attachments[0].file).exists())
        assertFalse(s.file(drop.attachments[0].file).exists())
    }

    @Test fun draftIsSavedAndClearedAndBlankDraftsAreNotKept() {
        val s = store()
        assertNull(s.readDraft())
        s.writeDraft(DraftText(RequestKind.Bug, RequestPriority.High, "half", "written"))
        assertEquals("half", store().readDraft()!!.title)
        s.writeDraft(DraftText(title = "  "))
        assertNull(store().readDraft())
        assertFalse(tmp.root.resolve("draft.json").exists())
    }

    // ---- sync

    @Test fun syncSendsUnsentRequestsOnceAndTakesClosedStateBack() = runTest {
        val s = store()
        s.add(RequestKind.Feature, "one", "", attachments = listOf(jpg(9)))
        s.add(RequestKind.Bug, "two", "")
        val sink = FakeSink()
        assertEquals(2, RequestSync(s, sink).run().sent)
        assertEquals(listOf(1, 2), s.requests.value.map { it.issueNumber })
        assertEquals(listOf("REQ-001-1-shot.jpg"), sink.submitted.first().second)
        assertEquals("https://x/REQ-001-1-shot.jpg", s.requests.value[0].attachments.single().url)
        sink.closed[2] = true
        assertEquals(0, RequestSync(s, sink).run().sent)
        assertEquals(2, sink.submitted.size)
        assertEquals(listOf(RequestStatus.Open, RequestStatus.Done), s.requests.value.map { it.status })
    }

    @Test fun syncPushesEditsWithOnlyTheNewFiles() = runTest {
        val s = store()
        val r = s.add(RequestKind.Feature, "one", "", attachments = listOf(jpg(1)))!!
        val sink = FakeSink()
        RequestSync(s, sink).run()
        clock += 1000
        s.update(1, edit(s.requests.value.single(), "one, better", add = listOf(PendingAttachment("n.txt", "text/plain", byteArrayOf(7)))))
        assertTrue(s.requests.value.single().unsynced)
        RequestSync(s, sink).run()
        assertEquals(listOf("REQ-001-2-n.txt"), sink.updated.single().second)
        assertEquals("one, better", sink.updated.single().first.title)
        assertFalse(s.requests.value.single().unsynced)
        assertEquals("https://x/REQ-001-2-n.txt", s.requests.value.single().attachments[1].url)
        RequestSync(s, sink).run()
        assertEquals(1, sink.updated.size) // nothing left to push
        assertEquals(r.attachments.single().file, s.requests.value.single().attachments[0].file)
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

    // ---- view model

    private fun vm(s: FileFeatureRequestStore = store(), settings: FakeGitHubSettings = FakeGitHubSettings(), sink: FakeSink = FakeSink(), reader: FakeReader = FakeReader()) =
        RequestsViewModel(s, FakeSharer, settings, sink, reader)

    @Test fun viewModelSavesClearsTheFormAndKeepsTheKindAndPriority() = runTest {
        val s = store()
        val vm = vm(s)
        vm.setKind(RequestKind.Bug)
        vm.setPriority(RequestPriority.High)
        vm.setTitle("   ")
        vm.send()
        assertTrue(s.requests.value.isEmpty())
        vm.setTitle("Fix eraser")
        vm.setDetails("it skips strokes")
        vm.send()
        realTime { s.requests.first { it.isNotEmpty() } }
        val saved = s.requests.value.single()
        assertEquals("Fix eraser", saved.title)
        assertEquals(RequestPriority.High, saved.priority)
        assertEquals(RequestKind.Bug, vm.draft.value.kind)
        assertEquals(RequestPriority.High, vm.draft.value.priority)
        assertEquals("", vm.draft.value.title)
        vm.toggleDone(saved)
        realTime { s.requests.first { it.single().status == RequestStatus.Done } }
        vm.toggleDone(s.requests.value.single())
        realTime { s.requests.first { it.single().status == RequestStatus.Open } }
    }

    @Test fun pickedFilesAreReadAndCappedAndProblemsAreReported() = runTest {
        val reader = FakeReader()
        val vm = vm(reader = reader)
        val uri = android.net.Uri.EMPTY
        vm.addDraftFiles(List(10) { uri })
        realTime { vm.draft.first { it.files.size == RequestsViewModel.MAX_FILES } }
        assertEquals(RequestsViewModel.MAX_FILES, vm.draft.value.files.size)
        vm.removeDraftFile(vm.draft.value.files.first())
        assertEquals(RequestsViewModel.MAX_FILES - 1, vm.draft.value.files.size)

        val problems = vm(reader = FakeReader().apply { result = ReadResult.TooLarge })
        problems.addDraftFiles(listOf(uri))
        val event = realTime { problems.events.first() }
        assertEquals(RequestEvent.Message(Notice.FileTooLarge, RequestsViewModel.MAX_FILE_MB), event)
        assertTrue(problems.draft.value.files.isEmpty())
    }

    @Test fun editFlowChangesTheRequestAndPushesItToGitHub() = runTest {
        val s = store()
        val sink = FakeSink()
        val vm = vm(s, FakeGitHubSettings(token = "ghp_1234"), sink)
        vm.setTitle("Add rulers")
        vm.send()
        realTime { s.requests.first { it.singleOrNull()?.issueNumber != null } }
        clock += 1000
        vm.startEdit(s.requests.value.single())
        vm.editTitle("Add rulers and grids")
        vm.editPriority(RequestPriority.High)
        vm.addEditFiles(listOf(android.net.Uri.EMPTY))
        realTime { vm.edit.first { it!!.added.size == 1 } }
        assertEquals(1, vm.edit.value!!.fileCount)
        vm.saveEdit()
        realTime { s.requests.first { it.single().title == "Add rulers and grids" && !it.single().unsynced } }
        assertNull(vm.edit.value)
        assertEquals(RequestPriority.High, s.requests.value.single().priority)
        assertEquals(1, sink.updated.size)
        assertEquals(1, s.requests.value.single().attachments.size)
    }

    @Test fun cancellingAnEditChangesNothing() = runTest {
        val s = store()
        s.add(RequestKind.Feature, "keep me", "")
        val vm = vm(s)
        vm.startEdit(s.requests.value.single())
        vm.editTitle("changed")
        vm.cancelEdit()
        assertNull(vm.edit.value)
        assertEquals("keep me", s.requests.value.single().title)
    }

    @Test fun deleteClosesTheIssueOnlyWhenAsked() = runTest {
        val s = store()
        val sink = FakeSink()
        val vm = vm(s, FakeGitHubSettings(token = "ghp_1234"), sink)
        vm.setTitle("one"); vm.send()
        realTime { s.requests.first { it.singleOrNull()?.issueNumber != null } }
        vm.setTitle("two"); vm.send()
        realTime { s.requests.first { it.size == 2 && it.all { r -> r.issueNumber != null } } }
        vm.delete(s.requests.value[0], closeIssue = true)
        vm.delete(s.requests.value[1], closeIssue = false)
        realTime { s.requests.first { it.isEmpty() } }
        realTime { while (sink.discarded.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(listOf(1), sink.discarded)
    }

    @Test fun clearDoneRemovesFinishedRequests() = runTest {
        val s = store()
        s.add(RequestKind.Feature, "a", ""); s.add(RequestKind.Feature, "b", "")
        s.setStatus(1, RequestStatus.Done)
        val vm = vm(s)
        vm.clearDone()
        realTime { s.requests.first { it.size == 1 } }
        assertEquals("b", s.requests.value.single().title)
    }

    @Test fun searchAndFilterNarrowTheListAndCountsStayTotal() = runTest {
        val s = store()
        s.add(RequestKind.Feature, "Dark canvas", "")
        s.add(RequestKind.Bug, "Pen lag", "only in dark mode", RequestPriority.High)
        s.add(RequestKind.Feature, "Rulers", "")
        s.setStatus(3, RequestStatus.Done)
        val vm = vm(s)
        realTime { vm.list.first { it.total == 3 } }
        assertEquals(listOf(2, 1), vm.list.value.open.map { it.number }) // high priority first
        assertEquals(listOf(3), vm.list.value.done.map { it.number })
        vm.setQuery("dark")
        realTime { vm.list.first { it.open.size == 2 && it.done.isEmpty() } }
        assertEquals(3, vm.list.value.total)
        vm.setQuery("req-003")
        realTime { vm.list.first { it.done.size == 1 && it.open.isEmpty() } }
        vm.setQuery("")
        vm.setFilter(RequestFilter.Done)
        realTime { vm.list.first { it.open.isEmpty() && it.done.size == 1 } }
        assertEquals(2, vm.list.value.openTotal)
    }

    @Test fun halfWrittenRequestIsRestoredNextTime() = runTest {
        val s = store()
        val first = vm(s)
        first.setKind(RequestKind.Improvement)
        first.setTitle("Half written")
        realTime { while (s.readDraft() == null) kotlinx.coroutines.delay(20) }
        val second = vm(store())
        assertEquals("Half written", second.draft.value.title)
        assertEquals(RequestKind.Improvement, second.draft.value.kind)
    }

    @Test fun detailLoadsGitHubRepliesForSentRequests() = runTest {
        val s = store()
        val sink = FakeSink().apply { replies[1] = listOf(app.bestnote.feature.settings.data.IssueComment("alidiab2", "Fixed in 0.1.90", "2026-10-19")) }
        val vm = vm(s, FakeGitHubSettings(token = "ghp_1234"), sink)
        vm.setTitle("one"); vm.send()
        realTime { s.requests.first { it.singleOrNull()?.issueNumber != null } }
        vm.openDetail(s.requests.value.single())
        realTime { vm.detail.first { it != null && !it.loadingComments } }
        assertEquals("Fixed in 0.1.90", vm.detail.value!!.comments.single().body)
        vm.closeDetail()
        assertNull(vm.detail.value)
    }

    @Test fun connectKeepsTheTokenOnlyWhenTheRepoAnswers() = runTest {
        val settings = FakeGitHubSettings()
        val sink = FakeSink().apply { failWith = SyncProblem.BadToken }
        val vm = vm(settings = settings, sink = sink)
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
