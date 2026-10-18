package app.bestnote.ai.vision.providers.account

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.net.URLDecoder

/** The whole flow with a fake OpenRouter and a real loopback socket standing in for the browser. */
class AiAccountsTest {
    private class FakeStore : AccountStore {
        var key: String? = null
        var signedIn = false
        override fun isSignedIn() = signedIn
        override fun keyHint() = key?.takeLast(4)?.let { "…$it" }
        override suspend fun key() = key
        override suspend fun signIn(key: String) {
            this.key = key
            signedIn = true
        }
        override suspend fun signOut() {
            key = null
            signedIn = false
        }
    }

    private class FakeOpenRouter : AuthHttp {
        var exchanged: Pair<String, String>? = null
        var reply = AuthReply(200, """{"key":"sk-or-v1-secret"}""")
        override suspend fun send(url: String, headers: Map<String, String>, body: String?): AuthReply {
            if (url.endsWith("/auth/keys")) {
                val json = kotlinx.serialization.json.Json.parseToJsonElement(body!!) as kotlinx.serialization.json.JsonObject
                exchanged = json["code"].toString().trim('"') to json["code_verifier"].toString().trim('"')
                return reply
            }
            return AuthReply(200, """{"data":{"label":"BestNote","usage":0.5,"is_free_tier":false}}""")
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = FakeStore()
    private val api = FakeOpenRouter()
    private var signedInHook = 0
    private val accounts = AiAccounts(OpenRouterAuth(api), store, scope, "app.bestnote.test://ai-account/done", timeoutMs = 5_000, onSignedIn = { signedInHook++ })

    @After
    fun tearDown() = scope.cancel()

    private fun params(url: String) = URI(url).rawQuery.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        k to URLDecoder.decode(v, "UTF-8")
    }

    /** What Chrome does after the user approves: GET the callback URL. Returns the response. */
    private fun browserGet(callback: String, query: String, path: String? = null): String {
        val uri = URI(callback)
        Socket(uri.host.replace("localhost", "127.0.0.1"), uri.port).use { s ->
            s.getOutputStream().write("GET ${path ?: uri.path}$query HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            return s.getInputStream().readBytes().decodeToString()
        }
    }

    private suspend fun awaitEnd() = withTimeout(5_000) {
        accounts.phase.first { it is SignInPhase.Done || it is SignInPhase.Failed }
    }

    @Test
    fun `sign in exchanges the code with the matching verifier and stores the key`() = runBlocking {
        val url = accounts.startOpenRouter()!!
        assertTrue(accounts.phase.value is SignInPhase.Waiting)
        val p = params(url)
        val callback = p.getValue("callback_url")
        assertTrue(callback, callback.matches(Regex("""http://localhost:\d+/callback""")))

        assertTrue(browserGet(callback, "", path = "/favicon.ico").contains("404")) // favicon-like noise is ignored
        val page = browserGet(callback, "?code=c0de&state=${p.getValue("state")}")
        assertTrue(page.startsWith("HTTP/1.1 200"))
        assertTrue(page.contains("app.bestnote.test://ai-account/done"))

        assertEquals(SignInPhase.Done, awaitEnd())
        assertEquals("c0de", api.exchanged!!.first)
        assertEquals(p.getValue("code_challenge"), Pkce.challenge(api.exchanged!!.second))
        assertEquals("sk-or-v1-secret", store.key)
        assertTrue(accounts.signedIn)
        assertEquals(1, signedInHook)
        assertEquals("https://openrouter.ai/keys/${OpenRouterAuth.keyHash("sk-or-v1-secret")}", accounts.manageUrl())
        assertEquals(0.5, accounts.status().usage, 0.0)
    }

    @Test
    fun `a callback with another state never exchanges`() = runBlocking {
        val url = accounts.startOpenRouter()!!
        browserGet(params(url).getValue("callback_url"), "?code=stolen&state=forged")
        assertEquals(SignInPhase.Failed(SignInFailure.Rejected), awaitEnd())
        assertNull(api.exchanged)
        assertFalse(accounts.signedIn)
    }

    @Test
    fun `user denies access`() = runBlocking {
        val url = accounts.startOpenRouter()!!
        browserGet(params(url).getValue("callback_url"), "?error=access_denied&state=${params(url).getValue("state")}")
        assertEquals(SignInPhase.Failed(SignInFailure.Denied), awaitEnd())
    }

    @Test
    fun `expired code is reported, key not stored`() = runBlocking {
        api.reply = AuthReply(403, """{"error":{"message":"Authorization code expired"}}""")
        val url = accounts.startOpenRouter()!!
        browserGet(params(url).getValue("callback_url"), "?code=old&state=${params(url).getValue("state")}")
        assertEquals(SignInPhase.Failed(SignInFailure.Rejected), awaitEnd())
        assertNull(store.key)
    }

    @Test
    fun `cancel closes the listener`() = runBlocking {
        val url = accounts.startOpenRouter()!!
        val callback = params(url).getValue("callback_url")
        accounts.cancel()
        assertEquals(SignInPhase.Failed(SignInFailure.Cancelled), accounts.phase.value)
        val refused = runCatching { browserGet(callback, "?code=x") }.exceptionOrNull()
        assertNotNull(refused)
        accounts.acknowledge()
        assertEquals(SignInPhase.Idle, accounts.phase.value)
    }

    @Test
    fun `timeout fails the sign in`() = runBlocking {
        val quick = AiAccounts(OpenRouterAuth(api), store, scope, "x://y", timeoutMs = 400)
        quick.startOpenRouter()
        assertEquals(SignInPhase.Failed(SignInFailure.TimedOut), withTimeout(5_000) { quick.phase.first { it is SignInPhase.Failed } })
    }

    @Test
    fun `no free port is a failure, not a crash`() = runBlocking {
        val broken = AiAccounts(OpenRouterAuth(api), store, scope, "x://y", openReceiver = { throw IOException("EACCES") })
        assertNull(broken.startOpenRouter())
        assertEquals(SignInPhase.Failed(SignInFailure.Failed), broken.phase.value)
    }

    @Test
    fun `sign out forgets the key`() = runBlocking {
        store.signIn("sk-or-1")
        accounts.signOut()
        assertFalse(accounts.signedIn)
        assertNull(accounts.manageUrl())
    }

    @Test
    fun `loopback reads the request target`() {
        assertEquals("/callback?code=1", LoopbackReceiver.readTarget("GET /callback?code=1 HTTP/1.1\r\nHost: x\r\n\r\n".byteInputStream()))
        assertNull(LoopbackReceiver.readTarget("POST /callback HTTP/1.1\r\n".byteInputStream()))
        assertNull(LoopbackReceiver.readTarget("".byteInputStream()))
    }
}
