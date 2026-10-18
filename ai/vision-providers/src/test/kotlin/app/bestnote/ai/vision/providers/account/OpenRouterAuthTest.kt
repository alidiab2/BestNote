package app.bestnote.ai.vision.providers.account

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom

class PkceTest {
    @Test
    fun `S256 challenge matches RFC 7636 appendix B`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun `verifiers are legal, unique and match their challenge`() {
        val random = SecureRandom()
        val pairs = List(20) { Pkce.create(random) }
        pairs.forEach {
            assertTrue(it.verifier, Pkce.isValidVerifier(it.verifier))
            assertEquals(Pkce.challenge(it.verifier), it.challenge)
            assertFalse("no padding", it.challenge.contains('='))
        }
        assertEquals(20, pairs.map { it.verifier }.toSet().size)
        assertFalse("toString hides the verifier", pairs[0].toString().contains(pairs[0].verifier))
    }

    @Test
    fun `invalid verifiers are rejected`() {
        assertFalse(Pkce.isValidVerifier("short"))
        assertFalse(Pkce.isValidVerifier("a".repeat(42) + "+"))
        assertTrue(Pkce.isValidVerifier("a".repeat(43)))
    }
}

class OpenRouterAuthTest {
    private class FakeHttp(var reply: AuthReply = AuthReply(200, "{}"), var fail: IOException? = null) : AuthHttp {
        val sent = mutableListOf<Triple<String, Map<String, String>, String?>>()
        override suspend fun send(url: String, headers: Map<String, String>, body: String?): AuthReply {
            sent += Triple(url, headers, body)
            fail?.let { throw it }
            return reply
        }
    }

    private val http = FakeHttp()
    private val auth = OpenRouterAuth(http)
    private val pkce = PkcePair("v".repeat(43), "challenge-abc")

    private fun params(url: String) = URI(url).rawQuery.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        k to URLDecoder.decode(v, "UTF-8")
    }

    @Test
    fun `auth url carries callback, S256 challenge, state and label`() {
        val url = auth.authUrl("http://localhost:41234/callback", pkce, "st/ate+1")
        assertTrue(url.startsWith("https://openrouter.ai/auth?"))
        val p = params(url)
        assertEquals("http://localhost:41234/callback", p["callback_url"])
        assertEquals("challenge-abc", p["code_challenge"])
        assertEquals("S256", p["code_challenge_method"])
        assertEquals("st/ate+1", p["state"])
        assertEquals("BestNote", p["key_label"])
        assertFalse("the verifier never goes to the browser", url.contains(pkce.verifier))
    }

    @Test
    fun `callback with matching state yields the code`() {
        assertEquals(CallbackResult.Code("abc 123"), auth.parseCallback("/callback?code=abc%20123&state=s1", "s1"))
        assertEquals(CallbackResult.Code("x"), auth.parseCallback("http://localhost:5000/callback/?state=s1&code=x", "s1"))
    }

    @Test
    fun `callback rejects wrong state, errors and unrelated paths`() {
        assertEquals(CallbackResult.StateMismatch, auth.parseCallback("/callback?code=x&state=other", "s1"))
        assertEquals(CallbackResult.StateMismatch, auth.parseCallback("/callback?code=x", "s1"))
        assertEquals(CallbackResult.Error("User denied"), auth.parseCallback("/callback?error=access_denied&error_description=User%20denied", "s1"))
        assertEquals(CallbackResult.Error("no code"), auth.parseCallback("/callback?state=s1", "s1"))
        assertEquals(CallbackResult.NotCallback, auth.parseCallback("/favicon.ico", "s1"))
        assertEquals(CallbackResult.NotCallback, auth.parseCallback("not a uri %%%", "s1"))
    }

    @Test
    fun `exchange posts code, verifier and method and returns the key`() = runTest {
        http.reply = AuthReply(200, """{"key":"sk-or-v1-abc","user_id":"u1"}""")
        assertEquals("sk-or-v1-abc", auth.exchange("the-code", pkce.verifier))
        val (url, _, body) = http.sent.single()
        assertEquals("https://openrouter.ai/api/v1/auth/keys", url)
        val json = Json.parseToJsonElement(body!!).jsonObject
        assertEquals("the-code", json["code"]!!.jsonPrimitive.content)
        assertEquals(pkce.verifier, json["code_verifier"]!!.jsonPrimitive.content)
        assertEquals("S256", json["code_challenge_method"]!!.jsonPrimitive.content)
    }

    @Test
    fun `exchange errors map to AccountError`() = runTest {
        http.reply = AuthReply(403, """{"error":{"message":"Invalid code or code_verifier","code":403}}""")
        val rejected = runCatching { auth.exchange("c", "v") }.exceptionOrNull()
        assertTrue(rejected is AccountError.Rejected && rejected.status == 403 && "Invalid code" in rejected.message!!)

        http.reply = AuthReply(200, """{"nokey":true}""")
        assertTrue(runCatching { auth.exchange("c", "v") }.exceptionOrNull() is AccountError.BadResponse)

        http.reply = AuthReply(200, "<html>")
        assertTrue(runCatching { auth.exchange("c", "v") }.exceptionOrNull() is AccountError.BadResponse)

        http.fail = IOException("no route")
        assertTrue(runCatching { auth.exchange("c", "v") }.exceptionOrNull() is AccountError.Offline)
    }

    @Test
    fun `status parses key info and sends the key as bearer`() = runTest {
        http.reply = AuthReply(
            200,
            """{"data":{"label":"BestNote","limit":20,"limit_remaining":12.5,"usage":7.5,"usage_monthly":1.25,"is_free_tier":false,"rate_limit":{}}}""",
        )
        val s = auth.status("sk-or-1")
        assertEquals(AccountStatus("BestNote", 20.0, 12.5, 7.5, 1.25, false), s)
        assertEquals("https://openrouter.ai/api/v1/key", http.sent.single().first)
        assertEquals("Bearer sk-or-1", http.sent.single().second["Authorization"])
    }

    @Test
    fun `status of an uncapped free account and of a revoked key`() = runTest {
        http.reply = AuthReply(200, """{"data":{"label":"x","limit":null,"limit_remaining":null,"usage":0,"is_free_tier":true}}""")
        val s = auth.status("k")
        assertNull(s.limit)
        assertNull(s.limitRemaining)
        assertTrue(s.freeTier)

        http.reply = AuthReply(401, """{"error":{"message":"No auth credentials found"}}""")
        try {
            auth.status("k")
            fail()
        } catch (e: AccountError.KeyRevoked) {
            // expected
        }
    }

    @Test
    fun `key hash is lowercase hex sha256`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", OpenRouterAuth.keyHash("abc"))
    }
}
