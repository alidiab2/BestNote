package app.bestnote.feature.settings

import app.bestnote.feature.settings.data.Attachment
import app.bestnote.feature.settings.data.FeatureRequest
import app.bestnote.feature.settings.data.RequestPriority
import app.bestnote.feature.settings.data.GitHubRequestSink
import app.bestnote.feature.settings.data.RequestKind
import app.bestnote.feature.settings.data.RequestSyncException
import app.bestnote.feature.settings.data.SyncProblem
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GitHubRequestSinkTest {
    private val server = MockWebServer()
    private lateinit var sink: GitHubRequestSink
    private val request = FeatureRequest(
        number = 7, createdAt = 1234, kind = RequestKind.Bug, title = "Pen lag", details = "Only in dark mode",
        app = "1.0 (1)", device = "samsung SM-X810, Android 14",
    )

    @Before fun setUp() {
        server.start()
        sink = GitHubRequestSink(OkHttpClient(), FakeGitHubSettings("ghp_token", "me/requests"), server.url("/").toString().trimEnd('/'))
    }

    @After fun tearDown() = server.close()

    private fun reply(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private val shot = Attachment(file = "REQ-007-1-shot.jpg", name = "shot.jpg", mime = "image/jpeg", size = 3)
    private val log = Attachment(file = "REQ-007-2-app.log", name = "app.log", mime = "text/plain", size = 2_500)

    @Test fun submitUploadsFilesThenOpensALabelledIssue() = runTest {
        reply(201, """{"content":{"html_url":"https://github.com/me/requests/blob/main/attachments/1234-REQ-007/REQ-007-1-shot.jpg"}}""")
        reply(201, """{"content":{"html_url":"https://github.com/me/requests/blob/main/attachments/1234-REQ-007/REQ-007-2-app.log"}}""")
        reply(201, """{"number":12,"html_url":"https://github.com/me/requests/issues/12"}""")
        val withFiles = request.copy(priority = RequestPriority.High, attachments = listOf(shot, log))
        val issue = sink.submit(withFiles, listOf(shot to byteArrayOf(1, 2, 3), log to "hi".toByteArray()))
        assertEquals(12, issue.number)
        assertEquals(setOf(shot.file, log.file), issue.urls.keys)

        val upload = server.takeRequest()
        assertEquals("PUT", upload.method)
        assertEquals("/repos/me/requests/contents/attachments/1234-REQ-007/REQ-007-1-shot.jpg", upload.url.encodedPath)
        assertEquals("Bearer ghp_token", upload.headers["Authorization"])
        assertTrue(upload.body!!.utf8().contains("AQID")) // base64 of 1,2,3
        server.takeRequest()

        val create = server.takeRequest()
        assertEquals("POST", create.method)
        val body = create.body!!.utf8()
        assertTrue(body.contains("[REQ-007] Pen lag"))
        assertTrue(body.contains("app-request"))
        assertTrue(body.contains("priority:high"))
        assertTrue(body.contains("Only in dark mode"))
        assertTrue(body.contains("![shot.jpg](https://github.com/me/requests/blob/main/attachments/1234-REQ-007/REQ-007-1-shot.jpg?raw=true)"))
        assertTrue(body.contains("[app.log](https://github.com/me/requests/blob/main/attachments/1234-REQ-007/REQ-007-2-app.log?raw=true) (2 KB)"))
        assertTrue(body.contains("samsung SM-X810"))
    }

    @Test fun existingFileOnRetryIsReused() = runTest {
        reply(422, """{"message":"sha wasn't supplied"}""")
        reply(201, """{"number":3,"html_url":"https://github.com/me/requests/issues/3"}""")
        assertEquals(3, sink.submit(request.copy(attachments = listOf(shot)), listOf(shot to byteArrayOf(1))).number)
        server.takeRequest()
        assertTrue(server.takeRequest().body!!.utf8().contains("blob/HEAD/attachments/1234-REQ-007/REQ-007-1-shot.jpg"))
    }

    @Test fun updateRewritesTheIssueKeepsLabelsAddedOnGitHubAndOnlyUploadsNewFiles() = runTest {
        val old = shot.copy(url = "https://github.com/me/requests/blob/main/attachments/1234-REQ-007/REQ-007-1-shot.jpg")
        reply(201, """{"content":{"html_url":"https://github.com/me/requests/blob/main/attachments/1234-REQ-007/REQ-007-2-app.log"}}""")
        reply(200, """{"labels":[{"name":"app-request"},{"name":"bug"},{"name":"wontfix"},{"name":"priority:low"}]}""")
        reply(200, "{}")
        val edited = request.copy(issueNumber = 9, title = "Pen lag v2", kind = RequestKind.Improvement, priority = RequestPriority.High, attachments = listOf(old, log))
        val urls = sink.update(edited, listOf(log to "hi".toByteArray()))
        assertEquals(setOf(log.file), urls.keys) // only the newly uploaded file is reported back

        assertEquals("PUT", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/repos/me/requests/issues/9", patch.url.encodedPath)
        val body = patch.body!!.utf8()
        assertTrue(body.contains("[REQ-007] Pen lag v2"))
        assertTrue(body.contains("wontfix"))
        assertTrue(body.contains("improvement"))
        assertTrue(body.contains("priority:high"))
        assertFalse(body.contains("priority:low"))
        assertFalse(body.contains("\"bug\""))
        assertTrue(body.contains("REQ-007-1-shot.jpg?raw=true"))
    }

    @Test fun discardLeavesANoteAndClosesAsNotPlanned() = runTest {
        reply(201, "{}")
        reply(200, "{}")
        sink.discard(5)
        val note = server.takeRequest()
        assertEquals("POST", note.method)
        assertEquals("/repos/me/requests/issues/5/comments", note.url.encodedPath)
        val close = server.takeRequest()
        assertEquals("PATCH", close.method)
        assertTrue(close.body!!.utf8().contains("not_planned"))
    }

    @Test fun commentsAreReadOldestFirst() = runTest {
        reply(200, """[{"user":{"login":"alidiab2"},"body":"On it","created_at":"2026-10-19T08:00:00Z"},{"user":{"login":"alidiab2"},"body":"Fixed","created_at":"2026-10-19T09:00:00Z"}]""")
        val comments = sink.comments(4)
        assertEquals(listOf("On it", "Fixed"), comments.map { it.body })
        assertEquals("alidiab2", comments.first().author)
    }

    @Test fun closedStatesSkipsPullRequests() = runTest {
        reply(200, """[{"number":1,"state":"closed"},{"number":2,"state":"open"},{"number":3,"state":"closed","pull_request":{}}]""")
        assertEquals(mapOf(1 to true, 2 to false), sink.closedStates())
        assertTrue(server.takeRequest().url.encodedQuery!!.contains("labels=app-request"))
    }

    @Test fun setClosedPatchesTheIssue() = runTest {
        reply(200, "{}")
        sink.setClosed(5, true)
        val r = server.takeRequest()
        assertEquals("PATCH", r.method)
        assertEquals("/repos/me/requests/issues/5", r.url.encodedPath)
        assertTrue(r.body!!.utf8().contains("closed"))
    }

    @Test fun httpErrorsBecomeProblemsTheScreenCanExplain() = runTest {
        suspend fun problemFor(code: Int): SyncProblem {
            reply(code, "{}")
            try { sink.check() } catch (e: RequestSyncException) { return e.problem }
            fail("expected a problem for $code")
            error("unreachable")
        }
        assertEquals(SyncProblem.BadToken, problemFor(401))
        assertEquals(SyncProblem.BadToken, problemFor(403))
        assertEquals(SyncProblem.NoRepo, problemFor(404))
        assertEquals(SyncProblem.Other, problemFor(500))
    }

    @Test fun noTokenMeansNoCall() = runTest {
        val bare = GitHubRequestSink(OkHttpClient(), FakeGitHubSettings(null), server.url("/").toString())
        try { bare.check(); fail() } catch (e: RequestSyncException) { assertEquals(SyncProblem.NoToken, e.problem) }
        assertEquals(0, server.requestCount)
    }
}
