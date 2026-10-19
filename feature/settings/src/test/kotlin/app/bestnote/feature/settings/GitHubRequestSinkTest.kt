package app.bestnote.feature.settings

import app.bestnote.feature.settings.data.FeatureRequest
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

    @Test fun submitUploadsScreenshotsThenOpensALabelledIssue() = runTest {
        reply(201, """{"content":{"html_url":"https://github.com/me/requests/blob/main/attachments/1234-REQ-007/1.jpg"}}""")
        reply(201, """{"number":12,"html_url":"https://github.com/me/requests/issues/12"}""")
        val issue = sink.submit(request, listOf("REQ-007-1.jpg" to byteArrayOf(1, 2, 3)))
        assertEquals(12, issue.number)

        val upload = server.takeRequest()
        assertEquals("PUT", upload.method)
        assertEquals("/repos/me/requests/contents/attachments/1234-REQ-007/1.jpg", upload.url.encodedPath)
        assertEquals("Bearer ghp_token", upload.headers["Authorization"])
        assertTrue(upload.body!!.utf8().contains("AQID")) // base64 of 1,2,3

        val create = server.takeRequest()
        assertEquals("POST", create.method)
        val body = create.body!!.utf8()
        assertTrue(body.contains("[REQ-007] Pen lag"))
        assertTrue(body.contains("app-request"))
        assertTrue(body.contains("bug"))
        assertTrue(body.contains("Only in dark mode"))
        assertTrue(body.contains("blob/main/attachments/1234-REQ-007/1.jpg?raw=true"))
        assertTrue(body.contains("samsung SM-X810"))
    }

    @Test fun existingScreenshotOnRetryIsReused() = runTest {
        reply(422, """{"message":"sha wasn't supplied"}""")
        reply(201, """{"number":3,"html_url":"https://github.com/me/requests/issues/3"}""")
        assertEquals(3, sink.submit(request, listOf("a.jpg" to byteArrayOf(1))).number)
        server.takeRequest()
        assertTrue(server.takeRequest().body!!.utf8().contains("blob/HEAD/attachments/1234-REQ-007/1.jpg"))
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
