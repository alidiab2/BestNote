package app.bestnote.core.updater

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GitHubReleaseSourceTest {
    @Test fun `fetches and parses over http`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body(javaClass.getResource("/releases.json")!!.readText()).build())
            server.start()
            val list = GitHubReleaseSource.fetchFrom(server.url("/releases").toString(), OkHttpClient())
            assertEquals(3, list.size)
            assertTrue(server.takeRequest().headers["Accept"]!!.contains("github"))
        }
    }

    @Test(expected = IOException::class) fun `http errors throw`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(403).body("{}").build())
            server.start()
            GitHubReleaseSource.fetchFrom(server.url("/releases").toString(), OkHttpClient())
        }
    }
}
