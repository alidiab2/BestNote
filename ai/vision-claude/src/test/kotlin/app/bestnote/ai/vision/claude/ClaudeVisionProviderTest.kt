package app.bestnote.ai.vision.claude

import app.bestnote.ai.vision.BoardRequest
import app.bestnote.ai.vision.Prompts
import app.bestnote.ai.vision.ProviderCredentials
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
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

private fun fixture(name: String) = requireNotNull(ClaudeVisionProviderTest::class.java.classLoader!!.getResource("claude/$name")).readText()

class ClaudeRequestsTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1)

    @Test
    fun buildsMessagesRequestWithImageSystemAndSchema() {
        val body = ClaudeRequests.messages("claude-sonnet-5-5", " sys ", "user", jpeg, "image/jpeg", 16_000)
        assertEquals("claude-sonnet-5-5", body.getValue("model").jsonPrimitive.content)
        assertEquals(16_000, body.getValue("max_tokens").jsonPrimitive.content.toInt())
        assertEquals("sys", body.getValue("system").jsonPrimitive.content)
        val content = body.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray.map { it.jsonObject }
        assertEquals("image", content[0].getValue("type").jsonPrimitive.content)
        val source = content[0].getValue("source").jsonObject
        assertEquals("base64", source.getValue("type").jsonPrimitive.content)
        assertEquals("image/jpeg", source.getValue("media_type").jsonPrimitive.content)
        assertEquals(Base64.getEncoder().encodeToString(jpeg), source.getValue("data").jsonPrimitive.content)
        assertEquals("user", content[1].getValue("text").jsonPrimitive.content)
        val format = body.getValue("output_config").jsonObject.getValue("format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        // Claude needs additionalProperties: false on every object.
        assertTrue(format.getValue("schema").toString().contains("\"additionalProperties\":false"))
        assertEquals("default", body.getValue("fallbacks").jsonPrimitive.content)
        // No sampling params, no thinking budget (both 400 on current models).
        assertNull(body["temperature"])
        assertNull(body["thinking"])
    }

    @Test
    fun structuredOutputAndFallbacksCanBeDropped() {
        val body = ClaudeRequests.messages("claude-haiku-4-5", "s", "u", jpeg, "image/jpeg", 100, structured = false)
        assertNull(body["output_config"])
        assertNull(body["fallbacks"])
    }

    @Test
    fun headersCarryKeyVersionAndBeta() {
        val h = ClaudeRequests.headers("k", fallbacks = true)
        assertEquals("k", h["x-api-key"])
        assertEquals("2023-06-01", h["anthropic-version"])
        assertEquals("server-side-fallback-2026-07-01", h["anthropic-beta"])
        assertNull(ClaudeRequests.headers("k", fallbacks = false)["anthropic-beta"])
    }

    @Test
    fun parsesTextBlocksSkippingThinking() {
        val answer = ClaudeResponses.answer(fixture("board_mixed.json"))
        assertFalse(answer.truncated)
        assertTrue(answer.text.startsWith("{\"title\":\"المتتاليات الحسابية\""))
    }

    @Test
    fun refusalAndTruncation() {
        try {
            ClaudeResponses.answer(fixture("refusal.json"))
            fail()
        } catch (e: VisionError.Blocked) {
            assertTrue(e.message!!.contains("cyber"))
        }
        assertTrue(ClaudeResponses.answer(fixture("board_truncated.json")).truncated)
    }

    @Test
    fun mapsErrors() {
        assertTrue(ClaudeResponses.errorFor(401, fixture("auth_error.json"), null, "m") is VisionError.InvalidKey)
        assertTrue(ClaudeResponses.errorFor(404, fixture("not_found.json"), null, "claude-nope") is VisionError.ModelUnavailable)
        assertTrue(ClaudeResponses.errorFor(529, fixture("overloaded.json"), null, "m") is VisionError.ServiceUnavailable)
        assertEquals(12L, (ClaudeResponses.errorFor(429, "{}", "12", "m") as VisionError.RateLimited).retryAfterSeconds)
    }
}

