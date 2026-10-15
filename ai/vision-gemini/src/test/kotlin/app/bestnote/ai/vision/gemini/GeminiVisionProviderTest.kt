package app.bestnote.ai.vision.gemini

import app.bestnote.ai.vision.BoardRequest
import app.bestnote.ai.vision.VisionError
import app.bestnote.ai.vision.VisionImage
import app.bestnote.core.model.LayoutTemplate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** End-to-end over a local MockWebServer (no real network, no real key). */
class GeminiVisionProviderTest {
    private val server = MockWebServer()
    private val fakeKey = "placeholder-not-a-key"
    private var key: String? = fakeKey
    private var model = "gemini-test-model"
    private val settings = object : GeminiSettings {
        override suspend fun apiKey() = key
        override suspend fun model() = model
    }
    private lateinit var provider: GeminiVisionProvider

    private val jpeg = VisionImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 9), "image/jpeg")
    private val request = BoardRequest(LayoutTemplate.Cornell, rtl = true, language = "Arabic")

    private fun respond(code: Int, body: String) = MockResponse.Builder().code(code).body(body).build()

    @Before
    fun setUp() {
        server.start()
        provider = GeminiVisionProvider(OkHttpClient(), settings, GeminiConfig(baseUrl = server.url("/").toString(), retryDelayMs = 1))
    }

    @After
    fun tearDown() = server.close()

    private fun takeJson(): JsonObject = server.takeRequest().let { r ->
        assertEquals("/v1beta/models/$model:generateContent", r.url.encodedPath)
        assertEquals("POST", r.method)
        assertEquals(fakeKey, r.headers[GeminiHttp.KEY_HEADER])
        assertFalse("key must never be in the URL", r.url.toString().contains(fakeKey))
        Json.parseToJsonElement(r.body!!.utf8()).jsonObject
    }

    private suspend inline fun <reified T : VisionError> failure(vararg responses: MockResponse): T {
        responses.forEach(server::enqueue)
        try {
            provider.reconstructBoard(jpeg, request)
        } catch (e: VisionError) {
            assertTrue("expected ${T::class.simpleName}, got $e", e is T)
            return e as T
        }
        fail("expected ${T::class.simpleName}")
        error("unreachable")
    }

    @Test
    fun reconstructsABoardWithTheExpertStudentPrompt() = runTest {
        server.enqueue(respond(200, Fixtures.read("board_response.json")))
        val doc = provider.reconstructBoard(jpeg, request)
        assertEquals("Derivatives", doc.title)
        assertEquals(4, doc.blocks.size)

        val body = takeJson()
        val system = body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(system.contains("elite, meticulous student"))
        assertTrue(system.contains("Cornell"))
        assertTrue(system.contains("right-to-left"))
        assertTrue(system.contains("Write the notes in: Arabic"))
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals("image/jpeg", parts[0].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content)
        assertEquals(GeminiVisionProvider.BOARD_USER_TEXT, parts[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue("responseJsonSchema" in body["generationConfig"]!!.jsonObject)
    }

    @Test
    fun beautifySendsThePngAndTheSelectionSize() = runTest {
        server.enqueue(respond(200, Fixtures.ok(Fixtures.NOTES)))
        val png = VisionImage(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), "image/jpeg")
        assertEquals("Work", provider.beautify(png, 320f, 180.5f).blocks.single().text)

        val body = takeJson()
        val system = body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(system.contains("YOUR OWN rough notes"))
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals("image/png", parts[0].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content)
        assertTrue(parts[1].jsonObject["text"]!!.jsonPrimitive.content.contains("320 × 180.5 pt"))
    }

    @Test
    fun modelIdComesFromSettingsWithADefault() = runTest {
        model = "gemini-other"
        server.enqueue(respond(200, Fixtures.ok(Fixtures.NOTES)))
        provider.reconstructBoard(jpeg, request)
        takeJson()

        model = "  "
        server.enqueue(respond(200, Fixtures.ok(Fixtures.NOTES)))
        provider.reconstructBoard(jpeg, request)
        assertEquals("/v1beta/models/${GeminiConfig.DEFAULT_MODEL}:generateContent", server.takeRequest().url.encodedPath)
    }

    @Test
    fun missingKeyFailsWithoutCalling() = runTest {
        key = "   "
        failure<VisionError.MissingKey>()
        key = null
        failure<VisionError.MissingKey>()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun fallsBackToResponseSchemaWhenJsonSchemaIsRejected() = runTest {
        server.enqueue(respond(400, Fixtures.error(400, "Invalid JSON payload received. Unknown name \\\"responseJsonSchema\\\"")))
        server.enqueue(respond(200, Fixtures.ok(Fixtures.NOTES)))
        provider.reconstructBoard(jpeg, request)
        takeJson()
        val retry = takeJson()["generationConfig"]!!.jsonObject
        assertEquals("OBJECT", retry["responseSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse("responseJsonSchema" in retry)
    }

    @Test
    fun retriesAServerErrorOnceThenGivesUp() = runTest {
        server.enqueue(respond(503, Fixtures.error(503, "The model is overloaded")))
        server.enqueue(respond(200, Fixtures.ok(Fixtures.NOTES)))
        provider.reconstructBoard(jpeg, request)
        assertEquals(2, server.requestCount)

        failure<VisionError.BadResponse>(respond(500, "boom"), respond(503, "boom"))
        assertEquals(4, server.requestCount)
    }

    @Test
    fun mapsProviderErrors() = runTest {
        failure<VisionError.InvalidKey>(respond(400, Fixtures.read("invalid_key.json")))
        assertEquals(17L + 1, failure<VisionError.RateLimited>(respond(429, Fixtures.read("rate_limited.json"))).retryAfterSeconds)
        failure<VisionError.Blocked>(respond(200, """{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        failure<VisionError.BadResponse>(respond(200, Fixtures.ok("""{"title":"no blocks"}""")))
    }

    @Test
    fun errorsNeverLeakTheKey() = runTest {
        val e = failure<VisionError.InvalidKey>(respond(401, Fixtures.error(401, "bad key $fakeKey")))
        assertFalse(e.message.orEmpty().contains(fakeKey))
    }

    @Test
    fun connectionFailureIsANetworkError() = runTest {
        // Twice: OkHttp may transparently retry a dropped connection once.
        val drop = { MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build() }
        failure<VisionError.Network>(drop(), drop())
    }

    @Test
    fun keyCheckerUsesTheModelsEndpoint() = runTest {
        val checker = GeminiKeyChecker(OkHttpClient(), GeminiConfig(baseUrl = server.url("/").toString()))
        server.enqueue(respond(200, """{"name":"models/gemini-test-model"}"""))
        checker.check(" $fakeKey ", "gemini-test-model")
        val r = server.takeRequest()
        assertEquals("/v1beta/models/gemini-test-model", r.url.encodedPath)
        assertEquals(fakeKey, r.headers[GeminiHttp.KEY_HEADER])

        server.enqueue(respond(400, Fixtures.read("invalid_key.json")))
        try {
            checker.check(fakeKey)
            fail()
        } catch (e: VisionError.InvalidKey) {
            // expected
        }
    }
}