/** End-to-end over a local MockWebServer (no real network, no real key). */
class ClaudeVisionProviderTest {
    private val server = MockWebServer()
    private val fakeKey = "placeholder-not-a-key"
    private var key: String? = fakeKey
    private var model = ""
    private lateinit var provider: ClaudeVisionProvider
    private val jpeg = VisionImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 9), "image/jpeg")
    private val request = BoardRequest(LayoutTemplate.LectureSummary, rtl = true, language = "ar")

    private fun respond(code: Int, body: String) = MockResponse.Builder().code(code).body(body).build()

    private fun takeJson(): Pair<JsonObject, okhttp3.Headers> = server.takeRequest().let { r ->
        Json.parseToJsonElement(r.body!!.utf8()).jsonObject to r.headers
    }

    @Before
    fun setUp() {
        server.start()
        val creds = object : ProviderCredentials {
            override suspend fun apiKey() = key
            override suspend fun model() = model
        }
        provider = ClaudeVisionProvider(OkHttpClient(), creds, ClaudeConfig(baseUrl = server.url("/").toString(), retryDelayMs = 0))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun reconstructsWithDefaultSonnetAndExpertPrompt() = runTest {
        server.enqueue(respond(200, fixture("board_mixed.json")))
        val doc = provider.reconstructBoard(jpeg, request)
        assertEquals(8, doc.blocks.size)
        val (body, headers) = takeJson()
        assertEquals(ClaudeConfig.DEFAULT_MODEL, body.getValue("model").jsonPrimitive.content)
        assertTrue(body.getValue("system").jsonPrimitive.content.startsWith(Prompts.EXPERT_STUDENT.trim().take(40)))
        assertEquals(fakeKey, headers["x-api-key"])
    }

    @Test
    fun retriesWithoutFallbacksThenWithoutSchema() = runTest {
        server.enqueue(respond(400, """{"type":"error","error":{"type":"invalid_request_error","message":"fallbacks: Extra inputs are not permitted"}}"""))
        server.enqueue(respond(400, """{"type":"error","error":{"type":"invalid_request_error","message":"output_config.format: schema is too complex"}}"""))
        server.enqueue(respond(200, fixture("board_mixed.json")))
        provider.reconstructBoard(jpeg, request)
        takeJson()
        val second = takeJson().first
        assertNull(second["fallbacks"])
        val third = takeJson()
        assertNull(third.first["output_config"])
        assertNull(third.second["anthropic-beta"])
    }

    @Test
    fun truncatedAnswerIsSalvaged() = runTest {
        server.enqueue(respond(200, fixture("board_truncated.json")))
        assertEquals(4, provider.reconstructBoard(jpeg, request).blocks.size)
    }

    @Test
    fun errorsAndRetry() = runTest {
        key = null
        try { provider.reconstructBoard(jpeg, request); fail() } catch (_: VisionError.MissingKey) { }
        assertEquals(0, server.requestCount)
        key = fakeKey
        server.enqueue(respond(529, fixture("overloaded.json")))
        server.enqueue(respond(529, fixture("overloaded.json")))
        try { provider.reconstructBoard(jpeg, request); fail() } catch (_: VisionError.ServiceUnavailable) { }
        assertEquals(2, server.requestCount)
        server.enqueue(respond(401, fixture("auth_error.json")))
        try { provider.reconstructBoard(jpeg, request); fail() } catch (e: VisionError.InvalidKey) {
            assertFalse(e.message.orEmpty().contains(fakeKey))
        }
    }

    @Test
    fun keyCheckerUsesTheModelsEndpoint() = runTest {
        server.enqueue(respond(200, """{"id":"claude-sonnet-5-5","type":"model"}"""))
        ClaudeKeyChecker(OkHttpClient(), ClaudeConfig(baseUrl = server.url("/").toString())).check(fakeKey, "claude-sonnet-5-5")
        val r = server.takeRequest()
        assertEquals("/v1/models/claude-sonnet-5-5", r.url.encodedPath)
        assertEquals("GET", r.method)
    }

    @Test
    fun listsModelsWithVisionAndPicksTheNewestOpus() = runTest {
        server.enqueue(respond(200, fixture("models.json")))
        val models = ClaudeKeyChecker(OkHttpClient(), ClaudeConfig(baseUrl = server.url("/").toString())).listModels(fakeKey)
        assertEquals(6, models.size)
        assertEquals("Claude Opus 5.5", models[1].displayName)
        assertEquals(true, models[1].vision)
        assertTrue(models[1].created!! > 0)
        val r = server.takeRequest()
        assertEquals("/v1/models", r.url.encodedPath)
        assertEquals("1000", r.url.queryParameter("limit"))
        assertEquals("claude-opus-5-5", app.bestnote.ai.vision.ModelCatalog.pick(models, app.bestnote.ai.vision.ModelCatalog.Preference.Quality)!!.id)
    }
}
